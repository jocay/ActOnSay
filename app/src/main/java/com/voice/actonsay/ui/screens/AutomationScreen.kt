package com.voice.actonsay.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.voice.actonsay.asr.AsrManager
import com.voice.actonsay.tasks.TaskRuntime
import com.voice.actonsay.ui.components.BridgeMessage
import com.voice.actonsay.ui.components.BridgeTextField
import com.voice.actonsay.ui.components.SectionCard
import com.voice.actonsay.ui.components.SettingHint
import com.voice.actonsay.ui.components.bridgeRequest
import com.voice.actonsay.ui.components.encodeBridgePath
import com.voice.actonsay.ui.components.objectList
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.TimeZone

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationScreen() {
    val state by AsrManager.state.collectAsState()
    val keywords by AsrManager.keywordDetector.keywords.collectAsState()
    val keywordEvents by AsrManager.keywordDetector.events.collectAsState()
    val serviceError by TaskRuntime.serviceError.collectAsState()
    val scope = rememberCoroutineScope()
    var schedules by remember { mutableStateOf(emptyList<JSONObject>()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<JSONObject?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<JSONObject?>(null) }
    val paused by TaskRuntime.automationPaused.collectAsState()
    var keyword by rememberSaveable { mutableStateOf("") }
    var target by rememberSaveable { mutableStateOf("") }

    suspend fun refresh() {
        schedules = bridgeRequest("GET", "/v1/schedules").objectList("schedules")
    }
    fun perform(action: suspend () -> Unit) {
        scope.launch {
            busy = true
            error = ""
            runCatching { action() }.onFailure { error = it.message ?: "执行环境请求失败" }
            busy = false
        }
    }
    LaunchedEffect(state.bridgeBaseUrl, state.bridgeToken) {
        busy = true
        runCatching { refresh() }.onFailure { error = it.message ?: "执行环境未连接" }
        busy = false
    }
    if (editorOpen) {
        ScheduleEditor(
            schedule = editing,
            busy = busy,
            requestError = error,
            onDismiss = { if (!busy) editorOpen = false },
            onSave = { body -> perform {
                if (editing == null) bridgeRequest("POST", "/v1/schedules", body)
                else bridgeRequest("PATCH", "/v1/schedules/${encodeBridgePath(editing!!.getString("id"))}", body)
                refresh()
                editorOpen = false
            } }
        )
    }
    deleting?.let { selected ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除计划？") },
            text = { Text(selected.optString("name")) },
            confirmButton = { TextButton(enabled = !busy, onClick = { perform {
                bridgeRequest("DELETE", "/v1/schedules/${encodeBridgePath(selected.getString("id"))}")
                refresh()
                deleting = null
            } }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }
        )
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text("自动化") }, actions = {
            TextButton(enabled = !busy, onClick = { perform { refresh() } }) { Text("刷新") }
            TextButton(onClick = { editing = null; editorOpen = true }) { Text("新建") }
        })
    }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SectionCard("执行规则") {
                    SettingHint("固定脚本由 Debian 调度；AI 目标等待 Android 在线领取。同一计划不重叠，后台结果默认不朗读。手机休眠或容器停止可能延迟执行。")
                    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("暂停所有自动化", Modifier.weight(1f))
                        Switch(checked = paused, onCheckedChange = { TaskRuntime.pauseAutomation(it) })
                    }
                }
                BridgeMessage(error, error = true)
                BridgeMessage(serviceError, error = true)
            }
            if (schedules.isEmpty()) item { Text(if (busy) "正在读取计划…" else "暂无定时计划") }
            items(schedules, key = { it.optString("id") }) { schedule ->
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(schedule.optString("name"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Switch(enabled = !busy, checked = schedule.optBoolean("enabled"), onCheckedChange = { enabled -> perform {
                                bridgeRequest("PATCH", "/v1/schedules/${encodeBridgePath(schedule.getString("id"))}", JSONObject().put("enabled", enabled))
                                refresh()
                            } })
                        }
                        Text(if (schedule.optString("kind") == "ai") "AI 目标 · ${schedule.optString("goal")}" else "固定脚本 · ${schedule.optString("command")}", style = MaterialTheme.typography.bodySmall)
                        val expression = when (schedule.optString("schedule_type")) {
                            "once" -> "一次性：${schedule.optString("run_at")}"
                            "interval" -> "每 ${schedule.optLong("interval_seconds")} 秒"
                            else -> "Cron：${schedule.optString("cron")}"
                        }
                        Text("${schedule.optString("timezone")} · $expression", style = MaterialTheme.typography.bodySmall)
                        Text("下一次：${schedule.optString("next_run").takeUnless { it.isBlank() || it == "null" } ?: "无待执行时间"}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick = { editing = schedule; editorOpen = true }) { Text("编辑") }
                            TextButton(onClick = { deleting = schedule }) { Text("删除") }
                        }
                    }
                }
            }
            item {
                SectionCard("关键词绑定任务模板") {
                    SettingHint("旧关键词保留为仅记录状态。绑定后，命中关键词会提交此处的 AI 目标；工具仍受应用权限控制。")
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        BridgeTextField(keyword, { keyword = it }, "关键词", singleLine = true)
                        BridgeTextField(target, { target = it }, "任务目标（留空表示仅记录）")
                        FilledTonalButton(enabled = keyword.isNotBlank(), onClick = {
                            AsrManager.addActionKeyword(keyword.trim())
                            AsrManager.bindKeywordTemplate(keyword.trim(), target.trim())
                            keyword = ""
                            target = ""
                        }) { Text("绑定模板") }
                    }
                    keywords.sorted().forEach { word ->
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                            Text(word, style = MaterialTheme.typography.titleSmall)
                            Text(state.keywordTemplates[word].orEmpty().ifBlank { "仅记录命中" }, style = MaterialTheme.typography.bodySmall)
                            Row {
                                TextButton(onClick = { keyword = word; target = state.keywordTemplates[word].orEmpty() }) { Text("编辑绑定") }
                                TextButton(onClick = { AsrManager.bindKeywordTemplate(word, ""); AsrManager.removeActionKeyword(word) }) { Text("移除") }
                            }
                        }
                    }
                }
            }
            if (keywordEvents.isNotEmpty()) item {
                SectionCard("最近关键词命中") {
                    keywordEvents.asReversed().take(20).forEach { event ->
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                            Text(event.keyword, style = MaterialTheme.typography.titleSmall)
                            Text(event.fullText, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    SettingHint("保留旧关键词的命中记录；这里只显示最近 20 次。")
                }
            }
        }
    }
}

