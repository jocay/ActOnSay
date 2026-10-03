package com.voice.actonsay.agent

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import com.voice.actonsay.tasks.CredentialRedactor

/** 阻塞执行器；输出流式写文件并限制内存和磁盘，取消清理独立进程组。 */
object RootShell {
    private const val TAG = "RootShell"
    private const val PREVIEW_LIMIT = 64 * 1024
    private const val LOG_LIMIT = 2 * 1024 * 1024L
    data class ExecResult(val exitCode: Int, val stdout: String, val stderr: String, val timedOut: Boolean, val asRoot: Boolean) {
        fun render(maxChars: Int = 8000): String = truncateOutput(buildString {
            if (stdout.isNotBlank()) append(stdout.trimEnd()).append('\n')
            if (stderr.isNotBlank()) append("[stderr]\n").append(stderr.trimEnd()).append('\n')
            if (timedOut) append("[已超时，进程组已终止]\n")
            append("[exit=$exitCode${if (asRoot) ", root" else ", non-root"}]")
        }, maxChars)
    }
    @Volatile private var rootChecked = false
    @Volatile var hasRoot: Boolean = false
        private set
    fun checkRoot(force: Boolean = false): Boolean {
        if (rootChecked && !force) return hasRoot
        hasRoot = runCatching {
            val probe = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
            if (!probe.waitFor(8, TimeUnit.SECONDS)) { probe.destroy(); false }
            else probe.exitValue() == 0 && probe.inputStream.bufferedReader().use { it.readText().take(1024) }.contains("uid=0")
        }.getOrDefault(false)
        rootChecked = true
        Log.i(TAG, "Root check: available=$hasRoot")
        return hasRoot
    }
    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
    fun exec(command: String, timeoutMs: Long = 30_000, useRoot: Boolean = hasRoot, logFile: File? = null): ExecResult {
        val marker = File.createTempFile("actonsay-process-", ".pid", logFile?.parentFile)
        // 仅 setsid 可保证取消整个组；设备不支持时拒绝长命令，避免遗留不可追踪子进程。
        val isolated = "echo \$\$ > ${quote(marker.absolutePath)}; exec sh -c ${quote(command)}"
        // su 启动的 Shell 可能本身就是会话组长，此时 setsid 会 fork 后立即返回；优先用 -w 等待真实退出码。
        val wrapped = "S=''; for c in setsid '/system/bin/toybox setsid'; do " +
            "if \$c -w sh -c 'exit 0' >/dev/null 2>&1; then S=\"\$c -w\"; break; fi; done; " +
            "if [ -z \"\$S\" ]; then for c in setsid '/system/bin/toybox setsid'; do " +
            "if \$c sh -c 'exit 0' >/dev/null 2>&1; then S=\"\$c\"; break; fi; done; fi; " +
            "if [ -n \"\$S\" ]; then exec \$S sh -c ${quote(isolated)}; " +
            "else echo 'setsid unavailable: isolated execution refused' >&2; exit 126; fi"
        val argv = if (useRoot) listOf("su", "-c", wrapped) else listOf("sh", "-c", wrapped)
        val process = try { ProcessBuilder(argv).start() } catch (error: Exception) {
            marker.delete()
            return ExecResult(127, "", "无法启动进程: ${error.message}", false, useRoot)
        }
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val bytes = AtomicLong(0)
        val log = logFile?.outputStream()
        val lock = Any()
        val outReader = pump(process.inputStream, stdout, "stdout", log, bytes, lock)
        val errReader = pump(process.errorStream, stderr, "stderr", log, bytes, lock)
        runCatching { process.outputStream.close() }
        var timeout = false
        var interrupted = false
        val exit: Int
        try {
            exit = if (process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) process.exitValue() else {
                timeout = true; -1
            }
        } catch (_: InterruptedException) {
            interrupted = true
            terminateGroup(marker, useRoot)
            process.destroy()
            runCatching { outReader.join(300); errReader.join(300) }
            runCatching { log?.close() }
            marker.delete()
            Thread.currentThread().interrupt()
            return ExecResult(-2, stdout.toString("UTF-8"), stderr.toString("UTF-8"), false, useRoot)
        } finally {
            // 即使直接进程结束，也清理它留下的后台子进程。
            if (!interrupted) terminateGroup(marker, useRoot)
            process.destroy()
        }
        runCatching { outReader.join(1000); errReader.join(1000) }
        runCatching { process.inputStream.close(); process.errorStream.close() }
        synchronized(lock) { runCatching { log?.close() } }
        marker.delete()
        return ExecResult(exit, stdout.toString("UTF-8"), stderr.toString("UTF-8"), timeout, useRoot)
    }
    private fun terminateGroup(marker: File, root: Boolean) {
        val pid = runCatching { marker.readText().trim().toLong() }.getOrNull() ?: return
        if (pid <= 1) return
        val command = "kill -TERM -- -$pid 2>/dev/null; sleep 0.15; kill -KILL -- -$pid 2>/dev/null"
        runCatching {
            val killer = ProcessBuilder(if (root) listOf("su", "-c", command) else listOf("sh", "-c", command))
                .redirectErrorStream(true).start()
            killer.inputStream.close()
            if (!killer.waitFor(2, TimeUnit.SECONDS)) killer.destroy()
        }
    }
    private fun pump(input: InputStream, preview: ByteArrayOutputStream, label: String,
                     log: java.io.OutputStream?, bytes: AtomicLong, lock: Any): Thread = Thread {
        try {
            val buffer = CharArray(2048)
            val reader = input.reader(Charsets.UTF_8)
            val pending = StringBuilder()
            val secrets = CredentialRedactor.knownSecrets()
            val retained = maxOf(256, secrets.maxOfOrNull { it.length } ?: 0)
            fun emit(raw: String) {
                val safe = CredentialRedactor.text(raw).toByteArray(Charsets.UTF_8)
                synchronized(preview) {
                    val remaining = PREVIEW_LIMIT - preview.size()
                    if (remaining > 0) preview.write(safe, 0, minOf(safe.size, remaining))
                }
                synchronized(lock) {
                    val remaining = (LOG_LIMIT - bytes.get()).coerceAtLeast(0).toInt()
                    if (remaining > 0 && log != null) {
                        val prefix = "[$label] ".toByteArray()
                        log.write(prefix); log.write(safe, 0, minOf(safe.size, remaining)); log.flush()
                        bytes.addAndGet(prefix.size + minOf(safe.size, remaining).toLong())
                    }
                }
            }
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) { if (pending.isNotEmpty()) emit(pending.toString()); break }
                pending.append(buffer, 0, count)
                if (pending.length > retained + 4096) {
                    var cutoff = pending.length - retained
                    // 保留跨本次输出边界的完整凭据，等下一块到达后再脱敏。
                    for (secret in secrets) {
                        var start = pending.indexOf(secret)
                        while (start >= 0) {
                            if (start < cutoff && start + secret.length > cutoff) cutoff = start
                            start = pending.indexOf(secret, start + 1)
                        }
                    }
                    if (cutoff > 0) { emit(pending.substring(0, cutoff)); pending.delete(0, cutoff) }
                }
            }
        } catch (_: Exception) { /* 取消关闭管道是正常路径。 */ }
    }.apply { isDaemon = true; name = "shell-$label"; start() }
}
