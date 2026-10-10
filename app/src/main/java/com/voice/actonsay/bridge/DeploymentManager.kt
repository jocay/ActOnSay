package com.voice.actonsay.bridge

import android.content.Context
import com.voice.actonsay.agent.RootShell
import com.voice.actonsay.asr.AsrManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import org.json.JSONObject
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 用户从向导发起部署；仅安装本项目服务，不启动、停止或修改其他容器服务。 */
object DeploymentManager {
    private const val CLI = "/data/local/Droidspaces/bin/droidspaces"
    private const val ROOT = "/opt/actonsay-bridge"
    private const val STEP_MARKER = "ACTONSAY_STEP_OK"
    private const val EXIT_MARKER = "ACTONSAY_INSTALL_EXIT="
    /** 实测 droidspaces run 的单条命令上限在 8K–12K 字符之间，留出余量。 */
    private const val CHUNK_CHARS = 6000
    private val installLock = Mutex()

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    private fun containerCommand(container: String, command: String): String {
        require(container.matches(Regex("[A-Za-z0-9_.-]{1,64}"))) { "容器名称只能包含字母、数字、点、下划线和短横线" }
        return "${quote(CLI)} --name=${quote(container)} run sh -c ${quote(command)}"
    }

    suspend fun inspect(container: String = "container"): JSONObject = withContext(Dispatchers.IO) {
        val root = RootShell.checkRoot(force = true)
        require(root) { "应用尚未取得 Root 授权" }
        val containers = runInterruptible { RootShell.exec("${quote(CLI)} show", 15_000, true) }
        val info = runInterruptible { RootShell.exec(containerCommand(container,
            "id; python3 --version; command -v systemctl; cat /etc/os-release; cat /proc/self/mountinfo"), 20_000, true) }
        JSONObject().put("root", root).put("container", container).put("containers", containers.stdout)
            .put("identity", info.stdout.lineSequence().firstOrNull().orEmpty())
            .put("python", info.stdout.lineSequence().firstOrNull { it.startsWith("Python ") }.orEmpty())
            .put("systemd", info.stdout.contains("/systemctl"))
            .put("shared_mounts", info.stdout.lineSequence().filter { it.contains("/storage/") || it.contains("/sdcard") || it.contains("/data/") }.take(40).joinToString("\n"))
            .put("details", info.stdout.take(12000)).put("exit_code", info.exitCode)
            .put("error", if (info.exitCode == 0) "" else info.stderr.take(1000))
    }

    suspend fun install(context: Context, container: String = "container", onProgress: (String) -> Unit = {}): JSONObject = installLock.withLock {
        withContext(Dispatchers.IO) {
            require(RootShell.checkRoot(force = true)) { "部署需要应用 Root 授权" }
            // 随 APK 携带源码，日常部署不依赖电脑或第三方源码地址。
            withContext(Dispatchers.Main) { onProgress("准备执行服务安装包…") }
            val archive = buildArchive(context)
            val digest = MessageDigest.getInstance("SHA-256").digest(archive).joinToString("") { "%02x".format(it) }
            // droidspaces run 不转发标准输入，单条命令也有长度上限：安装包编码后分块经参数写入容器。
            val chunks = Base64.encodeToString(archive, Base64.NO_WRAP).chunked(CHUNK_CHARS)
            step(container, "mkdir -p $ROOT && : > $ROOT/.upload.b64", "无法在容器内创建安装目录")
            chunks.forEachIndexed { index, chunk ->
                withContext(Dispatchers.Main) { onProgress("传输安装包 ${index + 1}/${chunks.size}…") }
                step(container, "printf %s ${quote(chunk)} >> $ROOT/.upload.b64", "安装包传输中断")
            }
            step(container, "cd $ROOT && base64 -d .upload.b64 > .upload.zip && echo ${quote("$digest  .upload.zip")} | sha256sum -c - >/dev/null " +
                "&& rm -rf source && mkdir source && python3 -m zipfile -e .upload.zip source && rm -f .upload.b64 .upload.zip",
                "安装包校验或解压失败（容器需要 python3 与 coreutils）")
            withContext(Dispatchers.Main) { onProgress("在 Debian 安装依赖与独立服务，保留已有数据库和工作文件…") }
            // droidspaces run 不传回退出码：安装结果写入日志末尾的标记，由这里解析。
            val install = runInterruptible { RootShell.exec(containerCommand(container,
                "cd $ROOT/source && (bash install.sh --non-interactive > $ROOT/install.log 2>&1; echo $EXIT_MARKER\$? >> $ROOT/install.log); tail -n 40 $ROOT/install.log"),
                10 * 60 * 1000L, true) }
            val exit = Regex("$EXIT_MARKER(\\d+)").findAll(install.stdout).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
            check(!install.timedOut) { "执行服务安装超时，可在容器内查看 $ROOT/install.log" }
            check(exit == 0) {
                "执行服务安装失败 [exit=${exit ?: "未知"}]：${install.stdout.replace("\r", "").takeLast(3000)}${install.stderr.takeLast(500)}"
            }
            withContext(Dispatchers.Main) { onProgress("保存配对凭据并连接本机服务…") }
            pair(container)
        }
    }

