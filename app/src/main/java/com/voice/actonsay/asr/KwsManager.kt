package com.voice.actonsay.asr

import android.content.res.AssetManager
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

class KwsManager {
    companion object {
        private const val TAG = "KwsManager"
        private const val SAMPLE_RATE = 16000
    }

    private var spotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null
    private var currentWakeWords: Set<String> = emptySet()
    /** 打断词单独一个流：播报、执行任务或播放音乐时与唤醒流并行监听。 */
    private var interruptStream: OnlineStream? = null
    private var currentInterruptWords: Set<String> = emptySet()

    /**
     * 在后台线程调用：模型加载耗时较长，放在锁外进行，加载期间主线程调用 [reset] 等方法不会被阻塞。
     */
    fun init(assetManager: AssetManager) {
        if (synchronized(this) { spotter != null }) return

        PinyinHelper.init(assetManager)

        val created = try {
            val modelConfig = OnlineModelConfig().apply {
                transducer = OnlineTransducerModelConfig(
                    encoder = "encoder.int8.onnx",
                    decoder = "decoder.onnx",
                    joiner = "joiner.int8.onnx"
                )
                tokens = "tokens.txt"
                numThreads = 2
                provider = "cpu"
            }

            val config = KeywordSpotterConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = modelConfig,
                maxActivePaths = 4,
                keywordsFile = "keywords.txt",
                keywordsScore = 1.5f,
                keywordsThreshold = 0.25f,
                numTrailingBlanks = 2
            )

            KeywordSpotter(assetManager = assetManager, config = config)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize KWS engine", e)
            return
        }
        synchronized(this) {
            if (spotter != null) { created.release(); return }
            spotter = created
            recreateStream()
            updateInterruptWords(currentInterruptWords)
        }
        Log.i(TAG, "Sherpa-onnx KWS engine initialized successfully")
    }

    @Synchronized
    fun updateWakeWords(wakeWords: Set<String>) {
        this.currentWakeWords = wakeWords
        recreateStream()
    }

    @Synchronized
    private fun recreateStream() {
        val s = spotter ?: return
        stream?.release()

        val keywordsFormatted = PinyinHelper.formatWakeWordsForStream(currentWakeWords)
        stream = if (keywordsFormatted.isNotEmpty()) {
            Log.i(TAG, "Creating KWS stream with custom wake words: $keywordsFormatted")
            s.createStream(keywordsFormatted)
        } else {
            Log.i(TAG, "Creating KWS stream with default keywords.txt")
            s.createStream()
        }
    }

    @Synchronized
    fun updateInterruptWords(words: Set<String>) {
        currentInterruptWords = words
        val s = spotter ?: return
        interruptStream?.release()
        // “一”在口语里常变调（等一下读 yí），模型音素带声调，三种读法都登记。
        val formatted = PinyinHelper.formatWakeWordsForStream(words).split(" / ").filter { it.isNotBlank() }.flatMap { line ->
            if (" y ī " in " $line ") listOf(line, line.replace("y ī", "y í"), line.replace("y ī", "y ì")) else listOf(line)
        }.joinToString(" / ")
        // 没有可识别的打断词时不建流，避免回退到默认唤醒词文件。
        interruptStream = if (formatted.isNotEmpty()) s.createStream(formatted) else null
        Log.i(TAG, "Interrupt stream: ${formatted.ifEmpty { "disabled" }}")
    }

    @Synchronized
    fun acceptInterruptSamples(samples: FloatArray, sampleRate: Int = SAMPLE_RATE): String? {
        val s = interruptStream ?: return null
        val spot = spotter ?: return null
        s.acceptWaveform(samples, sampleRate)
        while (spot.isReady(s)) {
            spot.decode(s)
            val kw = spot.getResult(s).keyword.trim()
            if (kw.isNotEmpty()) {
                spot.reset(s)
                Log.i(TAG, "Interrupt word detected: '$kw'")
                return kw
            }
        }
        return null
    }

    @Synchronized
    fun resetInterrupt() {
        val s = interruptStream ?: return
        spotter?.reset(s)
    }

    @Synchronized
    fun acceptSamples(samples: FloatArray, sampleRate: Int = SAMPLE_RATE): String? {
        val s = stream ?: return null
        val spot = spotter ?: return null

        s.acceptWaveform(samples, sampleRate)
        while (spot.isReady(s)) {
            spot.decode(s)
            val result = spot.getResult(s)
            val kw = result.keyword.trim()
            if (kw.isNotEmpty()) {
                spot.reset(s)
                Log.i(TAG, "Wake word detected: '$kw'")
                return kw
            }
        }
        return null
    }

    @Synchronized
    fun reset() {
        val s = stream ?: return
        spotter?.reset(s)
    }

    @Synchronized
    fun release() {
        stream?.release()
        stream = null
        interruptStream?.release()
        interruptStream = null
        spotter?.release()
        spotter = null
        Log.i(TAG, "KWS engine released")
    }
}
