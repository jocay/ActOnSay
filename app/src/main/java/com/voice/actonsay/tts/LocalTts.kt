package com.voice.actonsay.tts

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.voice.actonsay.ai.SmartDns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 本机离线语音合成（sherpa-onnx Matcha 中文模型）。模型按需下载到应用私有目录，不随安装包分发。
 * 作为独立引擎，也作为云端合成失败时的兜底。
 */
object LocalTts {
    private const val TAG = "LocalTts"
    private const val MODEL = "https://hf-mirror.com/csukuangfj/matcha-icefall-zh-baker/resolve/main"
    private const val VOCODER = "https://hf-mirror.com/k2-fsa/sherpa-onnx-models/resolve/main/vocoder-models"

    private class ModelFile(val name: String, val url: String, val bytes: Long)
    private val FILES = listOf(
        ModelFile("tokens.txt", "$MODEL/tokens.txt", 19_591),
        ModelFile("lexicon.txt", "$MODEL/lexicon.txt", 1_363_705),
        ModelFile("phone.fst", "$MODEL/phone.fst", 88_630),
        ModelFile("date.fst", "$MODEL/date.fst", 59_154),
        ModelFile("number.fst", "$MODEL/number.fst", 64_482),
        ModelFile("model-steps-3.onnx", "$MODEL/model-steps-3.onnx", 75_624_611),
        ModelFile("vocos-22khz-univ.onnx", "$VOCODER/vocos-22khz-univ.onnx", 53_884_024)
    )
    val totalBytes: Long = FILES.sumOf { it.bytes }

    private val client = OkHttpClient.Builder().dns(SmartDns)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    private val _installed = MutableStateFlow(false)
    val installed: StateFlow<Boolean> = _installed.asStateFlow()

    private var engine: OfflineTts? = null

    private fun directory(context: Context) = File(context.filesDir, "tts_models/matcha-icefall-zh-baker")

    /** 文件大小与发布清单一致才算安装完成，避免加载下载到一半的模型。 */
    fun refresh(context: Context): Boolean {
        val dir = directory(context)
        val ready = FILES.all { File(dir, it.name).length() == it.bytes }
        _installed.value = ready
        return ready
    }

    /** 逐个下载缺失的文件；[onProgress] 收到已完成的字节数。中断后再次调用只补缺失部分。 */
    suspend fun download(context: Context, onProgress: (Long) -> Unit) = withContext(Dispatchers.IO) {
        val dir = directory(context).apply { mkdirs() }
        var done = FILES.filter { File(dir, it.name).length() == it.bytes }.sumOf { it.bytes }
        onProgress(done)
        for (file in FILES) {
            val target = File(dir, file.name)
            if (target.length() == file.bytes) continue
            val partial = File(dir, file.name + ".partial")
            client.newCall(Request.Builder().url(file.url).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("下载 ${file.name} 失败 [HTTP ${response.code}]")
                val body = response.body ?: throw IOException("下载 ${file.name} 失败：没有内容")
                body.byteStream().use { input -> partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var lastReport = 0L
                    while (true) {
                        ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        done += count
                        if (done - lastReport >= 512 * 1024) { lastReport = done; onProgress(done) }
                    }
                } }
            }
            if (partial.length() != file.bytes) {
                partial.delete()
                throw IOException("${file.name} 大小不符，请重试")
            }
            if (!partial.renameTo(target)) throw IOException("无法保存 ${file.name}")
            onProgress(done)
        }
        release()
        refresh(context)
    }

    @Synchronized
    private fun engine(context: Context): OfflineTts? {
        engine?.let { return it }
        if (!refresh(context)) return null
        val dir = directory(context)
        fun path(name: String) = File(dir, name).absolutePath
        return try {
            val matcha = OfflineTtsMatchaModelConfig().apply {
                acousticModel = path("model-steps-3.onnx")
                vocoder = path("vocos-22khz-univ.onnx")
                lexicon = path("lexicon.txt")
                tokens = path("tokens.txt")
            }
            val model = OfflineTtsModelConfig().apply {
                this.matcha = matcha
                numThreads = 4
                debug = false
                provider = "cpu"
            }
            val config = OfflineTtsConfig().apply {
                this.model = model
                ruleFsts = listOf("phone.fst", "date.fst", "number.fst").joinToString(",") { path(it) }
            }
            OfflineTts(null, config).also {
                engine = it
                Log.i(TAG, "Offline TTS loaded, sampleRate=${it.sampleRate()}")
            }
        } catch (error: Throwable) {
            Log.e(TAG, "Offline TTS failed to load", error)
            null
        }
    }

    /** 把 [text] 合成为 WAV 写入 [target]；模型未安装或合成失败返回 false。引擎不是线程安全的，串行调用。 */
    @Synchronized
    fun synthesize(context: Context, text: String, speed: Float, target: File): Boolean {
        val tts = engine(context) ?: return false
        return try {
            val started = System.currentTimeMillis()
            val audio = tts.generate(text, 0, speed.coerceIn(0.5f, 2.0f))
            if (audio.samples.isEmpty()) return false
            target.parentFile?.mkdirs()
            val saved = audio.save(target.absolutePath)
            Log.d(TAG, "Offline TTS: chars=${text.length} audioMs=${audio.samples.size * 1000L / audio.sampleRate} synthMs=${System.currentTimeMillis() - started}")
            saved
        } catch (error: Throwable) {
            Log.e(TAG, "Offline TTS synthesis failed", error)
            false
        }
    }

    @Synchronized
    fun release() {
        runCatching { engine?.release() }
        engine = null
    }
}
