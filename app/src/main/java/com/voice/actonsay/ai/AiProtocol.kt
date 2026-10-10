package com.voice.actonsay.ai

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 在统一消息记录与供应商原生协议之间转换；原生回复随检查点保存。 */
internal object AiProtocol {
    const val NATIVE = "provider_output"
    private fun JSONArray.rows(): List<JSONObject> = List(length()) { getJSONObject(it) }
    private fun text(value: String) = JSONObject().put("type", "text").put("text", value)

    fun url(base: String, model: String, format: AiApiFormat, stream: Boolean): String {
        val parsed = base.trim().toHttpUrl()
        var path = parsed.encodedPath.trimEnd('/')
        path = path.replace(Regex("/(chat/completions|responses|messages)$"), "")
        path = path.replace(Regex("/models/[^/]+:(streamGenerateContent|generateContent)$"), "")
        val version = if (format == AiApiFormat.GEMINI) "v1beta" else "v1"
        if (path.isBlank()) path = "/$version"
        else if (!Regex("/v[0-9]+(beta[0-9]*)?$").containsMatchIn(path)) path += "/$version"
        val builder = parsed.newBuilder().encodedPath(path)
        when (format) {
            AiApiFormat.CHAT_COMPLETIONS -> builder.addPathSegment("chat").addPathSegment("completions")
            AiApiFormat.RESPONSES -> builder.addPathSegment("responses")
            AiApiFormat.CLAUDE -> builder.addPathSegment("messages")
            AiApiFormat.GEMINI -> {
                builder.addPathSegment("models").addPathSegment(model.trim().removePrefix("models/") +
                    if (stream) ":streamGenerateContent" else ":generateContent")
                if (stream) builder.setQueryParameter("alt", "sse")
            }
        }
        return builder.build().toString()
    }

