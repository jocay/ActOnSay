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
import com.voice.actonsay.tts.LocalTts
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
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

    val localInstalled by LocalTts.installed.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var downloading by remember { mutableStateOf(false) }
    var downloaded by remember { mutableStateOf(0L) }
    var downloadMessage by remember { mutableStateOf("") }

    fun commitMimo() = AsrManager.setMimoConfig(mimoKey, state.mimoVoice, style)

    SubPageScaffold(title = "语音播报", onBack = { commitMimo(); onBack() }) {
        SectionCard {
            SettingSwitchRow(
                title = "启用语音播报",
                subtitle = "回复边生成边分句朗读，可随时打断；后台任务结果默认不朗读",
                checked = state.ttsEnabled,
                onCheckedChange = { AsrManager.setTtsEnabled(it) },
                icon = AppIcons.VolumeUp
            )
            SettingSwitchRow(
                title = "朗读完整回复",
                subtitle = "关闭后只朗读前 160 字，完整内容仍在屏幕上；表格和代码块始终不读",
                checked = state.ttsFullReply,
                onCheckedChange = { AsrManager.setTtsFullReply(it) }
            )
        }

        SectionCard(title = "合成引擎") {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                listOf(TtsEngineType.MIMO to "MiMo 在线", TtsEngineType.LOCAL to "本地离线", TtsEngineType.SYSTEM to "系统引擎").forEachIndexed { index, (engine, label) ->
                    SegmentedButton(
                        selected = state.ttsEngine == engine,
                        onClick = { AsrManager.setTtsEngine(engine) },
                        shape = SegmentedButtonDefaults.itemShape(index, 3),
                        label = { Text(label) }
                    )
                }
            }
            SettingHint(
                when (state.ttsEngine) {
                    TtsEngineType.MIMO -> "小米 MiMo 拟人大模型语音，音质自然、支持情感风格；分段并行合成。请求失败时该段自动改用本地离线模型。"
                    TtsEngineType.LOCAL -> "应用内置的 sherpa-onnx 离线模型（中文女声），无需联网、出声最快，音质不如云端；语速沿用下方系统引擎的设置。"
                    TtsEngineType.SYSTEM -> "使用 Android 系统自带 TextToSpeech，无需联网，音色取决于设备已安装的语音包。"
                }
            )
        }

        SectionCard(title = "本地离线模型") {
            SettingHint(
                if (localInstalled) "已安装 matcha-icefall-zh-baker。云端合成失败时自动用它兜底，也可作为主引擎。"
                else "尚未安装。下载约 ${LocalTts.totalBytes / (1024 * 1024)} MB 到应用私有目录，之后云端失败时自动兜底，也可作为主引擎。"
            )
            Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    enabled = !downloading && !localInstalled,
                    onClick = {
                        downloading = true
                        downloadMessage = ""
                        scope.launch {
                            downloadMessage = runCatching {
                                LocalTts.download(context.applicationContext) { done -> downloaded = done }
                                "下载完成"
                            }.getOrElse { "下载失败：${it.message}，可再次点击继续" }
                            downloading = false
                        }
                    }
                ) {
                    Text(when {
                        localInstalled -> "已安装"
                        downloading -> "下载中 ${downloaded * 100 / LocalTts.totalBytes}%"
                        else -> "下载模型"
                    })
                }
                Spacer(Modifier.width(10.dp))
                Text(downloadMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (state.ttsEngine == TtsEngineType.MIMO) {
            SectionCard(title = "MiMo 参数") {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    SecretTextField(
                        value = mimoKey,
                        onValueChange = { mimoKey = it; commitMimo() },
                        label = "MiMo API Key",
                        supportingText = "填写自己的 MiMo API Key；留空时使用本地离线模型或系统 TTS。"
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
