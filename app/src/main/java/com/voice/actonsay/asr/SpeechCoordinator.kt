package com.voice.actonsay.asr

import com.voice.actonsay.tasks.TaskRecord
import com.voice.actonsay.tasks.TaskRuntime
import com.voice.actonsay.tts.TtsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** 协调回复分段、进度播报、后台通报与连续对话；识别连接与配置仍由 AsrManager 管理。 */
internal class SpeechCoordinator(
    private val _state: MutableStateFlow<AsrState>,
    private val scope: CoroutineScope,
    private val returnToStandby: () -> Unit,
    private val beginFollowUp: () -> Unit
) {
    /** 当前前台回合的流式朗读进度：回复边生成边按句入队，不等全文结束。 */
    private class SpeechStream(val taskId: String, val voice: Boolean) {
        var consumed = 0
        var lastAnswer = ""
        var spokenChars = 0
        var muted = false
        var inCode = false
        /** 用户打断或开始新一轮；与朗读预算用尽的 [muted] 区分，打断后不进入连续对话。 */
        var interrupted = false
        var promotedAnnounced = false
        /** 本次等待授权已经提示过；授权后继续执行时复位，下次需要授权再提示。 */
        var confirmationAnnounced = false
        var lastSpokenAt = System.currentTimeMillis()
    }
    private var speechStream: SpeechStream? = null
    /** 语音回合的回答已全部入队，播完后进入连续对话。 */
    private var followUpPending = false
    private var narrationJob: Job? = null
    private val announcedTasks = mutableSetOf<String>()
    private val taskStates = mutableMapOf<String, String>()
    private val pendingAnnouncements = ArrayDeque<String>()
    fun observe(tasks: List<TaskRecord>) {
        announceFinishedTasks(tasks)
        speechStream?.let { stream -> tasks.firstOrNull { it.id == stream.taskId }?.let { feedSpeech(stream, it) } }
    }

    fun start(taskId: String, voice: Boolean) {
        narrationJob?.cancel()
        speechStream = SpeechStream(taskId, voice)
        if (voice && _state.value.progressNarration && _state.value.ttsEnabled) startNarration(taskId)
    }

    fun interrupt() {
        speechStream?.let { it.muted = true; it.interrupted = true }
        followUpPending = false
        narrationJob?.cancel()
    }

    fun standby() { scope.launch { flushAnnouncements() } }

    fun reset() {
        interrupt()
        speechStream = null
        pendingAnnouncements.clear()
    }

    /**
     * 重播一条历史回复：与正常回答走同一套播报状态（SPEAKING_TTS），打断词、回声过滤和回到待机都照常生效。
     * 用户正在说话或识别中时返回 false。
     */
    fun replay(markdown: String): Boolean {
        val st = _state.value
        if (st.engineState != EngineState.WAITING_WAKEWORD && st.engineState != EngineState.IDLE && st.engineState != EngineState.SPEAKING_TTS) return false
        val plain = speakable(markdown)
        if (plain.isBlank()) return false
        // 重播取代当前朗读：本轮后续生成的内容不再接着念。
        interrupt()
        TtsManager.stop()
        val text = if (plain.length > REPLAY_CHARS) plain.take(REPLAY_CHARS) + "。完整内容请查看屏幕。" else plain
        sentences(text).forEach { say(it) }
        return true
    }

    /** 一句简短的操作反馈（没听清、需要确认、联网失败等）；用户正在说话或识别中时不抢话。 */
    fun notice(text: String) {
        if (_state.value.ttsEnabled) say(text)
    }

    /**
     * 把回合已生成的文本切成短段送入朗读队列，回复还在生成时就开始播报。
     * 首段取第一个分句以尽快出声，之后每段约 [SPEECH_CHUNK_CHARS] 字并在标点处断开；各段并行合成、顺序播放。
     */
    private fun feedSpeech(stream: SpeechStream, task: com.voice.actonsay.tasks.TaskRecord) {
        val finished = task.state == "SUCCEEDED"
        when (task.state) {
            "WAITING_CONFIRMATION" -> {
                // 等待授权时保留朗读进度：用户允许后任务继续，后面的内容接着念，回答结束后照常进入连续对话。
                narrationJob?.cancel()
                if (stream.voice && !stream.interrupted && !stream.confirmationAnnounced && !task.visible && _state.value.ttsEnabled) {
                    stream.confirmationAnnounced = true
                    say("这一步需要你确认，请在屏幕上点允许或拒绝。")
                }
                return
            }
            "WAITING_APP" -> return
            "QUEUED", "RUNNING" -> {
                stream.confirmationAnnounced = false
                // 授权或恢复后重新开始进度播报（等待期间已停止）。
                if (stream.voice && !stream.interrupted && !stream.muted && !task.visible && narrationJob?.isActive != true &&
                    _state.value.progressNarration && _state.value.ttsEnabled) startNarration(task.id)
            }
            "SUCCEEDED" -> Unit
            else -> {
                speechStream = null
                narrationJob?.cancel()
                if (stream.voice && !stream.interrupted && _state.value.ttsEnabled) failureNotice(task)?.let { say(it) }
                return
            }
        }
        // 转入后台时告诉用户一声，之后这一轮不再朗读，完成时由后台通报负责。
        if (task.visible && stream.voice && !stream.promotedAnnounced && !stream.interrupted && _state.value.ttsEnabled) {
            stream.promotedAnnounced = true
            stream.muted = true
            say("这件事要多花点时间，我转到后台继续，完成后告诉你。")
        }
        // 被打断或预算用尽后只等待回合结束。
        if (stream.muted || task.visible || !_state.value.ttsEnabled) { if (finished) speechStream = null; return }
        val answer = task.answer
        // 工具调用前后的每一轮生成都从头累计文本；前缀不再匹配说明进入了新一轮。
        if (!answer.startsWith(stream.lastAnswer.take(stream.consumed))) { stream.consumed = 0; stream.inCode = false }
        stream.lastAnswer = answer
        val budget = if (_state.value.ttsFullReply) Int.MAX_VALUE else SPEECH_BUDGET_CHARS
        while (!stream.muted) {
            val pending = answer.substring(stream.consumed)
            val first = stream.spokenChars == 0
            val minimum = if (first) 5 else 12
            // 句末标点优先；首段连逗号也算，让第一声尽早出来。
            var cut = pending.indexOfFirstFrom(minimum - 1) { it in "。！？!?\n；" || (first && it in "，,：:") } + 1
            if (cut <= 0 || cut > SPEECH_CHUNK_CHARS + 10) {
                cut = when {
                    pending.length >= SPEECH_CHUNK_CHARS -> pending.take(SPEECH_CHUNK_CHARS + 10).indexOfLast { it in "，,、：:；。！？!? \n" }.let { if (it >= minimum - 1) it + 1 else SPEECH_CHUNK_CHARS }
                    finished && pending.isNotEmpty() -> pending.length
                    else -> 0
                }
            }
            if (cut <= 0) break
            val raw = pending.take(cut)
            stream.consumed += cut
            // 代码块整体跳过：围栏成对出现，奇数个说明进入或离开了代码块。
            val fences = Regex("```").findAll(raw).count()
            val wasInCode = stream.inCode
            if (fences % 2 == 1) stream.inCode = !stream.inCode
            if (wasInCode || fences > 0) continue
            val chunk = speakable(raw)
            if (chunk.isBlank()) continue
            val remaining = budget - stream.spokenChars
            val text = if (chunk.length <= remaining) chunk else {
                stream.muted = true
                chunk.take(remaining).let { head -> head.take(head.indexOfLast { it in "。！？!?；，," } + 1).ifBlank { head } }
            }
            if (text.isBlank()) break
            stream.spokenChars += text.length
            if (!say(text)) stream.muted = true
        }
        if (finished) {
            speechStream = null
            narrationJob?.cancel()
            // 语音提问的回答播完后接着听，不用再喊唤醒词；回答若已提前播完，立即进入。
            if (stream.voice && !stream.interrupted && stream.spokenChars > 0) {
                followUpPending = true
                if (!TtsManager.isSpeaking.value) onSpeechIdle()
            }
        }
    }

    /**
     * 后台任务、定时任务在本次运行中由未完成变为完成时语音通报一句；定时任务直接念出结果（多为提醒）。
     * 正在对话时先排队，回到待机后再播。
     */
    private fun announceFinishedTasks(tasks: List<com.voice.actonsay.tasks.TaskRecord>) {
        for (task in tasks) {
            val previous = taskStates.put(task.id, task.state)
            if (!task.visible || previous == null || previous == task.state) continue
            if (task.state !in setOf("SUCCEEDED", "FAILED") || !announcedTasks.add(task.id)) continue
            if (!_state.value.announceBackground || !_state.value.ttsEnabled) continue
            val goal = speakable(task.goal).take(24)
            val text = when {
                task.source == "automation" && task.state == "SUCCEEDED" -> speakable(task.answer).take(SPEECH_BUDGET_CHARS).ifBlank { "定时任务完成了：$goal" }
                task.state == "SUCCEEDED" -> "后台任务完成了：$goal。结果在任务页。"
                else -> "后台任务没有完成：$goal。"
            }
            pendingAnnouncements.addLast(text)
        }
        flushAnnouncements()
    }

    /** 把一段话放进朗读队列；用户正在说话或识别中时不抢话。 */
    private fun say(text: String): Boolean {
        val st = _state.value
        if (st.engineState != EngineState.WAITING_WAKEWORD && st.engineState != EngineState.IDLE && st.engineState != EngineState.SPEAKING_TTS) return false
        if (st.engineState != EngineState.SPEAKING_TTS) _state.value = st.copy(engineState = EngineState.SPEAKING_TTS)
        TtsManager.enqueue(text) { scope.launch { onSpeechIdle() } }
        speechStream?.lastSpokenAt = System.currentTimeMillis()
        return true
    }

    /** 朗读队列播完：需要时进入连续对话，否则回到待机并补播积压的后台通报。 */
    private fun onSpeechIdle() {
        // 上一段的完成回调可能排在新播放之后，不让它把新播放切回待机。
        if (TtsManager.isSpeaking.value) return
        val st = _state.value
        val speaking = st.engineState == EngineState.SPEAKING_TTS
        if (followUpPending && speechStream == null) {
            followUpPending = false
            if (st.followUpEnabled && st.isListening && (speaking || st.engineState == EngineState.WAITING_WAKEWORD)) {
                beginFollowUp()
                return
            }
        }
        if (speaking) returnToStandby()
        flushAnnouncements()
    }

    private fun flushAnnouncements() {
        if (pendingAnnouncements.isEmpty() || speechStream != null) return
        val st = _state.value
        if (st.engineState != EngineState.WAITING_WAKEWORD && st.engineState != EngineState.IDLE) return
        while (pendingAnnouncements.isNotEmpty()) say(pendingAnnouncements.removeFirst())
    }

    /** 语音回合较久没出声时，按工具上报的当前步骤播一句进度，让用户知道还在干活。 */
    private fun startNarration(taskId: String) {
        narrationJob?.cancel()
        narrationJob = scope.launch {
            var lastPhrase = ""
            var lastNarratedAt = 0L
            while (true) {
                delay(1000)
                val stream = speechStream?.takeIf { it.taskId == taskId } ?: break
                if (stream.interrupted || stream.muted) break
                val task = TaskRuntime.tasks.value.firstOrNull { it.id == taskId } ?: continue
                if (task.visible || task.state !in setOf("QUEUED", "RUNNING")) break
                val now = System.currentTimeMillis()
                if (TtsManager.isSpeaking.value || now - stream.lastSpokenAt < NARRATION_SILENCE_MS) continue
                val phrase = narrationFor(task.progress)
                // 同一阶段不反复念，除非已经安静了很久。
                if (phrase == lastPhrase && now - lastNarratedAt < NARRATION_REPEAT_MS) continue
                if (say(phrase)) { lastPhrase = phrase; lastNarratedAt = now }
            }
        }
    }

    /** 语音回合没有完成时说一句原因；用户主动取消、已转后台（由后台通报负责）时不说。 */
    private fun failureNotice(task: com.voice.actonsay.tasks.TaskRecord): String? {
        if (task.visible) return null
        return when (task.state) {
            "FAILED" -> if (NETWORK_HINTS.any { task.error.contains(it, ignoreCase = true) }) "网络好像不通，这次没能完成。"
                else "这次没能完成，原因显示在屏幕上。"
            "RESULT_UNKNOWN" -> "有一步操作的结果不确定，请到任务页核实。"
            else -> null
        }
    }

    /** 按句切分重播文本，长段落也能尽早出声。 */
    private fun sentences(text: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        for (ch in text) {
            current.append(ch)
            if ((ch in "。！？!?；\n" && current.length >= 8) || current.length >= REPLAY_SEGMENT_CHARS) {
                result += current.toString()
                current.clear()
            }
        }
        if (current.isNotBlank()) result += current.toString()
        return result.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun narrationFor(progress: String): String = when {
        progress.startsWith("搜索") -> "正在搜索，请稍等。"
        progress.startsWith("读取") -> "正在读取网页。"
        progress.contains("浏览器") -> "正在浏览网页。"
        progress.contains("虚拟屏") -> "正在手机上操作。"
        progress.contains("Debian") -> "正在容器里执行，请稍等。"
        progress.contains("音乐") || progress.contains("歌") -> "正在找歌。"
        progress.contains("提醒") || progress.contains("计时") || progress.contains("闹钟") -> "正在设置。"
        progress.isBlank() || progress == "任务开始" || progress.startsWith("思考") -> "还在想，请稍等。"
        else -> "还在处理，请稍等。"
    }

    private inline fun String.indexOfFirstFrom(start: Int, predicate: (Char) -> Boolean): Int {
        for (index in start.coerceAtLeast(0) until length) if (predicate(this[index])) return index
        return -1
    }

    /** 去掉 Markdown 标记、表格和代码块，只留适合朗读的文字。 */
    private fun speakable(markdown: String): String = markdown.lineSequence()
        .filterNot { line -> line.trimStart().let { it.startsWith("|") || it.startsWith("```") || it.matches(Regex("[-*_ ]{3,}")) } }
        .joinToString("\n")
        .replace(Regex("!?\\[([^\\]]*)]\\([^)]*\\)"), "$1")
        .replace(Regex("https?://\\S+"), "")
        .replace(Regex("[`#*_>~]"), "")
        .replace(Regex("(?m)^\\s*[-+]\\s+"), "")
        .replace(Regex("(?<![。！？!?；，,：:])\\s*\\n\\s*"), "，")
        .replace(Regex("\\s*\\n\\s*"), "")
        .replace(Regex("[，,]{2,}"), "，")
        .trim().trim('，', ',')

    companion object {
        private const val SPEECH_BUDGET_CHARS = 160
        private const val SPEECH_CHUNK_CHARS = 30
        private const val NARRATION_SILENCE_MS = 8000L
        private const val NARRATION_REPEAT_MS = 20000L
        private const val REPLAY_CHARS = 400
        private const val REPLAY_SEGMENT_CHARS = 60
        private val NETWORK_HINTS = listOf("Unable to resolve host", "UnknownHost", "timeout", "timed out", "Failed to connect",
            "Connection reset", "SSL", "网络", "连接")
    }
}