    private fun parts(message: JSONObject, format: AiApiFormat): JSONArray {
        val content = message.opt("content")
        if (content !is JSONArray) return JSONArray().apply {
            val value = if (message.isNull("content")) "" else message.optString("content")
            if (value.isNotBlank()) put(if (format == AiApiFormat.GEMINI) JSONObject().put("text", value) else
                if (format == AiApiFormat.RESPONSES) JSONObject().put("type", "input_text").put("text", value) else text(value))
        }
        return JSONArray().apply {
            content.rows().forEach { part ->
                when (part.optString("type")) {
                    "text" -> put(when (format) {
                        AiApiFormat.GEMINI -> JSONObject().put("text", part.getString("text"))
                        AiApiFormat.RESPONSES -> JSONObject().put("type", "input_text").put("text", part.getString("text"))
                        else -> part
                    })
                    "image_url" -> {
                        val image = part.getJSONObject("image_url").getString("url")
                        if (format == AiApiFormat.RESPONSES) {
                            put(JSONObject().put("type", "input_image").put("image_url", image))
                        } else {
                            require(image.startsWith("data:image/") && image.contains(";base64,")) { "此接口需要内嵌图片数据" }
                            val mime = image.substringAfter("data:").substringBefore(';')
                            val data = image.substringAfter(";base64,")
                            put(if (format == AiApiFormat.GEMINI) JSONObject().put("inlineData", JSONObject().put("mimeType", mime).put("data", data))
                            else JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64").put("media_type", mime).put("data", data)))
                        }
                    }
                }
            }
        }
    }

    fun request(model: String, messages: JSONArray, tools: JSONArray?, format: AiApiFormat, stream: Boolean): JSONObject {
        val request = JSONObject()
        val rows = messages.rows()
        val system = rows.filter { it.optString("role") == "system" }.joinToString("\n\n") { it.optString("content") }
        val definitions = tools?.rows().orEmpty().map { it.getJSONObject("function") }
        if (format == AiApiFormat.RESPONSES) {
            val input = JSONArray()
            for (message in rows) {
                when (message.optString("role")) {
                    "system" -> Unit
                    "tool" -> input.put(JSONObject().put("type", "function_call_output").put("call_id", message.getString("tool_call_id")).put("output", message.optString("content")))
                    "assistant" -> {
                        val native = message.optJSONObject(NATIVE)?.takeIf { it.optString("format") == format.name }?.optJSONArray("items")
                        if (native != null) native.rows().forEach { input.put(it) }
                        else {
                            if (message.optString("content").isNotBlank()) input.put(JSONObject().put("role", "assistant").put("content", message.optString("content")))
                            message.optJSONArray("tool_calls")?.rows()?.forEach { call ->
                                val fn = call.getJSONObject("function")
                                input.put(JSONObject().put("type", "function_call").put("call_id", call.getString("id")).put("name", fn.getString("name")).put("arguments", fn.getString("arguments")))
                            }
                        }
                    }
                    else -> input.put(JSONObject().put("role", "user").put("content", parts(message, format)))
                }
            }
            request.put("model", model.trim()).put("input", input).put("stream", stream).put("store", false)
                .put("include", JSONArray().put("reasoning.encrypted_content"))
            if (system.isNotBlank()) request.put("instructions", system)
            if (definitions.isNotEmpty()) request.put("tools", JSONArray().apply {
                definitions.forEach { fn -> put(JSONObject().put("type", "function").put("name", fn.getString("name"))
                    .put("description", fn.optString("description")).put("parameters", fn.getJSONObject("parameters")).put("strict", false)) }
            })
            return request
        }
        val converted = JSONArray()
        val callNames = mutableMapOf<String, String>()
        for (message in rows) {
            val role = message.optString("role")
            if (role == "system") continue
            val calls = message.optJSONArray("tool_calls")?.rows().orEmpty()
            calls.forEach { callNames[it.getString("id")] = it.getJSONObject("function").getString("name") }
            val native = message.optJSONObject(NATIVE)?.takeIf { it.optString("format") == format.name }?.optJSONArray("items")
            val blocks = if (role == "assistant" && native != null) JSONArray(native.toString()) else parts(message, format)
            if (role == "tool") {
                // 工具结果必须紧随模型调用，并批量放入同一条 user 消息。
                while (blocks.length() > 0) blocks.remove(0)
                val id = message.getString("tool_call_id")
                blocks.put(if (format == AiApiFormat.CLAUDE) JSONObject().put("type", "tool_result").put("tool_use_id", id).put("content", message.optString("content"))
                else JSONObject().put("functionResponse", JSONObject().put("name", callNames[id] ?: error("工具调用记录缺少名称"))
                    .put("response", JSONObject().put("result", message.optString("content"))).apply {
                        // 新版 Gemini 可提供函数 ID，旧版不要求该字段。
                        if (!id.startsWith("gemini_")) put("id", id)
                    }))
            } else if (native == null) calls.forEach { call ->
                val fn = call.getJSONObject("function")
                blocks.put(if (format == AiApiFormat.CLAUDE) JSONObject().put("type", "tool_use").put("id", call.getString("id"))
                    .put("name", fn.getString("name")).put("input", JSONObject(fn.optString("arguments", "{}")))
                else JSONObject().put("functionCall", JSONObject().put("name", fn.getString("name")).put("args", JSONObject(fn.optString("arguments", "{}")))))
            }
            if (blocks.length() == 0) continue
            val outputRole = if (role == "assistant") (if (format == AiApiFormat.GEMINI) "model" else "assistant") else "user"
            val field = if (format == AiApiFormat.GEMINI) "parts" else "content"
            val last = converted.optJSONObject(converted.length() - 1)
            if (last?.optString("role") == outputRole) blocks.rows().forEach { last.getJSONArray(field).put(it) }
            else converted.put(JSONObject().put("role", outputRole).put(field, blocks))
        }
        if (format == AiApiFormat.CLAUDE) {
            request.put("model", model.trim()).put("max_tokens", 8192).put("messages", converted).put("stream", stream)
            if (system.isNotBlank()) request.put("system", system)
            if (definitions.isNotEmpty()) request.put("tools", JSONArray().apply {
                definitions.forEach { fn -> put(JSONObject().put("name", fn.getString("name")).put("description", fn.optString("description")).put("input_schema", fn.getJSONObject("parameters"))) }
            })
        } else {
            request.put("contents", converted)
            if (system.isNotBlank()) request.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            if (definitions.isNotEmpty()) request.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", JSONArray().apply {
                definitions.forEach { fn -> put(JSONObject().put("name", fn.getString("name")).put("description", fn.optString("description"))
                    .put("parametersJsonSchema", fn.getJSONObject("parameters"))) }
            })))
        }
        return request
    }

    fun response(json: JSONObject, format: AiApiFormat): ChatResponse {
        require(!json.has("error")) { "模型接口返回错误" }
        val items: JSONArray
        when (format) {
            AiApiFormat.RESPONSES -> {
                require(json.optString("status") == "completed") { "模型响应未完整结束：${json.optString("status")}" }
                items = json.getJSONArray("output")
            }
            AiApiFormat.CLAUDE -> {
                require(json.optString("stop_reason") in listOf("end_turn", "tool_use", "stop_sequence")) { "模型响应未完整结束：${json.optString("stop_reason")}" }
                items = json.getJSONArray("content")
                require(json.optString("stop_reason") == "tool_use" || items.rows().none { it.optString("type") == "tool_use" }) { "模型未授权执行工具调用" }
            }
            AiApiFormat.GEMINI -> {
                val candidate = json.optJSONArray("candidates")?.optJSONObject(0) ?: error("模型未返回候选回复，可能被安全策略拦截")
                require(candidate.optString("finishReason") == "STOP") { "模型响应未完整结束：${candidate.optString("finishReason")}" }
                items = candidate.getJSONObject("content").getJSONArray("parts")
            }
            else -> error("不支持的协议")
        }
        val content = StringBuilder()
        val calls = mutableListOf<ToolCall>()
        for (item in items.rows()) {
            when (format) {
                AiApiFormat.RESPONSES -> when (item.optString("type")) {
                    "message" -> item.optJSONArray("content")?.rows()?.forEach { if (it.optString("type") == "output_text") content.append(it.optString("text")) }
                    "function_call" -> calls.add(ToolCall(item.getString("call_id"), item.getString("name"), item.optString("arguments", "{}")))
                }
                AiApiFormat.CLAUDE -> when (item.optString("type")) {
                    "text" -> content.append(item.optString("text"))
                    "tool_use" -> calls.add(ToolCall(item.getString("id"), item.getString("name"), item.getJSONObject("input").toString()))
                }
                AiApiFormat.GEMINI -> {
                    if (!item.optBoolean("thought")) content.append(item.optString("text", ""))
                    item.optJSONObject("functionCall")?.let { fn -> calls.add(ToolCall(fn.optString("id").ifBlank { "gemini_${UUID.randomUUID()}" }, fn.getString("name"), fn.optJSONObject("args")?.toString() ?: "{}")) }
                }
                else -> Unit
            }
        }
        require(content.length <= 64_000 && calls.size <= 32) { "模型响应超过容量预算" }
        calls.forEach {
            require(it.id.isNotBlank() && it.name.isNotBlank() && it.name.length <= 128 && it.arguments.length <= 256_000) { "工具调用格式不完整或超过容量限制" }
            JSONObject(it.arguments)
        }
        return ChatResponse(content.toString().trim(), calls, JSONObject().put("format", format.name).put("items", items))
    }

    fun CoroutineScope.stream(source: BufferedSource, format: AiApiFormat, onDelta: ((String) -> Unit)?, onStarted: () -> Unit): ChatResponse {
        val text = StringBuilder()
        val blocks = sortedMapOf<Int, JSONObject>()
        val arguments = mutableMapOf<Int, StringBuilder>()
        var terminal: JSONObject? = null
        var stopReason = ""
        var stopped = false
        var bytes = 0
        val eventData = StringBuilder()
        fun consume(data: String) {
            if (data.isBlank() || data == "[DONE]") return
            val event = JSONObject(data)
            onStarted()
            require(!event.has("error") && event.optString("type") != "error") { "模型流返回错误，回复未完成" }
            var piece = ""
            when (format) {
                AiApiFormat.RESPONSES -> when (event.optString("type")) {
                    "response.output_text.delta" -> piece = event.optString("delta")
                    "response.completed" -> { terminal = event.getJSONObject("response"); stopped = true }
                    "response.failed", "response.incomplete" -> error("模型响应未完整结束")
                }
                AiApiFormat.CLAUDE -> when (event.optString("type")) {
                    "content_block_start" -> blocks[event.getInt("index")] = event.getJSONObject("content_block")
                    "content_block_delta" -> {
                        val index = event.getInt("index")
                        val block = blocks[index] ?: error("模型流缺少内容块")
                        val delta = event.getJSONObject("delta")
                        when (delta.optString("type")) {
                            "text_delta" -> { piece = delta.optString("text"); block.put("text", block.optString("text") + piece) }
                            "input_json_delta" -> arguments.getOrPut(index) { StringBuilder() }.append(delta.optString("partial_json"))
                            "thinking_delta" -> block.put("thinking", block.optString("thinking") + delta.optString("thinking"))
                            "signature_delta" -> block.put("signature", block.optString("signature") + delta.optString("signature"))
                        }
                    }
                    "message_delta" -> stopReason = event.getJSONObject("delta").optString("stop_reason")
                    "message_stop" -> stopped = true
                }
                AiApiFormat.GEMINI -> {
                    val candidate = event.optJSONArray("candidates")?.optJSONObject(0)
                    candidate?.optJSONArrayInContent()?.rows()?.forEach { part ->
                        if (!part.optBoolean("thought")) piece += part.optString("text", "")
                        blocks[blocks.size] = part
                    }
                    if (candidate != null && candidate.has("finishReason")) {
                        stopReason = candidate.getString("finishReason")
                        stopped = true
                    }
                }
                else -> error("不支持的协议")
            }
            if (piece.isNotEmpty()) {
                text.append(piece)
                require(text.length <= 64_000) { "模型回复超过容量预算" }
                onDelta?.invoke(text.toString())
            }
        }
        while (true) {
            ensureActive()
            val line = source.readUtf8Line() ?: break
            bytes += line.length
            require(bytes <= 2 * 1024 * 1024) { "模型流超过容量预算" }
            if (line.isEmpty()) {
                consume(eventData.toString()); eventData.setLength(0)
                if (stopped && format != AiApiFormat.GEMINI) break
            } else if (line.startsWith("data:")) {
                if (eventData.isNotEmpty()) eventData.append('\n')
                eventData.append(line.removePrefix("data:").trimStart())
            }
        }
        if (eventData.isNotEmpty()) consume(eventData.toString())
        if (!stopped) throw java.io.IOException("模型流提前关闭，回复未完成")
        return when (format) {
            AiApiFormat.RESPONSES -> response(terminal ?: error("模型流缺少完整回复"), format)
            AiApiFormat.CLAUDE -> {
                arguments.forEach { (index, value) -> blocks.getValue(index).put("input", JSONObject(value.toString())) }
                response(JSONObject().put("stop_reason", stopReason).put("content", JSONArray().apply { blocks.values.forEach { put(it) } }), format)
            }
            AiApiFormat.GEMINI -> response(JSONObject().put("candidates", JSONArray().put(JSONObject().put("finishReason", stopReason)
                .put("content", JSONObject().put("parts", JSONArray().apply { blocks.values.forEach { put(it) } })))), format)
            else -> error("不支持的协议")
        }
    }

    private fun JSONObject.optJSONArrayInContent(): JSONArray? = optJSONObject("content")?.optJSONArray("parts")
}
