package com.voice.actonsay.tasks

import com.voice.actonsay.asr.AsrManager
import org.json.JSONArray
import org.json.JSONObject

/** 展示、模型上下文和事件日志共用的凭据脱敏边界。 */
object CredentialRedactor {
    private val taskSecrets = java.util.concurrent.CopyOnWriteArraySet<String>()
    fun remember(value: String) { if (value.length >= 4) taskSecrets.add(value) }
    private val sensitive = Regex("(?i)(authorization|api[_-]?key|token|password|passwd|secret|credential|cookie)")
    fun knownSecrets(): List<String> {
        val state = AsrManager.state.value
        return (listOf(state.apiKey, state.aiApiKey, state.mimoApiKey, state.bridgeToken, state.fastDecisionApiKey, state.braveApiKey, state.exaApiKey) + state.aiProviders.map { it.apiKey } + taskSecrets).filter { it.length >= 4 }.distinct()
    }
    fun text(input: String): String {
        var result = input
        knownSecrets()
            .forEach { result = result.replace(it, "[已脱敏]") }
        return result.replace(Regex("(?i)(Bearer\\s+)[^\\s\"']+"), "$1[已脱敏]")
            .replace(Regex("(?i)((?:api[_-]?key|token|password|secret)\\s*[=:]\\s*)[^\\s,;\"']+"), "$1[已脱敏]")
    }
    fun json(value: JSONObject): JSONObject = JSONObject().apply {
        for (key in value.keys()) {
            val item = value.opt(key)
            put(key, if (sensitive.containsMatchIn(key)) "[已脱敏]" else when (item) {
                is JSONObject -> json(item)
                is JSONArray -> array(item)
                is String -> text(item)
                else -> item
            })
        }
    }
    private fun array(value: JSONArray): JSONArray = JSONArray().apply {
        for (index in 0 until value.length()) {
            val item = value.opt(index)
            put(when (item) { is JSONObject -> json(item); is JSONArray -> array(item); is String -> text(item); else -> item })
        }
    }
}
