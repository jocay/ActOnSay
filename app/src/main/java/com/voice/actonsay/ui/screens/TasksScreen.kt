package com.voice.actonsay.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.voice.actonsay.tasks.TaskRecord
import com.voice.actonsay.browser.AgentBrowser
import com.voice.actonsay.tasks.TaskRuntime
import com.voice.actonsay.bridge.BridgeClient
import com.voice.actonsay.ui.components.BridgeMessage
import com.voice.actonsay.ui.components.BridgeTextField
import com.voice.actonsay.ui.components.JsonResult
import com.voice.actonsay.ui.components.MarkdownText
import com.voice.actonsay.ui.components.bridgeRequest
import com.voice.actonsay.ui.components.encodeBridgePath
import com.voice.actonsay.ui.components.objectList
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.net.URLDecoder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen() {
    val tasks by TaskRuntime.tasks.collectAsState()
    val error by TaskRuntime.serviceError.collectAsState()
    var filter by rememberSaveable { mutableStateOf("work") }
    val visible = remember(tasks, filter) {
        tasks.filter { task -> when (filter) {
            "active" -> task.state in setOf("QUEUED", "RUNNING", "WAITING_APP", "WAITING_CONFIRMATION", "RESULT_UNKNOWN")
            "automation" -> task.source in setOf("automation", "script")
            "all" -> true
            else -> task.visible
        } }.sortedByDescending { it.createdAt }
    }
    var browserOpen by remember { mutableStateOf(false) }
    if (browserOpen) BrowserDialog(onDismiss = { browserOpen = false })
    Scaffold(topBar = { TopAppBar(title = { Text("任务") }, actions = { TextButton(onClick = { browserOpen = true }) { Text("AI 浏览器") } }) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Text("后台任务独立运行，继续对话不会取消它们。普通问答不算任务，可在“含对话”里查看。", style = MaterialTheme.typography.bodySmall) }
            if (error.isNotBlank()) item { BridgeMessage(error, error = true) }
            item { Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("work" to "任务", "active" to "待处理", "automation" to "自动化", "all" to "含对话").forEach { (value, label) ->
                    FilterChip(selected = filter == value, onClick = { filter = value }, label = { Text(label) })
                }
            } }
            if (visible.isEmpty()) item { Text(if (tasks.isEmpty()) "暂无任务。在对话中提交目标，或在自动化页创建计划。" else "当前筛选下没有任务。") }
            items(visible, key = { it.id }) { TaskCard(it) }
        }
    }
}

fun taskStateLabel(state: String): String = when (state) {
    "QUEUED" -> "排队中"
    "RUNNING" -> "执行中"
    "WAITING_APP" -> "等待应用"
    "WAITING_CONFIRMATION" -> "等待确认"
    "SUCCEEDED" -> "已完成"
    "FAILED" -> "失败"
    "CANCELLED" -> "已取消"
    "RESULT_UNKNOWN" -> "结果待确认"
    else -> state
}

fun taskSourceLabel(source: String): String = when (source) {
    "chat" -> "交流"
    "turn" -> "对话"
    "background" -> "后台任务"
    "manual" -> "对话任务"
    "keyword" -> "关键词"
    "automation" -> "定时 AI"
    "script" -> "固定脚本"
    "rerun" -> "重跑"
    else -> source
}

