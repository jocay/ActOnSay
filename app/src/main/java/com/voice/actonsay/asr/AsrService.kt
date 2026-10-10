package com.voice.actonsay.asr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.voice.actonsay.MainActivity
import com.voice.actonsay.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.sqrt

class AsrService : Service() {
    companion object {
        private const val TAG = "AsrService"
        private const val CHANNEL_ID = "asr_channel"
        private const val NOTIFICATION_ID = 1
        private const val SAMPLE_RATE = 16000
        const val ACTION_STOP = "com.voice.actonsay.action.STOP"

        fun start(context: Context) {
            val intent = Intent(context, AsrService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AsrService::class.java))
        }
    }

    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var recordingThread: Thread? = null
    @Volatile
    private var isRecording = false
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        AsrManager.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "Stop requested from notification")
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification(describeState(AsrManager.state.value))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        if (!isRecording) {
            startRecording()
            observeStateForNotification()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        stopRecording()
        AsrManager.setListening(false)
        super.onDestroy()
    }

    private fun observeStateForNotification() {
        serviceScope.launch {
            AsrManager.state
                .map { describeState(it) }
                .distinctUntilChanged()
                .collect { text ->
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, buildNotification(text))
                }
        }
    }

    private fun describeState(state: AsrState): String = when (state.engineState) {
        EngineState.IDLE -> "语音助手已停止"
        EngineState.WAITING_WAKEWORD -> if (!state.voiceReady) "语音模型准备中…" else {
            val words = state.customWakeWords.joinToString("、")
            if (words.isBlank()) "待机中 · 等待唤醒" else "待机中 · 呼叫「$words」"
        }
        EngineState.ACTIVE_LISTENING -> "正在倾听，请说话…"
        EngineState.PROCESSING_FINAL -> "正在识别…"
        EngineState.THINKING_AI -> "AI 正在思考…"
        EngineState.SPEAKING_TTS -> "正在播报回复…"
    }

    private fun startRecording() {
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing RECORD_AUDIO permission", e)
            null
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            stopSelf()
            return
        }

        isRecording = true
        AsrManager.setListening(true)
        // 播报时要监听打断词：开启系统回声消除（设备支持时），降低扬声器声音被当成用户说话的概率。
        echoCanceler = audioRecord?.audioSessionId?.takeIf { AcousticEchoCanceler.isAvailable() }
            ?.let { session -> runCatching { AcousticEchoCanceler.create(session)?.apply { enabled = true } }.getOrNull() }
        Log.i(TAG, "Echo canceler: ${if (echoCanceler?.enabled == true) "enabled" else "unavailable"}")
        audioRecord?.startRecording()

        recordingThread = Thread {
            val buffer = ShortArray(bufferSize / 2)
            // 绝大多数帧都读满缓冲区，复用同一个浮点数组；唤醒词与 VAD 引擎都会复制传入的数据。
            val fullFrame = FloatArray(buffer.size)
            var lastLevelReport = 0L
            while (isRecording) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (read > 0) {
                    val floatSamples = if (read == fullFrame.size) fullFrame else FloatArray(read)
                    var sumSquares = 0.0
                    for (i in 0 until read) {
                        val f = buffer[i] / 32768.0f
                        floatSamples[i] = f
                        sumSquares += (f * f).toDouble()
                    }

                    val now = System.currentTimeMillis()
                    if (now - lastLevelReport >= 40) {
                        // RMS 映射为感知电平：0.25 RMS 已相当响，作为满刻度
                        val rms = sqrt(sumSquares / read).toFloat()
                        AsrManager.reportAudioLevel((rms * 4f).coerceIn(0f, 1f))
                        lastLevelReport = now
                    }

                    AsrManager.acceptSamples(floatSamples, buffer, read, SAMPLE_RATE)
                }
            }
        }.also { it.name = "asr-record"; it.start() }
    }

    private fun stopRecording() {
        isRecording = false
        recordingThread?.join(1000)
        recordingThread = null
        try {
            audioRecord?.stop()
        } catch (_: IllegalStateException) {
        }
        echoCanceler?.release()
        echoCanceler = null
        audioRecord?.release()
        audioRecord = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "语音助手待机",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "后台语音唤醒与识别服务的运行状态"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, AsrService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ActOnSay 语音助手")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .addAction(0, "停止待机", stopIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }
}
