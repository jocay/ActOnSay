package com.voice.actonsay.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.util.Log
import com.voice.actonsay.MainActivity
import com.voice.actonsay.asr.AsrManager
import com.voice.actonsay.asr.EngineState
import com.voice.actonsay.tasks.AgentTaskService
import com.voice.actonsay.tts.TtsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 应用内音乐播放：播放队列、媒体会话（锁屏与通知栏控制）和音频焦点。
 * 播报或收音时自动压低音量，结束后恢复，所以边听歌边对话不用手动暂停。
 */
object MusicPlayer {
    private const val TAG = "MusicPlayer"
    private const val CHANNEL = "actonsay_music"
    private const val NOTIFICATION_ID = 205
    private const val DUCKED_VOLUME = 0.15f
    const val ACTION_TOGGLE = "com.voice.actonsay.MUSIC_TOGGLE"
    const val ACTION_NEXT = "com.voice.actonsay.MUSIC_NEXT"
    const val ACTION_PREVIOUS = "com.voice.actonsay.MUSIC_PREVIOUS"
    const val ACTION_STOP = "com.voice.actonsay.MUSIC_STOP"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var appContext: Context? = null
    private var player: MediaPlayer? = null
    private var session: MediaSession? = null
    private var focusRequest: AudioFocusRequest? = null
    private var loadJob: Job? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    private val _current = MutableStateFlow<Track?>(null)
    val current: StateFlow<Track?> = _current.asStateFlow()

