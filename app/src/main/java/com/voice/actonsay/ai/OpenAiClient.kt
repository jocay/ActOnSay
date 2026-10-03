package com.voice.actonsay.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import com.voice.actonsay.tasks.CredentialRedactor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ProviderPreset(
    val name: String,
    val baseUrl: String,
    val defaultModel: String,
    val description: String
)

/** 一轮历史对话，role 为 "user" 或 "assistant"。 */
data class ChatTurn(val role: String, val content: String)

/** 模型发起的一次工具调用（OpenAI tool_calls 格式）。 */
data class ToolCall(val id: String, val name: String, val arguments: String)

/** 一次 chat completion 的结果：文本内容与（可能为空的）工具调用列表。 */
data class ChatResponse(val content: String, val toolCalls: List<ToolCall>) {
    val hasToolCalls: Boolean get() = toolCalls.isNotEmpty()
}

object OpenAiClient {
    private const val TAG = "OpenAiClient"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    val PRESETS = listOf(
        ProviderPreset(
            name = "DeepSeek",
            baseUrl = "https://api.deepseek.com/v1",
            defaultModel = "deepseek-chat",
            description = "高性价比强力大模型"
        ),
        ProviderPreset(
            name = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            defaultModel = "gpt-4o-mini",
            description = "官方标准接口"
        ),
        ProviderPreset(
            name = "阿里通义百炼",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            defaultModel = "qwen-plus",
            description = "阿里云百炼兼容模式"
        ),
        ProviderPreset(
            name = "硅基流动",
            baseUrl = "https://api.siliconflow.cn/v1",
            defaultModel = "deepseek-ai/DeepSeek-V3",
            description = "国内高并发 API"
        ),
        ProviderPreset(
            name = "本地 Ollama",
            baseUrl = "http://10.0.2.2:11434/v1",
            defaultModel = "qwen2.5:7b",
            description = "电脑本地运行的 Ollama (10.0.2.2)"
        )
    )

