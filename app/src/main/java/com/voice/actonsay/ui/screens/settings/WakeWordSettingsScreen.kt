package com.voice.actonsay.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.voice.actonsay.asr.AsrManager
import com.voice.actonsay.ui.components.SectionCard
import com.voice.actonsay.ui.components.SettingHint
import com.voice.actonsay.ui.components.SettingSwitchRow
import com.voice.actonsay.ui.icons.AppIcons

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WakeWordSettingsScreen(onBack: () -> Unit) {
    val state by AsrManager.state.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    var interruptInput by rememberSaveable { mutableStateOf("") }

    fun add() {
        val w = input.trim()
        if (w.isNotEmpty()) {
            AsrManager.addWakeWord(w)
            input = ""
        }
    }

    SubPageScaffold(
        title = "唤醒词",
        onBack = onBack,
        actions = { TextButton(onClick = { AsrManager.resetWakeWords() }) { Text("恢复默认") } }
    ) {
        SectionCard(title = "当前唤醒词") {
            SettingHint("待机时在本机持续监听这些词，识别完全离线。建议使用 3～5 个字、发音清晰、日常不常说的词组。")
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("如：你好小智") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { add() })
                )
                Spacer(Modifier.width(10.dp))
                FilledIconButton(onClick = ::add, enabled = input.isNotBlank()) {
                    Icon(Icons.Default.Add, contentDescription = "添加")
                }
            }
            if (state.customWakeWords.isEmpty()) {
                SettingHint("列表为空时将使用内置默认唤醒词。")
            } else {
                FlowRow(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    state.customWakeWords.sorted().forEach { word ->
                        InputChip(
                            selected = true,
                            onClick = { AsrManager.removeWakeWord(word) },
                            label = { Text(word) },
                            leadingIcon = { Icon(AppIcons.Hearing, null, modifier = Modifier.size(16.dp)) },
                            trailingIcon = { Icon(Icons.Default.Close, contentDescription = "移除 $word", modifier = Modifier.size(16.dp)) },
                            shape = CircleShape
                        )
                    }
                }
                SettingHint("点击标签即可移除。")
            }
        }

        SectionCard(title = "唤醒应答") {
            SettingSwitchRow(
                title = "唤醒后先语音应答",
                subtitle = "听到唤醒词立即回一句“我在”，应答播完再开始收音；点击麦克风唤醒不应答。需开启语音播报",
                checked = state.wakeAckEnabled,
                onCheckedChange = { AsrManager.setWakeAck(it) }
            )
            SettingSwitchRow(
                title = "连续对话",
                subtitle = "语音提问的回答播完后自动再听 5 秒，接着说就行，不用再喊唤醒词",
                checked = state.followUpEnabled,
                onCheckedChange = { AsrManager.setVoiceExperience(it, state.progressNarration, state.announceBackground) }
            )
            SettingSwitchRow(
                title = "进度播报",
                subtitle = "语音提问后 AI 较久没出声时，播一句当前在做什么",
                checked = state.progressNarration,
                onCheckedChange = { AsrManager.setVoiceExperience(state.followUpEnabled, it, state.announceBackground) }
            )
            SettingSwitchRow(
                title = "后台任务完成时通报",
                subtitle = "后台任务完成说一声；定时任务直接念出结果（多为提醒）",
                checked = state.announceBackground,
                onCheckedChange = { AsrManager.setVoiceExperience(state.followUpEnabled, state.progressNarration, it) }
            )
        }

        SectionCard(title = "打断词") {
            SettingHint("AI 说话、干活或放歌时说出打断词，会立即停下来听你说；正在执行的任务继续。唤醒词在播报时同样能打断。播报内容里恰好含有这个词时会被忽略。")
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = interruptInput,
                    onValueChange = { interruptInput = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("如：等一下") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium
                )
                Spacer(Modifier.width(10.dp))
                FilledIconButton(onClick = {
                    val word = interruptInput.trim()
                    if (word.isNotEmpty()) { AsrManager.setInterruptWords(state.interruptWords + word); interruptInput = "" }
                }, enabled = interruptInput.isNotBlank()) {
                    Icon(Icons.Default.Add, contentDescription = "添加打断词")
                }
            }
            FlowRow(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                state.interruptWords.sorted().forEach { word ->
                    InputChip(
                        selected = true,
                        onClick = { AsrManager.setInterruptWords(state.interruptWords - word) },
                        label = { Text(word) },
                        trailingIcon = { Icon(Icons.Default.Close, contentDescription = "移除 $word", modifier = Modifier.size(16.dp)) },
                        shape = CircleShape
                    )
                }
            }
            if (state.interruptWords.isEmpty()) SettingHint("没有打断词时只有唤醒词能打断播报。")
        }

        SectionCard(title = "识别提示") {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Tip("中文会自动转换为拼音音素；英文字母、数字按单个字符识别。")
                Tip("包含生僻字或符号的词无法转换，会被忽略并在日志中提示。")
                Tip("误唤醒偏多时，可换用更长、更少见的词；漏唤醒偏多时，说得慢一点、清楚一点。")
            }
        }
    }
}

@Composable
private fun Tip(text: String) {
    Row {
        Text("•", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
