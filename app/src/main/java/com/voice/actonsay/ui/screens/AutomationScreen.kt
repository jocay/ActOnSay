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
                        Text("${describeSchedule(schedule)} · ${schedule.optString("timezone")}", style = MaterialTheme.typography.bodySmall)
                        Text("下一次：${describeNextRun(schedule) ?: "无待执行时间"}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
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
