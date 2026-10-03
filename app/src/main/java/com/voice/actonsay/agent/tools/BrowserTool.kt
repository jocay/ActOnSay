package com.voice.actonsay.agent.tools

import com.voice.actonsay.agent.AgentTool
import com.voice.actonsay.agent.ToolContext
import com.voice.actonsay.agent.ToolResult
import com.voice.actonsay.agent.schemaObject
import com.voice.actonsay.agent.schemaProp
import com.voice.actonsay.browser.AgentBrowser
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/** 内置浏览器：适合需要执行网页脚本、点击或填写表单的页面；只读静态内容时 fetch_url 更快。 */
class BrowserTool : AgentTool {
    override val name = "browser"
    override val description = "应用内置的浏览器，能运行网页脚本、点击和填写表单，用于 fetch_url 读不出内容的动态网页或需要交互的网站。" +
        "每个动作后返回页面标题、正文和带编号的可交互元素（格式：编号|标签|文字|链接）。" +
        "action：open 打开网址；read 重新读取当前页；click 点击第 index 个元素；type 在第 index 个输入框填入 text（submit=true 时回车提交）；" +
        "scroll 翻页（direction=down/up）；back 后退；close 关闭。"
    override val parameters = schemaObject(
        "action" to schemaProp("string", "动作", listOf("open", "read", "click", "type", "scroll", "back", "close")),
        "url" to schemaProp("string", "open：http(s) 网址"),
        "index" to schemaProp("integer", "click / type：元素编号"),
        "text" to schemaProp("string", "type：要填入的文字"),
        "submit" to schemaProp("boolean", "type：填完后是否回车提交", default = false),
        "direction" to schemaProp("string", "scroll：down 或 up", listOf("down", "up")),
        "max_chars" to schemaProp("integer", "正文最多返回的字符数，默认 6000，最大 20000", default = 6000),
        required = listOf("action")
    )

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val limit = args.optInt("max_chars", 6000).coerceIn(500, 20_000)
        val note: String = when (args.optString("action")) {
            "open" -> {
                val url = args.optString("url").trim().let { if (it.startsWith("http")) it else "https://$it" }.toHttpUrlOrNull()
                    ?: return ToolResult.failure("网址不合法", "INVALID_ARGUMENT")
                ctx.onProgress("浏览器打开 ${url.host}")
                AgentBrowser.open(ctx.context, url.toString())
                "已打开"
            }
            "read" -> "当前页面"
            "click" -> {
                ctx.onProgress("浏览器点击")
                if (AgentBrowser.click(args.optInt("index")) == "missing") return ToolResult.failure("没有这个编号的元素，请先 read 重新获取编号", "ELEMENT_NOT_FOUND")
                "已点击 ${args.optInt("index")}"
            }
            "type" -> {
                ctx.onProgress("浏览器输入")
                if (AgentBrowser.type(args.optInt("index"), args.optString("text"), args.optBoolean("submit")) == "missing") {
                    return ToolResult.failure("没有这个编号的输入框，请先 read 重新获取编号", "ELEMENT_NOT_FOUND")
                }
                if (args.optBoolean("submit")) "已填写并提交" else "已填写"
            }
            "scroll" -> { AgentBrowser.scroll(args.optString("direction") != "up"); "已翻页" }
            "back" -> if (AgentBrowser.back()) "已后退" else "没有可后退的页面"
            "close" -> { AgentBrowser.close(); return ToolResult.success("浏览器已关闭") }
            else -> return ToolResult.failure("不支持的 action", "INVALID_ARGUMENT")
        }
        ctx.checkCancelled()
        val page = try { AgentBrowser.read(limit) } catch (error: Exception) {
            return ToolResult.failure("$note，但读取页面失败：${error.message}", "PAGE_UNREADABLE")
        }
        val elements = page.optJSONArray("elements")
        val elementText = if (elements == null) "" else (0 until elements.length()).joinToString("\n") { elements.optString(it) }
        val truncated = page.optInt("text_length") > limit
        return ToolResult.success("$note\n标题：${page.optString("title")}\n地址：${page.optString("url")}\n\n正文${if (truncated) "（已截断，可 scroll 或调大 max_chars）" else ""}：\n" +
            page.optString("text") + "\n\n可交互元素：\n" + elementText.ifBlank { "（无）" })
    }
}
