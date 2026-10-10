package com.voice.actonsay.asr

import com.voice.actonsay.tasks.TaskRecord
import com.voice.actonsay.tasks.TaskStates

/**
 * OuO 风格表情状态。
 */
enum class FaceMood(val kaomoji: String, val label: String) {
    NORMAL("0u0", "平静"),
    HAPPY("^w^", "开心"),
    ENJOY("˘▾˘", "舒服"),
    LISTENING("OwO", "倾听中"),
    THINKING("◔_◔", "思考中"),
    SPEAKING("0o0", "播报中"),
    SLEEPY("-.-", "犯困"),
    DIZZY("@_@", "晕乎乎"),
    ANGRY(">_<", "不高兴"),
    SAD("TwT", "难过"),
    SHY("//u//", "害羞"),
    SURPRISED("O_O", "惊讶"),
    PROUD(">v<", "得意"),
    MUSIC("~0u0~", "听歌中")
}

/**
 * 视觉形象类型：支持经典的呼吸光球或 OuO 互动表情。
 */
enum class AvatarStyle {
    OUO,
    ORB
}

/**
 * 根据对话内容、语音状态、任务进展和环境感知推断当前表情。
 */
object EmotionAnalyzer {
    private val HAPPY_WORDS = listOf(
        "哈哈", "嘿嘿", "开心", "太棒了", "太好了", "喜欢", "成功", "搞定", "完成了", "恭喜",
        "不客气", "不用谢", "荣幸", "愉快", "笑话", "有趣", "好玩", "棒", "赞", "爱你", "么么"
    )
    private val PROUD_WORDS = listOf(
        "交给我", "没问题", "小意思", "包在我身上", "已经帮你", "已为您", "轻松", "搞定啦"
    )
    private val SHY_WORDS = listOf(
        "害羞", "不好意思", "夸奖", "脸红", "谬赞", "过奖", "讨厌啦", "哎呀"
    )
    private val SAD_WORDS = listOf(
        "抱歉", "对不起", "遗憾", "失败", "出错", "无法", "做不到", "难过", "伤心", "委屈", "超时", "断网"
    )
    private val ANGRY_WORDS = listOf(
        "生气", "愤怒", "不许", "警告", "禁止", "可恶", "哼"
    )
    private val SURPRISED_WORDS = listOf(
        "哇", "天哪", "居然", "竟然", "没想到", "真的吗", "不可思议", "震惊", "！？", "!?"
    )

    /**
     * 从单条用户或助手文本中提取情绪倾向；无明显情绪时返回 null。
     */
    fun analyzeText(text: String, isError: Boolean = false): FaceMood? {
        if (isError) return FaceMood.SAD
        if (text.isBlank()) return null
        val sample = text.takeLast(240)
        return when {
            SHY_WORDS.any { sample.contains(it) } -> FaceMood.SHY
            PROUD_WORDS.any { sample.contains(it) } -> FaceMood.PROUD
            HAPPY_WORDS.any { sample.contains(it) } -> FaceMood.HAPPY
            SAD_WORDS.any { sample.contains(it) } -> FaceMood.SAD
            ANGRY_WORDS.any { sample.contains(it) } -> FaceMood.ANGRY
            SURPRISED_WORDS.any { sample.contains(it) } -> FaceMood.SURPRISED
            else -> null
        }
    }

    /**
     * 综合瞬时手势/传感器反应、引擎状态、音乐播放与最近一轮对话内容，决定当前展示的主表情。
     */
    fun resolveMood(
        state: AsrState,
        musicPlaying: Boolean,
        transientMood: FaceMood?,
        isDarkEnvironment: Boolean,
        isCharging: Boolean,
        isLowBattery: Boolean,
        activeTask: TaskRecord? = null
    ): FaceMood {
        // 1. 最高优先级：用户触摸、摸头、摇晃等瞬时交互反应
        if (transientMood != null) return transientMood

        // 2. 语音引擎实时状态
        when (state.engineState) {
            EngineState.ACTIVE_LISTENING -> return FaceMood.LISTENING
            EngineState.PROCESSING_FINAL, EngineState.THINKING_AI -> return FaceMood.THINKING
            EngineState.SPEAKING_TTS -> {
                val lastAssistant = state.messages.lastOrNull { it.role == "assistant" }
                val textMood = lastAssistant?.let { analyzeText(it.content, it.isError) }
                return textMood ?: FaceMood.SPEAKING
            }
            EngineState.IDLE, EngineState.WAITING_WAKEWORD -> Unit
        }

        // 3. 后台/当前任务执行状态
        if (activeTask != null) {
            when (activeTask.state) {
                TaskStates.RUNNING -> return FaceMood.THINKING
                TaskStates.WAITING_CONFIRMATION, TaskStates.RESULT_UNKNOWN -> return FaceMood.SURPRISED
                TaskStates.FAILED -> return FaceMood.SAD
            }
        }

        // 4. 正在播放音乐时优先展示听歌律动状态
        if (musicPlaying) return FaceMood.MUSIC

        // 5. 最近一条消息在 12 秒内产生时，保留对话余韵表情
        val recentMsg = state.messages.lastOrNull()
        if (recentMsg != null && System.currentTimeMillis() - recentMsg.timestamp < 12_000L) {
            analyzeText(recentMsg.content, recentMsg.isError)?.let { return it }
        }

        // 6. 传感器与设备环境状态
        if (isLowBattery && !isCharging) return FaceMood.SAD
        if (isDarkEnvironment || state.engineState == EngineState.IDLE) return FaceMood.SLEEPY
        if (isCharging) return FaceMood.HAPPY

        return FaceMood.NORMAL
    }
}
