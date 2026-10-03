package com.voice.actonsay.tts

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object MimoTtsClient {
    private const val TAG = "MimoTtsClient"
    const val DEFAULT_API_URL = "https://api.xiaomimimo.com/v1/chat/completions"
    const val DEFAULT_MODEL = "mimo-v2.5-tts"
    const val DEFAULT_VOICE = "mimo_default"
    const val DEFAULT_KEY = ""

    val SUPPORTED_VOICES = listOf(
        "mimo_default" to "默认 (冰糖)",
        "冰糖" to "冰糖 (女声)",
        "茉莉" to "茉莉 (女声)",
        "苏打" to "苏打 (男声)",
        "白桦" to "白桦 (男声)",
        "Mia" to "Mia (英文女声)",
        "Dean" to "Dean (英文男声)"
    )

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .dns(com.voice.actonsay.ai.SmartDns)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun synthesize(
        text: String,
        apiKey: String = DEFAULT_KEY,
        voice: String = DEFAULT_VOICE,
        stylePrompt: String = "",
        format: String = "mp3"
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("合成文本不能为空"))
            }

            val key = apiKey.trim()
            require(key.isNotEmpty()) { "请先配置 MiMo TTS API Key，或使用系统 TTS" }

            val messagesArray = JSONArray().apply {
                if (stylePrompt.isNotBlank()) {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", stylePrompt.trim())
                    })
                }
                put(JSONObject().apply {
                    put("role", "assistant")
                    put("content", trimmed)
                })
            }

            val audioObj = JSONObject().apply {
                put("format", format)
                put("voice", if (voice.isNotBlank()) voice.trim() else DEFAULT_VOICE)
            }

            val requestJson = JSONObject().apply {
                put("model", DEFAULT_MODEL)
                put("messages", messagesArray)
                put("audio", audioObj)
            }

            val request = Request.Builder()
                .url(DEFAULT_API_URL)
                .header("api-key", key)
                .header("Authorization", "Bearer $key")
                .post(requestJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            Log.d(TAG, "Requesting MiMo TTS: voice=$voice, format=$format, textLength=${trimmed.length}")

            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    val errMsg = try {
                        val obj = JSONObject(bodyString)
                        obj.optJSONObject("error")?.optString("message") ?: bodyString
                    } catch (_: Exception) {
                        bodyString
                    }
                    val msg = "MiMo TTS 请求失败 [HTTP ${response.code}]: $errMsg"
                    Log.e(TAG, msg)
                    return@withContext Result.failure(Exception(msg))
                }

                val json = JSONObject(bodyString)
                val choices = json.optJSONArray("choices")
                if (choices == null || choices.length() == 0) {
                    return@withContext Result.failure(Exception("MiMo TTS 返回 choices 为空"))
                }

                val firstChoice = choices.getJSONObject(0)
                val message = firstChoice.optJSONObject("message")
                val audio = message?.optJSONObject("audio")
                val base64Data = audio?.optString("data")

                if (base64Data.isNullOrEmpty()) {
                    return@withContext Result.failure(Exception("MiMo TTS 未返回音频数据"))
                }

                val audioBytes = Base64.decode(base64Data, Base64.DEFAULT)
                Log.d(TAG, "MiMo TTS synthesis succeeded, audio bytes size: ${audioBytes.size}")
                Result.success(audioBytes)
            }
        } catch (e: Exception) {
            Log.e(TAG, "MiMo TTS synthesis error", e)
            Result.failure(e)
        }
    }
}
