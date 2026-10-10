package com.voice.actonsay.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.voice.actonsay.diagnostics.Diagnostics
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.Locale
import java.util.UUID

enum class TtsEngineType {
    MIMO,       // 小米 MiMo 拟人大模型语音合成 (在线高保真)
    SYSTEM,     // 系统原生 TextToSpeech (离线原生)
    LOCAL       // 应用内置的 sherpa-onnx 离线模型（需先下载）
}

/**
 * 播放队列：分段入队、顺序播放。MiMo 分段在入队时就开始合成，播放前一段时后一段已在准备，
 * 因此回复可以边生成边朗读。队列播完或被 [stop] 打断时触发待完成回调。
 */
object TtsManager : TextToSpeech.OnInitListener {
    private const val TAG = "TtsManager"
    private val ACK_PHRASES = listOf("我在", "在呢", "请说")

    private var appContext: Context? = null
    private var tts: TextToSpeech? = null
    private var mediaPlayer: MediaPlayer? = null
    private var currentSynthesisJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private var currentOnDone: (() -> Unit)? = null
    private var systemUtteranceId: String? = null

    /** 正在播放的分段文字；打断检测据此忽略播报内容本身含有的打断词或唤醒词。 */
    @Volatile var speakingText: String = ""
        private set

    /** [audio] 为空表示用系统 TTS 朗读；[keepFile] 的音频是唤醒应答缓存，播放后保留。 */
    private class Segment(val text: String, var audio: Deferred<File?>? = null, val keepFile: Boolean = false) {
        var file: File? = null
        fun dispose() {
            audio?.cancel()
            if (!keepFile) file?.delete()
            file = null
        }
    }
    private val queue = ArrayDeque<Segment>()
    private var activeSegment: Segment? = null
    private var playing = false
    /** 每次 [stop] 递增，使被打断的合成与播放回调失效。 */
    private var generation = 0
    private var ackJob: Job? = null
    /** 云端合成的并行上限：足以让后续分段在前一段播放时就绪，又不至于触发限流。 */
    private val mimoSlots = Semaphore(3)

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
            // 上次进程遗留的分段音频不再使用。
            File(context.applicationContext.cacheDir, "tts_segments").deleteRecursively()
            LocalTts.refresh(context.applicationContext)
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
                    scope.launch {
                        synchronized(this@TtsManager) {
                            if (utteranceId != null && utteranceId == systemUtteranceId) {
                                _isSpeaking.value = true
                                Diagnostics.playbackStarted()
                            }
                        }
                    }
                }

                override fun onDone(utteranceId: String?) {
                    scope.launch { systemSegmentFinished(utteranceId) }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    scope.launch { systemSegmentFinished(utteranceId) }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    Log.e(TAG, "System TTS Utterance error: $errorCode")
                    scope.launch { systemSegmentFinished(utteranceId) }
                }
            })

            _isReady.value = true
            Log.i(TAG, "System TTS initialized successfully")
        } else {
            Log.e(TAG, "System TTS initialization failed with status $status")
            _isReady.value = false
        }
    }

    private fun utteranceGeneration(utteranceId: String?): Int = utteranceId?.substringBefore(':')?.toIntOrNull() ?: -1

    @Synchronized
    private fun systemSegmentFinished(utteranceId: String?) {
        if (utteranceId == null || utteranceId != systemUtteranceId) return
        systemUtteranceId = null
        segmentFinished(utteranceGeneration(utteranceId))
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
        prepareAcks()
    }

    /** 打断当前播放并朗读 [text]；播放结束或被打断时调用 [onDone]。 */
    @Synchronized
    fun speak(text: String, onDone: (() -> Unit)? = null) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            onDone?.invoke()
            return
        }

        stop()
        currentOnDone = onDone
        queue.addLast(newSegment(trimmed))
        playNext()
    }

    /** 追加一段到队列，不打断正在播放的内容；队列播完或被打断时调用 [onIdle]。 */
    @Synchronized
    fun enqueue(text: String, onIdle: (() -> Unit)? = null) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (onIdle != null) currentOnDone = onIdle
        queue.addLast(newSegment(trimmed))
        if (!playing) playNext()
    }

    /** 唤醒应答：优先播放已缓存的 MiMo 短语，没有缓存时用系统 TTS，保证立即出声。 */
    @Synchronized
    fun speakAck(onDone: () -> Unit) {
        stop()
        currentOnDone = onDone
        val index = ACK_PHRASES.indices.random()
        val cached = ackFile(index)?.takeIf { engineType == TtsEngineType.MIMO && it.length() > 0 }
        // 没有缓存的云端应答时走常规分段（离线模型合成两个字只需几十毫秒）。
        queue.addLast(if (cached != null) Segment(ACK_PHRASES[index], CompletableDeferred(cached), keepFile = true) else newSegment(ACK_PHRASES[index]))
        playNext()
    }

    private fun newSegment(text: String): Segment {
        val context = appContext ?: return Segment(text, null)
        val directory = File(context.cacheDir, "tts_segments")
        val rate = speechRate
        fun local(): File? = File(directory, "${UUID.randomUUID()}.wav").takeIf { LocalTts.synthesize(context, text, rate, it) }
        val engine = engineType
        val key = mimoApiKey
        val voice = mimoVoice
        val style = mimoStylePrompt
        val segment = Segment(text)
        if (engine == TtsEngineType.SYSTEM || (engine == TtsEngineType.MIMO && key.isBlank() && !LocalTts.installed.value)) return segment
        val current = generation
        segment.audio = scope.async(Dispatchers.IO) {
            var file: File? = null
            try {
                ensureActive()
                file = if (engine == TtsEngineType.MIMO && key.isNotBlank()) {
                    val cloud = mimoSlots.withPermit {
                        MimoTtsClient.synthesize(text = text, apiKey = key, voice = voice, stylePrompt = style, format = "mp3")
                    }
                    ensureActive()
                    cloud.fold(onSuccess = { bytes ->
                        directory.mkdirs()
                        File(directory, "${UUID.randomUUID()}.mp3").also { it.writeBytes(bytes) }
                    }, onFailure = { error ->
                        Log.e(TAG, "MiMo TTS failed, falling back to offline TTS: ${error.message}")
                        Diagnostics.failure("语音合成", "MiMo 合成失败，改用本地模型：${error.message.orEmpty()}")
                        local()
                    })
                } else local()
                ensureActive()
                synchronized(this@TtsManager) {
                    ensureActive()
                    if (current == generation) segment.file = file else { file?.delete(); file = null }
                }
                file
            } finally {
                // 本地引擎不能中途终止；返回后仍必须清理已取消分段的产物。
                if (!isActive) file?.delete()
            }
        }
        return segment
    }

    @Synchronized
    private fun playNext() {
        val current = generation
        val segment = queue.removeFirstOrNull()
        activeSegment = segment
        if (segment == null) {
            playing = false
            speakingText = ""
            _isSpeaking.value = false
            val callback = currentOnDone
            currentOnDone = null
            callback?.invoke()
            return
        }
        playing = true
        speakingText = segment.text
        _isSpeaking.value = true
        val audio = segment.audio
        if (audio == null) {
            speakWithSystemTts(segment.text, current)
            return
        }
        currentSynthesisJob = scope.launch {
            val file = runCatching { audio.await() }.getOrNull()
            if (current != generation) {
                if (!segment.keepFile) file?.delete()
                return@launch
            }
            if (file != null) playFile(file, segment.keepFile, segment.text, current) else speakWithSystemTts(segment.text, current)
        }
    }

    @Synchronized
    private fun segmentFinished(segmentGeneration: Int) {
        if (segmentGeneration != generation) return
        activeSegment?.dispose()
        activeSegment = null
        playNext()
    }

    @Synchronized
    private fun playFile(file: File, keepFile: Boolean, fallbackText: String, current: Int) {
        if (current != generation) { if (!keepFile) file.delete(); return }
        val segment = activeSegment
        try {
            val player = mediaPlayer ?: MediaPlayer().also { mediaPlayer = it }
            player.reset()
            player.setDataSource(file.absolutePath)
            player.prepare()
            player.setOnCompletionListener {
                if (!keepFile) file.delete()
                synchronized(this@TtsManager) { if (activeSegment === segment) segmentFinished(current) }
            }
            player.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                if (!keepFile) file.delete()
                synchronized(this@TtsManager) { if (activeSegment === segment) segmentFinished(current) }
                true
            }
            player.start()
            Diagnostics.playbackStarted()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play synthesized audio", e)
            file.delete()
            speakWithSystemTts(fallbackText, current)
        }
    }

    @Synchronized
    private fun speakWithSystemTts(text: String, current: Int) {
        if (current != generation) return
        val engine = tts
        if (engine == null || !_isReady.value) {
            Log.w(TAG, "System TTS not ready yet, skipping speak")
            segmentFinished(current)
            return
        }

        val utteranceId = "$current:${UUID.randomUUID()}"
        systemUtteranceId = utteranceId
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), utteranceId)
        if (result == TextToSpeech.ERROR) {
            Log.e(TAG, "Error invoking System TTS speak")
            systemSegmentFinished(utteranceId)
        }
    }

    @Synchronized
    fun stop() {
        generation++
        systemUtteranceId = null
        currentSynthesisJob?.cancel()
        currentSynthesisJob = null
        activeSegment?.dispose()
        activeSegment = null
        queue.forEach { it.dispose() }
        queue.clear()
        playing = false
        speakingText = ""

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

    private fun ackFile(index: Int, voice: String = mimoVoice, style: String = mimoStylePrompt): File? {
        val context = appContext ?: return null
        val variant = (voice + "|" + style).hashCode().toUInt().toString(16)
        return File(File(context.filesDir, "tts_ack"), "${variant}_$index.mp3")
    }

    /** 后台准备当前音色的唤醒应答音频；已存在的不重复合成，失败时下次配置或启动再试。 */
    private fun prepareAcks() {
        ackJob?.cancel()
        if (engineType != TtsEngineType.MIMO || mimoApiKey.isBlank() || appContext == null) return
        val key = mimoApiKey
        val voice = mimoVoice
        val style = mimoStylePrompt
        ackJob = scope.launch(Dispatchers.IO) {
            // 即时配置输入停止后再准备应答，避免每个按键都产生网络请求。
            delay(800)
            ACK_PHRASES.forEachIndexed { index, phrase ->
                ensureActive()
                val target = ackFile(index, voice, style) ?: return@launch
                if (target.length() > 0) return@forEachIndexed
                MimoTtsClient.synthesize(text = phrase, apiKey = key, voice = voice, stylePrompt = style, format = "mp3").onSuccess { bytes ->
                    ensureActive()
                    target.parentFile?.mkdirs()
                    val partial = File(target.parentFile, target.name + ".partial")
                    partial.writeBytes(bytes)
                    if (!partial.renameTo(target)) partial.delete()
                }
            }
        }
    }

    @Synchronized
    fun shutdown() {
        ackJob?.cancel()
        stop()
        mediaPlayer?.release()
        mediaPlayer = null
        tts?.shutdown()
        tts = null
        _isReady.value = false
    }
}
