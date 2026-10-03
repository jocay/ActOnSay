package com.voice.actonsay.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.voice.actonsay.asr.AsrManager
import com.voice.actonsay.tts.TtsEngineType
import com.voice.actonsay.ui.components.SectionCard
import com.voice.actonsay.ui.components.SettingNavRow
import com.voice.actonsay.ui.icons.AppIcons

enum class SettingsPage { AI, ASR, TTS, WAKE_WORDS, APPEARANCE, ABOUT, EXECUTION, MCP, SKILLS, PERMISSIONS, MEMORY, WEB }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsHubScreen(onOpen: (SettingsPage) -> Unit) {
    val state by AsrManager.state.collectAsState()
    val scheme = MaterialTheme.colorScheme

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = scheme.surface)
            )
        },
        containerColor = scheme.surface
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 概览卡
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = scheme.primaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("链路状态", style = MaterialTheme.typography.labelLarge, color = scheme.onPrimaryContainer.copy(alpha = 0.8f))
                    Spacer(Modifier.height(10.dp))
                    StatusLine(ok = true, label = "本地唤醒", detail = "${state.customWakeWords.size} 个唤醒词")
                    StatusLine(ok = state.asrReady, label = "云端识别", detail = if (state.asrReady) "火山引擎已配置" else "未配置 API Key")
                    StatusLine(
                        ok = state.aiReady,
                        label = "AI 回复",
                        detail = when {
                            !state.aiEnabled -> "已关闭"
                            state.aiReady -> state.aiModel
                            else -> "未配置"
                        }
                    )
                    StatusLine(
                        ok = state.ttsEnabled,
                        label = "语音播报",
                        detail = if (!state.ttsEnabled) "已关闭" else when (state.ttsEngine) { TtsEngineType.MIMO -> "MiMo · ${state.mimoVoice}"; TtsEngineType.LOCAL -> "本地离线"; else -> "系统引擎" }
                    )
                }
            }

            SectionCard(title = "语音链路") {
                SettingNavRow(
                    title = "AI 模型",
                    subtitle = if (state.aiEnabled) "${state.aiModel} · ${if (state.aiStreaming) "流式" else "非流式"} · 记忆 ${state.aiContextTurns} 轮" else "已关闭 AI 回复",
                    icon = AppIcons.Robot,
                    onClick = { onOpen(SettingsPage.AI) }
                )
                SettingNavRow(
                    title = "语音识别",
                    subtitle = "火山引擎流式 ASR · " + if (state.asrReady) "已配置" else "未配置",
                    icon = AppIcons.Cloud,
                    iconTint = scheme.onSecondaryContainer,
                    iconBackground = scheme.secondaryContainer,
                    onClick = { onOpen(SettingsPage.ASR) }
                )
                SettingNavRow(
                    title = "语音播报",
                    subtitle = if (!state.ttsEnabled) "已关闭" else when (state.ttsEngine) { TtsEngineType.MIMO -> "小米 MiMo 拟人语音"; TtsEngineType.LOCAL -> "本地离线模型"; else -> "系统 TextToSpeech" },
                    icon = AppIcons.RecordVoice,
                    iconTint = scheme.onTertiaryContainer,
                    iconBackground = scheme.tertiaryContainer,
                    onClick = { onOpen(SettingsPage.TTS) }
                )
                SettingNavRow(
                    title = "唤醒词",
                    subtitle = state.customWakeWords.joinToString("、").ifBlank { "使用内置默认唤醒词" },
                    icon = AppIcons.Hearing,
                    onClick = { onOpen(SettingsPage.WAKE_WORDS) }
                )
            }

            SectionCard(title = "执行与扩展") {
                SettingNavRow("Debian 执行环境", "容器、配对、部署向导与首次检查", AppIcons.Terminal, onClick = { onOpen(SettingsPage.EXECUTION) })
                SettingNavRow("联网、搜索与音乐", "搜索密钥、音乐解析和可选代理", AppIcons.Language, onClick = { onOpen(SettingsPage.WEB) })
                SettingNavRow("MCP 服务", "stdio、HTTP、工具、资源与提示词", AppIcons.Extension, onClick = { onOpen(SettingsPage.MCP) })
                SettingNavRow("Skills", "导入、按需加载与版本管理", AppIcons.Skill, onClick = { onOpen(SettingsPage.SKILLS) })
                SettingNavRow("权限与任务预算", "Android Root、Debian 执行和高度自主模式", AppIcons.Key, onClick = { onOpen(SettingsPage.PERMISSIONS) })
                SettingNavRow("长期偏好与项目备注", "明确记住后用于后续任务", AppIcons.History, onClick = { onOpen(SettingsPage.MEMORY) })
            }

            SectionCard(title = "通用") {
                SettingNavRow(
                    title = "外观与行为",
                    subtitle = "主题、动态取色、自动待机、屏幕常亮",
                    icon = AppIcons.Palette,
                    onClick = { onOpen(SettingsPage.APPEARANCE) }
                )
                SettingNavRow(
                    title = "关于与数据",
                    subtitle = "版本信息、使用统计、重置",
                    icon = Icons.Default.Info,
                    onClick = { onOpen(SettingsPage.ABOUT) }
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun StatusLine(ok: Boolean, label: String, detail: String) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier = Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = CircleShape,
            color = if (ok) scheme.secondary else scheme.onPrimaryContainer.copy(alpha = 0.25f),
            modifier = Modifier.size(9.dp)
        ) {}
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = scheme.onPrimaryContainer, modifier = Modifier.width(80.dp))
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = scheme.onPrimaryContainer.copy(alpha = 0.75f))
    }
}

@Composable
fun SubPageIconTitle(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text(title)
    }
}
