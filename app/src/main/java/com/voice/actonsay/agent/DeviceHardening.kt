package com.voice.actonsay.agent

import android.content.Context
import android.util.Log

/**
 * 专用设备加固：让本应用不受后台联网/省电策略限制。
 *
 * 实测（Xiaomi HyperOS）：智能体打开别的应用后本应用退到后台，几秒后系统就会掐断它的新建网络连接，
 * 大模型请求全部 SYN 超时。有 root 时直接把应用加入 Doze 白名单并放开后台运行相关 appops。
 */
object DeviceHardening {
    private const val TAG = "DeviceHardening"

    @Volatile
    private var applied = false

    fun applyIfPossible(context: Context) {
        if (applied || !RootShell.hasRoot) return
        applied = true
        val pkg = context.packageName
        val commands = listOf(
            "dumpsys deviceidle whitelist +$pkg",
            "cmd appops set $pkg RUN_IN_BACKGROUND allow",
            "cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow",
            "cmd appops set $pkg SYSTEM_ALERT_WINDOW allow"
        )
        Thread {
            commands.forEach { cmd ->
                val r = RootShell.exec(cmd, timeoutMs = 8000)
                Log.i(TAG, "$cmd -> exit=${r.exitCode} ${r.stdout.trim().take(80)} ${r.stderr.trim().take(80)}")
            }
        }.apply { isDaemon = true; name = "device-hardening" }.start()
    }
}
