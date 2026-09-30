package com.voice.actonsay.agent

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * 本机 shell 执行器。优先走 `su -c`（KernelSU / Magisk 授权后可用），否则退回普通 `sh -c`。
 * 所有调用都是阻塞的，请在 IO 线程或 [kotlinx.coroutines.runInterruptible] 中使用；
 * 线程被中断时会销毁子进程。
 */
object RootShell {
    private const val TAG = "RootShell"

    data class ExecResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean,
        val asRoot: Boolean
    ) {
        /** 拼成喂给模型的文本。 */
        fun render(maxChars: Int = 8000): String {
            val sb = StringBuilder()
            if (stdout.isNotBlank()) sb.append(stdout.trimEnd()).append('\n')
            if (stderr.isNotBlank()) sb.append("[stderr]\n").append(stderr.trimEnd()).append('\n')
            if (timedOut) sb.append("[已超时，进程被终止]\n")
            sb.append("[exit=$exitCode${if (asRoot) ", root" else ", non-root"}]")
            return truncateOutput(sb.toString(), maxChars)
        }
    }

    @Volatile
    private var rootChecked = false

    @Volatile
    var hasRoot: Boolean = false
        private set

    /** 探测 root 是否可用（会弹出授权请求）。结果缓存，`force=true` 时重新探测。 */
    fun checkRoot(force: Boolean = false): Boolean {
        if (rootChecked && !force) return hasRoot
        val result = runCatching { exec("id", timeoutMs = 8000, useRoot = true) }.getOrNull()
        hasRoot = result != null && !result.timedOut && result.exitCode == 0 && result.stdout.contains("uid=0")
        rootChecked = true
        Log.i(TAG, "Root check: hasRoot=$hasRoot exit=${result?.exitCode} out=${result?.stdout?.trim()} err=${result?.stderr?.trim()}")
        return hasRoot
    }

    /** 执行一条命令。默认在 root 可用时以 root 执行。 */
    fun exec(
        command: String,
        timeoutMs: Long = 30_000,
        useRoot: Boolean = hasRoot
    ): ExecResult {
        val argv = if (useRoot) listOf("su", "-c", command) else listOf("sh", "-c", command)
        val process = try {
            ProcessBuilder(argv).redirectErrorStream(false).start()
        } catch (e: Exception) {
            return ExecResult(127, "", "无法启动进程: ${e.message}", timedOut = false, asRoot = useRoot)
        }

        val stdoutBuf = ByteArrayOutputStream()
        val stderrBuf = ByteArrayOutputStream()
        val outReader = pump(process.inputStream, stdoutBuf)
        val errReader = pump(process.errorStream, stderrBuf)
        runCatching { process.outputStream.close() }

        val deadline = System.currentTimeMillis() + timeoutMs
        var timedOut = false
        var exitCode: Int
        while (true) {
            try {
                exitCode = process.exitValue()
                break
            } catch (_: IllegalThreadStateException) {
                if (System.currentTimeMillis() > deadline) {
                    timedOut = true
                    process.destroy()
                    exitCode = -1
                    break
                }
                try {
                    Thread.sleep(40)
                } catch (_: InterruptedException) {
                    process.destroy()
                    Thread.currentThread().interrupt()
                    exitCode = -2
                    break
                }
            }
        }
        outReader.join(1000)
        errReader.join(1000)
        return ExecResult(
            exitCode = exitCode,
            stdout = stdoutBuf.toString("UTF-8"),
            stderr = stderrBuf.toString("UTF-8"),
            timedOut = timedOut,
            asRoot = useRoot
        )
    }

    private fun pump(input: InputStream, target: ByteArrayOutputStream): Thread =
        Thread {
            try {
                val buf = ByteArray(4096)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    synchronized(target) { target.write(buf, 0, n) }
                }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true; start() }
}
