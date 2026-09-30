package com.voice.actonsay.agent.tools

import com.voice.actonsay.agent.AgentTool
import com.voice.actonsay.agent.RootShell
import com.voice.actonsay.agent.ToolContext
import com.voice.actonsay.agent.schemaObject
import com.voice.actonsay.agent.schemaProp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.json.JSONObject

/** 在本机执行 shell 命令（root 可用时以 root 执行）。 */
class ShellTool : AgentTool {
    override val name = "run_shell"

    override val description =
        "在这台 Android 设备上执行 shell 命令，root 可用时以 root 身份运行。适合：查看系统信息、读写文件、" +
            "管理应用(am/pm)、模拟输入(input tap X Y / input swipe / input text / input keyevent)、截屏(screencap)、" +
            "修改设置(settings)、网络请求(curl)、查看通知(dumpsys notification)等。返回 stdout、stderr 与退出码。" +
            "高风险命令（递归删除、重启关机、卸载/清空应用、分区写入等）必须先征得用户口头同意，再带 confirmed=true 重试。"

    override val parameters = schemaObject(
        "command" to schemaProp("string", "要执行的 shell 命令，可用 && ; | 组合"),
        "timeout_seconds" to schemaProp("integer", "超时秒数，默认 30，最大 300", default = 30),
        "confirmed" to schemaProp("boolean", "用户已明确同意执行该高风险命令时设为 true", default = false),
        required = listOf("command")
    )

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String {
        val command = args.optString("command").trim()
        if (command.isEmpty()) return "错误：command 不能为空"
        val timeoutSec = args.optInt("timeout_seconds", 30).coerceIn(1, 300)
        val confirmed = args.optBoolean("confirmed", false)

        val danger = dangerReason(command)
        if (danger != null && !confirmed) {
            return "已拦截：该命令属于高风险操作（$danger）。请先向用户口头确认；用户明确同意后再以 confirmed=true 重新调用。"
        }

        ctx.onProgress("执行命令：${command.take(40)}")
        val result = runInterruptible(Dispatchers.IO) {
            RootShell.exec(command, timeoutMs = timeoutSec * 1000L)
        }
        return result.render()
    }

    companion object {
        private val DANGER_PATTERNS: List<Pair<Regex, String>> = listOf(
            Regex("""\brm\s+(-\w*r\w*|--recursive)\b""") to "递归删除",
            Regex("""\b(reboot|poweroff|shutdown|halt)\b""") to "重启/关机",
            Regex("""\bsvc\s+power\b""") to "电源控制",
            Regex("""\b(mkfs|fastboot|wipe|format)\b""") to "分区/格式化",
            Regex("""\bdd\s+.*\bof=""") to "dd 写入",
            Regex("""/dev/block""") to "块设备写入",
            Regex("""\bpm\s+(uninstall|clear|disable|disable-user)\b""") to "卸载/清空/停用应用",
            Regex("""\bsettings\s+put\s+(secure|global)\b""") to "修改系统安全/全局设置",
            Regex("""\b(factory|recovery)\b""") to "恢复出厂/进入 recovery"
        )

        fun dangerReason(command: String): String? =
            DANGER_PATTERNS.firstOrNull { (re, _) -> re.containsMatchIn(command) }?.second
    }
}