@Composable
private fun ScheduleEditor(schedule: JSONObject?, busy: Boolean, requestError: String, onDismiss: () -> Unit, onSave: (JSONObject) -> Unit) {
    val id = schedule?.optString("id").orEmpty()
    var name by rememberSaveable(id) { mutableStateOf(schedule?.optString("name").orEmpty()) }
    var kind by rememberSaveable(id) { mutableStateOf(schedule?.optString("kind") ?: "ai") }
    var type by rememberSaveable(id) { mutableStateOf(schedule?.optString("schedule_type") ?: "once") }
    var timezone by rememberSaveable(id) { mutableStateOf(schedule?.optString("timezone") ?: TimeZone.getDefault().id) }
    var runAt by rememberSaveable(id) { mutableStateOf(schedule?.optString("run_at").orEmpty()) }
    var interval by rememberSaveable(id) { mutableStateOf(schedule?.optLong("interval_seconds", 3600)?.toString() ?: "3600") }
    var cron by rememberSaveable(id) { mutableStateOf(schedule?.optString("cron") ?: "0 9 * * *") }
    var content by rememberSaveable(id) { mutableStateOf(schedule?.optString(if (kind == "ai") "goal" else "command").orEmpty()) }
    var cwd by rememberSaveable(id) { mutableStateOf(schedule?.optString("cwd").orEmpty()) }
    var skip by rememberSaveable(id) { mutableStateOf(schedule?.optString("misfire_policy") == "skip") }
    var localError by remember { mutableStateOf("") }
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
                item { Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("once" to "一次", "interval" to "间隔", "cron" to "Cron").forEach { (value, label) ->
                        FilterChip(selected = type == value, onClick = { type = value }, label = { Text(label) })
                    }
                } }
                item { BridgeTextField(timezone, { timezone = it }, "时区", true) }
                item { when (type) {
                    "once" -> BridgeTextField(runAt, { runAt = it }, "ISO 时间，如 2026-10-03T09:00:00+08:00", true)
                    "interval" -> BridgeTextField(interval, { interval = it }, "间隔秒数（至少 60）", true)
                    else -> BridgeTextField(cron, { cron = it }, "Cron：分 时 日 月 星期（mon–sun 或 *）", true)
                } }
                if (kind == "script") item { BridgeTextField(cwd, { cwd = it }, "工作目录（可选）", true) }
                item { Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("错过后跳过补跑", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Switch(checked = skip, onCheckedChange = { skip = it })
                } }
                item { Text("${timezone} · ${if (skip) "跳过错过的触发" else "24 小时内合并补跑最近一次"}。保存后以服务返回的下一次时间为准。", style = MaterialTheme.typography.bodySmall) }
                if (localError.isNotBlank()) item { BridgeMessage(localError, true) }
                if (requestError.isNotBlank()) item { BridgeMessage(requestError, true) }
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            when {
                name.isBlank() || content.isBlank() -> localError = "名称和执行内容不能为空"
                timezone !in TimeZone.getAvailableIDs() -> localError = "请输入有效时区，例如 Asia/Shanghai"
                type == "once" && runAt.isBlank() -> localError = "请输入一次性执行时间"
                type == "interval" && (interval.toLongOrNull() ?: 0) < 60 -> localError = "间隔需至少 60 秒"
                type == "cron" && cron.trim().split(Regex("\\s+")).size != 5 -> localError = "Cron 需要 5 个字段"
                type == "cron" && cron.trim().split(Regex("\\s+")).last().any { it.isDigit() } -> localError = "星期请使用 mon、tue、wed、thu、fri、sat、sun 或 *"
                else -> {
                    localError = ""
                    val body = JSONObject().put("name", name.trim()).put("kind", kind).put("schedule_type", type)
                        .put("timezone", timezone.trim()).put("enabled", schedule?.optBoolean("enabled") ?: true)
                        .put("misfire_policy", if (skip) "skip" else "latest").put("catchup_window_seconds", 86400)
                        .put(if (kind == "ai") "goal" else "command", content)
                    when (type) { "once" -> body.put("run_at", runAt.trim()); "interval" -> body.put("interval_seconds", interval.toLong()); else -> body.put("cron", cron.trim()) }
                    if (kind == "script") body.put("cwd", cwd.trim().ifBlank { null } ?: JSONObject.NULL)
                    onSave(body)
                }
            }
        }) { Text(if (busy) "保存中…" else "保存") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } }
    )
}
