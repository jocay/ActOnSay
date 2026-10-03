package com.voice.actonsay.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * “黑屏但醒着”：系统保持交互状态，物理面板断电（`cmd display power-off 0`）。
 * 这台设备上虚拟屏只在交互状态下渲染，所以 AI 在息屏时操作虚拟屏需要这个状态。
 *
 * 两种来源：虚拟屏操作期间的临时会话（空闲后恢复并睡回去），以及常驻交互模式（一直保持）。
 * 电源键的语义保持直观：黑屏时按一下会正常亮屏；亮屏时按一下，常驻模式下回到黑屏但醒着。
 */
object PowerKeeper {
    private const val TAG = "PowerKeeper"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var appContext: Context? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var receiver: BroadcastReceiver? = null

    /** 面板被我们断电中。 */
    @Volatile private var panelForcedOff = false
    /** 临时会话是我们把休眠的设备唤醒的，结束时要睡回去。 */
    @Volatile private var wokeBySession = false
    @Volatile private var sessionActive = false
    @Volatile private var always = false

    private suspend fun root(command: String) = runInterruptible(Dispatchers.IO) { RootShell.exec(command, 10_000, true) }

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(received: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_OFF) scope.launch { onSystemSleep() }
            }
        }
        ContextCompat.registerReceiver(context.applicationContext, screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        receiver = screenReceiver
    }

    /** 系统进入休眠：黑屏时用户按了电源键表示想看屏幕；亮屏时休眠则在常驻模式下回到黑屏醒着。 */
    private suspend fun onSystemSleep() = mutex.withLock {
        if (panelForcedOff) {
            panelForcedOff = false
            wokeBySession = false
            releaseLock()
            runCatching { root("cmd display power-reset 0; input keyevent KEYCODE_WAKEUP") }
            Log.i(TAG, "Power key while dark: screen turned on for the user")
            return@withLock
        }
        if (always || sessionActive) {
            delay(1500)
            goDark()
        }
    }

    private suspend fun isAwake(): Boolean = root("dumpsys power | grep -m1 -o 'mWakefulness=[A-Za-z]*'").stdout.contains("=Awake")

    /** 设备在休眠时唤醒并断开面板；已经醒着（用户正在用）时不动屏幕。 */
    private suspend fun goDark() {
        if (!isAwake()) {
            // 两条命令放在同一次调用里，面板亮起的时间尽量短。
            root("input keyevent KEYCODE_WAKEUP; cmd display power-off 0")
            panelForcedOff = true
            Log.i(TAG, "Woke the system with the panel powered off")
        }
        holdLock()
    }

    @Suppress("DEPRECATION")
    private fun holdLock() {
        val context = appContext ?: return
        val lock = wakeLock ?: (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "actonsay:interactive").also { it.setReferenceCounted(false); wakeLock = it }
        // 带超时以防进程异常遗留；常驻模式和会话会在到期前续上。
        lock.acquire(15 * 60_000L)
    }

    private fun releaseLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
    }

    /** 虚拟屏每次操作前调用：保证系统处于可渲染状态。 */
    suspend fun beginSession(context: Context) = mutex.withLock {
        init(context)
        val wasForced = panelForcedOff
        sessionActive = true
        goDark()
        if (!wasForced && panelForcedOff) wokeBySession = true
    }

    /** 虚拟屏空闲：常驻模式下保持；否则恢复面板供电，并让我们唤醒的设备睡回去。 */
    suspend fun endSession() = mutex.withLock {
        sessionActive = false
        if (always) return@withLock
        releaseLock()
        if (wokeBySession && panelForcedOff) {
            wokeBySession = false
            panelForcedOff = false
            runCatching { root("cmd display power-reset 0; input keyevent KEYCODE_SLEEP") }
            Log.i(TAG, "Session ended: panel restored and device put back to sleep")
        }
    }

    fun setAlwaysInteractive(context: Context, enabled: Boolean) {
        init(context)
        always = enabled
        scope.launch {
            mutex.withLock {
                if (enabled) {
                    goDark()
                    keepAlive()
                } else if (!sessionActive) {
                    releaseLock()
                    if (panelForcedOff) {
                        panelForcedOff = false
                        runCatching { root("cmd display power-reset 0; input keyevent KEYCODE_SLEEP") }
                    }
                }
            }
        }
    }

    /** 常驻模式下定期续上唤醒锁。 */
    private fun keepAlive() {
        scope.launch {
            while (always) {
                delay(10 * 60_000L)
                if (always) mutex.withLock { holdLock() }
            }
        }
    }
}
