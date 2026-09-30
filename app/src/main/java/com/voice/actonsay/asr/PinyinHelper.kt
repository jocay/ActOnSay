package com.voice.actonsay.asr

import android.content.res.AssetManager
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

object PinyinHelper {
    private const val TAG = "PinyinHelper"
    private val charToTokens = HashMap<Char, String>(21000)
    private var isInitialized = false

    @Synchronized
    fun init(assetManager: AssetManager) {
        if (isInitialized) return
        try {
            assetManager.open("pinyin_dict.txt").use { inputStream ->
                BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        val trimmed = line.trim()
                        val colonIndex = trimmed.indexOf(':')
                        if (colonIndex > 0) {
                            val ch = trimmed[0]
                            val tokens = trimmed.substring(colonIndex + 1).trim()
                            charToTokens[ch] = tokens
                        }
                        line = reader.readLine()
                    }
                }
            }
            isInitialized = true
            Log.i(TAG, "Loaded ${charToTokens.size} pinyin mapping entries")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load pinyin_dict.txt", e)
        }
    }

    /**
     * Converts a single wake word (e.g. "你好小智") to sherpa-onnx KWS keyword format:
     * "n ǐ h ǎo x iǎo zh ì @你好小智"
     */
    fun convertToKwsFormat(rawWord: String): String? {
        val word = rawWord.trim()
        if (word.isEmpty()) return null

        // If the word is already pre-formatted with tokens (contains @)
        if (word.contains("@")) return word

        val tokensList = mutableListOf<String>()
        for (ch in word) {
            val token = charToTokens[ch]
            if (token != null) {
                tokensList.add(token)
            } else if (ch.isLetterOrDigit()) {
                // If it's ascii letter, uppercase it or use directly
                tokensList.add(ch.uppercaseChar().toString())
            } else if (ch.isWhitespace()) {
                // skip whitespace
            } else {
                Log.w(TAG, "Unsupported character in wake word: $ch")
                return null
            }
        }

        if (tokensList.isEmpty()) return null
        val tokenStr = tokensList.joinToString(" ")
        // Label cannot contain spaces, replace spaces with underscores
        val label = word.replace("\\s+".toRegex(), "_")
        return "$tokenStr @$label"
    }

    /**
     * Converts a list of wake words into sherpa-onnx slash-separated stream keywords string:
     * "n ǐ h ǎo x iǎo zh ì @你好小智 / x iǎo ài t óng x ué @小爱同学"
     */
    fun formatWakeWordsForStream(wakeWords: Collection<String>): String {
        val list = mutableListOf<String>()
        for (word in wakeWords) {
            val formatted = convertToKwsFormat(word)
            if (formatted != null) {
                list.add(formatted)
            }
        }
        return list.joinToString(" / ")
    }
}
