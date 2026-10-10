package com.voice.actonsay.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.voice.actonsay.ui.components.BridgeMessage
import com.voice.actonsay.ui.components.BridgeTextField
import com.voice.actonsay.ui.components.bridgeRequest
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

private val WEEKDAYS = listOf("mon" to "一", "tue" to "二", "wed" to "三", "thu" to "四", "fri" to "五", "sat" to "六", "sun" to "日")
private val MODES = listOf("daily" to "每天", "weekdays" to "工作日", "weekly" to "每周", "once" to "一次", "advanced" to "高级")
private const val ISO_OFFSET = "yyyy-MM-dd'T'HH:mm:ssXXX"

/** 能用“每天 / 工作日 / 每周”表达的 Cron；其余返回 null，由高级模式原样编辑。 */
private data class SimpleCron(val mode: String, val hour: Int, val minute: Int, val days: Set<String>)

private fun parseSimpleCron(expression: String): SimpleCron? {
    val match = Regex("^(\\d{1,2}) (\\d{1,2}) \\* \\* (\\S+)$").find(expression.trim()) ?: return null
    val (minute, hour, dow) = match.destructured
    val h = hour.toInt()
    val m = minute.toInt()
    if (h !in 0..23 || m !in 0..59) return null
    val names = WEEKDAYS.map { it.first }
    return when (dow) {
        "*" -> SimpleCron("daily", h, m, emptySet())
        "mon-fri" -> SimpleCron("weekdays", h, m, emptySet())
        else -> dow.split(",").takeIf { parts -> parts.all { it in names } }?.let { SimpleCron("weekly", h, m, it.toSet()) }
    }
}

/** 解析带时区偏移的 ISO 时间；秒以下的小数部分忽略。 */
private fun parseIso(value: String): java.util.Date? {
    val cleaned = value.trim().replace(Regex("\\.\\d+"), "").replace(Regex("Z$"), "+00:00")
    return runCatching { SimpleDateFormat(ISO_OFFSET, Locale.US).parse(cleaned) }.getOrNull()
}

/** 按计划时区显示为“10月4日 周六 09:00”。 */
private fun formatInstant(value: String, timezone: String): String {
    val parsed = parseIso(value) ?: return value
    return SimpleDateFormat("M月d日 EEE HH:mm", Locale.CHINA).apply {
        timeZone = TimeZone.getTimeZone(timezone.ifBlank { TimeZone.getDefault().id })
    }.format(parsed)
}

/** 计划的易读描述：能识别的 Cron 显示为“每天 09:00”，其余显示原表达式。 */
fun describeSchedule(schedule: JSONObject): String {
    val timezone = schedule.optString("timezone")
    return when (schedule.optString("schedule_type")) {
        "once" -> "一次：${formatInstant(schedule.optString("run_at"), timezone)}"
        "interval" -> schedule.optLong("interval_seconds").let { seconds ->
            when {
                seconds > 0 && seconds % 86400 == 0L -> "每 ${seconds / 86400} 天"
                seconds > 0 && seconds % 3600 == 0L -> "每 ${seconds / 3600} 小时"
                seconds > 0 && seconds % 60 == 0L -> "每 ${seconds / 60} 分钟"
                else -> "每 $seconds 秒"
            }
        }
        else -> parseSimpleCron(schedule.optString("cron"))?.let { simple ->
            val time = "%02d:%02d".format(simple.hour, simple.minute)
            when (simple.mode) {
                "daily" -> "每天 $time"
                "weekdays" -> "工作日 $time"
                else -> "每周" + WEEKDAYS.filter { it.first in simple.days }.joinToString("、") { it.second } + " $time"
            }
        } ?: "Cron：${schedule.optString("cron")}"
    }
}

/** 下一次执行时间的易读形式；没有待执行时间时返回 null。 */
fun describeNextRun(schedule: JSONObject): String? =
    schedule.optString("next_run").takeUnless { it.isBlank() || it == "null" }?.let { formatInstant(it, schedule.optString("timezone")) }

