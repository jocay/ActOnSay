package com.voice.actonsay.agent.tools

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.telephony.SmsManager
import com.voice.actonsay.agent.AgentTool
import com.voice.actonsay.agent.NotificationReader
import com.voice.actonsay.agent.ToolContext
import com.voice.actonsay.agent.ToolResult
import com.voice.actonsay.agent.schemaObject
import com.voice.actonsay.agent.schemaProp
import com.voice.actonsay.asr.AsrManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 系统时钟、日历、通讯录、电话和短信。界面由系统应用负责，提醒的声音和锁屏显示也由它们保证。 */
private fun launch(context: Context, intent: Intent): Result<Unit> = runCatching {
    // 后台启动 Activity 依赖“显示在其他应用上层”权限；没有可处理的应用时直接报错。
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: android.content.ActivityNotFoundException) {
        error("系统里没有能处理这个操作的应用")
    }
}

class SetTimerTool : AgentTool {
    override val name = "set_timer"
    override val description = "用系统时钟设一个倒计时提醒，时间到会响铃并在锁屏显示。用户说“N 分钟/小时后提醒我……”时用它，比 create_automation 可靠。最长 24 小时。"
    override val parameters = schemaObject(
        "seconds" to schemaProp("integer", "倒计时秒数，1–86400"),
        "message" to schemaProp("string", "提醒内容，会显示在计时器上"),
        required = listOf("seconds")
    )
    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val seconds = args.optInt("seconds")
        if (seconds !in 1..86400) return ToolResult.failure("倒计时需在 1 秒到 24 小时之间；更久的提醒用 set_alarm", "INVALID_ARGUMENT")
        ctx.onProgress("设置计时提醒")
        val message = args.optString("message").take(100)
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true).apply { if (message.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, message) }
        return launch(ctx.context, intent).fold(
            onSuccess = {
                val due = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(System.currentTimeMillis() + seconds * 1000L))
                ToolResult.success("已在系统时钟设置 ${seconds / 60} 分 ${seconds % 60} 秒的计时器，约 $due 响铃" + if (message.isNotBlank()) "：$message" else "")
            },
            onFailure = { ToolResult.failure("设置计时器失败：${it.message}", "CLOCK_UNAVAILABLE") }
        )
    }
}

class SetAlarmTool : AgentTool {
    override val name = "set_alarm"
    override val description = "用系统时钟设一个闹钟（指定钟点，可每周重复）。用户说“明早七点叫我”“每个工作日八点提醒”时用它。"
    override val parameters = schemaObject(
        "hour" to schemaProp("integer", "小时 0–23"),
        "minute" to schemaProp("integer", "分钟 0–59"),
        "message" to schemaProp("string", "闹钟标签"),
        "days" to schemaProp("array", "可选，每周重复的日子：mon tue wed thu fri sat sun；留空表示只响一次"),
        required = listOf("hour", "minute")
    )
    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val hour = args.optInt("hour", -1)
        val minute = args.optInt("minute", -1)
        if (hour !in 0..23 || minute !in 0..59) return ToolResult.failure("时间不合法", "INVALID_ARGUMENT")
        val names = mapOf("sun" to Calendar.SUNDAY, "mon" to Calendar.MONDAY, "tue" to Calendar.TUESDAY, "wed" to Calendar.WEDNESDAY,
            "thu" to Calendar.THURSDAY, "fri" to Calendar.FRIDAY, "sat" to Calendar.SATURDAY)
        val dayArray = args.optJSONArray("days")
        val days = ArrayList<Int>()
        if (dayArray != null) for (i in 0 until dayArray.length()) names[dayArray.optString(i).lowercase().take(3)]?.let { days += it }
        ctx.onProgress("设置闹钟")
        val message = args.optString("message").take(100)
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, hour).putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true).apply {
                if (message.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, message)
                if (days.isNotEmpty()) putExtra(AlarmClock.EXTRA_DAYS, days)
            }
        return launch(ctx.context, intent).fold(
            onSuccess = { ToolResult.success("已在系统时钟设置 %02d:%02d 的闹钟".format(hour, minute) + (if (days.isNotEmpty()) "，每周重复" else "") + if (message.isNotBlank()) "：$message" else "") },
            onFailure = { ToolResult.failure("设置闹钟失败：${it.message}", "CLOCK_UNAVAILABLE") }
        )
    }
}

