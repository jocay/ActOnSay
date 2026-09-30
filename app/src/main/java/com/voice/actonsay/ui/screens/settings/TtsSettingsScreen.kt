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
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.voice.actonsay.asr.AsrManager
import com.voice.actonsay.tts.MimoTtsClient
import com.voice.actonsay.tts.TtsEngineType
import com.voice.actonsay.tts.TtsManager
import com.voice.actonsay.ui.components.FieldSpacer
import com.voice.actonsay.ui.components.SecretTextField
import com.voice.actonsay.ui.components.SectionCard
import com.voice.actonsay.ui.components.SettingHint
import com.voice.actonsay.ui.components.SettingSwitchRow
import com.voice.actonsay.ui.icons.AppIcons

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TtsSettingsScreen(onBack: () -> Unit) {
    val state by AsrManager.state.collectAsState()
    val isSpeaking by TtsManager.isSpeaking.collectAsState()

    var mimoKey by remember(state.mimoApiKey) { mutableStateOf(state.mimoApiKey) }
    var style by remember(state.mimoStylePrompt) { mutableStateOf(state.mimoStylePrompt) }
    var demoText by rememberSaveable { mutableStateOf("你好，我是你的语音助手，很高兴为你服务。") }
    var rate by remember(state.ttsSpeechRate) { mutableStateOf(state.ttsSpeechRate) }
    var pitch by remember(state.ttsPitch) { mutableStateOf(state.ttsPitch) }

    fun commitMimo() = AsrManager.setMimoConfig(mimoKey, state.mimoVoice, style)

    SubPageScaffold(title = "语音播报", onBack = { commitMimo(); onBack() }) {
        SectionCard {
            SettingSwitchRow(
                title = "启用语音播报",
                subtitle = "AI 回复完成后自动朗读；播报期间暂停拾音防止回声",
                checked = state.ttsEnabled,
                onCheckedChange = { AsrManager.setTtsEnabled(it) },
                icon = AppIcons.VolumeUp
            )
        }

        SectionCard(title = "合成引擎") {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                SegmentedButton(
                    selected = state.ttsEngine == TtsEngineType.MIMO,
                    onClick = { AsrManager.setTtsEngine(TtsEngineType.MIMO) },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                    label = { Text("MiMo 在线") }
                )
                SegmentedButton(
                    selected = state.ttsEngine == TtsEngineType.SYSTEM,
                    onClick = { AsrManager.setTtsEngine(TtsEngineType.SYSTEM) },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                    label = { Text("系统离线") }
                )
            }
            SettingHint(
                if (state.ttsEngine == TtsEngineType.MIMO)
                    "小米 MiMo 拟人大模型语音，音质自然、支持情感风格；网络异常时自动回退到系统引擎。"
                else
                    "使用 Android 系统自带 TextToSpeech，无需联网，音色取决于设备已安装的语音包。"
            )
        }

        if (state.ttsEngine == TtsEngineType.MIMO) {
            SectionCard(title = "MiMo 参数") {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    SecretTextField(
                        value = mimoKey,
                        onValueChange = { mimoKey = it; commitMimo() },
                        label = "MiMo API Key",
                        supportingText = "留空则使用内置体验密钥（额度有限，建议填写自己的）"
                    )
                    FieldSpacer()
                    Text("音色", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        MimoTtsClient.SUPPORTED_VOICES.forEach { (id, label) ->
                            val selected = state.mimoVoice == id
                            FilterChip(
                                selected = selected,
                                onClick = { AsrManager.setMimoConfig(mimoKey, id, style) },
                                label = { Text(label) },
                                leadingIcon = if (selected) {
                                    { Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp)) }
                                } else null
                            )
                        }
                    }
                    FieldSpacer()
                    OutlinedTextField(
                        value = style,
                        onValueChange = { style = it; commitMimo() },
                        label = { Text("风格提示词（可选）") },
                        placeholder = { Text("例如：语气温柔、略带笑意，语速稍慢") },
                        minLines = 2,
                        maxLines = 4,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        } else {
            SectionCard(title = "系统引擎参数") {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    LabeledSlider(
                        label = "语速",
                        value = rate,
                        display = String.format("%.1fx", rate),
                        range = 0.5f..2.0f,
                        onChange = { rate = it },
                        onChangeFinished = { AsrManager.setSystemTtsParams(rate, pitch) }
                    )
                    LabeledSlider(
                        label = "音调",
                        value = pitch,
                        display = String.format("%.1f", pitch),
                        range = 0.5f..2.0f,
                        onChange = { pitch = it },
                        onChangeFinished = { AsrManager.setSystemTtsParams(rate, pitch) }
                    )
                }
            }
        }

        SectionCard(title = "试听") {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                OutlinedTextField(
                    value = demoText,
                    onValueChange = { demoText = it },
                    label = { Text("试听文本") },
                    minLines = 2,
                    maxLines = 4,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            commitMimo()
                            AsrManager.setSystemTtsParams(rate, pitch)
                            TtsManager.speak(demoText)
                        },
                        enabled = demoText.isNotBlank() && !isSpeaking
                    ) {
                        Icon(AppIcons.VolumeUp, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (isSpeaking) "播放中…" else "试听")
                    }
                    Spacer(Modifier.width(10.dp))
                    OutlinedButton(onClick = { TtsManager.stop() }, enabled = isSpeaking) {
                        Icon(AppIcons.Stop, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("停止")
                    }
                }
            }
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    display: String,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onChangeFinished: () -> Unit
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(display, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, onValueChangeFinished = onChangeFinished)
    }
}
