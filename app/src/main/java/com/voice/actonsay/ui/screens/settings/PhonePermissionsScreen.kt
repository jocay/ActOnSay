package com.voice.actonsay.ui.screens.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.voice.actonsay.MainActivity
import com.voice.actonsay.agent.SystemPermissions
import com.voice.actonsay.ui.components.SectionCard
import com.voice.actonsay.ui.components.SettingHint

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** 手机能力需要的 Android 系统权限与特殊授权，集中查看和开启。 */
@Composable
fun PhonePermissionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    // 从系统设置返回或授权对话框关闭后重新读取状态。
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    val prefs = remember(context) { context.getSharedPreferences(MainActivity.PERMISSION_PREFS, Context.MODE_PRIVATE) }

    fun openAppSettings() {
        runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
    }

    fun request(group: SystemPermissions.Group) {
        val missing = SystemPermissions.missing(context, group)
        if (missing.isEmpty()) return
        val asked = prefs.getStringSet("asked", emptySet()).orEmpty()
        // 申请过且系统不再给出说明，说明选了“不再询问”：只能去系统设置里开启。
        val blocked = activity == null || missing.any { it in asked && !activity.shouldShowRequestPermissionRationale(it) }
        if (blocked) openAppSettings() else {
            prefs.edit().putStringSet("asked", asked + missing).apply()
            launcher.launch(missing.toTypedArray())
        }
    }

    SubPageScaffold("手机权限", onBack) {
        SectionCard("说明") {
            SettingHint("这里是 Android 系统权限，决定应用本身能否读取通讯录、写入日历、拨号或发短信。" +
                "任务卡片上的“允许本次”是对某一次工具调用的确认，两者互不替代：系统权限缺失时工具会直接报告，授权后可让 AI 重试。")
        }
        SectionCard("系统权限") {
            SystemPermissions.runtimeGroups.filter { it.permissions.isNotEmpty() }.forEach { group ->
                val granted = remember(refresh) { SystemPermissions.granted(context, group) }
                PermissionRow(group.label, group.purpose, granted, if (granted) null else "授权") { request(group) }
            }
        }
        SectionCard("特殊授权") {
            val listener = remember(refresh) { NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName) }
            PermissionRow("读取通知", "回答“有什么新消息”", listener, if (listener) "管理" else "去开启") {
                runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            }
            val overlay = remember(refresh) { Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context) }
            PermissionRow("显示在其他应用上层", "后台设置闹钟、计时器、拨号时拉起系统应用", overlay, if (overlay) null else "去开启") {
                runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))) }
            }
            val battery = remember(refresh) {
                Build.VERSION.SDK_INT < 23 || (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)
            }
            PermissionRow("忽略电池优化", "息屏后保持待机与后台任务", battery, if (battery) null else "去开启") {
                runCatching { context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))) }
            }
            SettingHint("拒绝过且勾选了“不再询问”的权限无法再弹窗，点“授权”会打开系统的应用设置页。")
        }
    }
}

@Composable
private fun PermissionRow(title: String, purpose: String, granted: Boolean, action: String?, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(purpose, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (granted) "已授权" else "未授权", style = MaterialTheme.typography.labelSmall,
                color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        }
        if (action != null) FilledTonalButton(onClick = onAction) { Text(action) }
    }
}
