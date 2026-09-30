package com.voice.actonsay.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID

enum class TtsEngineType {
    MIMO,       // 小米 MiMo 拟人大模型语音合成 (在线高保真)
    SYSTEM      // 系统原生 TextToSpeech (离线原生)
}

object TtsManager : TextToSpeech.OnInitListener {
    private const val TAG = "TtsManager"

    private var appContext: Context? = null
    private var tts: TextToSpeech? = null
    private var mediaPlayer: MediaPlayer? = null
    private var currentSynthesisJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private var currentOnDone: (() -> Unit)? = null

    // Configuration
    var engineType: TtsEngineType = TtsEngineType.MIMO
    var mimoApiKey: String = MimoTtsClient.DEFAULT_KEY
    var mimoVoice: String = MimoTtsClient.DEFAULT_VOICE
    var mimoStylePrompt: String = ""

    private var speechRate: Float = 1.0f
    private var pitch: Float = 1.0f

    @Synchronized
    fun init(context: Context) {
        appContext = context.applicationContext
        if (tts == null) {
            tts = TextToSpeech(context.applicationContext, this)
        }
        if (mediaPlayer == null) {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .build()
                )
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val engine = tts ?: return
            val result = engine.setLanguage(Locale.CHINESE)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "Locale.CHINESE not supported, falling back to default locale")
                engine.language = Locale.getDefault()
            }
            engine.setSpeechRate(speechRate)
            engine.setPitch(pitch)

            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    scope.launch { _isSpeaking.value = true }
                }

                override fun onDone(utteranceId: String?) {
                    scope.launch {
                        _isSpeaking.value = false
                        currentOnDone?.invoke()
                        currentOnDone = null
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    scope.launch {
                        _isSpeaking.value = false
                        currentOnDone?.invoke()
                        currentOnDone = null
                    }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    Log.e(TAG, "System TTS Utterance error: $errorCode")
                    scope.launch {
                        _isSpeaking.value = false
                        currentOnDone?.invoke()
                        currentOnDone = null
                    }
                }
            })

            _isReady.value = true
            Log.i(TAG, "System TTS initialized successfully")
        } else {
            Log.e(TAG, "System TTS initialization failed with status $status")
            _isReady.value = false
        }
    }

    fun setSpeechRate(rate: Float) {
        speechRate = rate
        tts?.setSpeechRate(rate)
    }

    fun setPitch(pitchValue: Float) {
        pitch = pitchValue
        tts?.setPitch(pitchValue)
    }

    fun configureMimo(apiKey: String, voice: String, stylePrompt: String) {
        mimoApiKey = apiKey.trim()
        mimoVoice = voice.trim()
        mimoStylePrompt = stylePrompt.trim()
    }

    @Synchronized
    fun speak(text: String, onDone: (() -> Unit)? = null) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            onDone?.invoke()
            return
        }

        stop()
        currentOnDone = onDone

        when (engineType) {
            TtsEngineType.MIMO -> speakWithMimo(trimmed)
            TtsEngineType.SYSTEM -> speakWithSystemTts(trimmed)
        }
    }

    private fun speakWithMimo(text: String) {
        _isSpeaking.value = true
        currentSynthesisJob = scope.launch {
            val result = MimoTtsClient.synthesize(
                text = text,
                apiKey = mimoApiKey,
                voice = mimoVoice,
                stylePrompt = mimoStylePrompt,
                format = "mp3"
            )

            result.fold(
                onSuccess = { audioBytes ->
                    playAudioBytes(audioBytes)
                },
                onFailure = { error ->
                    Log.e(TAG, "MiMo TTS failed, falling back to System TTS: ${error.message}")
                    speakWithSystemTts(text)
                }
            )
        }
    }

    private fun playAudioBytes(audioBytes: ByteArray) {
        val context = appContext
        if (context == null) {
            _isSpeaking.value = false
            currentOnDone?.invoke()
            currentOnDone = null
            return
        }

        try {
            val cacheDir = context.cacheDir
            val tempFile = File(cacheDir, "mimo_tts_${System.currentTimeMillis()}.mp3")
            FileOutputStream(tempFile).use { it.write(audioBytes) }

            val player = mediaPlayer ?: MediaPlayer().also { mediaPlayer = it }
            player.reset()
            player.setDataSource(tempFile.absolutePath)
            player.prepare()
            player.setOnCompletionListener {
                tempFile.delete()
                _isSpeaking.value = false
                currentOnDone?.invoke()
                currentOnDone = null
            }
            player.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                tempFile.delete()
                _isSpeaking.value = false
                currentOnDone?.invoke()
                currentOnDone = null
                true
            }
            player.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play synthesized audio", e)
            _isSpeaking.value = false
            currentOnDone?.invoke()
            currentOnDone = null
        }
    }

    private fun speakWithSystemTts(text: String) {
        val engine = tts
        if (engine == null || !_isReady.value) {
            Log.w(TAG, "System TTS not ready yet, skipping speak")
            _isSpeaking.value = false
            currentOnDone?.invoke()
            currentOnDone = null
            return
        }

        val utteranceId = UUID.randomUUID().toString()
        val params = Bundle()
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        if (result == TextToSpeech.ERROR) {
            Log.e(TAG, "Error invoking System TTS speak")
            _isSpeaking.value = false
            currentOnDone?.invoke()
            currentOnDone = null
        }
    }

    @Synchronized
    fun stop() {
        currentSynthesisJob?.cancel()
        currentSynthesisJob = null

        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.stop()
            }
            mediaPlayer?.reset()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping MediaPlayer", e)
        }

        tts?.stop()
        _isSpeaking.value = false
        val callback = currentOnDone
        currentOnDone = null
        callback?.invoke()
    }

    @Synchronized
    fun shutdown() {
        stop()
        mediaPlayer?.release()
        mediaPlayer = null
        tts?.shutdown()
        tts = null
        _isReady.value = false
    }
}
