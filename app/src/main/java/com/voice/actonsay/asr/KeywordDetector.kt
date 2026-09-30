package com.voice.actonsay.asr

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class KeywordEvent(
    val id: String = UUID.randomUUID().toString(),
    val keyword: String,
    val fullText: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 动作关键词检测器：对最终识别文本做大小写不敏感的子串匹配，
 * 每次命中都会追加一条 [KeywordEvent]。持久化由 [AsrManager] 负责。
 */
class KeywordDetector {
    private val _keywords = MutableStateFlow<Set<String>>(emptySet())
    val keywords: StateFlow<Set<String>> = _keywords.asStateFlow()

    private val _events = MutableStateFlow<List<KeywordEvent>>(emptyList())
    val events: StateFlow<List<KeywordEvent>> = _events.asStateFlow()

    fun addKeyword(word: String) {
        val trimmed = word.trim()
        if (trimmed.isEmpty()) return
        _keywords.value = _keywords.value + trimmed
    }

    fun removeKeyword(word: String) {
        _keywords.value = _keywords.value - word
    }

    fun setKeywords(words: Set<String>) {
        _keywords.value = words.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    fun setEvents(events: List<KeywordEvent>) {
        _events.value = events
    }

    fun removeEvent(id: String) {
        _events.value = _events.value.filterNot { it.id == id }
    }

    fun clearEvents() {
        _events.value = emptyList()
    }

    /** 返回本次命中的关键词列表（可能为空）。 */
    fun check(text: String): List<String> {
        val lowerText = text.lowercase()
        val hits = mutableListOf<String>()
        for (kw in _keywords.value) {
            if (lowerText.contains(kw.lowercase())) {
                Log.i("KeywordDetector", "Keyword '$kw' detected in: $text")
                hits += kw
                _events.value = (_events.value + KeywordEvent(keyword = kw, fullText = text)).takeLast(MAX_EVENTS)
            }
        }
        return hits
    }

    companion object {
        const val MAX_EVENTS = 200
    }
}
