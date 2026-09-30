package com.voice.actonsay.agent.tools

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.voice.actonsay.agent.AgentTool
import com.voice.actonsay.agent.RootShell
import com.voice.actonsay.agent.ToolContext
import com.voice.actonsay.agent.schemaObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 不需要 root 的设备状态快照：时间、电量、网络、音量、屏幕、内存、root 状态。 */
class DeviceStatusTool : AgentTool {
    override val name = "get_device_status"
    override val description = "获取设备当前状态：日期时间、电量与充电状态、网络连接类型、媒体/铃声音量、屏幕是否点亮、可用内存、是否有 root。回答“几点了 / 还有多少电 / 有没有联网”这类问题时直接用它，不要去翻 shell。"
    override val parameters = schemaObject()

    override suspend fun execute(args: JSONObject, ctx: ToolContext): String = withContext(Dispatchers.IO) {
        val c = ctx.context
        val sb = StringBuilder()
        sb.append("时间: ").append(SimpleDateFormat("yyyy-MM-dd EEEE HH:mm:ss", Locale.CHINA).format(Date())).append('\n')
        sb.append("设备: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}\n")
        sb.append(runCatching { batteryLine(c) }.getOrElse { "batteryLine: 读取失败 ${it.message}" }).append('\n')
        sb.append(runCatching { networkLine(c) }.getOrElse { "networkLine: 读取失败 ${it.message}" }).append('\n')
        sb.append(runCatching { volumeLine(c) }.getOrElse { "volumeLine: 读取失败 ${it.message}" }).append('\n')
        val pm = c.getSystemService(Context.POWER_SERVICE) as? PowerManager
        sb.append("屏幕: ").append(if (pm?.isInteractive == true) "点亮" else "熄灭").append('\n')
        sb.append(runCatching { memoryLine(c) }.getOrElse { "memoryLine: 读取失败 ${it.message}" }).append('\n')
        sb.append("root: ").append(if (RootShell.hasRoot) "已授权" else "未授权")
        sb.toString()
    }

    private fun batteryLine(c: Context): String {
        val intent = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return "电量: 未知"
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val pct = if (level >= 0) level * 100 / scale else -1
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
        val statusText = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "充电中"
            BatteryManager.BATTERY_STATUS_FULL -> "已充满"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "放电中"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "未充电"
            else -> "未知"
        }
        val source = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "，接交流电源"
            BatteryManager.BATTERY_PLUGGED_USB -> "，接 USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "，无线充电"
            else -> ""
        }
        val tempText = if (temp >= 0) "，温度 ${temp / 10.0}°C" else ""
        return "电量: ${if (pct >= 0) "$pct%" else "未知"}，$statusText$source$tempText"
    }

    private fun networkLine(c: Context): String {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return "网络: 未知"
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return "网络: 未连接"
        val type = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动数据"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "有线"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "其他"
        }
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return "网络: $type${if (validated) "，可上网" else "，可能无法上网"}"
    }

    private fun volumeLine(c: Context): String {
        val am = c.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return "音量: 未知"
        fun pct(stream: Int): Int {
            val max = am.getStreamMaxVolume(stream).coerceAtLeast(1)
            return am.getStreamVolume(stream) * 100 / max
        }
        val mode = when (am.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "静音"
            AudioManager.RINGER_MODE_VIBRATE -> "振动"
            else -> "响铃"
        }
        return "音量: 媒体 ${pct(AudioManager.STREAM_MUSIC)}%，铃声 ${pct(AudioManager.STREAM_RING)}%，闹钟 ${pct(AudioManager.STREAM_ALARM)}%，模式 $mode"
    }

    private fun memoryLine(c: Context): String {
        val am = c.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager ?: return "内存: 未知"
        val info = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val avail = info.availMem / (1024 * 1024)
        val total = info.totalMem / (1024 * 1024)
        return "内存: 可用 ${avail}MB / 共 ${total}MB${if (info.lowMemory) "，内存紧张" else ""}"
    }
}
