package com.voice.actonsay.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.voice.actonsay.asr.AsrManager
import com.voice.actonsay.asr.ChatMessage
import com.voice.actonsay.ui.icons.AppIcons
import com.voice.actonsay.tasks.TaskRecord
import com.voice.actonsay.tasks.TaskRuntime
import com.voice.actonsay.ui.screens.TaskCard
import androidx.compose.material3.TextButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatBubble(
    message: ChatMessage,
    task: TaskRecord? = null,
    onReplayTts: () -> Unit,
    onCopyText: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onRetry: (() -> Unit)? = null,
    onOpenTasks: () -> Unit = {}
) {
    val isUser = message.role == "user"
    val dateFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    var menuOpen by remember { mutableStateOf(false) }

    val scheme = MaterialTheme.colorScheme
    val bubbleColor = when {
        isUser -> scheme.primary
        message.isError -> scheme.errorContainer
        else -> scheme.surfaceContainerHigh
    }
    val textColor = when {
        isUser -> scheme.onPrimary
        message.isError -> scheme.onErrorContainer
        else -> scheme.onSurface
    }
    val shape = RoundedCornerShape(
        topStart = 20.dp,
        topEnd = 20.dp,
        bottomStart = if (isUser) 20.dp else 6.dp,
        bottomEnd = if (isUser) 6.dp else 20.dp
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        // 元信息行：来源标签 + 时间
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
        ) {
            if (!isUser) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(scheme.secondaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(AppIcons.Robot, null, tint = scheme.onSecondaryContainer, modifier = Modifier.size(12.dp))
                }
                Spacer(Modifier.width(6.dp))
            }
            if (isUser && message.wakeWord.isNotEmpty()) {
                SourceTag(
                    text = message.wakeWord,
                    icon = when (message.wakeWord) {
                        AsrManager.MANUAL_INPUT_WORD -> AppIcons.Keyboard
                        AsrManager.MANUAL_WAKE_WORD -> AppIcons.Mic
                        else -> AppIcons.Hearing
                    }
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = dateFormat.format(Date(message.timestamp)),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.outline
            )
        }

        Box {
            Surface(
                shape = shape,
                color = bubbleColor,
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .clip(shape)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { menuOpen = true }
                    )
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    if (message.isLoading && message.content.isBlank()) {
                        TypingDots(color = scheme.onSurfaceVariant)
                    } else {
                        // 助手回复按 Markdown 渲染；用户消息和错误提示保持原文。
                        if (isUser || message.isError) {
                            Text(
                                text = message.content,
                                style = MaterialTheme.typography.bodyLarge,
                                color = textColor
                            )
                        } else {
                            MarkdownText(text = message.content, color = textColor)
                        }
                        if (message.isLoading) {
                            Spacer(Modifier.height(4.dp))
                            TypingDots(color = scheme.onSurfaceVariant, small = true)
                        }
                    }
                    if (!isUser && message.steps.isNotEmpty()) {
                        var stepsOpen by remember { mutableStateOf(false) }
                        Spacer(Modifier.height(6.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { stepsOpen = !stepsOpen }
                        ) {
                            Icon(AppIcons.Bolt, null, tint = scheme.primary, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "执行了 ${message.steps.size} 步" + if (stepsOpen) " ▾" else " ▸",
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.primary
                            )
                        }
                        if (stepsOpen) {
                            message.steps.forEach { step ->
                                Text(
                                    step,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = scheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 18.dp, top = 2.dp)
                                )
                            }
                        }
                    }
                    if (isUser && message.hitKeywords.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(AppIcons.Bolt, null, tint = scheme.onPrimary.copy(alpha = 0.9f), modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "命中动作：" + message.hitKeywords.joinToString("、"),
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onPrimary.copy(alpha = 0.9f)
                            )
                        }
                    }
                }
            }

            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("复制") },
                    leadingIcon = { Icon(AppIcons.ContentCopy, null) },
                    onClick = { menuOpen = false; onCopyText() }
                )
                if (!isUser && !message.isError && !message.isLoading) {
                    DropdownMenuItem(
                        text = { Text("重新播报") },
                        leadingIcon = { Icon(AppIcons.VolumeUp, null) },
                        onClick = { menuOpen = false; onReplayTts() }
                    )
                }
                if (onRetry != null) {
                    DropdownMenuItem(
                        text = { Text("重新提问") },
                        leadingIcon = { Icon(Icons.Default.Refresh, null) },
                        onClick = { menuOpen = false; onRetry() }
                    )
                }
                DropdownMenuItem(
                    text = { Text("分享") },
                    leadingIcon = { Icon(Icons.Default.Share, null) },
                    onClick = { menuOpen = false; onShare() }
                )
                DropdownMenuItem(
                    text = { Text("删除", color = scheme.error) },
                    leadingIcon = { Icon(Icons.Default.Delete, null, tint = scheme.error) },
                    onClick = { menuOpen = false; onDelete() }
                )
            }
        }

        if (!isUser && task != null) {
            val needsAttention = task.state in setOf("WAITING_CONFIRMATION", "RESULT_UNKNOWN", "WAITING_APP")
            if (task.visible || needsAttention) {
                    // 后台任务，或需要用户授权、核实的回合：直接展示任务卡片。
                    Spacer(Modifier.height(6.dp))
                    TaskCard(task, compact = true)
                    if (task.visible) TextButton(onClick = onOpenTasks) { Text("打开任务页") }
                } else {
                    // 普通回合只在用过工具时提供可展开的执行过程。
                    val callsFlow = remember(task.id) { TaskRuntime.toolCalls(task.id) }
                    val calls by callsFlow.collectAsState(initial = emptyList())
                    if (calls.isNotEmpty()) {
                        var open by remember(task.id) { mutableStateOf(false) }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 6.dp, top = 4.dp).clickable { open = !open }
                        ) {
                            Icon(AppIcons.Bolt, null, tint = scheme.primary, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "执行了 ${calls.size} 步" + if (open) " ▾" else " ▸",
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.primary
                            )
                        }
                        if (open) {
                            Spacer(Modifier.height(6.dp))
                            TaskCard(task, compact = true)
                        }
                    }
            }
        }

        // 助手消息下方的快捷操作
        if (!isUser && !message.isError && !message.isLoading) {
            Row(
                modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                MiniAction(icon = AppIcons.VolumeUp, label = "播报", onClick = onReplayTts)
                MiniAction(icon = AppIcons.ContentCopy, label = "复制", onClick = onCopyText)
            }
        }
        if (!isUser && message.isError && onRetry != null) {
            Row(modifier = Modifier.padding(start = 4.dp, top = 2.dp)) {
                MiniAction(icon = Icons.Default.Refresh, label = "重试", onClick = onRetry)
            }
        }
    }
}

@Composable
private fun SourceTag(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(11.dp))
            Spacer(Modifier.width(3.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun MiniAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = Color.Transparent
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 三点跳动的「正在输入」动画。 */
@Composable
fun TypingDots(color: Color, small: Boolean = false) {
    val transition = rememberInfiniteTransition(label = "typing")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart),
        label = "typingPhase"
    )
    val dot = if (small) 5.dp else 8.dp
    Row(horizontalArrangement = Arrangement.spacedBy(if (small) 3.dp else 5.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until 3) {
            val phase = ((t - i * 0.2f) % 1f + 1f) % 1f
            val alpha = 0.3f + 0.7f * (kotlin.math.sin(phase * Math.PI).toFloat())
            Box(
                modifier = Modifier
                    .size(dot)
                    .alpha(alpha.coerceIn(0.3f, 1f))
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}
