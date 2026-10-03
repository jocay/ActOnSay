package com.voice.actonsay.agent

import android.content.Context
import com.voice.actonsay.ai.ToolCall
import com.voice.actonsay.asr.AsrManager
import com.voice.actonsay.tasks.CredentialRedactor
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

enum class ExecutionEnvironment { ANDROID, DEBIAN }

/** 工具的完整结果落库，模型只接收受限的文本和产物引用。 */
data class ToolResult(
    val status: String,
    val output: String = "",
    val exitCode: Int? = null,
    val outputRef: String? = null,
    val errorType: String? = null,
    val executionId: String? = null,
    val artifacts: JSONArray = JSONArray(),
    val data: JSONObject? = null
) {
    val ok: Boolean get() = status == "SUCCEEDED"
    fun toJson(): JSONObject = JSONObject().apply {
        put("status", status); put("output", output); put("artifacts", artifacts)
        exitCode?.let { put("exitCode", it) }; outputRef?.let { put("outputRef", it) }
        errorType?.let { put("errorType", it) }; executionId?.let { put("executionId", it) }
        data?.let { put("data", it) }
    }
    fun displayJson(): JSONObject {
        val json = toJson().put("output", truncateOutput(output, 16_000))
        if (data != null && data.toString().length > 32_000) json.put("data", JSONObject()
            .put("说明", "完整结构化结果已保存在加密任务记录中，当前展示省略大数据。"))
        if (artifacts.length() > 200) json.put("artifacts", JSONArray().apply { for (i in 0 until 200) put(artifacts.get(i)) })
            .put("artifactCount", artifacts.length())
        return CredentialRedactor.json(json)
    }
    fun modelContent(): String = displayJson().put("output", truncateOutput(output)).toString()
    companion object {
        fun success(output: String, data: JSONObject? = null) = ToolResult("SUCCEEDED", output, data = data)
        fun failure(output: String, type: String = "EXECUTION_FAILED") = ToolResult("FAILED", output, errorType = type)
        fun fromJson(json: JSONObject) = ToolResult(
            json.optString("status", "FAILED"), json.optString("output"),
            if (json.has("exitCode")) json.optInt("exitCode") else null,
            json.optString("outputRef").ifBlank { null }, json.optString("errorType").ifBlank { null },
            json.optString("executionId").ifBlank { null }, json.optJSONArray("artifacts") ?: JSONArray(),
            json.optJSONObject("data")
        )
    }
}

class AuthorizationRequired(val callId: String) : Exception("工具调用等待应用授权")
class UncertainToolResult : Exception("上次操作结果待确认，需要检查结果后显式处理")

/** 先持久化调用和恢复检查点，再发生副作用。 */
interface AgentObserver {
    suspend fun checkpoint(messages: JSONArray)
    suspend fun beginTool(call: ToolCall): ToolResult?
    suspend fun finishTool(call: ToolCall, result: ToolResult)
    suspend fun executionStarted(callId: String, executionId: String)
    suspend fun authorize(callId: String, environment: ExecutionEnvironment): Boolean?
}

class ToolContext(
    val context: Context,
    val onProgress: (String) -> Unit,
    val taskId: String = "",
    val callId: String = "",
    val cancellation: Job? = null,
    val environment: ExecutionEnvironment = ExecutionEnvironment.ANDROID,
    val observer: AgentObserver? = null
) {
    fun checkCancelled() { cancellation?.ensureActive() }
    suspend fun requirePermission(target: ExecutionEnvironment) {
        checkCancelled()
        val state = AsrManager.state.value
        if (target == ExecutionEnvironment.DEBIAN && state.debianExecutionAllowed) return
        if (target == ExecutionEnvironment.ANDROID && state.androidRootAllowed) return
        when (observer?.authorize(callId, target)) {
            true -> return
            false -> throw SecurityException("用户拒绝了本次工具调用")
            null -> throw AuthorizationRequired(callId)
        }
    }
    /** 不受自主模式影响、每次都要界面确认的操作（打电话、发短信）。 */
    suspend fun requireConfirmation() {
        checkCancelled()
        when (observer?.authorize(callId, ExecutionEnvironment.ANDROID)) {
            true -> return
            false -> throw SecurityException("用户拒绝了本次操作")
            null -> if (observer == null) throw SecurityException("这个操作需要在任务里由用户确认") else throw AuthorizationRequired(callId)
        }
    }

    suspend fun requireAndroidRoot(): Boolean {
        requirePermission(ExecutionEnvironment.ANDROID)
        return runInterruptible(Dispatchers.IO) { RootShell.checkRoot() }
    }
    suspend fun recordExecution(id: String) { observer?.executionStarted(callId, id) }
}

/** 设备前台操作互斥，避免任务和对话同时点击同一屏幕。 */
object DeviceOperationGate {
    private val mutex = Mutex()
    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock { block() }
}

interface AgentTool {
    val name: String
    val description: String
    val parameters: JSONObject
    suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult
}

object ToolRegistry {
    private val tools = LinkedHashMap<String, AgentTool>()
    @Synchronized fun register(tool: AgentTool) { tools[tool.name] = tool }
    @Synchronized fun get(name: String): AgentTool? = tools[name]
    @Synchronized fun all(): List<AgentTool> = tools.values.toList()
    @Synchronized fun removePrefix(prefix: String) { tools.keys.removeAll { it.startsWith(prefix) } }
    fun toOpenAiTools(): JSONArray = JSONArray().apply {
        all().forEach { tool -> put(JSONObject().put("type", "function").put("function", JSONObject()
            .put("name", tool.name).put("description", tool.description).put("parameters", tool.parameters))) }
    }
}

fun schemaObject(vararg props: Pair<String, JSONObject>, required: List<String> = emptyList()): JSONObject =
    JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply { props.forEach { (key, value) -> put(key, value) } })
        if (required.isNotEmpty()) put("required", JSONArray(required))
        put("additionalProperties", false)
    }

fun schemaProp(type: String, description: String, enumValues: List<String>? = null, default: Any? = null): JSONObject =
    JSONObject().apply {
        put("type", type); put("description", description)
        if (type == "array") put("items", JSONObject().put("type", "string"))
        enumValues?.let { put("enum", JSONArray(it)) }; default?.let { put("default", it) }
    }

fun truncateOutput(text: String, maxChars: Int = 8000): String {
    if (text.length <= maxChars) return text
    return text.take(maxChars * 3 / 4) + "\n…[已省略 ${text.length - maxChars} 字符]…\n" + text.takeLast(maxChars / 4)
}
