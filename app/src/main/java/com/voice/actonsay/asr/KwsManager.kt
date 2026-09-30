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

    @Synchronized
    fun init(assetManager: AssetManager) {
        if (spotter != null) return

        PinyinHelper.init(assetManager)

        try {
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

            spotter = KeywordSpotter(assetManager = assetManager, config = config)
            recreateStream()
            Log.i(TAG, "Sherpa-onnx KWS engine initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize KWS engine", e)
        }
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
        spotter?.release()
        spotter = null
        Log.i(TAG, "KWS engine released")
    }
}