/** 本机日历：设备没有云账户时自动建一个本地日历“ActOnSay”。 */
private object LocalCalendar {
    private const val NAME = "ActOnSay"
    fun id(context: Context): Long {
        val resolver = context.contentResolver
        resolver.query(CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID),
            "${CalendarContract.Calendars.ACCOUNT_NAME}=? AND ${CalendarContract.Calendars.ACCOUNT_TYPE}=?",
            arrayOf(NAME, CalendarContract.ACCOUNT_TYPE_LOCAL), null)?.use { if (it.moveToFirst()) return it.getLong(0) }
        val uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, NAME)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build()
        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, NAME)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME, NAME)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, NAME)
            put(CalendarContract.Calendars.CALENDAR_COLOR, 0xFF3F51B5.toInt())
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, NAME)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().id)
        }
        return ContentUris.parseId(resolver.insert(uri, values) ?: error("无法创建本地日历"))
    }

    /** 接受带时区的 ISO 时间，或按手机时区理解的“yyyy-MM-ddTHH:mm”。 */
    fun parse(value: String): Long = runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrElse {
        LocalDateTime.parse(value.trim().replace(' ', 'T').let { if (it.length == 16) "$it:00" else it })
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}

class AddCalendarEventTool : AgentTool {
    override val name = "add_calendar_event"
    override val description = "在手机日历里添加日程（标题、开始结束时间、地点、备注）。日程是记录用的；需要到点响铃提醒时另外用 set_alarm 或 set_timer。"
    override val parameters = schemaObject(
        "title" to schemaProp("string", "标题"),
        "start" to schemaProp("string", "开始时间，ISO 格式，如 2026-10-04T15:00:00+08:00"),
        "end" to schemaProp("string", "结束时间，留空默认一小时"),
        "location" to schemaProp("string", "地点"),
        "description" to schemaProp("string", "备注"),
        required = listOf("title", "start")
    )
    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val start = runCatching { LocalCalendar.parse(args.getString("start")) }.getOrElse { return@withContext ToolResult.failure("开始时间格式不对", "INVALID_ARGUMENT") }
        val end = args.optString("end").takeIf { it.isNotBlank() }?.let { runCatching { LocalCalendar.parse(it) }.getOrNull() } ?: (start + 3_600_000)
        ctx.onProgress("添加日程")
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, LocalCalendar.id(ctx.context))
            put(CalendarContract.Events.TITLE, args.getString("title").take(200))
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.DTEND, end)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            args.optString("location").takeIf { it.isNotBlank() }?.let { put(CalendarContract.Events.EVENT_LOCATION, it.take(200)) }
            args.optString("description").takeIf { it.isNotBlank() }?.let { put(CalendarContract.Events.DESCRIPTION, it.take(2000)) }
        }
        val uri = ctx.context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: return@withContext ToolResult.failure("写入日历失败", "CALENDAR_UNAVAILABLE")
        val format = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)
        ToolResult.success("已添加日程“${args.getString("title")}”，${format.format(Date(start))} 至 ${format.format(Date(end))}", JSONObject().put("event_id", ContentUris.parseId(uri)))
    }
}

class ListCalendarEventsTool : AgentTool {
    override val name = "list_calendar_events"
    override val description = "查看手机日历里未来若干天的日程。"
    override val parameters = schemaObject("days" to schemaProp("integer", "未来几天，默认 7，最多 60", default = 7))
    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val until = now + args.optInt("days", 7).coerceIn(1, 60) * 86_400_000L
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { ContentUris.appendId(it, now); ContentUris.appendId(it, until) }.build()
        val format = SimpleDateFormat("M月d日 E HH:mm", Locale.CHINA)
        val lines = mutableListOf<String>()
        ctx.context.contentResolver.query(uri, arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.EVENT_LOCATION),
            null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { cursor ->
            while (cursor.moveToNext() && lines.size < 50) {
                val location = cursor.getString(2).orEmpty()
                lines += "${format.format(Date(cursor.getLong(1)))} ${cursor.getString(0).orEmpty()}" + if (location.isNotBlank()) "（$location）" else ""
            }
        }
        ToolResult.success(if (lines.isEmpty()) "这段时间没有日程" else lines.joinToString("\n"))
    }
}

/** 按姓名查联系人号码；没有匹配时原样当作号码。 */
private fun resolveNumber(context: Context, target: String): Pair<String, String>? {
    val digits = target.filter { it.isDigit() || it == '+' }
    if (digits.length >= 3 && digits.length >= target.count { !it.isWhitespace() } - 1) return target to digits
    val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(target))
    context.contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
        null, null, null)?.use { if (it.moveToFirst()) return it.getString(0) to it.getString(1) }
    return null
}

