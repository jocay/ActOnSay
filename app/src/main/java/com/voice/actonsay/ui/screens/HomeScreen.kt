package com.voice.actonsay.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.voice.actonsay.asr.AsrManager
import com.voice.actonsay.asr.AsrService
import com.voice.actonsay.asr.AsrState
import com.voice.actonsay.asr.ChatMessage
import com.voice.actonsay.asr.EngineState
import com.voice.actonsay.tasks.ConversationRecord
import com.voice.actonsay.tasks.CredentialRedactor
import com.voice.actonsay.tasks.MessageSearchHit
import com.voice.actonsay.tasks.TaskRecord
import com.voice.actonsay.tasks.TaskRuntime
import kotlinx.coroutines.delay
import com.voice.actonsay.ui.components.ChatBubble
import com.voice.actonsay.ui.components.LevelBars
import com.voice.actonsay.ui.components.VoiceOrb
import com.voice.actonsay.ui.components.iconForState
import com.voice.actonsay.ui.icons.AppIcons
import com.voice.actonsay.ui.theme.engineStateColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val SUGGESTIONS = listOf(
    "今天天气怎么样",
    "帮我写一首关于春天的短诗",
    "用一句话介绍量子计算",
    "给我讲个冷笑话",
    "明天早上七点提醒我开会"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenAiSettings: () -> Unit,
    onOpenAsrSettings: () -> Unit,
    onOpenTasks: () -> Unit = {}
) {
    val context = LocalContext.current
    val asrState by AsrManager.state.collectAsState()
    val audioLevel by AsrManager.audioLevel.collectAsState()
    val conversations by AsrManager.conversations.collectAsState()
    val tasks by TaskRuntime.tasks.collectAsState()
    val tasksById = remember(tasks) { tasks.associateBy { it.id } }
    val pendingTurns = remember(tasks, asrState.conversationId) { AsrManager.pendingTurns(tasks) }
    val conversationTitle = conversations.firstOrNull { it.id == asrState.conversationId }?.title ?: "新对话"

    var manualInputText by rememberSaveable { mutableStateOf("") }
    var showClearDialog by remember { mutableStateOf(false) }
    var showConversations by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scrollScope = rememberCoroutineScope()
    var followLatest by remember { mutableStateOf(true) }
    var autoScrolling by remember { mutableStateOf(false) }
    var hasNewContent by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }.collect { (scrolling, canForward) ->
            if (scrolling && !autoScrolling) followLatest = !canForward
            if (!canForward) { followLatest = true; hasNewContent = false }
        }
    }

    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }
    var pendingStart by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasAudioPermission = permissions[Manifest.permission.RECORD_AUDIO] == true
        if (hasAudioPermission && pendingStart) {
            AsrService.start(context)
        } else if (!hasAudioPermission) {
            Toast.makeText(context, "未授予麦克风权限，无法启动语音待机", Toast.LENGTH_SHORT).show()
        }
        pendingStart = false
    }

    fun toggleStandby() {
        if (asrState.isListening) {
            AsrService.stop(context)
        } else if (!hasAudioPermission) {
            pendingStart = true
            val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            permissionLauncher.launch(perms.toTypedArray())
        } else {
            AsrService.start(context)
        }
    }

    // 自动滚动到底部（新消息或流式内容增长时）
    val lastMessage = asrState.messages.lastOrNull()
    LaunchedEffect(asrState.messages.size, lastMessage?.id, lastMessage?.content) {
        if (asrState.messages.isNotEmpty() && followLatest) {
            autoScrolling = true
            try { listState.scrollToItem(asrState.messages.size + 1) }
            finally { autoScrolling = false }
        } else if (asrState.messages.isNotEmpty()) {
            hasNewContent = true
        }
    }

    fun sendManual() {
        if (manualInputText.isNotBlank()) {
            followLatest = true
            AsrManager.sendManualMessage(manualInputText.trim())
            manualInputText = ""
        }
    }

    fun copy(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("actonsay", text))
        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
    }

    fun share(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, CredentialRedactor.text(text))
        }
        context.startActivity(Intent.createChooser(intent, "分享"))
    }

    fun exportConversation() {
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val text = asrState.messages.filterNot { it.isLoading }.joinToString("\n\n") { m ->
            val who = if (m.role == "user") "我" else "助手"
            "[$who · ${df.format(Date(m.timestamp))}]\n${m.content}"
        }
        share(CredentialRedactor.text(text))
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            icon = { Icon(Icons.Default.Delete, null) },
            title = { Text("清空当前对话？") },
            text = { Text("将删除「$conversationTitle」中的全部消息，其他会话不受影响，此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = { AsrManager.clearMessages(); showClearDialog = false }) {
                    Text("清空", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showClearDialog = false }) { Text("取消") } }
        )
    }

    if (showConversations) ConversationsDialog(
        current = asrState.conversationId,
        conversations = conversations,
        onDismiss = { showConversations = false },
        onOpen = { id -> AsrManager.switchConversation(id); showConversations = false },
        onNew = { AsrManager.newConversation(); showConversations = false },
        onDelete = { AsrManager.deleteConversation(it) }
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(Modifier.clip(RoundedCornerShape(8.dp)).clickable { showConversations = true }) {
                        Text(conversationTitle, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            text = standbySubtitle(asrState),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    StandbyToggle(listening = asrState.isListening, onToggle = ::toggleStandby)
                    Spacer(Modifier.width(4.dp))
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("新建对话") },
                                leadingIcon = { Icon(AppIcons.Chat, null) },
                                enabled = asrState.messages.isNotEmpty(),
                                onClick = { menuOpen = false; AsrManager.newConversation() }
                            )
                            DropdownMenuItem(
                                text = { Text("会话列表与搜索") },
                                leadingIcon = { Icon(AppIcons.History, null) },
                                onClick = { menuOpen = false; showConversations = true }
                            )
                            DropdownMenuItem(
                                text = { Text("设置") },
                                leadingIcon = { Icon(Icons.Default.Settings, null) },
                                onClick = { menuOpen = false; onOpenSettings() }
                            )
                            DropdownMenuItem(
                                text = { Text("导出对话") },
                                leadingIcon = { Icon(Icons.Default.Share, null) },
                                enabled = asrState.messages.isNotEmpty(),
                                onClick = { menuOpen = false; exportConversation() }
                            )
                            DropdownMenuItem(
                                text = { Text("清空当前对话") },
                                leadingIcon = { Icon(Icons.Default.Delete, null) },
                                enabled = asrState.messages.isNotEmpty(),
                                onClick = { menuOpen = false; showClearDialog = true }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            ComposerBar(
                state = asrState,
                audioLevel = audioLevel,
                text = manualInputText,
                onTextChange = { manualInputText = it },
                onSend = ::sendManual,
                onMicTap = {
                    if (!asrState.isListening) toggleStandby() else AsrManager.onMicTapped()
                },
                onCancelAi = { AsrManager.cancelAi() }
            )
        },
        containerColor = MaterialTheme.colorScheme.surface
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            StatusBanner(
                state = asrState,
                audioLevel = audioLevel,
                taskActive = pendingTurns.any { it.state == "RUNNING" },
                onStopSpeaking = { AsrManager.stopTts() },
                onStopTask = { AsrManager.stopCurrentTask() },
                onCancelAi = { AsrManager.cancelAi() }
            )

            // 有排队的请求时显示队列，每一项都能单独取消。
            AnimatedVisibility(
                visible = pendingTurns.size > 1 || pendingTurns.any { it.state == "QUEUED" },
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                QueueStrip(pendingTurns)
            }

            AnimatedVisibility(
                visible = asrState.errorMessage.isNotEmpty(),
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                ErrorBanner(
                    message = asrState.errorMessage,
                    onDismiss = { AsrManager.clearError() },
                    onAction = when {
                        asrState.errorMessage.contains("火山引擎") -> onOpenAsrSettings
                        asrState.errorMessage.contains("AI") -> onOpenAiSettings
                        else -> null
                    }
                )
            }

            if (asrState.messages.isEmpty()) {
                EmptyConversation(
                    state = asrState,
                    audioLevel = audioLevel,
                    onSuggestion = { AsrManager.sendManualMessage(it) },
                    onStart = ::toggleStandby,
                    onOpenAiSettings = onOpenAiSettings,
                    onOpenAsrSettings = onOpenAsrSettings,
                    modifier = Modifier.weight(1f)
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item(key = "header") {
                        Text(
                            text = "共 ${asrState.messages.count { it.role == "user" }} 轮对话 · 长按消息可复制、分享或删除",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                    itemsIndexed(asrState.messages, key = { _, it -> it.id }) { idx, msg ->
                        val prevUser = if (msg.role == "assistant") {
                            asrState.messages.getOrNull(idx - 1)?.takeIf { it.role == "user" }
                        } else null
                        val task = if (msg.role == "assistant" && msg.taskId.isNotBlank()) tasksById[msg.taskId] else null
                        ChatBubble(
                            message = msg,
                            task = task,
                            onReplayTts = { AsrManager.replayMessage(msg.content) },
                            onCopyText = { copy(msg.content) },
                            onShare = { share(msg.content) },
                            onDelete = { AsrManager.deleteMessage(msg.id) },
                            onRetry = prevUser?.let { u -> { AsrManager.retryMessage(u.id) } },
                            onOpenTasks = onOpenTasks
                        )
                    }
                    if (asrState.engineState == EngineState.ACTIVE_LISTENING && asrState.currentText.isNotBlank()) {
                        item(key = "live") {
                            LiveTranscriptBubble(asrState.currentText)
                        }
                    }
                    item(key = "bottom-space") { Spacer(Modifier.height(4.dp)) }
                }
                if (hasNewContent) {
                    TextButton(onClick = {
                        followLatest = true
                        hasNewContent = false
                        scrollScope.launch {
                            autoScrolling = true
                            try { listState.animateScrollToItem(asrState.messages.size + 1) }
                            finally { autoScrolling = false }
                        }
                    }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("有新内容 · 回到最新消息") }
                }
            }
        }
    }
}

