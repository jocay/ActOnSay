package com.voice.actonsay.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 新消息到来时上一轮仍在处理的处置方式。 */
enum class OverlapDecision { QUEUE, INTERRUPT, MERGE, STOP }

/**
 * TypeSafe Jev（System One）快速决策模型：只做选择、打分和是非判断，不生成文本。
 * 任何失败、超时或低置信度都返回 null，由调用方退回默认规则，不能阻塞对话。
 */
object FastDecisionClient {
    private const val TAG = "FastDecision"
    private const val ENDPOINT = "https://api.typesafe.ai/v1/systemone"
    private const val MODEL = "jev-latest"
    private const val MIN_CONFIDENCE = 0.6
    private const val STOP_THRESHOLD = 0.7
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder().dns(SmartDns)
        .connectTimeout(3, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS).callTimeout(5, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()

    /** 原始调用：返回 answers 对象；失败返回 null。 */
    suspend fun ask(apiKey: String, state: Any, questions: JSONObject, timeoutMs: Long = 2500): JSONObject? {
        if (apiKey.isBlank()) return null
        val body = JSONObject().put("model", MODEL).put("state", state).put("questions", questions).toString()
        val request = Request.Builder().url(ENDPOINT).header("Authorization", "Bearer ${apiKey.trim()}")
            .post(body.toRequestBody(JSON_TYPE)).build()
        return withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.IO) {
                try {
                    receive(client.newCall(request)) { response ->
                        val text = response.body?.source()?.let { source -> source.request(256 * 1024); source.buffer.readUtf8() }.orEmpty()
                        if (!response.isSuccessful) {
                            Log.w(TAG, "Decision request failed: HTTP ${response.code}")
                            null
                        } else JSONObject(text).optJSONObject("answers")
                    }
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    Log.w(TAG, "Decision request error: ${error.javaClass.simpleName}")
                    null
                }
            }
        }
    }

    /** 上一轮 [activeGoal] 还在处理时，用户又说了 [newText]。无法可靠判断时返回 null。 */
    suspend fun decideOverlap(apiKey: String, activeGoal: String, newText: String): OverlapDecision? {
        val state = JSONObject().put("正在处理", activeGoal.take(1500)).put("新话", newText.take(1500))
        val questions = JSONObject()
            .put("action", JSONObject().put("type", "choice")
                .put("instructions", "助手正在处理上一条请求时，用户又说了一句新的话。应如何处理这句新话？")
                .put("criteria", JSONObject()
                    .put("interrupt", "用户要求停止、取消、纠正或替换正在处理的请求")
                    .put("queue", "新话是另一件独立的事，应等上一条处理完再处理")
                    .put("merge", "新话是对正在处理的请求的补充说明")))
            .put("stop", JSONObject().put("type", "noul")
                .put("instructions", "用户这句新话是在要求助手立刻停止或取消当前操作"))
        val answers = ask(apiKey, state, questions) ?: return null
        val action = answers.optJSONObject("action") ?: return null
        if (action.optDouble("confidence", 0.0) < MIN_CONFIDENCE) return null
        val stop = answers.optJSONObject("stop")?.optDouble("noul", 0.0) ?: 0.0
        return when (action.optString("choice")) {
            "interrupt" -> if (stop >= STOP_THRESHOLD) OverlapDecision.STOP else OverlapDecision.INTERRUPT
            "merge" -> OverlapDecision.MERGE
            "queue" -> OverlapDecision.QUEUE
            else -> null
        }
    }

    /** 设置页的连通性检查：用一个固定的是非题验证密钥和网络。 */
    suspend fun test(apiKey: String): Result<String> {
        val started = System.currentTimeMillis()
        val answers = ask(apiKey, "请帮我把灯关掉", JSONObject().put("is_request", JSONObject().put("type", "noul")
            .put("instructions", "这句话是在请求执行一个操作")), timeoutMs = 6000)
            ?: return Result.failure(IOException("没有得到有效回答，请检查 API Key 和网络"))
        val value = answers.optJSONObject("is_request")?.optDouble("noul", -1.0) ?: -1.0
        return if (value >= 0) Result.success("连接成功，用时 ${System.currentTimeMillis() - started} ms")
        else Result.failure(IOException("返回内容不完整"))
    }

    private suspend fun <T> receive(call: Call, consume: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { continuation.resumeWith(Result.failure(e)) }
            override fun onResponse(call: Call, response: Response) {
                continuation.resumeWith(runCatching { response.use { consume(it) } })
            }
        })
    }
}