class FindContactTool : AgentTool {
    override val name = "find_contact"
    override val description = "在手机通讯录里按姓名查找联系人及号码。"
    override val parameters = schemaObject("name" to schemaProp("string", "姓名或部分姓名"), required = listOf("name"))
    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(args.getString("name")))
        val lines = mutableListOf<String>()
        ctx.context.contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null)?.use { while (it.moveToNext() && lines.size < 20) lines += "${it.getString(0)}：${it.getString(1)}" }
        ToolResult.success(if (lines.isEmpty()) "通讯录里没有找到“${args.getString("name")}”" else lines.distinct().joinToString("\n"))
    }
}

/** 打电话、发短信默认每次都要在界面确认，模型和语音都不能代替这一步。 */
private suspend fun confirmPhoneAction(ctx: ToolContext) {
    if (AsrManager.state.value.phoneActionsConfirm) ctx.requireConfirmation()
}

class CallPhoneTool : AgentTool {
    override val name = "call_phone"
    override val description = "拨打电话（联系人姓名或号码）。默认需要用户在界面确认后才会拨出；拨打前先用一句话告诉用户要打给谁。"
    override val parameters = schemaObject("target" to schemaProp("string", "联系人姓名或电话号码"), required = listOf("target"))
    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val (name, number) = withContext(Dispatchers.IO) { resolveNumber(ctx.context, args.getString("target")) }
            ?: return ToolResult.failure("通讯录里没有找到“${args.getString("target")}”", "CONTACT_NOT_FOUND")
        confirmPhoneAction(ctx)
        ctx.onProgress("拨打 $name")
        return launch(ctx.context, Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number)))).fold(
            onSuccess = { ToolResult.success("正在拨打 $name（$number）") },
            onFailure = { ToolResult.failure("拨号失败：${it.message}", "CALL_FAILED") }
        )
    }
}

class SendSmsTool : AgentTool {
    override val name = "send_sms"
    override val description = "发送短信（联系人姓名或号码）。默认需要用户在界面确认后才会发出；发送前先把收件人和内容念给用户。"
    override val parameters = schemaObject(
        "target" to schemaProp("string", "联系人姓名或电话号码"),
        "text" to schemaProp("string", "短信内容"),
        required = listOf("target", "text")
    )
    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val text = args.getString("text").trim()
        if (text.isEmpty()) return ToolResult.failure("短信内容不能为空", "INVALID_ARGUMENT")
        val (name, number) = withContext(Dispatchers.IO) { resolveNumber(ctx.context, args.getString("target")) }
            ?: return ToolResult.failure("通讯录里没有找到“${args.getString("target")}”", "CONTACT_NOT_FOUND")
        confirmPhoneAction(ctx)
        ctx.onProgress("发送短信给 $name")
        return runCatching {
            @Suppress("DEPRECATION")
            val sms = if (android.os.Build.VERSION.SDK_INT >= 31) ctx.context.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
            sms.sendMultipartTextMessage(number, null, sms.divideMessage(text), null, null)
        }.fold(
            onSuccess = { ToolResult.success("已向 $name（$number）发送短信") },
            onFailure = { ToolResult.failure("发送失败：${it.message}", "SMS_FAILED") }
        )
    }
}

class ReadNotificationsTool : AgentTool {
    override val name = "read_notifications"
    override val description = "读取手机最近收到的通知（消息、短信、应用提醒），用于回答“有什么新消息”。可按应用名过滤。"
    override val parameters = schemaObject(
        "limit" to schemaProp("integer", "最多返回条数，默认 10", default = 10),
        "app" to schemaProp("string", "可选，只看某个应用（应用名或包名的一部分）")
    )
    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        if (!NotificationReader.connected) return ToolResult.failure("通知读取权限未开启：请在系统设置里允许 ActOnSay 读取通知", "PERMISSION_DENIED")
        val filter = args.optString("app").trim()
        val format = SimpleDateFormat("HH:mm", Locale.CHINA)
        val entries = NotificationReader.snapshot()
            .filter { filter.isEmpty() || it.app.contains(filter, true) || it.packageName.contains(filter, true) }
            .take(args.optInt("limit", 10).coerceIn(1, 50))
        if (entries.isEmpty()) return ToolResult.success("没有新的通知")
        return ToolResult.success(entries.joinToString("\n") { "${format.format(Date(it.time))} ${it.app}｜${it.title}：${it.text.take(300)}" })
    }
}
