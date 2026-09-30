package com.voice.actonsay.agent

import android.content.Context
import android.util.Log
import com.voice.actonsay.agent.tools.DeviceStatusTool
import com.voice.actonsay.agent.tools.ListAppsTool
import com.voice.actonsay.agent.tools.OpenAppTool
import com.voice.actonsay.agent.tools.ScreenContentTool
import com.voice.actonsay.agent.tools.ShellTool
import com.voice.actonsay.ai.ChatTurn
import com.voice.actonsay.ai.OpenAiClient
import com.voice.actonsay.ai.ToolCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class AgentConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val systemPrompt: String,
    val stream: Boolean
)

/** 一次工具调用的记录，用于在对话气泡里展示“做了什么”。 */
data class AgentStep(val tool: String, val summary: String, val ok: Boolean) {
    fun render(): String = (if (ok) "✓ " else "✗ ") + summary
}

/**
 * 智能体循环：模型 → 工具 → 模型 … 直到模型给出不带工具调用的最终回复。
 * 进程内单例；[init] 负责注册内置工具并探测 root。
 */
object AgentRunner {
    private const val TAG = "AgentRunner"
    const val MAX_STEPS = 15
    /** 连续失败这么多次后，提示模型停止尝试并向用户说明。 */
    private const val MAX_CONSECUTIVE_FAILURES = 4

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        ToolRegistry.register(DeviceStatusTool())
        ToolRegistry.register(ShellTool())
        ToolRegistry.register(ListAppsTool())
        ToolRegistry.register(OpenAppTool())
        ToolRegistry.register(ScreenContentTool())
        Log.i(TAG, "Registered ${ToolRegistry.all().size} tools")
    }

    /**
     * @param onDelta  模型流式输出的累计文本（每一轮模型调用都会从空开始）
     * @param onStep   每次工具调用完成后的记录
     * @param onProgress 当前正在做的事（一句话，展示在状态栏）
     */
    suspend fun run(
        config: AgentConfig,
        history: List<ChatTurn>,
        userMessage: String,
        onDelta: (String) -> Unit,
        onStep: (AgentStep) -> Unit,
        onProgress: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        val context = appContext ?: return@withContext Result.failure(IllegalStateException("AgentRunner 未初始化"))
        val toolCtx = ToolContext(context, onProgress)
        val messages = OpenAiClient.buildMessages(config.systemPrompt, history, userMessage)
        val tools = ToolRegistry.toOpenAiTools()
        var consecutiveFailures = 0
        var nudged = false

        repeat(MAX_STEPS) { round ->
            ensureActive()
            onProgress(if (round == 0) "" else "思考下一步…")
            val response = OpenAiClient.complete(
                baseUrl = config.baseUrl,
                apiKey = config.apiKey,
                model = config.model,
                messages = messages,
                tools = tools,
                stream = config.stream,
                onDelta = onDelta
            ).getOrElse { return@withContext Result.failure(it) }

            if (!response.hasToolCalls) {
                if (response.content.isBlank()) {
                    return@withContext Result.failure(Exception("模型回复内容为空"))
                }
                return@withContext Result.success(response.content)
            }

            messages.put(assistantMessage(response.content, response.toolCalls))
            for (call in response.toolCalls) {
                ensureActive()
                val step = executeCall(call, toolCtx)
                onStep(step.first)
                consecutiveFailures = if (step.first.ok) 0 else consecutiveFailures + 1
                messages.put(JSONObject().apply {
                    put("role", "tool")
                    put("tool_call_id", call.id)
                    put("content", step.second)
                })
            }
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES && !nudged) {
                nudged = true
                Log.w(TAG, "Too many consecutive tool failures, nudging model to stop")
                messages.put(JSONObject().apply {
                    put("role", "system")
                    put("content", "已连续 $consecutiveFailures 次工具调用失败。请停止继续尝试，用一两句话向用户说明目前做不到的原因和可能的解决办法（例如需要 root 授权）。")
                })
            }
            if (round == MAX_STEPS - 2) {
                messages.put(JSONObject().apply {
                    put("role", "system")
                    put("content", "步骤即将用尽。下一条回复请直接给出目前的结论或结果，不要再调用工具。")
                })
            }
        }
        Result.failure(Exception("任务步骤过多（超过 $MAX_STEPS 步），已停止"))
    }

    private suspend fun executeCall(call: ToolCall, ctx: ToolContext): Pair<AgentStep, String> {
        val tool = ToolRegistry.get(call.name)
        val args = try {
            JSONObject(call.arguments.ifBlank { "{}" })
        } catch (e: Exception) {
            val msg = "参数不是合法 JSON：${call.arguments.take(200)}"
            return AgentStep(call.name, "${call.name}：参数错误", false) to msg
        }
        if (tool == null) {
            return AgentStep(call.name, "未知工具 ${call.name}", false) to "错误：没有名为 ${call.name} 的工具"
        }
        val argSummary = summarizeArgs(args)
        Log.i(TAG, "Tool call ${call.name}($argSummary)")
        return try {
            val output = tool.execute(args, ctx)
            val ok = !looksLikeFailure(output)
            AgentStep(call.name, "${call.name}($argSummary)", ok) to output
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Tool ${call.name} threw", e)
            AgentStep(call.name, "${call.name}($argSummary) 异常", false) to "工具执行异常：${e.message}"
        }
    }

    private fun assistantMessage(content: String, calls: List<ToolCall>): JSONObject = JSONObject().apply {
        put("role", "assistant")
        put("content", content)
        put("tool_calls", JSONArray().apply {
            calls.forEach { c ->
                put(JSONObject().apply {
                    put("id", c.id)
                    put("type", "function")
                    put("function", JSONObject().apply {
                        put("name", c.name)
                        put("arguments", c.arguments)
                    })
                })
            }
        })
    }

    private fun summarizeArgs(args: JSONObject): String {
        val parts = ArrayList<String>()
        args.keys().forEach { k ->
            val v = args.opt(k)?.toString().orEmpty().replace('\n', ' ')
            if (v.isNotBlank()) parts += "$k=${v.take(50)}"
        }
        return parts.joinToString(", ")
    }

    private fun looksLikeFailure(output: String): Boolean {
        val head = output.take(40)
        return head.startsWith("错误") || head.startsWith("已拦截") || head.contains("失败") ||
            Regex("""\[exit=(?!0[,\]])""").containsMatchIn(output.takeLast(40))
    }
}
