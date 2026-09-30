package com.voice.actonsay.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
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
            val wrappedDelta: ((String) -> Unit)? = onDelta?.let { cb -> { text -> streamedAnything = true; cb(text) } }
            val result = completeOnce(baseUrl, apiKey, model, messages, tools, stream, wrappedDelta)
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
        onDelta: ((String) -> Unit)?
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

            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    val responseBody = response.body?.string() ?: ""
                    val errMsg = try {
                        val errObj = JSONObject(responseBody).optJSONObject("error")
                        errObj?.optString("message") ?: responseBody
                    } catch (_: Exception) {
                        responseBody
                    }
                    val msg = "API 请求失败 [HTTP ${response.code}]: $errMsg"
                    Log.e(TAG, msg)
                    return@withContext Result.failure(Exception(msg))
                }

                val body = response.body ?: return@withContext Result.failure(Exception("API 返回空响应"))
                val contentType = body.contentType()?.toString() ?: ""

                if (stream && contentType.contains("text/event-stream")) {
                    return@withContext Result.success(parseStream(body.source(), onDelta))
                }

                // 非流式（或服务端忽略了 stream 参数）
                val json = JSONObject(body.string())
                val choices = json.optJSONArray("choices")
                if (choices == null || choices.length() == 0) {
                    return@withContext Result.failure(Exception("API 返回结果为空 (无 choices)"))
                }
                val message = choices.getJSONObject(0).optJSONObject("message")
                    ?: return@withContext Result.failure(Exception("API 返回结果缺少 message"))
                val content = message.optString("content").trim()
                val toolCalls = parseToolCalls(message.optJSONArray("tool_calls"))
                if (content.isNotEmpty()) onDelta?.invoke(content)
                Result.success(ChatResponse(content, toolCalls))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!isActive) throw kotlinx.coroutines.CancellationException("request cancelled", e)
            Log.e(TAG, "Chat completion error", e)
            Result.failure(e)
        }
    }

    private fun parseToolCalls(arr: JSONArray?): List<ToolCall> {
        if (arr == null) return emptyList()
        val list = ArrayList<ToolCall>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val fn = o.optJSONObject("function") ?: continue
            val name = fn.optString("name")
            if (name.isBlank()) continue
            list += ToolCall(
                id = o.optString("id").ifBlank { "call_$i" },
                name = name,
                arguments = fn.optString("arguments").ifBlank { "{}" }
            )
        }
        return list
    }

    private class PartialToolCall(var id: String = "", val name: StringBuilder = StringBuilder(), val args: StringBuilder = StringBuilder())

    private fun kotlinx.coroutines.CoroutineScope.parseStream(source: okio.BufferedSource, onDelta: ((String) -> Unit)?): ChatResponse {
        val sb = StringBuilder()
        val partialCalls = sortedMapOf<Int, PartialToolCall>()
        while (true) {
            ensureActive()
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data.isEmpty()) continue
            if (data == "[DONE]") break
            val chunk = try {
                JSONObject(data)
            } catch (_: Exception) {
                continue
            }
            val choices = chunk.optJSONArray("choices") ?: continue
            if (choices.length() == 0) continue
            val delta = choices.getJSONObject(0).optJSONObject("delta") ?: continue
            val piece = delta.optString("content", "")
            if (piece.isNotEmpty()) {
                sb.append(piece)
                onDelta?.invoke(sb.toString())
            }
            val calls = delta.optJSONArray("tool_calls") ?: continue
            for (i in 0 until calls.length()) {
                val c = calls.optJSONObject(i) ?: continue
                val index = c.optInt("index", i)
                val partial = partialCalls.getOrPut(index) { PartialToolCall() }
                c.optString("id").takeIf { it.isNotBlank() }?.let { partial.id = it }
                val fn = c.optJSONObject("function") ?: continue
                fn.optString("name").takeIf { it.isNotBlank() }?.let { partial.name.append(it) }
                fn.optString("arguments").takeIf { it.isNotEmpty() }?.let { partial.args.append(it) }
            }
        }
        val toolCalls = partialCalls.map { (index, p) ->
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
