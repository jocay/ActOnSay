package com.voice.actonsay.ui.screens.settings

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.voice.actonsay.asr.AsrManager
import com.voice.actonsay.diagnostics.Diagnostics
import com.voice.actonsay.diagnostics.TurnTiming
import com.voice.actonsay.ui.components.SectionCard
import com.voice.actonsay.ui.components.SettingHint
import com.voice.actonsay.ui.components.SettingSwitchRow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 用户主动开启的诊断页：各环节耗时与失败原因，用来找出真正慢的地方。 */
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val state by AsrManager.state.collectAsState()
    val turns by Diagnostics.turns.collectAsState()
    val failures by Diagnostics.failures.collectAsState()
    val context = LocalContext.current
    val format = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }

    SubPageScaffold("诊断", onBack, actions = {
        if (state.diagnosticsEnabled) {
            TextButton(onClick = {
                val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, Diagnostics.report())
                context.startActivity(Intent.createChooser(intent, "导出诊断记录"))
            }) { Text("导出") }
            TextButton(onClick = { Diagnostics.clear() }) { Text("清空") }
        }
    }) {
        SectionCard("记录") {
            SettingSwitchRow("开启诊断记录", "记录识别收尾、模型首字、首次出声和工具执行的耗时，以及失败原因", state.diagnosticsEnabled, {
                AsrManager.setDiagnosticsEnabled(it)
            })
            SettingHint("只保存在内存中，最多保留最近 50 轮和 100 条失败；关闭或重启应用后清空。不记录回答正文，导出内容已脱敏。")
        }
        if (!state.diagnosticsEnabled) return@SubPageScaffold

        SectionCard("最近 ${turns.size} 轮的中位耗时") {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MetricLine("识别收尾", "说完话到拿到最终文字", median(turns.mapNotNull { it.asrFinalizeMs }))
                MetricLine("排队等待", "提交到开始执行", median(turns.mapNotNull { it.queueMs }))
                MetricLine("模型首字", "开始执行到模型返回第一个字", median(turns.mapNotNull { it.firstTokenMs }))
                MetricLine("首次出声", "提交到第一次播放声音（语音回合）", median(turns.mapNotNull { it.firstAudioMs }))
                MetricLine("整轮耗时", "提交到回合结束", median(turns.mapNotNull { it.totalMs }))
                val tools = turns.flatMap { it.tools }.groupBy { it.name }
                tools.entries.sortedByDescending { entry -> entry.value.sumOf { it.durationMs } }.take(6).forEach { (name, calls) ->
                    MetricLine("工具 $name", "${calls.size} 次，失败 ${calls.count { it.status != "SUCCEEDED" }} 次", median(calls.map { it.durationMs }))
                }
            }
        }

        SectionCard("最近的回合") {
            if (turns.isEmpty()) SettingHint("开启后发起对话即可在这里看到记录。")
            turns.asReversed().take(20).forEach { turn -> TurnRow(turn, format.format(Date(turn.submittedAt))) }
        }

        SectionCard("失败原因") {
            if (failures.isEmpty()) SettingHint("暂无失败记录。")
            failures.asReversed().take(30).forEach { failure ->
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text("${format.format(Date(failure.time))} · ${failure.stage}", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error)
                    Text(failure.message, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun median(values: List<Long>): Long? = values.sorted().takeIf { it.isNotEmpty() }?.let { it[it.size / 2] }

private fun ms(value: Long?): String = when {
    value == null -> "—"
    value >= 10_000 -> "%.1f 秒".format(value / 1000.0)
    else -> "$value ms"
}

@Composable
private fun MetricLine(label: String, detail: String, value: Long?) {
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(ms(value), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun TurnRow(turn: TurnTiming, time: String) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("$time · ${if (turn.voice) "语音" else "文字"} · ${turn.outcome.ifBlank { "进行中" }}", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
            Text(turn.goal, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            Text("识别 ${ms(turn.asrFinalizeMs)} · 排队 ${ms(turn.queueMs)} · 首字 ${ms(turn.firstTokenMs)} · 出声 ${ms(turn.firstAudioMs)} · 总计 ${ms(turn.totalMs)}",
                style = MaterialTheme.typography.labelSmall)
            if (turn.tools.isNotEmpty()) Text("工具：" + turn.tools.joinToString("、") { "${it.name} ${ms(it.durationMs)}" + if (it.status != "SUCCEEDED") "（${it.status}）" else "" },
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