@Composable
fun TaskCard(task: TaskRecord, compact: Boolean = false) {
    var expanded by remember(task.id) { mutableStateOf(false) }
    var confirmRerun by remember(task.id) { mutableStateOf(false) }
    var resolveDialog by remember(task.id) { mutableStateOf(false) }
    val date = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    val canCancel = task.state in setOf("QUEUED", "RUNNING", "WAITING_APP", "WAITING_CONFIRMATION")
    if (confirmRerun) {
        AlertDialog(
            onDismissRequest = { confirmRerun = false },
            title = { Text("重新执行这个目标？") },
            text = { Text("将创建一个新任务。之前的工具操作可能已经生效，重新执行可能再次产生副作用。\n\n${task.goal}") },
            confirmButton = { TextButton(onClick = { TaskRuntime.rerun(task.id); confirmRerun = false }) { Text("创建新任务") } },
            dismissButton = { TextButton(onClick = { confirmRerun = false }) { Text("取消") } }
        )
    }
    if (resolveDialog) ResolveUnknownDialog(task, onDismiss = { resolveDialog = false })
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(taskStateLabel(task.state), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                Text(taskSourceLabel(task.source), style = MaterialTheme.typography.labelSmall)
                Text(date.format(Date(task.createdAt)), style = MaterialTheme.typography.labelSmall)
            }
            Text(task.goal, style = MaterialTheme.typography.titleSmall, maxLines = if (compact) 3 else Int.MAX_VALUE)
            if (task.progress.isNotBlank()) Text(task.progress, style = MaterialTheme.typography.bodySmall)
            if (task.error.isNotBlank()) Text(task.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (task.answer.isNotBlank() && !compact) SelectionContainer { MarkdownText(task.answer, style = MaterialTheme.typography.bodyMedium) }
            Row {
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起详情" else "日志与产物") }
                if (canCancel) TextButton(onClick = { TaskRuntime.cancel(task.id) }) { Text("取消任务") }
            }
            if (!compact && task.source != "script" && task.state in setOf("WAITING_APP", "FAILED", "CANCELLED", "RESULT_UNKNOWN")) {
                FilledTonalButton(onClick = { TaskRuntime.resume(task.id) }) { Text(if (task.state == "RESULT_UNKNOWN") "查询原调用并恢复" else "从检查点恢复") }
            }
            if (!compact && !canCancel) TextButton(onClick = { confirmRerun = true }) { Text("显式重跑") }
            if (task.state == "RESULT_UNKNOWN") {
                Text("已有操作的结果无法确认。请核实设备或执行环境；应用不会自动重放该操作。", style = MaterialTheme.typography.bodySmall)
                if (!compact) TextButton(onClick = { resolveDialog = true }) { Text("人工核实结果") }
            }
            // 等待授权时直接展开，用户不必再点一次才看到“允许本次”。
            if (expanded || task.state == "WAITING_CONFIRMATION") TaskDetail(task.id)
        }
    }
}

@Composable
private fun ResolveUnknownDialog(task: TaskRecord, onDismiss: () -> Unit) {
    val callsFlow = remember(task.id) { TaskRuntime.toolCalls(task.id) }
    val calls by callsFlow.collectAsState(initial = emptyList())
    val unknownCalls = calls.filter { it.state.equals("RESULT_UNKNOWN", ignoreCase = true) }
    var status by remember(task.id) { mutableStateOf("") }
    var note by remember(task.id) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("人工核实操作结果") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { Text("请先在手机或 Debian 中核实以下操作。提交后会保留人工核实记录，并解除该操作的未知状态。应用不会重放这次工具调用。", style = MaterialTheme.typography.bodySmall) }
                item { Text(task.goal, style = MaterialTheme.typography.titleSmall) }
                unknownCalls.forEach { call ->
                    item(key = call.id) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(call.name, style = MaterialTheme.typography.labelLarge)
                            JsonResult(call.arguments)
                            if (call.executionId.isNotBlank()) Text("执行 ID：${call.executionId}", style = MaterialTheme.typography.labelSmall)
                            if (call.result.isNotBlank()) JsonResult(call.result.take(6000))
                        }
                    }
                }
                if (unknownCalls.isEmpty()) item { Text(task.error.ifBlank { "请根据任务日志核实最后一次设备操作。" }, style = MaterialTheme.typography.bodySmall) }
                item { Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("succeeded" to "已成功", "failed" to "已失败", "cancelled" to "已取消").forEach { (value, label) ->
                        FilterChip(selected = status == value, onClick = { status = value }, label = { Text(label) })
                    }
                } }
                item { BridgeTextField(note, { note = it.take(4096) }, "核实说明（必填，例如核对的文件、设备状态）") }
                item { Text("只有你实际核实过的结果才应在这里提交。后续恢复使用这份人工结论；若关联定时计划，调度将依据新的执行状态继续。", style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(enabled = note.isNotBlank() && status in setOf("succeeded", "failed", "cancelled"), onClick = {
            TaskRuntime.resolveUnknown(task.id, status, note.trim())
            onDismiss()
        }) { Text("提交核实结果") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("暂不处理") } }
    )
}