private fun standbySubtitle(state: AsrState): String = when (state.engineState) {
    EngineState.IDLE -> "语音待机已关闭"
    EngineState.WAITING_WAKEWORD -> if (!state.voiceReady) "语音模型准备中，可先文字输入" else {
        val words = state.customWakeWords.joinToString("、")
        if (words.isBlank()) "待机中" else "待机中 · 呼叫「$words」"
    }
    EngineState.ACTIVE_LISTENING -> "正在倾听…"
    EngineState.PROCESSING_FINAL -> "正在整理识别结果…"
    EngineState.THINKING_AI -> "AI 正在思考…"
    EngineState.SPEAKING_TTS -> "正在播报回复"
}

@Composable
private fun StandbyToggle(listening: Boolean, onToggle: () -> Unit) {
    val container by animateColorAsState(
        if (listening) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        label = "standbyBg"
    )
    val content by animateColorAsState(
        if (listening) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "standbyFg"
    )
    Surface(onClick = onToggle, shape = CircleShape, color = container) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 10.dp, end = 14.dp, top = 6.dp, bottom = 6.dp)
        ) {
            Icon(AppIcons.Power, null, tint = content, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                if (listening) "待机中" else "已关闭",
                style = MaterialTheme.typography.labelLarge,
                color = content
            )
        }
    }
}

