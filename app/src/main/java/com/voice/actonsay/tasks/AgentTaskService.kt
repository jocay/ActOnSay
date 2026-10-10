package com.voice.actonsay.tasks

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.voice.actonsay.MainActivity
import com.voice.actonsay.bridge.BridgeClient
import com.voice.actonsay.asr.AsrManager
import com.voice.actonsay.media.MusicPlayer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.json.JSONObject

/** 与录音服务独立，语音待机停止后后台任务仍继续。 */
class AgentTaskService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var foregroundReady = false
    private val announced = mutableSetOf<String>()
    private val observedStates = mutableMapOf<String, String>()
    private val startedAt = System.currentTimeMillis()
    private var wakeLockRenewal: Job? = null
    private data class NotificationView(val running: Boolean, val taskId: String?, val text: String)
    private val wakeLock by lazy {
        (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "actonsay:tasks")
            .apply { setReferenceCounted(false) }
    }
    override fun onCreate() {
        super.onCreate()
        AsrManager.initControlPlane(this)
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL, "任务与自动化", NotificationManager.IMPORTANCE_LOW))
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID, notification("任务执行服务已启动"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(ID, notification("任务执行服务已启动"))
            foregroundReady = true
        } catch (error: Exception) {
            TaskRuntime.serviceStartFailed(error.message ?: "系统拒绝任务服务")
            stopSelf()
            return
        }
        TaskRuntime.serviceStarted()
        // 流式回答每 200 毫秒刷新一次任务列表；通知和唤醒锁只关心是否在运行与展示的进度，去重后至多每秒更新一次。
        scope.launch {
            TaskRuntime.tasks.map { tasks ->
                val current = tasks.firstOrNull { it.state == TaskStates.RUNNING && it.visible }
                NotificationView(tasks.any { it.state == TaskStates.RUNNING }, current?.id, current?.progress?.ifBlank { "执行任务" } ?: "等待任务与自动化")
            }.distinctUntilChanged().conflate().collect { view ->
                updateWakeLock(view.running)
                manager.notify(ID, notification(view.text, view.taskId))
                delay(1000)
            }
        }
        scope.launch {
            TaskRuntime.tasks.map { tasks -> tasks.map { it.id to it.state } }.distinctUntilChanged().collect { states ->
                val byId = TaskRuntime.tasks.value.associateBy { it.id }
                states.forEach { (id, state) ->
                    val task = byId[id] ?: return@forEach
                    val previous = observedStates.put(id, state)
                    val finished = state in listOf(TaskStates.SUCCEEDED, TaskStates.FAILED)
                    val transitioned = previous != null && previous !in listOf(TaskStates.SUCCEEDED, TaskStates.FAILED, TaskStates.CANCELLED)
                    // 首次载入旧记录、向前翻页都不代表刚刚完成。
                    if (finished && task.visible && (transitioned || task.createdAt >= startedAt) && announced.add(id)) {
                        TaskRuntime.announceCompletion(task)
                    }
                }
            }
        }
        // 只同步服务内的任务事件；不依赖 ADB，也不自动朗读后台结果。
        scope.launch {
            while (isActive) {
                runCatching { TaskRuntime.syncBridge() }
                delay(15_000)
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundReady) return START_NOT_STICKY
        TaskRuntime.serviceStarted()
        when (intent?.action) {
            CANCEL -> intent.getStringExtra("task_id")?.let { TaskRuntime.cancel(it) }
            PAUSE -> TaskRuntime.pauseAutomation(true)
            MusicPlayer.ACTION_TOGGLE, MusicPlayer.ACTION_NEXT, MusicPlayer.ACTION_PREVIOUS, MusicPlayer.ACTION_STOP -> MusicPlayer.handleAction(intent.action)
        }
        return START_STICKY
    }
    /** 有任务在执行时保持 CPU 唤醒，息屏后模型请求和工具调用才不会被休眠打断；唤醒锁带超时，长任务期间定期续期。 */
    private fun updateWakeLock(running: Boolean) {
        if (running) {
            if (wakeLockRenewal?.isActive == true) return
            wakeLockRenewal = scope.launch {
                while (isActive) {
                    wakeLock.acquire(10 * 60_000L)
                    delay(9 * 60_000L)
                }
            }
        } else {
            wakeLockRenewal?.cancel()
            wakeLockRenewal = null
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    override fun onDestroy() {
        wakeLockRenewal?.cancel()
        if (wakeLock.isHeld) wakeLock.release()
        TaskRuntime.serviceStopped()
        scope.cancel()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    private fun notification(text: String, taskId: String? = null): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("open_tasks", true), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getService(this, 1, Intent(this, AgentTaskService::class.java).setAction(PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        builder.setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("ActOnSay 任务")
            .setContentText(text.take(100)).setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "暂停自动化", pause).build())
        if (taskId != null) {
            val stop = PendingIntent.getService(this, taskId.hashCode(), Intent(this, AgentTaskService::class.java)
                .setAction(CANCEL).putExtra("task_id", taskId), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(Notification.Action.Builder(null, "停止任务", stop).build())
        }
        return builder.build()
    }
    companion object {
        private const val CHANNEL = "actonsay_tasks"
        private const val ID = 203
        private const val CANCEL = "com.voice.actonsay.CANCEL_TASK"
        private const val PAUSE = "com.voice.actonsay.PAUSE_AUTOMATION"
    }
}
