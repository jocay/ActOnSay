package com.voice.actonsay.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.voice.actonsay.agent.ToolRegistry
import com.voice.actonsay.ai.OpenAiClient
import com.voice.actonsay.asr.AsrManager
import com.voice.actonsay.ui.components.FieldSpacer
import com.voice.actonsay.ui.components.SecretTextField
import com.voice.actonsay.ui.components.SectionCard
import com.voice.actonsay.ui.components.SettingHint
import com.voice.actonsay.ui.components.SettingSwitchRow
import com.voice.actonsay.ui.icons.AppIcons
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiSettingsScreen(onBack: () -> Unit) {
    val state by AsrManager.state.collectAsState()
    val scope = rememberCoroutineScope()

    // 本地草稿，失焦/变更即写回
    var baseUrl by remember(state.aiBaseUrl) { mutableStateOf(state.aiBaseUrl) }
    var apiKey by remember(state.aiApiKey) { mutableStateOf(state.aiApiKey) }
    var model by remember(state.aiModel) { mutableStateOf(state.aiModel) }
    var prompt by remember(state.aiSystemPrompt) { mutableStateOf(state.aiSystemPrompt) }

    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    fun commitEndpoint() = AsrManager.setAiEndpoint(baseUrl, apiKey, model)

    SubPageScaffold(title = "AI 模型", onBack = { commitEndpoint(); AsrManager.setAiSystemPrompt(prompt); onBack() }) {
        SectionCard {
            SettingSwitchRow(
                title = "启用 AI 回复",
                subtitle = "识别完成后把文本交给大模型，并把回答播报出来",
                checked = state.aiEnabled,
                onCheckedChange = { AsrManager.setAiEnabled(it) },
                icon = AppIcons.Robot
            )
        }

        SectionCard(title = "服务商") {
            SettingHint("选择预设会填入 Base URL 与默认模型，API Key 需要自行填写。")
            FlowRow(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                OpenAiClient.PRESETS.forEach { preset ->
                    val selected = baseUrl.trimEnd('/') == preset.baseUrl.trimEnd('/')
                    FilterChip(
                        selected = selected,
                        onClick = {
                            baseUrl = preset.baseUrl
                            model = preset.defaultModel
                            testResult = null
                            commitEndpoint()
                        },
                        label = { Text(preset.name) },
                        leadingIcon = if (selected) {
                            { Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp)) }
                        } else null
                    )
                }
            }
            val activePreset = OpenAiClient.PRESETS.firstOrNull { baseUrl.trimEnd('/') == it.baseUrl.trimEnd('/') }
            if (activePreset != null) SettingHint(activePreset.description)

            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it; testResult = null; commitEndpoint() },
                    label = { Text("Base URL") },
                    placeholder = { Text("https://api.example.com/v1") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    supportingText = { Text("自动补全 /chat/completions", style = MaterialTheme.typography.bodySmall) },
                    modifier = Modifier.fillMaxWidth()
                )
                FieldSpacer()
                SecretTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it; testResult = null; commitEndpoint() },
                    label = "API Key",
                    placeholder = "sk-…",
                    supportingText = if (apiKey.isBlank()) "必填。本地 Ollama 等无鉴权服务可填任意字符" else null
                )
                FieldSpacer()
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it; testResult = null; commitEndpoint() },
                    label = { Text("模型名称") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth()
                )
                FieldSpacer()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            commitEndpoint()
                            testing = true
                            testResult = null
                            scope.launch {
                                val res = OpenAiClient.testConnection(baseUrl, apiKey, model)
                                testing = false
                                testResult = res.fold(
                                    onSuccess = { true to "连接成功，模型回复：${it.take(60)}" },
                                    onFailure = { false to (it.localizedMessage ?: "未知错误") }
                                )
                            }
                        },
                        enabled = !testing && baseUrl.isNotBlank() && model.isNotBlank()
                    ) {
                        if (testing) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(8.dp))
                            Text("测试中…")
                        } else {
                            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("测试连接")
                        }
                    }
                }
                testResult?.let { (ok, msg) ->
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = if (ok) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (ok) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
        }

        SectionCard(title = "对话行为") {
            SettingSwitchRow(
                title = "流式输出",
                subtitle = "回复逐字显示，体感更快；播报仍在全文生成后开始",
                checked = state.aiStreaming,
                onCheckedChange = { AsrManager.setAiBehavior(it, state.aiContextTurns) }
            )
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("上下文记忆", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(
                        if (state.aiContextTurns == 0) "关闭" else "${state.aiContextTurns} 轮",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    "每次提问附带最近几轮对话，让 AI 能听懂「它」「刚才那个」这类指代。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = state.aiContextTurns.toFloat(),
                    onValueChange = { AsrManager.setAiBehavior(state.aiStreaming, it.toInt()) },
                    valueRange = 0f..12f,
                    steps = 11
                )
            }
        }

        SectionCard(title = "智能体") {
            SettingSwitchRow(
                title = "允许调用工具",
                subtitle = "让 AI 在本机执行命令、打开应用、读取屏幕，把说的话真正做成事",
                checked = state.agentEnabled,
                onCheckedChange = { AsrManager.setAgentEnabled(it) },
                icon = AppIcons.Bolt
            )
            Row(
                modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (state.hasRoot) "Root 权限：已授权" else "Root 权限：未授权",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state.hasRoot) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                    Text(
                        if (state.hasRoot) "命令以 root 执行，可读取屏幕、控制其他应用"
                        else "请在 KernelSU / Magisk 管理器中授权本应用，然后点击重新检测",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = { AsrManager.refreshRootStatus(force = true) }) { Text("重新检测") }
            }
            SettingHint("内置工具：" + ToolRegistry.all().joinToString("、") { it.name } + "。模型需要支持 function calling（DeepSeek、GPT、Qwen 均可）。")
        }

        SectionCard(title = "系统提示词") {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it; AsrManager.setAiSystemPrompt(it) },
                    label = { Text("System Prompt") },
                    minLines = 4,
                    maxLines = 8,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = {
                        prompt = AsrManager.DEFAULT_SYSTEM_PROMPT
                        AsrManager.setAiSystemPrompt(prompt)
                    }) { Text("恢复默认") }
                }
            }
        }
    }
}
