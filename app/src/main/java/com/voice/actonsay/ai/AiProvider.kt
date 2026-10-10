package com.voice.actonsay.ai

import org.json.JSONObject

enum class AiApiFormat(val label: String) {
    CHAT_COMPLETIONS("OpenAI Chat Completions"),
    RESPONSES("OpenAI Responses"),
    CLAUDE("Claude Messages"),
    GEMINI("Gemini generateContent");

    companion object {
        fun from(value: String): AiApiFormat = entries.firstOrNull { it.name == value } ?: CHAT_COMPLETIONS
    }
}

/** 密钥单独加密保存，供应商元数据不包含凭据。 */
data class AiProvider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val model: String,
    val format: AiApiFormat = AiApiFormat.CHAT_COMPLETIONS,
    val apiKey: String = ""
) {
    fun metadata(): JSONObject = JSONObject().put("id", id).put("name", name)
        .put("baseUrl", baseUrl).put("model", model).put("format", format.name)
}
