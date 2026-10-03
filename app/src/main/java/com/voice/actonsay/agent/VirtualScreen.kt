package com.voice.actonsay.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.BufferedWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 独立于主屏的虚拟显示器。画面由 Root 启动的 [com.voice.actonsay.vdisplay.DisplayHelper] 进程持有，
 * 应用通过它的标准输入输出通信，点击和启动应用仍走带显示器编号的 Shell 命令。
 *
 * 这台设备上虚拟屏与主屏同属一个电源组：系统休眠时虚拟屏停止渲染，操作前由 [PowerKeeper] 让系统保持“黑屏但醒着”。
 */
object VirtualScreen {
    private const val TAG = "VirtualScreen"
    private const val WIDTH = 720
    private const val HEIGHT = 1600
    private const val DPI = 280
    private const val IDLE_RESTORE_MS = 90_000L
    private const val IDLE_CLOSE_MS = 10 * 60_000L

    data class Info(val displayId: Int, val width: Int, val height: Int)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JSONObject>>()
    private val nextId = AtomicLong(1)
    private var ready: CompletableDeferred<Info>? = null
    private val _info = MutableStateFlow<Info?>(null)
    val info: StateFlow<Info?> = _info.asStateFlow()

    private var appContext: Context? = null
    private var idleJob: Job? = null

    private suspend fun root(command: String, timeoutMs: Long = 10_000): RootShell.ExecResult =
        runInterruptible(Dispatchers.IO) { RootShell.exec(command, timeoutMs, true) }

    /** 确保虚拟屏存在且系统处于可渲染状态；每次操作前调用。 */
    suspend fun prepare(context: Context): Info = mutex.withLock {
        appContext = context.applicationContext
        val current = startHelper(context.applicationContext)
        PowerKeeper.beginSession(context.applicationContext)
        scheduleIdle()
        current
    }

    private suspend fun startHelper(context: Context): Info {
        process?.takeIf { it.isAlive }?.let { _ -> _info.value?.let { return it } }
        stopHelper()
        val apk = context.applicationInfo.sourceDir
        val command = "CLASSPATH='$apk' exec app_process /system/bin com.voice.actonsay.vdisplay.DisplayHelper $WIDTH $HEIGHT $DPI"
        val started = withContext(Dispatchers.IO) { ProcessBuilder("su", "-c", command).start() }
        val waiting = CompletableDeferred<Info>()
        process = started
        writer = started.outputStream.bufferedWriter()
        ready = waiting
        Thread({
            runCatching {
                started.inputStream.bufferedReader().forEachLine { line ->
                    val message = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
                    when (message.optString("event")) {
                        "ready" -> waiting.complete(Info(message.getInt("displayId"), message.getInt("width"), message.getInt("height")))
                        "error" -> waiting.completeExceptionally(IllegalStateException(message.optString("message")))
                        else -> pending.remove(message.optLong("id"))?.complete(message)
                    }
                }
            }
            // 进程结束：让所有等待方立即失败，下次操作会重新启动。
            waiting.completeExceptionally(IllegalStateException("虚拟屏进程已退出"))
            pending.values.forEach { it.completeExceptionally(IllegalStateException("虚拟屏进程已退出")) }
            pending.clear()
            if (process === started) _info.value = null
        }, "virtual-screen-out").apply { isDaemon = true }.start()
        Thread({
            runCatching { started.errorStream.bufferedReader().forEachLine { Log.w(TAG, "helper: ${it.take(300)}") } }
        }, "virtual-screen-err").apply { isDaemon = true }.start()
        return try {
            withTimeout(15_000) { waiting.await() }.also {
                _info.value = it
                Log.i(TAG, "Virtual display ready: id=${it.displayId} ${it.width}x${it.height}")
            }
        } catch (error: Exception) {
            stopHelper()
            throw IllegalStateException("无法创建虚拟屏：${error.message}", error)
        }
    }

    /** 空闲一段时间后结束电源会话；更久不用时关闭虚拟屏进程。 */
    private fun scheduleIdle() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_RESTORE_MS)
            PowerKeeper.endSession()
            delay(IDLE_CLOSE_MS - IDLE_RESTORE_MS)
            mutex.withLock { stopHelper() }
        }
    }

    private suspend fun request(command: JSONObject, timeoutMs: Long = 8000): JSONObject {
        val id = nextId.getAndIncrement()
        val waiting = CompletableDeferred<JSONObject>()
        pending[id] = waiting
        try {
            withContext(Dispatchers.IO) {
                val out = writer ?: error("虚拟屏尚未启动")
                synchronized(out) { out.write(command.put("id", id).toString()); out.newLine(); out.flush() }
            }
            return withTimeout(timeoutMs) { waiting.await() }
        } finally {
            pending.remove(id)
        }
    }

    /** 当前画面的 JPEG。刚创建还没有任何内容时等待首帧。 */
    suspend fun screenshot(): ByteArray {
        repeat(6) {
            val reply = request(JSONObject().put("cmd", "screenshot").put("quality", 70))
            if (reply.optBoolean("ok")) return Base64.decode(reply.getString("jpeg"), Base64.NO_WRAP)
            delay(400)
        }
        error("虚拟屏还没有画面，请先在虚拟屏上打开一个应用")
    }

    /** 在虚拟屏上执行一条带显示器编号的 Shell 命令（点击、滑动、启动应用）。 */
    suspend fun shell(command: String): RootShell.ExecResult = root(command, 15_000)

    private fun stopHelper() {
        val running = process
        process = null
        _info.value = null
        if (running != null) {
            runCatching { writer?.let { out -> synchronized(out) { out.write("{\"cmd\":\"quit\"}"); out.newLine(); out.flush() } } }
            runCatching { running.outputStream.close() }
            running.destroy()
        }
        writer = null
        ready = null
    }

    /** 关闭虚拟屏：其上的应用窗口随之销毁，电源状态恢复。 */
    suspend fun close() = mutex.withLock {
        idleJob?.cancel()
        stopHelper()
        PowerKeeper.endSession()
    }
}
