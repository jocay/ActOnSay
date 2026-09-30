package com.voice.actonsay.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 工具执行时可用的环境。 */
class ToolContext(
    val context: Context,
    /** 向 UI 汇报当前进度（一句话）。 */
    val onProgress: (String) -> Unit
)

/** 可被大模型调用的工具。参数用 JSON Schema 描述，返回值为纯文本（喂回模型）。 */
interface AgentTool {
    val name: String
    val description: String
    val parameters: JSONObject

    suspend fun execute(args: JSONObject, ctx: ToolContext): String
}

/** 工具注册表：进程内单例，按注册顺序暴露给模型。 */
object ToolRegistry {
    private val tools = LinkedHashMap<String, AgentTool>()

    @Synchronized
    fun register(tool: AgentTool) {
        tools[tool.name] = tool
    }

    @Synchronized
    fun get(name: String): AgentTool? = tools[name]

    @Synchronized
    fun all(): List<AgentTool> = tools.values.toList()

    /** 转成 OpenAI chat completions 的 `tools` 数组。 */
    fun toOpenAiTools(): JSONArray = JSONArray().apply {
        all().forEach { t ->
            put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", t.name)
                    put("description", t.description)
                    put("parameters", t.parameters)
                })
            })
        }
    }
}

// ---------------------------------------------------------------------------
// JSON Schema 小助手
// ---------------------------------------------------------------------------

fun schemaObject(vararg props: Pair<String, JSONObject>, required: List<String> = emptyList()): JSONObject =
    JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply { props.forEach { (k, v) -> put(k, v) } })
        if (required.isNotEmpty()) put("required", JSONArray(required))
    }

fun schemaProp(type: String, description: String, enumValues: List<String>? = null, default: Any? = null): JSONObject =
    JSONObject().apply {
        put("type", type)
        put("description", description)
        if (enumValues != null) put("enum", JSONArray(enumValues))
        if (default != null) put("default", default)
    }

/** 截断过长的工具输出，避免撑爆上下文。 */
fun truncateOutput(text: String, maxChars: Int = 8000): String {
    if (text.length <= maxChars) return text
    val head = text.substring(0, maxChars * 3 / 4)
    val tail = text.substring(text.length - maxChars / 4)
    return "$head\n…[已省略 ${text.length - maxChars} 字符]…\n$tail"
}