    private var queue: List<Track> = emptyList()
    private var index = -1
    private var ducked = false
    /** 用户或模型主动暂停的，不因焦点或压低音量的变化自动恢复。 */
    private var userPaused = false

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        if (Build.VERSION.SDK_INT >= 26) {
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, "音乐播放", NotificationManager.IMPORTANCE_LOW))
        }
        session = MediaSession(context.applicationContext, "ActOnSay").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = resume()
                override fun onPause() = pause()
                override fun onSkipToNext() { scope.launch { next() } }
                override fun onSkipToPrevious() { scope.launch { previous() } }
                override fun onStop() = stop()
            })
        }
        // 播报、收音和识别期间压低音乐，结束后恢复。
        scope.launch {
            combine(TtsManager.isSpeaking, AsrManager.state) { speaking, state ->
                speaking || state.engineState == EngineState.ACTIVE_LISTENING || state.engineState == EngineState.PROCESSING_FINAL
            }.distinctUntilChanged().collect { duck ->
                ducked = duck
                applyVolume()
            }
        }
    }

    private fun applyVolume() {
        val volume = if (ducked) DUCKED_VOLUME else 1f
        runCatching { player?.setVolume(volume, volume) }
    }

    /** 从 [start] 开始播放一组歌；其余的排在队列里依次播放。 */
    fun play(tracks: List<Track>, start: Int = 0) {
        if (tracks.isEmpty()) return
        queue = tracks
        userPaused = false
        load(start.coerceIn(0, tracks.lastIndex))
    }

    private fun load(position: Int) {
        val context = appContext ?: return
        index = position
        val track = queue.getOrNull(position) ?: return stop()
        _current.value = track
        loadJob?.cancel()
        loadJob = scope.launch {
            val url = try { MusicSource.resolve(track) } catch (error: Exception) {
                Log.w(TAG, "Skip ${track.source}:${track.id}: ${error.message}")
                // 解析失败跳到下一首，整组都失败时停止。
                if (position + 1 < queue.size) load(position + 1) else stop()
                return@launch
            }
            if (!requestFocus(context)) return@launch
            val media = player ?: MediaPlayer().also { player = it }
            media.reset()
            media.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            media.setOnPreparedListener {
                applyVolume()
                it.start()
                _isPlaying.value = true
                publish()
            }
            media.setOnCompletionListener { scope.launch { next(auto = true) } }
            media.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "Playback error what=$what extra=$extra")
                scope.launch { next(auto = true) }
                true
            }
            runCatching {
                media.setDataSource(url)
                media.prepareAsync()
            }.onFailure {
                Log.w(TAG, "Cannot open stream: ${it.message}")
                next(auto = true)
            }
            publish()
        }
    }

    private fun requestFocus(context: Context): Boolean {
        val audio = context.getSystemService(AudioManager::class.java)
        val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> pause()
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { runCatching { player?.pause() }; _isPlaying.value = false; publish() }
                    AudioManager.AUDIOFOCUS_GAIN -> if (!userPaused && player != null && _current.value != null) resume()
                }
            }.build().also { focusRequest = it }
        return audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    fun pause() {
        userPaused = true
        runCatching { if (player?.isPlaying == true) player?.pause() }
        _isPlaying.value = false
        publish()
    }

    fun resume() {
        val media = player ?: return
        if (_current.value == null) return
        userPaused = false
        appContext?.let { if (!requestFocus(it)) return }
        runCatching { media.start() }.onSuccess { _isPlaying.value = true; applyVolume() }
        publish()
    }

    suspend fun next(auto: Boolean = false) {
        if (index + 1 < queue.size) load(index + 1)
        else if (auto) stop()
    }

    suspend fun previous() {
        // 播放超过 5 秒时“上一首”回到本首开头，与常见播放器一致。
        val position = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)
        if (position > 5000 || index <= 0) runCatching { player?.seekTo(0) } else load(index - 1)
    }

    fun stop() {
        loadJob?.cancel()
        runCatching { player?.stop() }
        runCatching { player?.reset() }
        _isPlaying.value = false
        _current.value = null
        queue = emptyList()
        index = -1
        appContext?.let { context ->
            focusRequest?.let { context.getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        }
        session?.isActive = false
    }

    /** 媒体音量 0–100。 */
    fun setVolume(percent: Int) {
        val audio = appContext?.getSystemService(AudioManager::class.java) ?: return
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (percent.coerceIn(0, 100) * max + 50) / 100, 0)
    }

    fun volumePercent(): Int {
        val audio = appContext?.getSystemService(AudioManager::class.java) ?: return 0
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    }

    fun queueSummary(): String = queue.mapIndexed { position, track -> (if (position == index) "▶ " else "  ") + "${position + 1}. ${track.label}" }
        .joinToString("\n")

    private fun publish() {
        val context = appContext ?: return
        val track = _current.value ?: return
        val playing = _isPlaying.value
        session?.apply {
            isActive = true
            setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, track.name)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, track.artist).putString(MediaMetadata.METADATA_KEY_ALBUM, track.album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, track.durationMs).build())
            setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP)
                .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    runCatching { player?.currentPosition?.toLong() ?: 0L }.getOrDefault(0L), 1f).build())
        }
        fun action(icon: Int, title: String, intentAction: String, code: Int) = Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(context, icon), title,
            PendingIntent.getService(context, code, Intent(context, AgentTaskService::class.java).setAction(intentAction),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build()
        val open = PendingIntent.getActivity(context, 7, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(context)
        val notification = builder.setSmallIcon(android.R.drawable.ic_media_play).setContentTitle(track.name).setContentText(track.artist)
            .setContentIntent(open).setOngoing(playing).setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action(android.R.drawable.ic_media_previous, "上一首", ACTION_PREVIOUS, 31))
            .addAction(action(if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, if (playing) "暂停" else "播放", ACTION_TOGGLE, 32))
            .addAction(action(android.R.drawable.ic_media_next, "下一首", ACTION_NEXT, 33))
            .setStyle(Notification.MediaStyle().setMediaSession(session?.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification) }
    }

    /** 通知栏按钮经由任务服务转到这里。 */
    fun handleAction(action: String?) {
        when (action) {
            ACTION_TOGGLE -> if (_isPlaying.value) pause() else resume()
            ACTION_NEXT -> scope.launch { next() }
            ACTION_PREVIOUS -> scope.launch { previous() }
            ACTION_STOP -> stop()
        }
    }
}
