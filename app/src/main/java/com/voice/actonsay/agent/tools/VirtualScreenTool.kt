package com.voice.actonsay.agent.tools

import com.voice.actonsay.agent.AgentTool
import com.voice.actonsay.agent.ToolContext
import com.voice.actonsay.agent.ToolResult
import com.voice.actonsay.agent.VirtualScreen
import com.voice.actonsay.agent.schemaObject
import com.voice.actonsay.agent.schemaProp
import com.voice.actonsay.asr.AsrManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 在独立的虚拟屏上操作应用：不占用主屏，息屏和锁屏时也能用。虚拟屏上读不到控件树，
 * 每个动作之后返回一张截图交给视觉模型观察，坐标以截图像素为准。
 */
class VirtualScreenTool : AgentTool {
    override val name = "virtual_screen"
    override val description = "在独立的虚拟屏上打开和操作手机应用，不影响主屏，手机息屏或锁屏时也能用。每个动作后会返回最新截图，根据截图决定下一步；坐标是截图上的像素（原点在左上角）。" +
        "action：open_app 打开应用（app 填应用名或包名）；screenshot 只截图；tap 点击 x,y；swipe 从 x,y 滑到 x2,y2；text 向当前获得焦点的输入框输入文字（先 tap 输入框）；" +
        "key 按键（back、enter、delete、tab、escape）；输入中文时会自动通过剪贴板粘贴；close 关闭虚拟屏。用户正在看着主屏操作时才用 screen_content 加 run_shell 操作主屏。"
    override val parameters = schemaObject(
        "action" to schemaProp("string", "动作", listOf("open_app", "screenshot", "tap", "swipe", "text", "key", "close")),
        "app" to schemaProp("string", "open_app：应用名或包名"),
        "x" to schemaProp("integer", "tap / swipe 起点的横坐标"),
        "y" to schemaProp("integer", "tap / swipe 起点的纵坐标"),
        "x2" to schemaProp("integer", "swipe 终点的横坐标"),
        "y2" to schemaProp("integer", "swipe 终点的纵坐标"),
        "duration_ms" to schemaProp("integer", "swipe 持续毫秒数，默认 300", default = 300),
        "text" to schemaProp("string", "text：要输入的文字"),
        "key" to schemaProp("string", "key：按键名", KEYS.keys.toList()),
        required = listOf("action")
    )

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val action = args.optString("action")
        if (!ctx.requireAndroidRoot()) return ToolResult.failure("虚拟屏需要 root 权限", "ROOT_UNAVAILABLE")
        if (action == "close") {
            VirtualScreen.close()
            return ToolResult.success("虚拟屏已关闭")
        }
        if (!AsrManager.state.value.aiVisionEnabled) {
            return ToolResult.failure("虚拟屏靠截图观察，需要视觉模型：请用户在“设置 › AI 模型”打开“模型支持图像输入”。", "VISION_DISABLED")
        }
        val screen = try { VirtualScreen.prepare(ctx.context) } catch (error: IllegalStateException) {
            return ToolResult.failure(error.message ?: "无法创建虚拟屏", "VIRTUAL_SCREEN_UNAVAILABLE")
        }
        val id = screen.displayId
        fun inside(x: Int, y: Int) = x in 0 until screen.width && y in 0 until screen.height