    /** 服务已安装时只重新读取配对令牌并连接，不重装依赖。 */
    suspend fun pair(container: String = "container"): JSONObject = withContext(Dispatchers.IO) {
        require(RootShell.checkRoot(force = true)) { "读取配对令牌需要应用 Root 授权" }
        val result = runInterruptible { RootShell.exec(containerCommand(container, "cat /etc/actonsay-bridge/token"), 10000, true) }
        // Shell 输出会对已知凭据脱敏：读到的令牌与已保存的相同时拿到的是脱敏标记，沿用已保存的值。
        val token = result.stdout.trim().let { if (it == "[已脱敏]") AsrManager.state.value.bridgeToken else it }
        require(token.matches(Regex("[A-Za-z0-9_-]{32,256}"))) { "没有读到有效的配对令牌，请先安装执行服务" }
        AsrManager.setPairedBridgeConfig(container, "http://127.0.0.1:18765", token)
        withTimeout(45_000) {
            var lastError: Exception? = null
            for (attempt in 1..15) {
                try { return@withTimeout BridgeClient.health() }
                catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    lastError = error
                    delay(800)
                }
            }
            throw IllegalStateException("已保存配对令牌，但执行服务尚未就绪；请检查服务状态。", lastError)
        }
    }

    /** 在容器内执行一步并以输出标记确认成功；droidspaces run 的退出码恒为 0，不能作为依据。 */
    private suspend fun step(container: String, command: String, failure: String) {
        val result = runInterruptible { RootShell.exec(containerCommand(container, "{ $command; } && echo $STEP_MARKER"), 60_000, true) }
        check(result.stdout.contains(STEP_MARKER)) {
            "$failure：${(result.stdout + result.stderr).replace("\r", "").takeLast(600)}"
        }
    }

    private fun buildArchive(context: Context): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            fun copy(path: String) {
                val children = context.assets.list(path).orEmpty()
                if (children.isNotEmpty()) children.sorted().forEach { copy(if (path.isEmpty()) it else "$path/$it") }
                else {
                    zip.putNextEntry(ZipEntry(path))
                    context.assets.open(path).use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            val roots = context.assets.list("").orEmpty()
            val selected = roots.filter { it == "install.sh" || it == "pyproject.toml" || it == "requirements.txt" || it == "actonsay_bridge" || it == "skills" || it == "systemd" }
            check("install.sh" in selected && "actonsay_bridge" in selected) { "APK 缺少 Debian 执行服务源码，请使用更新后的构建配置" }
            selected.sorted().forEach(::copy)
        }
        check(bytes.size() <= 32 * 1024 * 1024) { "安装包超过 32 MiB" }
        return bytes.toByteArray()
    }
}