/**
 * 定时任务编辑器：常用的“每天 / 工作日 / 每周 / 一次”用时间选择完成，Cron 与固定间隔放在“高级”。
 * 保存前由执行服务按正式调度规则预览接下来几次触发时间。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ScheduleEditor(schedule: JSONObject?, busy: Boolean, requestError: String, onDismiss: () -> Unit, onSave: (JSONObject) -> Unit) {
    val id = schedule?.optString("id").orEmpty()
    val existingType = schedule?.optString("schedule_type") ?: "cron"
    val simple = remember(id) { schedule?.takeIf { existingType == "cron" }?.let { parseSimpleCron(it.optString("cron")) } }
    val existingRunAt = remember(id) { schedule?.takeIf { existingType == "once" }?.let { parseIso(it.optString("run_at")) } }
    var name by rememberSaveable(id) { mutableStateOf(schedule?.optString("name").orEmpty()) }
    var kind by rememberSaveable(id) { mutableStateOf(schedule?.optString("kind") ?: "ai") }
    var timezone by rememberSaveable(id) { mutableStateOf(schedule?.optString("timezone") ?: TimeZone.getDefault().id) }
    // 新建默认“每天”；已有计划选择最贴近其表达式的模式。
    var mode by rememberSaveable(id) { mutableStateOf(when {
        schedule == null -> "daily"
        simple != null -> simple.mode
        existingType == "once" && existingRunAt != null -> "once"
        else -> "advanced"
    }) }
    var days by rememberSaveable(id) { mutableStateOf(simple?.days?.toList() ?: listOf("mon")) }
    val initialCalendar = remember(id) {
        Calendar.getInstance(TimeZone.getTimeZone(timezone)).apply {
            if (existingRunAt != null) time = existingRunAt
            else { add(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0) }
        }
    }
    var date by rememberSaveable(id) {
        mutableStateOf(SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = initialCalendar.timeZone }.format(initialCalendar.time))
    }
    val timeState = rememberTimePickerState(
        initialHour = simple?.hour ?: initialCalendar.get(Calendar.HOUR_OF_DAY),
        initialMinute = simple?.minute ?: initialCalendar.get(Calendar.MINUTE),
        is24Hour = true
    )
    var advancedType by rememberSaveable(id) { mutableStateOf(if (existingType == "interval") "interval" else "cron") }
    var interval by rememberSaveable(id) { mutableStateOf(schedule?.optLong("interval_seconds", 3600)?.toString() ?: "3600") }
    var cron by rememberSaveable(id) { mutableStateOf(schedule?.optString("cron")?.takeIf { it.isNotBlank() } ?: "0 9 * * *") }
    var content by rememberSaveable(id) { mutableStateOf(schedule?.optString(if (kind == "ai") "goal" else "command").orEmpty()) }
    var cwd by rememberSaveable(id) { mutableStateOf(schedule?.optString("cwd").orEmpty()) }
    var skip by rememberSaveable(id) { mutableStateOf(schedule?.optString("misfire_policy") == "skip") }
    var localError by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf(emptyList<String>()) }
    var previewError by remember { mutableStateOf("") }

    /** 按当前选择生成时间字段；不完整时返回错误说明。 */
    fun timing(): Pair<JSONObject?, String> {
        if (timezone.trim() !in TimeZone.getAvailableIDs()) return null to "请输入有效时区，例如 Asia/Shanghai"
        val zone = TimeZone.getTimeZone(timezone.trim())
        val body = JSONObject().put("timezone", timezone.trim())
        val hour = timeState.hour
        val minute = timeState.minute
        when (mode) {
            "daily" -> body.put("schedule_type", "cron").put("cron", "$minute $hour * * *")
            "weekdays" -> body.put("schedule_type", "cron").put("cron", "$minute $hour * * mon-fri")
            "weekly" -> {
                if (days.isEmpty()) return null to "请至少选择一天"
                val selected = WEEKDAYS.map { it.first }.filter { it in days }.joinToString(",")
                body.put("schedule_type", "cron").put("cron", "$minute $hour * * $selected")
            }
            "once" -> {
                val parser = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = zone; isLenient = false }
                val instant = runCatching { parser.parse("${date.trim()} %02d:%02d".format(hour, minute)) }.getOrNull()
                    ?: return null to "日期格式应为 2026-10-04"
                body.put("schedule_type", "once").put("run_at", SimpleDateFormat(ISO_OFFSET, Locale.US).apply { timeZone = zone }.format(instant))
            }
            else -> if (advancedType == "interval") {
                val seconds = interval.trim().toLongOrNull() ?: 0
                if (seconds < 60) return null to "间隔需至少 60 秒"
                body.put("schedule_type", "interval").put("interval_seconds", seconds)
                // 已有计划保留原起点，预览与保存后的实际时间一致。
                schedule?.optString("anchor")?.takeIf { it.isNotBlank() }?.let { body.put("anchor", it) }
            } else {
                val fields = cron.trim().split(Regex("\\s+"))
                if (fields.size != 5) return null to "Cron 需要 5 个字段"
                if (fields.last().any { it.isDigit() }) return null to "星期请使用 mon、tue、wed、thu、fri、sat、sun 或 *"
                body.put("schedule_type", "cron").put("cron", fields.joinToString(" "))
            }
        }
        return body to ""
    }

    val (currentTiming, timingError) = timing()
    val timingKey = currentTiming?.toString() ?: timingError
    // 预览由执行服务按正式调度规则计算，保证与实际触发一致；输入停顿后再请求。
    LaunchedEffect(timingKey) {
        preview = emptyList()
        previewError = ""
        val body = currentTiming ?: return@LaunchedEffect
        delay(500)
        runCatching { bridgeRequest("POST", "/v1/schedules/preview", JSONObject(body.toString()).put("count", 5)) }
            .onSuccess { result ->
                val runs = result.optJSONArray("next_runs")
                preview = if (runs == null) emptyList() else List(runs.length()) { formatInstant(runs.optString(it), timezone) }
                if (preview.isEmpty()) previewError = "没有待执行的时间，一次性时间可能已经过去"
            }
            .onFailure { previewError = "暂时无法预览：${it.message ?: "执行环境未连接"}" }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (schedule == null) "新建定时任务" else "编辑定时任务") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { BridgeTextField(name, { name = it }, "名称", true) }
                item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = kind == "ai", onClick = { kind = "ai" }, label = { Text("AI 目标") })
                    FilterChip(selected = kind == "script", onClick = { kind = "script" }, label = { Text("固定脚本") })
                } }
                item { BridgeTextField(content, { content = it }, if (kind == "ai") "目标" else "Debian Bash 脚本") }
                item { FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    MODES.forEach { (value, label) ->
                        FilterChip(selected = mode == value, onClick = { mode = value }, label = { Text(label) })
                    }
                } }
                if (mode == "weekly") item { FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    WEEKDAYS.forEach { (value, label) ->
                        FilterChip(selected = value in days, onClick = { days = if (value in days) days - value else days + value },
                            label = { Text("周$label") })
                    }
                } }
                if (mode == "once") item { BridgeTextField(date, { date = it }, "日期（yyyy-MM-dd）", true) }
                if (mode != "advanced") {
                    item {
                        Column {
                            Text("时间", style = MaterialTheme.typography.labelMedium)
                            TimeInput(state = timeState)
                        }
                    }
                } else {
                    item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = advancedType == "interval", onClick = { advancedType = "interval" }, label = { Text("固定间隔") })
                        FilterChip(selected = advancedType == "cron", onClick = { advancedType = "cron" }, label = { Text("Cron") })
                    } }
                    item {
                        if (advancedType == "interval") BridgeTextField(interval, { interval = it }, "间隔秒数（至少 60）", true)
                        else BridgeTextField(cron, { cron = it }, "Cron：分 时 日 月 星期（mon–sun 或 *）", true)
                    }
                }
                item { BridgeTextField(timezone, { timezone = it }, "时区", true) }
                if (kind == "script") item { BridgeTextField(cwd, { cwd = it }, "工作目录（可选）", true) }
                item { Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("错过后跳过补跑", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Switch(checked = skip, onCheckedChange = { skip = it })
                } }
                item {
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("接下来的执行时间（$timezone）", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            when {
                                timingError.isNotBlank() -> Text(timingError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                previewError.isNotBlank() -> Text(previewError, style = MaterialTheme.typography.bodySmall)
                                preview.isEmpty() -> Text("正在计算…", style = MaterialTheme.typography.bodySmall)
                                else -> preview.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                            }
                            Text(if (skip) "错过的触发直接跳过。" else "24 小时内错过的触发会合并补跑最近一次。", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                if (localError.isNotBlank()) item { BridgeMessage(localError, true) }
                if (requestError.isNotBlank()) item { BridgeMessage(requestError, true) }
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            val timingBody = currentTiming
            when {
                name.isBlank() || content.isBlank() -> localError = "名称和执行内容不能为空"
                timingBody == null -> localError = timingError
                else -> {
                    localError = ""
                    val body = JSONObject(timingBody.toString()).put("name", name.trim()).put("kind", kind)
                        .put("enabled", schedule?.optBoolean("enabled") ?: true)
                        .put("misfire_policy", if (skip) "skip" else "latest").put("catchup_window_seconds", 86400)
                        .put(if (kind == "ai") "goal" else "command", content)
                    if (kind == "script") body.put("cwd", cwd.trim().ifBlank { null } ?: JSONObject.NULL)
                    onSave(body)
                }
            }
        }) { Text(if (busy) "保存中…" else "保存") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } }
    )
}