        var settle = 900L
        val summary: String = when (action) {
            "screenshot" -> { settle = 0; "当前画面" }
            "open_app" -> {
                val query = args.optString("app").trim()
                val app = withContext(Dispatchers.IO) { resolveApp(ctx.context, query) }
                    ?: return ToolResult.failure("没有找到叫“$query”的应用，可先用 list_apps 查找", "APP_NOT_FOUND")
                val component = ctx.context.packageManager.getLaunchIntentForPackage(app.packageName)?.component
                    ?: return ToolResult.failure("应用 ${app.label} 没有可启动的入口", "APP_NOT_LAUNCHABLE")
                ctx.onProgress("在虚拟屏打开 ${app.label}")
                val result = VirtualScreen.shell("am start --display $id -n ${component.flattenToShortString()}")
                if (result.exitCode != 0 || result.stdout.contains("Error", true) || result.stderr.contains("Error", true)) {
                    return ToolResult.failure("打开 ${app.label} 失败：${result.render(400)}")
                }
                settle = 2200
                "已在虚拟屏打开 ${app.label}"
            }
            "tap" -> {
                val x = args.optInt("x", -1); val y = args.optInt("y", -1)
                if (!inside(x, y)) return ToolResult.failure("坐标超出屏幕 ${screen.width}x${screen.height}", "INVALID_ARGUMENT")
                ctx.onProgress("虚拟屏点击 $x,$y")
                VirtualScreen.shell("input -d $id tap $x $y")
                "已点击 ($x,$y)"
            }
            "swipe" -> {
                val x = args.optInt("x", -1); val y = args.optInt("y", -1)
                val x2 = args.optInt("x2", -1); val y2 = args.optInt("y2", -1)
                if (!inside(x, y) || !inside(x2, y2)) return ToolResult.failure("坐标超出屏幕 ${screen.width}x${screen.height}", "INVALID_ARGUMENT")
                val duration = args.optInt("duration_ms", 300).coerceIn(50, 3000)
                ctx.onProgress("虚拟屏滑动")
                VirtualScreen.shell("input -d $id swipe $x $y $x2 $y2 $duration")
                "已从 ($x,$y) 滑到 ($x2,$y2)"
            }
            "text" -> {
                val text = args.optString("text")
                if (text.isEmpty()) return ToolResult.failure("text 不能为空", "INVALID_ARGUMENT")
                ctx.onProgress("虚拟屏输入文字")
                if (text.all { it in ' '..'~' }) {
                    val escaped = text.replace("'", "'\\''").replace(" ", "%s")
                    VirtualScreen.shell("input -d $id text '$escaped'")
                } else {
                    // input text 只能注入按键能表达的字符；中文先放进剪贴板，再向虚拟屏的焦点输入框发送粘贴键。
                    withContext(Dispatchers.Main) {
                        val clipboard = ctx.context.getSystemService(android.content.ClipboardManager::class.java)
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("ActOnSay", text))
                    }
                    delay(150)
                    VirtualScreen.shell("input -d $id keyevent 279")
                }
                "已输入文字"
            }
            "key" -> {
                val code = KEYS[args.optString("key")] ?: return ToolResult.failure("不支持的按键", "INVALID_ARGUMENT")
                ctx.onProgress("虚拟屏按键 ${args.optString("key")}")
                VirtualScreen.shell("input -d $id keyevent $code")
                "已按 ${args.optString("key")}"
            }
            else -> return ToolResult.failure("不支持的 action", "INVALID_ARGUMENT")
        }
        if (settle > 0) delay(settle)
        ctx.checkCancelled()
        val jpeg = try { VirtualScreen.screenshot() } catch (error: Exception) {
            return ToolResult.failure("$summary，但截图失败：${error.message}", "SCREENSHOT_FAILED")
        }
        val file = withContext(Dispatchers.IO) {
            File(ctx.context.filesDir, "tasks/${ctx.taskId.ifBlank { "interactive" }}/vision/screen-${System.currentTimeMillis()}.jpg").apply {
                parentFile?.mkdirs()
                writeBytes(jpeg)
            }
        }
        // ephemeral：对话里只保留最新一张截图，旧的由 AgentRunner 替换成文字，避免上下文被图片占满。
        val artifact = JSONObject().put("name", file.name).put("mime_type", "image/jpeg").put("size", jpeg.size)
            .put("local_path", file.absolutePath).put("ephemeral", true)
        return ToolResult("SUCCEEDED", "$summary。虚拟屏 ${screen.width}x${screen.height}，最新截图随后提供。", artifacts = JSONArray().put(artifact))
    }

    companion object {
        private val KEYS = mapOf("back" to "KEYCODE_BACK", "enter" to "KEYCODE_ENTER", "delete" to "KEYCODE_DEL",
            "tab" to "KEYCODE_TAB", "escape" to "KEYCODE_ESCAPE")
    }
}