@Composable
private fun StatusBanner(
    state: AsrState,
    audioLevel: Float,
    taskActive: Boolean,
    onStopSpeaking: () -> Unit,
    onStopTask: () -> Unit,
    onCancelAi: () -> Unit
) {
    val accent = engineStateColor(state.engineState)
    val bg by animateColorAsState(accent.copy(alpha = if (state.engineState == EngineState.IDLE) 0.06f else 0.12f), label = "bannerBg")
    val interruptWord = state.interruptWords.firstOrNull()

    val (title, detail) = when (state.engineState) {
        EngineState.IDLE -> "待机已关闭" to "点击右上角或底部麦克风开启语音待机"
        EngineState.WAITING_WAKEWORD -> if (!state.voiceReady) "语音模型准备中" to "唤醒词与断句模型正在加载，文字对话可直接使用"
            else if (taskActive) "AI 正在处理" to (if (interruptWord != null) "说「$interruptWord」可让我停下来听你说，任务继续执行" else "可继续说话或输入，新消息会排队")
            else "正在等待唤醒" to "说出唤醒词，或点击底部麦克风直接说话"
        EngineState.ACTIVE_LISTENING -> "我在听，请说" to (if (state.lastWakeWord.isNotBlank()) "由「${state.lastWakeWord}」唤醒 · 停顿后自动结束" else "停顿后自动结束")
        EngineState.PROCESSING_FINAL -> "正在识别" to "等待云端返回最终文本"
        EngineState.THINKING_AI -> (if (state.agentActivity.isNotBlank()) "正在执行任务" else "AI 正在思考") to state.agentActivity.ifBlank { "模型 ${state.aiModel}" }
        EngineState.SPEAKING_TTS -> "正在播报" to (if (interruptWord != null) "说「$interruptWord」停止朗读并继续听你说" else "可点“停止朗读”")
    }

    Surface(
        color = bg,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(40.dp).clip(CircleShape).background(accent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    targetState = state.engineState,
                    transitionSpec = { (fadeIn(tween(200)) + slideInVertically { it / 3 }) togetherWith (fadeOut(tween(150)) + slideOutVertically { -it / 3 }) },
                    label = "stateIcon"
                ) { s ->
                    Icon(iconForState(s), null, tint = accent, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = accent, fontWeight = FontWeight.SemiBold)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            when (state.engineState) {
                EngineState.ACTIVE_LISTENING -> LevelBars(
                    level = audioLevel, active = true, color = accent,
                    modifier = Modifier.size(width = 36.dp, height = 22.dp)
                )
                // “停止朗读”只让声音停下，任务继续；“停止任务”取消正在执行的回合。
                EngineState.SPEAKING_TTS -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (taskActive) TextButton(
                        onClick = onStopTask,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) { Text("停止任务") }
                    FilledTonalButton(
                        onClick = onStopSpeaking,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Icon(AppIcons.Stop, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("停止朗读")
                    }
                }
                EngineState.WAITING_WAKEWORD -> if (taskActive) TextButton(
                    onClick = onStopTask,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) { Text("停止任务") }
                EngineState.THINKING_AI -> TextButton(
                    onClick = onCancelAi,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) { Text("取消") }
                else -> Unit
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit, onAction: (() -> Unit)?) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            if (onAction != null) {
                TextButton(onClick = onAction, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("去设置", color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.SemiBold)
                }
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Close, "关闭", tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun LiveTranscriptBubble(text: String) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Text(
            "正在识别…",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
        )
        Surface(
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyConversation(
    state: AsrState,
    audioLevel: Float,
    onSuggestion: (String) -> Unit,
    onStart: () -> Unit,
    onOpenAiSettings: () -> Unit,
    onOpenAsrSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        VoiceOrb(state = state.engineState, audioLevel = audioLevel, size = 180.dp)
        Spacer(Modifier.height(24.dp))
        Text(
            text = when (state.engineState) {
                EngineState.IDLE -> "你好，我是你的语音助手"
                EngineState.WAITING_WAKEWORD -> "随时呼叫我"
                EngineState.ACTIVE_LISTENING -> "我在听…"
                else -> "稍等一下…"
            },
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = when {
                state.engineState == EngineState.IDLE -> "开启待机后，说出「${state.customWakeWords.firstOrNull() ?: "唤醒词"}」即可对话，回复会自动语音播报。"
                state.engineState == EngineState.WAITING_WAKEWORD -> "说「${state.customWakeWords.firstOrNull() ?: "唤醒词"}」开始，或点击下方麦克风直接说话。"
                else -> "识别结果会显示在这里。"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))

        // 配置检查清单
        if (!state.asrReady || !state.aiReady) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(vertical = 6.dp)) {
                    SetupItem(
                        done = state.asrReady,
                        title = "配置火山引擎语音识别",
                        subtitle = if (state.asrReady) "已就绪" else "唤醒后进行云端识别需要 API Key",
                        onClick = onOpenAsrSettings
                    )
                    SetupItem(
                        done = state.aiReady,
                        title = "配置 AI 模型",
                        subtitle = if (state.aiReady) "已就绪 · ${state.aiModel}" else if (!state.aiEnabled) "已关闭 AI 回复" else "填写 Base URL 与 API Key",
                        onClick = onOpenAiSettings
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        if (state.engineState == EngineState.IDLE) {
            FilledTonalButton(onClick = onStart) {
                Icon(AppIcons.Power, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("开启语音待机")
            }
            Spacer(Modifier.height(16.dp))
        }

        Text(
            "试试直接输入",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SUGGESTIONS.forEach { s ->
                AssistChip(
                    onClick = { onSuggestion(s) },
                    label = { Text(s) },
                    shape = CircleShape,
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    border = null
                )
            }
        }
    }
}

@Composable
private fun SetupItem(done: Boolean, title: String, subtitle: String, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Color.Transparent) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(if (done) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center
            ) {
                if (done) {
                    Icon(
                        Icons.Default.Check, null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(16.dp)
                    )
                } else {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outline))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 底部输入区：文本框 + 发送 + 大麦克风按钮。 */
@Composable
private fun ComposerBar(
    state: AsrState,
    audioLevel: Float,
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onMicTap: () -> Unit,
    onCancelAi: () -> Unit
) {
    val accent = engineStateColor(state.engineState)
    val micEnabled = state.engineState != EngineState.PROCESSING_FINAL && state.engineState != EngineState.THINKING_AI
    val micColor by animateColorAsState(
        if (state.engineState == EngineState.IDLE) MaterialTheme.colorScheme.primary else accent,
        label = "micColor"
    )

    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(top = 6.dp, bottom = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("输入文字，直接问 AI…", style = MaterialTheme.typography.bodyMedium) },
                    singleLine = true,
                    shape = CircleShape,
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = Color.Transparent,
                        focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSend() }),
                    trailingIcon = {
                        AnimatedVisibility(visible = text.isNotBlank(), enter = fadeIn(), exit = fadeOut()) {
                            FilledIconButton(
                                onClick = onSend,
                                modifier = Modifier.size(36.dp),
                                colors = IconButtonDefaults.filledIconButtonColors()
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Send, "发送", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                )
                Spacer(Modifier.width(10.dp))
                Box(contentAlignment = Alignment.Center) {
                    if (state.engineState == EngineState.ACTIVE_LISTENING) {
                        VoiceOrb(
                            state = state.engineState,
                            audioLevel = audioLevel,
                            size = 76.dp,
                            modifier = Modifier.clip(CircleShape)
                        )
                        Surface(
                            onClick = onMicTap,
                            shape = CircleShape,
                            color = Color.Transparent,
                            modifier = Modifier.size(76.dp)
                        ) {}
                    } else {
                        FilledTonalIconButton(
                            onClick = if (state.engineState == EngineState.THINKING_AI) onCancelAi else onMicTap,
                            enabled = micEnabled || state.engineState == EngineState.THINKING_AI,
                            modifier = Modifier.size(56.dp),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = micColor,
                                contentColor = Color.White,
                                disabledContainerColor = micColor.copy(alpha = 0.5f),
                                disabledContentColor = Color.White.copy(alpha = 0.7f)
                            )
                        ) {
                            Icon(
                                imageVector = when (state.engineState) {
                                    EngineState.IDLE -> AppIcons.Power
                                    EngineState.SPEAKING_TTS -> AppIcons.Stop
                                    EngineState.THINKING_AI -> Icons.Default.Close
                                    else -> AppIcons.Mic
                                },
                                contentDescription = "麦克风",
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }
                }
            }
            Text(
                text = when (state.engineState) {
                    EngineState.IDLE -> "点击右侧按钮开启语音待机"
                    EngineState.WAITING_WAKEWORD -> "点击麦克风可直接说话，无需唤醒词"
                    EngineState.ACTIVE_LISTENING -> "再次点击可立即结束本句"
                    EngineState.PROCESSING_FINAL -> "正在等待识别结果…"
                    EngineState.THINKING_AI -> "点击 × 可取消本次回答"
                    EngineState.SPEAKING_TTS -> "点击可打断播报"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 6.dp, start = 4.dp)
            )
        }
    }
}

/** 当前会话中正在执行和排队的请求，每项可单独停止或取消。 */
@Composable
private fun QueueStrip(turns: List<TaskRecord>) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text("请求队列", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            turns.forEach { task ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when (task.state) { "RUNNING" -> "执行中"; "WAITING_CONFIRMATION" -> "待确认"; else -> "排队" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(44.dp)
                    )
                    Text(
                        task.goal.lineSequence().firstOrNull().orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = { TaskRuntime.cancel(task.id) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) { Text(if (task.state == "QUEUED") "取消" else "停止") }
                }
            }
        }
    }
}

/** 会话列表：切换、新建、删除，以及按内容搜索全部会话。 */
@Composable
private fun ConversationsDialog(
    current: String,
    conversations: List<ConversationRecord>,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    onDelete: (String) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var hits by remember { mutableStateOf(emptyList<MessageSearchHit>()) }
    var deleting by remember { mutableStateOf<ConversationRecord?>(null) }
    val format = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    LaunchedEffect(query) {
        if (query.isBlank()) { hits = emptyList(); return@LaunchedEffect }
        delay(300)
        hits = AsrManager.searchMessages(query)
    }
    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除这个会话？") },
            text = { Text("「${target.title}」的消息将被删除，相关任务记录仍保留在任务页。") },
            confirmButton = { TextButton(onClick = { onDelete(target.id); deleting = null }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("会话") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("搜索全部会话的消息") },
                    singleLine = true,
                    shape = CircleShape,
                    modifier = Modifier.fillMaxWidth()
                )
                LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (query.isNotBlank()) {
                        if (hits.isEmpty()) item { Text("没有找到相关消息", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp)) }
                        items(hits, key = { "hit-" + it.message.id }) { hit ->
                            Surface(onClick = { onOpen(hit.conversationId) }, color = Color.Transparent, shape = RoundedCornerShape(12.dp)) {
                                Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
                                    Text("${hit.conversationTitle} · ${format.format(Date(hit.message.timestamp))}",
                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                    Text(hit.message.content, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    } else {
                        items(conversations, key = { it.id }) { conversation ->
                            val selected = conversation.id == current
                            Surface(
                                onClick = { onOpen(conversation.id) },
                                color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(Modifier.fillMaxWidth().padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                                        Text(conversation.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(format.format(Date(conversation.updatedAt)), style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    IconButton(onClick = { deleting = conversation }) {
                                        Icon(Icons.Default.Delete, "删除会话", modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                        if (conversations.isEmpty()) item { Text("还没有保存的会话", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp)) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onNew) { Text("新建对话") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}