    private val client = OkHttpClient.Builder()
        .dns(SmartDns)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    fun normalizeUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        return when {
            trimmed.endsWith("/chat/completions") -> trimmed
            trimmed.endsWith("/v1") -> "$trimmed/chat/completions"
            else -> "$trimmed/v1/chat/completions"
        }
    }

    /** 组装 system + 历史 + 本轮 user 的 messages 数组。 */
    fun buildMessages(systemPrompt: String, history: List<ChatTurn>, userMessage: String): JSONArray =
        JSONArray().apply {
            if (systemPrompt.isNotBlank()) {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt.trim())
                })
            }
            history.forEach { turn ->
                if (turn.content.isNotBlank()) {
                    put(JSONObject().apply {
                        put("role", turn.role)
                        put("content", turn.content.trim())
                    })
                }
            }
            put(JSONObject().apply {
                put("role", "user")
                put("content", userMessage.trim())
            })
        }

    /**
     * 发起一次纯文本 chat completion（无工具）。
     *
     * @param history   之前的多轮对话（按时间顺序），会插在 system 与本轮 user 之间
     * @param stream    是否使用 SSE 流式返回；为 true 时每收到一段增量文本就回调 [onDelta]
     * @return 完整回复文本
     */
    suspend fun chatCompletion(
        baseUrl: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        userMessage: String,
        history: List<ChatTurn> = emptyList(),
        stream: Boolean = false,
        onDelta: ((String) -> Unit)? = null
    ): Result<String> {
        val messages = buildMessages(systemPrompt, history, userMessage)
        return complete(baseUrl, apiKey, model, messages, tools = null, stream = stream, onDelta = onDelta)
            .mapCatching { resp ->
                if (resp.content.isBlank()) throw Exception("模型回复内容为空")
                resp.content
            }
    }

    /**
     * 通用 chat completion：调用方自行组装 messages，可附带 tools 定义。
     * 返回的 [ChatResponse] 中若 toolCalls 非空，表示模型要求执行工具。
     */
    suspend fun complete(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: JSONArray,
        tools: JSONArray? = null,
        stream: Boolean = false,
        onDelta: ((String) -> Unit)? = null
    ): Result<ChatResponse> = withContext(Dispatchers.IO) {
        var attempt = 0
        while (true) {
            attempt++
            var streamedAnything = false
            val wrappedDelta: (String) -> Unit = { text -> streamedAnything = true; onDelta?.invoke(text) }
            val result = completeOnce(baseUrl, apiKey, model, messages, tools, stream, wrappedDelta) { streamedAnything = true }
            val error = result.exceptionOrNull()
            // 连接被代理/服务端掐断且还没收到任何内容：重试一次（请求本身幂等）
            if (error is java.io.IOException && !streamedAnything && attempt < MAX_ATTEMPTS && isActive) {
                Log.w(TAG, "Request failed with ${error.javaClass.simpleName}: ${error.message}; retrying ($attempt/$MAX_ATTEMPTS)")
                kotlinx.coroutines.delay(600L * attempt)
                continue
            }
            return@withContext result
        }
        @Suppress("UNREACHABLE_CODE")
        Result.failure<ChatResponse>(IllegalStateException("unreachable"))
    }

    private const val MAX_ATTEMPTS = 3

    private suspend fun completeOnce(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: JSONArray,
        tools: JSONArray?,
        stream: Boolean,
        onDelta: ((String) -> Unit)?,
        onStreamStarted: () -> Unit
    ): Result<ChatResponse> = withContext(Dispatchers.IO) {
        try {
            if (baseUrl.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Base URL 不能为空"))
            }
            if (model.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("模型名称不能为空"))
            }

            val targetUrl = normalizeUrl(baseUrl)
            Log.d(TAG, "Requesting $targetUrl model=$model stream=$stream messages=${messages.length()} tools=${tools?.length() ?: 0}")

            val requestJson = JSONObject().apply {
                put("model", model.trim())
                put("messages", messages)
                put("stream", stream)
                if (tools != null && tools.length() > 0) {
                    put("tools", tools)
                    put("tool_choice", "auto")
                }
            }

            val bodyText = requestJson.toString()
            Log.d(TAG, "Request body: ${bodyText.length} chars")
            val requestBuilder = Request.Builder()
                .url(targetUrl)
                .post(bodyText.toRequestBody(JSON_MEDIA_TYPE))

            if (apiKey.isNotBlank()) {
                requestBuilder.header("Authorization", "Bearer ${apiKey.trim()}")
            }
            if (stream) {
                requestBuilder.header("Accept", "text/event-stream")
            }

            val call = client.newCall(requestBuilder.build())
            // 同步读取响应时也取消底层 Socket，避免 Job 取消后仍等到 readTimeout。
            val cancelWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { call.cancel() }
            }
            try { call.execute().use { response ->
                if (!response.isSuccessful) {
                    val responseBody = response.body?.let { readLimitedBody(it) } ?: ""
                    val errMsg = try {
                        val errObj = JSONObject(responseBody).optJSONObject("error")
                        errObj?.optString("message") ?: responseBody
                    } catch (_: Exception) {
                        responseBody
                    }
                    val msg = CredentialRedactor.text("API 请求失败 [HTTP ${response.code}]: $errMsg")
                    Log.e(TAG, msg)
                    return@withContext Result.failure(Exception(msg))
                }

                val body = response.body ?: return@withContext Result.failure(Exception("API 返回空响应"))
                val contentType = body.contentType()?.toString() ?: ""

                if (stream && contentType.contains("text/event-stream")) {
                    return@withContext Result.success(parseStream(body.source(), onDelta, onStreamStarted))
                }

                // 非流式（或服务端忽略了 stream 参数）
                val json = JSONObject(readLimitedBody(body))
                val choices = json.optJSONArray("choices")
                if (choices == null || choices.length() == 0) {
                    return@withContext Result.failure(Exception("API 返回结果为空 (无 choices)"))
                }
                val choice = choices.getJSONObject(0)
                if (choice.optString("finish_reason") !in listOf("stop", "tool_calls")) {
                    return@withContext Result.failure(Exception("模型响应未完整结束：${choice.optString("finish_reason")}"))
                }
                val message = choice.optJSONObject("message")
                    ?: return@withContext Result.failure(Exception("API 返回结果缺少 message"))
                val content = if (message.isNull("content")) "" else message.optString("content").trim()
                if (content.length > MAX_CONTENT_CHARS) throw IllegalStateException("模型回复超过容量预算，未标记为完成")
                val toolCalls = parseToolCalls(message.optJSONArray("tool_calls"))
                if (content.isNotEmpty()) onDelta?.invoke(content)
                Result.success(ChatResponse(content, toolCalls))
            } } finally { cancelWatcher.cancel() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!isActive) throw kotlinx.coroutines.CancellationException("request cancelled", e)
            Log.e(TAG, "Chat completion error: ${e.javaClass.simpleName}")
            Result.failure(e)
        }
    }

    private const val MAX_CONTENT_CHARS = 64_000
    private fun readLimitedBody(body: okhttp3.ResponseBody): String {
        val source = body.source()
        source.request(2L * 1024 * 1024 + 1)
        check(source.buffer.size <= 2L * 1024 * 1024) { "模型响应超过容量预算，未标记为完成" }
        return source.readUtf8()
    }

    private fun parseToolCalls(arr: JSONArray?): List<ToolCall> {
        if (arr == null) return emptyList()
        require(arr.length() <= 32) { "模型一次返回过多工具调用，未执行" }
        val list = ArrayList<ToolCall>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: throw IllegalStateException("模型工具调用格式不完整")
            val fn = o.optJSONObject("function") ?: throw IllegalStateException("模型工具调用缺少function")
            val name = fn.optString("name")
            require(name.isNotBlank() && o.optString("id").isNotBlank()) { "模型工具调用缺少名称或ID" }
            val arguments = fn.optString("arguments").ifBlank { "{}" }
            require(arguments.length <= 256_000) { "模型工具参数超过容量限制" }
            JSONObject(arguments)
            list += ToolCall(
                id = o.getString("id"),
                name = name,
                arguments = arguments
            )
        }
        return list
    }

    private class PartialToolCall(var id: String = "", val name: StringBuilder = StringBuilder(), val args: StringBuilder = StringBuilder())

    private fun kotlinx.coroutines.CoroutineScope.parseStream(source: okio.BufferedSource, onDelta: ((String) -> Unit)?, onStreamStarted: () -> Unit): ChatResponse {
        val sb = StringBuilder()
        val partialCalls = sortedMapOf<Int, PartialToolCall>()
        var finishReason = ""
        var done = false
        while (true) {
            ensureActive()
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data.isEmpty()) continue
            if (data == "[DONE]") { done = true; break }
            val chunk = try {
                JSONObject(data)
            } catch (_: Exception) {
                throw java.io.IOException("模型流包含无效 JSON，回复未完成")
            }
            val choices = chunk.optJSONArray("choices") ?: continue
            if (choices.length() == 0) continue
            val choice = choices.getJSONObject(0)
            if (!choice.isNull("finish_reason")) finishReason = choice.optString("finish_reason")
            val delta = choice.optJSONObject("delta") ?: continue
            val piece = if (delta.isNull("content")) "" else delta.optString("content", "")
            if (piece.isNotEmpty()) {
                onStreamStarted()
                sb.append(piece)
                if (sb.length > MAX_CONTENT_CHARS) throw IllegalStateException("模型回复超过容量预算，未标记为完成")
                onDelta?.invoke(sb.toString())
            }
            val calls = delta.optJSONArray("tool_calls") ?: continue
            if (calls.length() > 0) onStreamStarted()
            for (i in 0 until calls.length()) {
                val c = calls.optJSONObject(i) ?: continue
                val index = c.optInt("index", i)
                val partial = partialCalls.getOrPut(index) { PartialToolCall() }
                if (partialCalls.size > 32) throw IllegalStateException("模型一次返回过多工具调用，未执行")
                c.optString("id").takeIf { it.isNotBlank() }?.let { partial.id = it }
                val fn = c.optJSONObject("function") ?: continue
                fn.optString("name").takeIf { it.isNotBlank() }?.let { partial.name.append(it) }
                if (partial.name.length > 128) throw IllegalStateException("模型工具名称超过容量限制")
                fn.optString("arguments").takeIf { it.isNotEmpty() }?.let { partial.args.append(it) }
                if (partial.args.length > 256_000) throw java.io.IOException("工具参数超过容量限制")
            }
        }
        if (finishReason !in listOf("stop", "tool_calls") || (!done && finishReason.isBlank())) {
            throw java.io.IOException("模型流未完整结束：${finishReason.ifBlank { "连接提前关闭" }}")
        }
        val toolCalls = partialCalls.map { (index, p) ->
            if (p.id.isBlank() || p.name.isBlank()) throw java.io.IOException("工具调用未完整返回")
            try { JSONObject(p.args.toString().ifBlank { "{}" }) } catch (_: Exception) {
                throw java.io.IOException("工具调用参数未完整返回")
            }
            ToolCall(
                id = p.id.ifBlank { "call_$index" },
                name = p.name.toString(),
                arguments = p.args.toString().ifBlank { "{}" }
            )
        }.filter { it.name.isNotBlank() }
        return ChatResponse(sb.toString().trim(), toolCalls)
    }

    suspend fun testConnection(
        baseUrl: String,
        apiKey: String,
        model: String
    ): Result<String> = chatCompletion(
        baseUrl = baseUrl,
        apiKey = apiKey,
        model = model,
        systemPrompt = "You are a helpful assistant. Reply with: OK",
        userMessage = "Ping test, please reply with OK"
    )
}