@Composable
private fun TaskDetail(taskId: String) {
    val eventsFlow = remember(taskId) { TaskRuntime.events(taskId) }
    val callsFlow = remember(taskId) { TaskRuntime.toolCalls(taskId) }
    val events by eventsFlow.collectAsState(initial = emptyList())
    val calls by callsFlow.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var output by remember(taskId) { mutableStateOf("") }
    var loadingLog by remember(taskId) { mutableStateOf(false) }
    var selectedArtifact by remember(taskId) { mutableStateOf<JSONObject?>(null) }
    var previewImage by remember(taskId) { mutableStateOf<ImageBitmap?>(null) }
    var logOffsets by remember(taskId) { mutableStateOf(emptyMap<String, Long>()) }
    val saveArtifact = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val artifact = selectedArtifact
        if (uri != null && artifact != null) scope.launch {
            loadingLog = true
            output = runCatching {
                withContext(Dispatchers.IO) {
                    val temporary = File(context.cacheDir, "artifact-${UUID.randomUUID()}")
                    try {
                        BridgeClient.download(artifact.getString("reference"), temporary)
                        val destination = context.contentResolver.openOutputStream(uri) ?: error("无法写入所选文件")
                        destination.use { target -> temporary.inputStream().use { it.copyTo(target) } }
                    } finally { temporary.delete() }
                }
                "已保存：${artifact.optString("name") }"
            }.getOrElse { "保存失败：${it.message}" }
            loadingLog = false
        }
    }
    Text("任务 ID：$taskId", style = MaterialTheme.typography.labelSmall)
    events.takeLast(60).forEach { event ->
        Text("${event.type} · ${event.content}", style = MaterialTheme.typography.bodySmall)
    }
    if (events.size > 60) Text("仅展示最近 60 条事件", style = MaterialTheme.typography.labelSmall)
    calls.forEach { call ->
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${call.name} · ${call.state}", style = MaterialTheme.typography.labelLarge)
                JsonResult(call.arguments)
                if (call.state == "WAITING_CONFIRMATION") {
                    Text("授权仅适用于以上工具及参数。", style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { TaskRuntime.grantAuthorization(taskId, call.id, true) }) { Text("允许本次") }
                        TextButton(onClick = { TaskRuntime.grantAuthorization(taskId, call.id, false) }) { Text("拒绝") }
                    }
                }
                if (call.result.isNotBlank()) JsonResult(call.result.take(24000))
                val result = remember(call.result) { runCatching { JSONObject(call.result) }.getOrNull() }
                result?.objectList("artifacts")?.forEach { artifact ->
                    val path = artifact.optString("path", artifact.optString("reference", artifact.optString("uri")))
                    Text("文件：${artifact.optString("name", path)}", style = MaterialTheme.typography.bodyMedium)
                    if (path.isNotBlank()) TextButton(onClick = {
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("产物路径", path))
                    }) { Text("复制文件路径") }
                    val reference = artifact.optString("reference")
                    // 虚拟屏截图保存在应用私有目录，直接从本地文件预览。
                    val localImage = artifact.optString("local_path").takeIf { artifact.optString("mime_type").startsWith("image/") && it.isNotBlank() }
                    if (localImage != null) TextButton(onClick = { scope.launch {
                        runCatching { withContext(Dispatchers.IO) {
                            val file = File(localImage).canonicalFile
                            require(file.path.startsWith(context.filesDir.canonicalPath + File.separator)) { "截图不在应用目录内" }
                            (BitmapFactory.decodeFile(file.path) ?: error("截图已被清理")).asImageBitmap()
                        } }.onSuccess { previewImage = it }.onFailure { output = "截图预览失败：${it.message}" }
                    } }) { Text("查看这一步的截图") }
                    if (reference.isNotBlank()) TextButton(enabled = !loadingLog, onClick = {
                        selectedArtifact = artifact
                        saveArtifact.launch(artifact.optString("name", "artifact").substringAfterLast('/'))
                    }) { Text("保存文件到手机") }
                    if (reference.isNotBlank() && artifact.optString("mime_type").startsWith("image/")) {
                        TextButton(enabled = !loadingLog, onClick = { scope.launch {
                            loadingLog = true
                            runCatching { withContext(Dispatchers.IO) {
                                val temporary = File(context.cacheDir, "image-${UUID.randomUUID()}")
                                try {
                                    BridgeClient.download(reference, temporary)
                                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                    BitmapFactory.decodeFile(temporary.path, bounds)
                                    var sample = 1
                                    while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
                                    val bitmap = BitmapFactory.decodeFile(temporary.path, BitmapFactory.Options().apply { inSampleSize = sample })
                                        ?: error("无法读取此图片")
                                    bitmap.asImageBitmap()
                                } finally { temporary.delete() }
                            } }.onSuccess { previewImage = it }.onFailure { output = "图片预览失败：${it.message}" }
                            loadingLog = false
                        } }) { Text("预览图片") }
                    }
                    if (reference.startsWith("/v1/executions/") && (artifact.optString("mime_type").startsWith("text/") || artifact.optString("name").endsWith(".md"))) {
                        TextButton(enabled = !loadingLog, onClick = { scope.launch {
                            loadingLog = true
                            output = runCatching {
                                val execution = reference.substringAfter("/v1/executions/").substringBefore('/')
                                val relative = URLDecoder.decode(reference.substringAfter("/artifacts/"), "UTF-8")
                                bridgeRequest("GET", "/v1/executions/${encodeBridgePath(execution)}/artifact-text?path=${encodeBridgePath(relative)}").toString(2)
                            }.getOrElse { "读取产物失败：${it.message}" }
                            loadingLog = false
                        } }) { Text("预览文本产物") }
                    }
                }
                val executionId = call.executionId
                val containerExecution = executionId.matches(Regex("[A-Za-z0-9_-]{1,128}"))
                if (containerExecution) TextButton(enabled = !loadingLog, onClick = {
                    scope.launch {
                        loadingLog = true
                        output = runCatching {
                            val log = bridgeRequest("GET", "/v1/executions/${encodeBridgePath(executionId)}/log?offset=${logOffsets[executionId] ?: 0}")
                            logOffsets = logOffsets + (executionId to log.optLong("next_offset"))
                            log.toString(2)
                        }
                            .getOrElse { "读取日志失败：${it.message}" }
                        loadingLog = false
                    }
                }) { Text(if ((logOffsets[executionId] ?: 0) > 0) "读取下一段容器日志" else "读取容器日志") }
                if (containerExecution && (logOffsets[executionId] ?: 0) > 0) TextButton(onClick = { logOffsets = logOffsets - executionId }) { Text("从头读取日志") }
            }
        }
    }
    if (output.isNotBlank()) JsonResult(output)
    previewImage?.let { image ->
        Image(image, contentDescription = "任务图片产物", modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp), contentScale = ContentScale.Fit)
        TextButton(onClick = { previewImage = null }) { Text("关闭图片") }
    }
}

/** 查看 AI 浏览器当前的页面；关闭后浏览器仍在后台供 AI 使用。 */
@Composable
private fun BrowserDialog(onDismiss: () -> Unit) {
    val title by AgentBrowser.title.collectAsState()
    val url by AgentBrowser.url.collectAsState()
    androidx.compose.ui.window.Dialog(
        onDismissRequest = { AgentBrowser.detachFromParent(); onDismiss() },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(title.ifBlank { "AI 浏览器" }, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                        Text(url.ifBlank { "AI 还没有打开网页" }, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                    TextButton(onClick = { AgentBrowser.detachFromParent(); onDismiss() }) { Text("关闭") }
                }
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { context -> AgentBrowser.view(context).also { AgentBrowser.detachFromParent() } },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
