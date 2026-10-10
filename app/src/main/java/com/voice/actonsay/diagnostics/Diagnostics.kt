package com.voice.actonsay.diagnostics

import com.voice.actonsay.tasks.CredentialRedactor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ToolTiming(val name: String, val durationMs: Long, val status: String)

/** 一轮对话的耗时拆分；没有经过的阶段为 null。 */
data class TurnTiming(
    val taskId: String,
    val voice: Boolean,
    val goal: String,
    val submittedAt: Long,
    /** 说完话（判停或手动结束）到识别出最终文本。 */
    val asrFinalizeMs: Long? = null,
    /** 提交到开始执行，即排队等待。 */
    val queueMs: Long? = null,
    /** 开始执行到模型返回第一个字。 */
    val firstTokenMs: Long? = null,
    /** 提交到第一次出声（含进度播报）。 */
    val firstAudioMs: Long? = null,
    /** 提交到回合结束。 */
    val totalMs: Long? = null,
    val tools: List<ToolTiming> = emptyList(),
    val outcome: String = ""
)

data class DiagnosticFailure(val time: Long, val stage: String, val message: String)

/**
 * 用户主动开启的耗时与失败记录，只保存在内存里，关闭或重启后清空。
 * 未开启时所有记录方法立即返回，不产生开销。
 */
object Diagnostics {
    private const val MAX_TURNS = 50
    private const val MAX_FAILURES = 100

    @Volatile var enabled = false
        set(value) {
            field = value
            if (!value) clear()
        }

    private val _turns = MutableStateFlow<List<TurnTiming>>(emptyList())
    val turns: StateFlow<List<TurnTiming>> = _turns.asStateFlow()
    private val _failures = MutableStateFlow<List<DiagnosticFailure>>(emptyList())
    val failures: StateFlow<List<DiagnosticFailure>> = _failures.asStateFlow()

    private var speechEndedAt = 0L
    private var pendingAsrMs: Long? = null
    private val startedAt = HashMap<String, Long>()
    private val toolStarts = HashMap<String, Long>()
    private var awaitingAudio: String? = null

    @Synchronized fun speechEnded() {
        if (!enabled) return
        speechEndedAt = System.currentTimeMillis()
    }

    @Synchronized fun asrFinal() {
        if (!enabled) return
        pendingAsrMs = if (speechEndedAt > 0) System.currentTimeMillis() - speechEndedAt else null
        speechEndedAt = 0
    }

    @Synchronized fun turnSubmitted(taskId: String, voice: Boolean, goal: String) {
        if (!enabled) return
        val turn = TurnTiming(taskId, voice, CredentialRedactor.text(goal.take(60)), System.currentTimeMillis(),
            asrFinalizeMs = if (voice) pendingAsrMs else null)
        pendingAsrMs = null
        awaitingAudio = if (voice) taskId else null
        _turns.value = (_turns.value + turn).takeLast(MAX_TURNS)
    }

    @Synchronized fun taskStarted(taskId: String) {
        if (!enabled) return
        val now = System.currentTimeMillis()
        startedAt[taskId] = now
        update(taskId) { if (it.queueMs == null) it.copy(queueMs = now - it.submittedAt) else it }
    }

    @Synchronized fun firstToken(taskId: String) {
        if (!enabled) return
        val now = System.currentTimeMillis()
        update(taskId) { if (it.firstTokenMs == null) it.copy(firstTokenMs = now - (startedAt[taskId] ?: it.submittedAt)) else it }
    }

    /** 由播放器在一段音频真正开始播放时调用。 */
    @Synchronized fun playbackStarted() {
        if (!enabled) return
        val id = awaitingAudio ?: return
        awaitingAudio = null
        val now = System.currentTimeMillis()
        update(id) { if (it.firstAudioMs == null) it.copy(firstAudioMs = now - it.submittedAt) else it }
    }

    @Synchronized fun toolStarted(taskId: String, callId: String) {
        if (!enabled) return
        toolStarts["$taskId:$callId"] = System.currentTimeMillis()
    }

    @Synchronized fun toolFinished(taskId: String, callId: String, name: String, status: String) {
        if (!enabled) return
        val started = toolStarts.remove("$taskId:$callId") ?: return
        val timing = ToolTiming(name, System.currentTimeMillis() - started, status)
        update(taskId) { it.copy(tools = (it.tools + timing).takeLast(30)) }
    }

    @Synchronized fun taskFinished(taskId: String, state: String) {
        if (!enabled) return
        startedAt.remove(taskId)
        val now = System.currentTimeMillis()
        update(taskId) { it.copy(totalMs = now - it.submittedAt, outcome = state) }
    }

    @Synchronized fun failure(stage: String, message: String) {
        if (!enabled || message.isBlank()) return
        _failures.value = (_failures.value + DiagnosticFailure(System.currentTimeMillis(), stage,
            CredentialRedactor.text(message.take(500)))).takeLast(MAX_FAILURES)
    }

    @Synchronized fun clear() {
        _turns.value = emptyList()
        _failures.value = emptyList()
        startedAt.clear()
        toolStarts.clear()
        awaitingAudio = null
        pendingAsrMs = null
        speechEndedAt = 0
    }

    /** 可分享的文字报告，已脱敏，不含回答正文。 */
    fun report(): String {
        val format = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
        fun ms(value: Long?) = value?.let { "${it} ms" } ?: "—"
        val turns = _turns.value.joinToString("\n\n") { turn ->
            "[${format.format(Date(turn.submittedAt))}] ${if (turn.voice) "语音" else "文字"} · ${turn.outcome.ifBlank { "进行中" }} · ${turn.goal}\n" +
                "识别收尾 ${ms(turn.asrFinalizeMs)}；排队 ${ms(turn.queueMs)}；模型首字 ${ms(turn.firstTokenMs)}；首次出声 ${ms(turn.firstAudioMs)}；总计 ${ms(turn.totalMs)}" +
                if (turn.tools.isEmpty()) "" else "\n工具：" + turn.tools.joinToString("、") { "${it.name} ${it.durationMs} ms ${it.status}" }
        }
        val failures = _failures.value.joinToString("\n") { "[${format.format(Date(it.time))}] ${it.stage}：${it.message}" }
        return "ActOnSay 诊断记录\n\n== 最近回合 ==\n${turns.ifBlank { "无" }}\n\n== 失败原因 ==\n${failures.ifBlank { "无" }}"
    }

    private fun update(taskId: String, transform: (TurnTiming) -> TurnTiming) {
        val list = _turns.value
        val index = list.indexOfLast { it.taskId == taskId }
        if (index < 0) return
        _turns.value = list.toMutableList().also { it[index] = transform(it[index]) }
    }
}
