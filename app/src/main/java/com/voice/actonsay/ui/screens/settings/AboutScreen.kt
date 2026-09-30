package com.voice.actonsay.ui.screens.settings

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.voice.actonsay.asr.AsrManager
import com.voice.actonsay.ui.components.SectionCard
import com.voice.actonsay.ui.components.SettingNavRow
import com.voice.actonsay.ui.icons.AppIcons

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val state by AsrManager.state.collectAsState()
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "1.0"
    }
    var confirm by remember { mutableStateOf<String?>(null) }

    confirm?.let { which ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (which == "history") "清空对话与命中记录？" else "重置全部设置？") },
            text = {
                Text(
                    if (which == "history") "将删除本机保存的所有对话与动作命中记录。"
                    else "API Key、模型、唤醒词、外观等全部设置将恢复默认，对话记录会保留。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (which == "history") {
                        AsrManager.clearMessages()
                        AsrManager.keywordDetector.clearEvents()
                    } else {
                        AsrManager.resetAllSettings()
                    }
                    confirm = null
                }) { Text("确定", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } }
        )
    }

    SubPageScaffold(title = "关于与数据", onBack = onBack) {
        // 品牌卡
        Surface(shape = MaterialTheme.shapes.extraLarge, color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .clip(MaterialTheme.shapes.extraLarge)
                    .background(
                        Brush.linearGradient(
                            listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)
                        )
                    )
                    .padding(22.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(AppIcons.Mic, null, tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text("ActOnSay", style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.Bold)
                        Text("版本 $version", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f))
                        Text("说到即做 · 离线唤醒 + 云端识别 + 大模型 + 语音播报", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f))
                    }
                }
            }
        }

        SectionCard(title = "使用统计") {
            Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(label = "累计唤醒", value = state.totalWakeCount.toString(), modifier = Modifier.weight(1f))
                StatTile(label = "对话轮次", value = state.totalTurnCount.toString(), modifier = Modifier.weight(1f))
                StatTile(label = "本机消息", value = state.messages.size.toString(), modifier = Modifier.weight(1f))
            }
        }

        SectionCard(title = "处理流程") {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PipelineStep(1, "本机唤醒", "sherpa-onnx KeywordSpotter 持续监听唤醒词，音频不出设备")
                PipelineStep(2, "云端识别", "火山引擎流式 ASR，Silero VAD 判停自动断句")
                PipelineStep(3, "关键词动作", "对识别文本做关键词匹配并记录命中")
                PipelineStep(4, "大模型回复", "OpenAI 兼容接口，支持多轮上下文与流式输出")
                PipelineStep(5, "语音播报", "MiMo 拟人语音或系统 TTS，播报期间静音防回声")
            }
        }

        SectionCard(title = "数据管理") {
            SettingNavRow(
                title = "清空对话与命中记录",
                subtitle = "删除本机保存的全部历史",
                icon = Icons.Default.Delete,
                iconTint = MaterialTheme.colorScheme.onErrorContainer,
                iconBackground = MaterialTheme.colorScheme.errorContainer,
                onClick = { confirm = "history" }
            )
            SettingNavRow(
                title = "重置全部设置",
                subtitle = "恢复出厂默认，不影响对话记录",
                icon = Icons.Default.Refresh,
                iconTint = MaterialTheme.colorScheme.onErrorContainer,
                iconBackground = MaterialTheme.colorScheme.errorContainer,
                onClick = { confirm = "settings" }
            )
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PipelineStep(index: Int, title: String, detail: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier.size(24.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(index.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
