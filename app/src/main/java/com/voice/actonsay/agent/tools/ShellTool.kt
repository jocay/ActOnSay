package com.voice.actonsay.agent.tools

import com.voice.actonsay.agent.AgentTool
import com.voice.actonsay.agent.RootShell
import com.voice.actonsay.agent.ToolContext
import com.voice.actonsay.agent.ToolResult
import com.voice.actonsay.agent.DeviceOperationGate
import com.voice.actonsay.agent.ExecutionEnvironment
import com.voice.actonsay.agent.schemaObject
import com.voice.actonsay.agent.schemaProp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.json.JSONObject
import java.io.File

/** 仅操作 Android；Debian 命令必须使用 run_debian。权限由应用保存。 */
class ShellTool : AgentTool {
    override val name = "run_shell"
    override val description = "在 Android 上执行 Shell，root 可用时以 root 身份执行。用于设备设置、应用和屏幕操作。Debian 文件与Linux自动化使用 run_debian。本工具需应用授权，模型不能自行确认授权。日志限制大小并返回真实退出码。"
    override val parameters = schemaObject(
        "command" to schemaProp("string", "Android Shell命令"),
        "timeout_seconds" to schemaProp("integer", "超时秒数，默认30，最大300", default = 30),
        required = listOf("command")
    )
    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult = DeviceOperationGate.run {
        val command = args.optString("command").trim()
        if (command.isEmpty()) return@run ToolResult.failure("command 不能为空", "INVALID_ARGUMENT")
        val root = ctx.requireAndroidRoot()
        val logDir = File(ctx.context.filesDir, "tasks/${ctx.taskId.ifBlank { "interactive" }}/logs").apply { mkdirs() }
        val file = File(logDir, "${ctx.callId.ifBlank { "shell" }.replace(Regex("[^A-Za-z0-9_-]"), "_")}.log")
        ctx.onProgress("执行 Android 命令")
        val result = runInterruptible(Dispatchers.IO) {
            RootShell.exec(command, args.optInt("timeout_seconds", 30).coerceIn(1, 300) * 1000L,
                useRoot = root, logFile = file)
        }
        ToolResult(if (result.timedOut || result.exitCode != 0) "FAILED" else "SUCCEEDED",
            result.render(), result.exitCode, file.absolutePath,
            if (result.timedOut) "TIMEOUT" else if (result.exitCode != 0) "EXIT_NONZERO" else null)
    }
    companion object {
        fun dangerReason(command: String): String? = when {
            Regex("\\b(rm|reboot|poweroff|shutdown|mkfs|dd|wipe|format)\\b").containsMatchIn(command) -> "删除、电源或磁盘操作"
            Regex("\\bpm\\s+(uninstall|clear|disable)").containsMatchIn(command) -> "修改应用"
            else -> null
        }
    }
}
