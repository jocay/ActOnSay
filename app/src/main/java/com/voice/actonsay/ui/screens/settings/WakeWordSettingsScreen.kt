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
import com.voice.actonsay.ui.icons.AppIcons

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WakeWordSettingsScreen(onBack: () -> Unit) {
    val state by AsrManager.state.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }

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
