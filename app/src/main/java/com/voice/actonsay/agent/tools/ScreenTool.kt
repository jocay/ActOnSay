package com.voice.actonsay.agent.tools

import android.util.Xml
import com.voice.actonsay.agent.AgentTool
import com.voice.actonsay.agent.RootShell
import com.voice.actonsay.agent.ToolContext
import com.voice.actonsay.agent.ToolResult
import com.voice.actonsay.agent.DeviceOperationGate
import com.voice.actonsay.agent.ExecutionEnvironment
import com.voice.actonsay.agent.schemaObject
import com.voice.actonsay.agent.schemaProp
import com.voice.actonsay.agent.truncateOutput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/**
 * 读取当前屏幕的控件树（uiautomator dump），压缩成模型易读的文本：
 * 每行一个可见控件，附带中心坐标，方便后续 `input tap X Y`。
 */
class ScreenContentTool : AgentTool {
    override val name = "screen_content"
    override val description =
        "读取当前屏幕上的界面内容：前台应用、可见文字与可点击控件及其中心坐标 (x,y)。" +
            "看完后可用 run_shell 执行 input tap X Y / input text / input swipe 进行操作。需要 root。"
    override val parameters = schemaObject(
        "max_items" to schemaProp("integer", "最多返回的控件条数，默认 120", default = 120)
    )

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult = DeviceOperationGate.run {
        if (!ctx.requireAndroidRoot()) return@run ToolResult.failure("需要 root 权限才能读取屏幕内容", "ROOT_UNAVAILABLE")
        val maxItems = args.optInt("max_items", 120).coerceIn(10, 400)
        ctx.onProgress("读取屏幕内容")

        val focus = runInterruptible(Dispatchers.IO) {
            RootShell.exec("dumpsys window 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp' | head -2", timeoutMs = 8000)
        }.stdout.trim()

        val dump = runInterruptible(Dispatchers.IO) {
            RootShell.exec("uiautomator dump $DUMP_PATH >/dev/null 2>&1; cat $DUMP_PATH", timeoutMs = 20_000)
        }
        val xml = dump.stdout
        if (!xml.contains("<hierarchy")) {
            return@run ToolResult.failure("读取屏幕失败：${dump.render(600)}")
        }

        val lines = parseNodes(xml, maxItems)
        val sb = StringBuilder()
        if (focus.isNotBlank()) sb.append("前台窗口: ").append(focus.replace(Regex("\\s+"), " ")).append('\n')
        if (lines.isEmpty()) {
            sb.append("屏幕上没有可读的文字或控件（可能是锁屏、全屏视频或纯图形界面）")
        } else {
            sb.append("控件（格式：文本 [描述] {resource-id} 类型 @中心坐标 标记）：\n")
            lines.forEach { sb.append(it).append('\n') }
        }
        ToolResult.success(truncateOutput(sb.toString().trimEnd(), 10_000))
    }

    private fun parseNodes(xml: String, maxItems: Int): List<String> {
        val result = ArrayList<String>()
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT && result.size < maxItems) {
            if (event == XmlPullParser.START_TAG && parser.name == "node") {
                val text = parser.getAttributeValue(null, "text").orEmpty().trim()
                val desc = parser.getAttributeValue(null, "content-desc").orEmpty().trim()
                val resId = parser.getAttributeValue(null, "resource-id").orEmpty().substringAfterLast('/')
                val clazz = parser.getAttributeValue(null, "class").orEmpty().substringAfterLast('.')
                val clickable = parser.getAttributeValue(null, "clickable") == "true"
                val editable = clazz.contains("EditText")
                val checked = parser.getAttributeValue(null, "checked") == "true"
                val bounds = parser.getAttributeValue(null, "bounds").orEmpty()
                val interesting = text.isNotEmpty() || desc.isNotEmpty() || clickable || editable
                if (interesting) {
                    val center = BOUNDS_RE.find(bounds)?.let { m ->
                        val (l, t, r, b) = m.destructured
                        "@${(l.toInt() + r.toInt()) / 2},${(t.toInt() + b.toInt()) / 2}"
                    } ?: ""
                    val sb = StringBuilder("- ")
                    if (text.isNotEmpty()) sb.append('"').append(text.take(60)).append('"').append(' ')
                    if (desc.isNotEmpty() && desc != text) sb.append('[').append(desc.take(40)).append("] ")
                    if (resId.isNotEmpty()) sb.append('{').append(resId).append("} ")
                    sb.append(clazz).append(' ').append(center)
                    if (clickable) sb.append(" 可点击")
                    if (editable) sb.append(" 可输入")
                    if (checked) sb.append(" 已选中")
                    result += sb.toString()
                }
            }
            event = parser.next()
        }
        return result
    }

    companion object {
        private const val DUMP_PATH = "/data/local/tmp/actonsay_ui.xml"
        private val BOUNDS_RE = Regex("""\[(\d+),(\d+)]\[(\d+),(\d+)]""")
    }
}
