package com.voice.actonsay.agent

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/**
 * Android 系统运行时权限：决定应用本身能否读通讯录、写日历、拨号或发短信。
 * 与任务卡片上的“允许本次”不同，后者是对单次工具调用的确认，两者互不替代。
 */
object SystemPermissions {
    data class Group(val key: String, val label: String, val permissions: List<String>, val purpose: String)

    val MICROPHONE = Group("microphone", "麦克风", listOf(Manifest.permission.RECORD_AUDIO), "语音唤醒与识别")
    val NOTIFICATIONS = Group("notifications", "通知", if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList(),
        "任务完成、定时提醒和前台服务通知")
    val CONTACTS = Group("contacts", "通讯录", listOf(Manifest.permission.READ_CONTACTS), "按姓名查找联系人，打电话和发短信时匹配号码")
    val CALENDAR = Group("calendar", "日历", listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR), "添加和查看日程")
    val PHONE = Group("phone", "电话", listOf(Manifest.permission.CALL_PHONE), "直接拨出电话")
    val SMS = Group("sms", "短信", listOf(Manifest.permission.SEND_SMS), "发送短信")
    val runtimeGroups = listOf(MICROPHONE, NOTIFICATIONS, CONTACTS, CALENDAR, PHONE, SMS)

    /** 工具运行时发现缺少的权限；应用回到前台时据此弹出系统授权框。 */
    private val _pending = MutableStateFlow<List<String>>(emptyList())
    val pending: StateFlow<List<String>> = _pending.asStateFlow()

    fun missing(context: Context, group: Group): List<String> = group.permissions.filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }

    fun granted(context: Context, group: Group): Boolean = missing(context, group).isEmpty()

    fun labelOf(permission: String): String = runtimeGroups.firstOrNull { permission in it.permissions }?.label ?: permission

    /** 缺少权限时登记请求并返回给模型的失败结果；权限齐全时返回 null。 */
    fun check(context: Context, vararg groups: Group): ToolResult? {
        val missingGroups = groups.filter { missing(context, it).isNotEmpty() }
        if (missingGroups.isEmpty()) return null
        val permissions = missingGroups.flatMap { missing(context, it) }
        _pending.update { (it + permissions).distinct() }
        val labels = missingGroups.joinToString("、") { "${it.label}（${it.purpose}）" }
        return ToolResult("FAILED", "缺少 Android 系统权限：$labels。应用会在用户打开界面时请求授权，也可在“设置 › 手机权限”开启。" +
            "这是系统权限缺失，不是用户拒绝了本次操作；请告诉用户需要授权，授权后可重试。",
            errorType = "SYSTEM_PERMISSION_MISSING", data = JSONObject().put("permissions", JSONArray(permissions)))
    }

    /** 由界面取走待申请的权限，并去掉已经授予的。 */
    fun takePending(context: Context): List<String> {
        val requested = _pending.value
        _pending.value = emptyList()
        return requested.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
    }
}
