package com.voice.actonsay.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import com.voice.actonsay.asr.AsrManager
import com.voice.actonsay.asr.VolcengineStreamAsrClient
import com.voice.actonsay.ui.components.SecretTextField
import com.voice.actonsay.ui.components.SectionCard
import com.voice.actonsay.ui.components.SettingHint

@Composable
fun AsrSettingsScreen(onBack: () -> Unit) {
    val state by AsrManager.state.collectAsState()
    var key by remember(state.apiKey) { mutableStateOf(state.apiKey) }

    SubPageScaffold(title = "语音识别", onBack = { AsrManager.setApiKey(key); onBack() }) {
        SectionCard(title = "火山引擎凭据") {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                SecretTextField(
                    value = key,
                    onValueChange = { key = it; AsrManager.setApiKey(it) },
                    label = "API Key",
                    supportingText = "在火山引擎控制台「语音技术 › 流式语音识别大模型」中获取"
                )
            }
        }

        SectionCard(title = "识别模型") {
            ResourceOption(
                title = "BigASR 1.0",
                subtitle = "默认可用，通用场景识别",
                id = VolcengineStreamAsrClient.RESOURCE_ID_BIGASR_1,
                selected = state.resourceId == VolcengineStreamAsrClient.RESOURCE_ID_BIGASR_1
            ) { AsrManager.setResourceId(it) }
            ResourceOption(
                title = "Seed ASR 2.0",
                subtitle = "更强的抗噪与口音鲁棒性，需在控制台单独开通",
                id = VolcengineStreamAsrClient.RESOURCE_ID_SEED_2,
                selected = state.resourceId == VolcengineStreamAsrClient.RESOURCE_ID_SEED_2
            ) { AsrManager.setResourceId(it) }
        }

        SectionCard(title = "工作方式") {
            Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("唤醒后才会连接云端", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "待机阶段的唤醒词识别全部在本机完成，不上传任何音频。只有在唤醒后，音频才会通过 WebSocket 流式发送到火山引擎；说话停顿约 0.8 秒即自动结束，6 秒内无人说话则自动返回待机。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            SettingHint("接入地址：wss://openspeech.bytedance.com/api/v3/sauc/bigmodel")
        }
    }
}

@Composable
private fun ResourceOption(title: String, subtitle: String, id: String, selected: Boolean, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(id) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = { onSelect(id) })
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(
                shape = MaterialTheme.shapes.extraSmall,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Text(
                    id,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}
