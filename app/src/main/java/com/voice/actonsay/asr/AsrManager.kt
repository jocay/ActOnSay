package com.voice.actonsay.asr

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.voice.actonsay.agent.AgentConfig
import com.voice.actonsay.agent.AgentPrompt
import com.voice.actonsay.agent.AgentRunner
import com.voice.actonsay.agent.DeviceHardening
import com.voice.actonsay.agent.RootShell
import com.voice.actonsay.ai.ChatTurn
import com.voice.actonsay.ai.FastDecisionClient
import com.voice.actonsay.ai.OverlapDecision
import com.voice.actonsay.ai.OpenAiClient
import com.voice.actonsay.bridge.BridgeClient
import com.voice.actonsay.config.SettingsRepository
import com.voice.actonsay.tasks.CredentialRedactor
import com.voice.actonsay.tasks.TaskRepository
import com.voice.actonsay.tasks.TaskRuntime
import com.voice.actonsay.tts.MimoTtsClient
import com.voice.actonsay.tts.TtsEngineType
import com.voice.actonsay.tts.TtsManager
import com.voice.actonsay.media.MusicPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class EngineState {
    IDLE,               // 录音服务未启动
    WAITING_WAKEWORD,   // 本地待机：KWS 监听唤醒词中
    ACTIVE_LISTENING,   // 已唤醒：流式推送到云端 ASR
    PROCESSING_FINAL,   // 判停断句中，等待 ASR 最终结果
    THINKING_AI,        // 已转录，正在请求 OpenAI 兼容大模型
    SPEAKING_TTS        // AI 回复完成，正在进行语音播报 (静默拾音防回声)
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String, // "user", "assistant"
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val wakeWord: String = "",
    val isLoading: Boolean = false,
    val isError: Boolean = false,
    val hitKeywords: List<String> = emptyList(),
    /** 智能体模式下本条回复执行过的工具步骤（已渲染的一行文本）。 */
    val steps: List<String> = emptyList(),
    val taskId: String = ""
)

data class AsrState(
    val isListening: Boolean = false,
    val engineState: EngineState = EngineState.IDLE,
    val lastWakeWord: String = "",
    val currentText: String = "",
    val lastFinalText: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val apiKey: String = "",
    val resourceId: String = VolcengineStreamAsrClient.RESOURCE_ID_BIGASR_1,
    val customWakeWords: Set<String> = emptySet(),
    val errorMessage: String = "",

    // OpenAI 兼容配置
    val aiEnabled: Boolean = true,
    val aiBaseUrl: String = "https://api.deepseek.com/v1",
    val aiApiKey: String = "",
    val aiModel: String = "deepseek-chat",
    val aiSystemPrompt: String = AsrManager.DEFAULT_SYSTEM_PROMPT,
    val aiStreaming: Boolean = true,
    val aiContextTurns: Int = 6,
    val aiVisionEnabled: Boolean = false,

    // 智能体（工具调用）
    val agentEnabled: Boolean = true,
    /** 正在执行的工具进度（THINKING_AI 时显示） */
    val agentActivity: String = "",
    val hasRoot: Boolean = false,
    val bridgeBaseUrl: String = "http://127.0.0.1:18765",
    val bridgeToken: String = "",
    val bridgeContainer: String = "container",
    val debianExecutionAllowed: Boolean = false,
    val androidRootAllowed: Boolean = false,
    val taskMaxSteps: Int = 30,
    val taskTimeoutMinutes: Int = 30,
    val longTermMemory: String = "",
    val keywordTemplates: Map<String, String> = emptyMap(),
    /** 快速决策模型（TypeSafe Jev）：上一轮未结束时判断新消息是排队、打断还是补充；关闭或未配置时一律排队。 */
    val fastDecisionEnabled: Boolean = true,
    val fastDecisionApiKey: String = "",
    /** 唤醒词命中后先语音应答（“我在”），应答播完再开始收音。 */
    val wakeAckEnabled: Boolean = true,
    /** 朗读完整回复（可随时打断）；关闭时只读前 160 字。 */
    val ttsFullReply: Boolean = true,
    /** 可选的 HTTP 代理（host:port），仅在联网工具直连失败时使用。 */
    val webProxy: String = "",
    val braveApiKey: String = "",
    val exaApiKey: String = "",
    /** 搜索服务优先级：auto 按已配置的密钥依次尝试。 */
    val searchProvider: String = "auto",
    /** 打断词：播报、执行任务或播放音乐时说出即停止播报并开始收音。 */
    val interruptWords: Set<String> = AsrManager.DEFAULT_INTERRUPT_WORDS,
    /** 语音回答播完后自动再听几秒，不用再喊唤醒词。 */
    val followUpEnabled: Boolean = true,
    /** 语音提问的回合较久没出声时，按当前步骤播报进度。 */
    val progressNarration: Boolean = true,
    /** 后台任务和定时任务完成时语音通报。 */
    val announceBackground: Boolean = true,
    /** 常驻交互：系统一直保持交互、面板断电，虚拟屏随时可用。 */
    val alwaysInteractive: Boolean = false,
    val musicApiKey: String = "",
    val musicQuality: String = "exhigh",
    /** 打电话、发短信每次都要界面确认。 */
    val phoneActionsConfirm: Boolean = true,

    // TTS 语音播报配置
    val ttsEnabled: Boolean = true,
    val ttsEngine: TtsEngineType = TtsEngineType.MIMO,
    val mimoApiKey: String = MimoTtsClient.DEFAULT_KEY,
    val mimoVoice: String = MimoTtsClient.DEFAULT_VOICE,
    val mimoStylePrompt: String = "",
    val ttsSpeechRate: Float = 1.0f,
    val ttsPitch: Float = 1.0f,

    // 外观与行为
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val autoStartOnLaunch: Boolean = false,
    val keepScreenOn: Boolean = false,

    // 统计
    val totalWakeCount: Int = 0,
    val totalTurnCount: Int = 0
) {
    /** 语音链路是否已具备 AI 回复条件。 */
    val aiReady: Boolean get() = aiEnabled && aiBaseUrl.isNotBlank() && aiApiKey.isNotBlank()
    val asrReady: Boolean get() = apiKey.isNotBlank()
}

object AsrManager {
    private const val TAG = "AsrManager"
    private const val SAMPLE_RATE = 16000
    private const val VAD_WINDOW_SIZE = 512

    const val DEFAULT_SYSTEM_PROMPT =
        "你是 ActOnSay 随身智能助手。按用户目标完成任务，清楚说明真实结果与文件产物。屏幕回答可使用 Markdown 和完整细节。Linux 工作使用 Debian 工具，手机操作使用 Android 工具。不要将未验证的执行当作成功。"
    const val MANUAL_WAKE_WORD = "点击唤醒"
    const val MANUAL_INPUT_WORD = "文字输入"
    const val MAX_MESSAGES = 300

    val DEFAULT_WAKE_WORDS = setOf("你好小智", "小爱同学")
    val DEFAULT_INTERRUPT_WORDS = setOf("等一下")
    const val FOLLOW_UP_WORD = "连续对话"
    const val INTERRUPT_SOURCE = "语音打断"

    private const val PREFS_NAME = "actonsay_prefs"
    private const val KEY_API_KEY = "volc_api_key"
    private const val KEY_RESOURCE_ID = "volc_resource_id"
    private const val KEY_WAKE_WORDS = "custom_wake_words"
    private const val KEY_ACTION_KEYWORDS = "action_keywords"

    private const val KEY_AI_ENABLED = "ai_enabled"
    private const val KEY_AI_BASE_URL = "ai_base_url"
    private const val KEY_AI_API_KEY = "ai_api_key"
    private const val KEY_AI_MODEL = "ai_model"
    private const val KEY_AI_SYSTEM_PROMPT = "ai_system_prompt"
    private const val KEY_AI_STREAMING = "ai_streaming"
    private const val KEY_AI_CONTEXT_TURNS = "ai_context_turns"
    private const val KEY_AGENT_ENABLED = "agent_enabled"

    private const val KEY_TTS_ENABLED = "tts_enabled"
    private const val KEY_TTS_ENGINE = "tts_engine"
    private const val KEY_MIMO_API_KEY = "mimo_api_key"
    private const val KEY_MIMO_VOICE = "mimo_voice"
    private const val KEY_MIMO_STYLE = "mimo_style"
    private const val KEY_TTS_SPEECH_RATE = "tts_speech_rate"
    private const val KEY_TTS_PITCH = "tts_pitch"

    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_DYNAMIC_COLOR = "dynamic_color"
    private const val KEY_AUTO_START = "auto_start"
    private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
    private const val KEY_WAKE_COUNT = "stat_wake_count"
    private const val KEY_TURN_COUNT = "stat_turn_count"

    private const val EVENTS_FILE = "keyword_events.json"

    private const val SPEECH_SILENCE_TIMEOUT_MS = 800L
    private const val WAKE_NO_SPEECH_TIMEOUT_MS = 6000L
    private const val MANUAL_NO_SPEECH_TIMEOUT_MS = 10000L

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var prefs: SharedPreferences? = null
    private var settingsRepository: SettingsRepository? = null
    private var credentialError = ""
    private var appContext: Context? = null
    private var filesDir: File? = null

    private val kwsManager = KwsManager()
    private val volcClient = VolcengineStreamAsrClient()
    private var vad: Vad? = null
    private var pendingVadSamples = FloatArray(0)

    val keywordDetector = KeywordDetector()

    private val _state = MutableStateFlow(AsrState())
    val state: StateFlow<AsrState> = _state.asStateFlow()

    /** 麦克风实时电平 0..1，独立于 [state] 以避免高频重组整个界面。 */
    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    private var hasSpokenInSession = false
    private var lastSpeechTimestamp = 0L
    private var sessionStartTimestamp = 0L
    private var sessionNoSpeechTimeoutMs = WAKE_NO_SPEECH_TIMEOUT_MS
    private var saveHistoryJob: Job? = null
    private var currentAiJob: Job? = null
    private var taskObserver: Job? = null
    private var foregroundTaskId: String? = null
    private val processStartedAt = System.currentTimeMillis()
    private val bubbleTaskIds = mutableSetOf<String>()

    /** 唤醒应答播放期间丢弃拾音，避免把自己的应答识别成用户的话。 */
    @Volatile private var ackPlaying = false
    @Volatile private var listenResumeAt = 0L
    private var ackToken = 0

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
        var lastSpokenAt = System.currentTimeMillis()
    }
    private var speechStream: SpeechStream? = null
    /** 语音回合的回答已全部入队，播完后进入连续对话。 */
    private var followUpPending = false
    private var narrationJob: Job? = null
    /** 有前台回合在执行；录音线程据此决定是否监听打断词，避免每帧扫描任务列表。 */
    @Volatile private var foregroundBusy = false
    private val announcedTasks = mutableSetOf<String>()
    private val taskStates = mutableMapOf<String, String>()
    private val pendingAnnouncements = ArrayDeque<String>()
    private const val VOICE_TURN_HINT = "\n本条消息来自语音，回答会被完整朗读：用口语化的句子先直接给出结论，" +
        "除非用户要求详细否则保持简短，不要使用表格、代码块和长列表。"
    private const val SPEECH_BUDGET_CHARS = 160
    private const val SPEECH_CHUNK_CHARS = 30
    private const val FOLLOW_UP_TIMEOUT_MS = 5000L
    private const val NARRATION_SILENCE_MS = 8000L
    private const val NARRATION_REPEAT_MS = 20000L

    @Synchronized
    fun initControlPlane(context: Context) {
        TtsManager.init(context)

        if (prefs == null) {
            credentialError = ""
            val repository = SettingsRepository(context)
            settingsRepository = repository
            val p = repository.preferences
            prefs = p
            appContext = context.applicationContext
            filesDir = context.applicationContext.filesDir

            val savedTtsEngine = runCatching {
                TtsEngineType.valueOf(p.getString(KEY_TTS_ENGINE, TtsEngineType.MIMO.name) ?: TtsEngineType.MIMO.name)
            }.getOrDefault(TtsEngineType.MIMO)
            val savedThemeMode = runCatching {
                ThemeMode.valueOf(p.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name)
            }.getOrDefault(ThemeMode.SYSTEM)

            val savedMimoKey = readCredential(KEY_MIMO_API_KEY)
            val savedMimoVoice = p.getString(KEY_MIMO_VOICE, MimoTtsClient.DEFAULT_VOICE) ?: MimoTtsClient.DEFAULT_VOICE
            val savedMimoStyle = p.getString(KEY_MIMO_STYLE, "") ?: ""
            val savedTtsRate = p.getFloat(KEY_TTS_SPEECH_RATE, 1.0f)
            val savedTtsPitch = p.getFloat(KEY_TTS_PITCH, 1.0f)

            TtsManager.engineType = savedTtsEngine
            TtsManager.configureMimo(savedMimoKey, savedMimoVoice, savedMimoStyle)
            TtsManager.setSpeechRate(savedTtsRate)
            TtsManager.setPitch(savedTtsPitch)

            keywordDetector.setKeywords(p.getStringSet(KEY_ACTION_KEYWORDS, emptySet()) ?: emptySet())

            _state.value = _state.value.copy(
                apiKey = readCredential(KEY_API_KEY),
                resourceId = p.getString(KEY_RESOURCE_ID, VolcengineStreamAsrClient.RESOURCE_ID_BIGASR_1)
                    ?: VolcengineStreamAsrClient.RESOURCE_ID_BIGASR_1,
                customWakeWords = p.getStringSet(KEY_WAKE_WORDS, DEFAULT_WAKE_WORDS) ?: DEFAULT_WAKE_WORDS,
                aiEnabled = p.getBoolean(KEY_AI_ENABLED, true),
                aiBaseUrl = p.getString(KEY_AI_BASE_URL, "https://api.deepseek.com/v1") ?: "https://api.deepseek.com/v1",
                aiApiKey = readCredential(KEY_AI_API_KEY),
                aiModel = p.getString(KEY_AI_MODEL, "deepseek-chat") ?: "deepseek-chat",
                aiSystemPrompt = p.getString(KEY_AI_SYSTEM_PROMPT, DEFAULT_SYSTEM_PROMPT) ?: DEFAULT_SYSTEM_PROMPT,
                aiStreaming = p.getBoolean(KEY_AI_STREAMING, true),
                aiContextTurns = p.getInt(KEY_AI_CONTEXT_TURNS, 6),
                aiVisionEnabled = p.getBoolean("ai_vision_enabled", false),
                agentEnabled = p.getBoolean(KEY_AGENT_ENABLED, true),
                bridgeBaseUrl = p.getString("bridge_base_url", "http://127.0.0.1:18765").orEmpty(),
                bridgeToken = readCredential("bridge_token"),
                bridgeContainer = p.getString("bridge_container", "container").orEmpty(),
                androidRootAllowed = p.getBoolean("android_root_allowed", false),
                debianExecutionAllowed = p.getBoolean("debian_execution_allowed", false),
                taskMaxSteps = p.getInt("task_max_steps", 30).coerceIn(1, 200),
                taskTimeoutMinutes = p.getInt("task_timeout_minutes", 30).coerceIn(1, 1440),
                longTermMemory = p.getString("long_term_memory", "").orEmpty(),
                keywordTemplates = readKeywordTemplates(p.getString("keyword_templates", "{}").orEmpty()),
                fastDecisionEnabled = p.getBoolean("fast_decision_enabled", true),
                fastDecisionApiKey = readCredential("fast_decision_api_key"),
                wakeAckEnabled = p.getBoolean("wake_ack_enabled", true),
                ttsFullReply = p.getBoolean("tts_full_reply", true),
                interruptWords = p.getStringSet("interrupt_words", DEFAULT_INTERRUPT_WORDS) ?: DEFAULT_INTERRUPT_WORDS,
                followUpEnabled = p.getBoolean("follow_up_enabled", true),
                progressNarration = p.getBoolean("progress_narration", true),
                announceBackground = p.getBoolean("announce_background", true),
                alwaysInteractive = p.getBoolean("always_interactive", false),
                musicApiKey = readCredential("music_api_key"),
                musicQuality = p.getString("music_quality", "exhigh").orEmpty().ifBlank { "exhigh" },
                phoneActionsConfirm = p.getBoolean("phone_actions_confirm", true),
                webProxy = p.getString("web_proxy", "").orEmpty(),
                braveApiKey = readCredential("brave_api_key"),
                exaApiKey = readCredential("exa_api_key"),
                searchProvider = p.getString("search_provider", "auto").orEmpty().ifBlank { "auto" },
                ttsEnabled = p.getBoolean(KEY_TTS_ENABLED, true),
                ttsEngine = savedTtsEngine,
                mimoApiKey = savedMimoKey,
                mimoVoice = savedMimoVoice,
                mimoStylePrompt = savedMimoStyle,
                ttsSpeechRate = savedTtsRate,
                ttsPitch = savedTtsPitch,
                themeMode = savedThemeMode,
                dynamicColor = p.getBoolean(KEY_DYNAMIC_COLOR, false),
                autoStartOnLaunch = p.getBoolean(KEY_AUTO_START, false),
                keepScreenOn = p.getBoolean(KEY_KEEP_SCREEN_ON, false),
                totalWakeCount = p.getInt(KEY_WAKE_COUNT, 0),
                totalTurnCount = p.getInt(KEY_TURN_COUNT, 0),
                errorMessage = credentialError
            )

            TaskRepository.init(context)
            TaskRuntime.init(context) { agentConfig() }
            MusicPlayer.init(context)
            com.voice.actonsay.agent.PowerKeeper.init(context)
            if (_state.value.alwaysInteractive) com.voice.actonsay.agent.PowerKeeper.setAlwaysInteractive(context, true)
            loadPersistedData()
        }

    }

    @Synchronized
    fun init(context: Context) {
        initControlPlane(context)

        AgentRunner.init(context)
        refreshRootStatus()

        kwsManager.init(context.assets)
        kwsManager.updateWakeWords(_state.value.customWakeWords)
        kwsManager.updateInterruptWords(_state.value.interruptWords)

        if (vad == null) {
            val vadConfig = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = "silero_vad.onnx",
                    threshold = 0.5f,
                    minSilenceDuration = 0.25f,
                    minSpeechDuration = 0.25f,
                    windowSize = VAD_WINDOW_SIZE,
                ),
                sampleRate = SAMPLE_RATE,
                numThreads = 1,
            )
            vad = Vad(assetManager = context.assets, config = vadConfig)
            Log.i(TAG, "Silero VAD initialized")
        }
    }

    // ---------------------------------------------------------------------
    // 持久化：对话历史 / 关键词命中记录
    // ---------------------------------------------------------------------

    private fun loadPersistedData() {
        val dir = filesDir ?: return
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val messages = runCatching { TaskRepository.loadMessages() }
                    .onFailure { Log.w(TAG, "Failed to load chat history", it) }
                    .getOrDefault(emptyList())
                val events = runCatching { readEvents(File(dir, EVENTS_FILE)) }
                    .onFailure { Log.w(TAG, "Failed to load keyword events", it) }
                    .getOrDefault(emptyList())
                messages to events
            }
            if (loaded.first.isNotEmpty()) {
                // 仅在期间没有产生新消息时合并历史，避免覆盖用户刚发出的消息
                _state.value = _state.value.copy(messages = (loaded.first + _state.value.messages).associateBy { it.id }.values.toList().takeLast(MAX_MESSAGES))
            }
            if (loaded.second.isNotEmpty() && keywordDetector.events.value.isEmpty()) {
                keywordDetector.setEvents(loaded.second)
            }
            Log.i(TAG, "Loaded ${loaded.first.size} messages and ${loaded.second.size} keyword events")
            observeTasks()

            // 之后任何命中记录的变化都会落盘
            keywordDetector.events.drop(1).collect { events ->
                withContext(Dispatchers.IO) {
                    runCatching { writeEvents(File(dir, EVENTS_FILE), events) }
                        .onFailure { Log.w(TAG, "Failed to save keyword events", it) }
                }
            }
        }
    }

    private fun readEvents(file: File): List<KeywordEvent> {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            KeywordEvent(
                id = o.optString("id", UUID.randomUUID().toString()),
                keyword = o.optString("kw", ""),
                fullText = o.optString("text", ""),
                timestamp = o.optLong("ts", 0L)
            )
        }
    }

    private fun writeEvents(file: File, events: List<KeywordEvent>) {
        val arr = JSONArray()
        events.takeLast(KeywordDetector.MAX_EVENTS).forEach { e ->
            arr.put(JSONObject().apply {
                put("id", e.id)
                put("kw", e.keyword)
                put("text", e.fullText)
                put("ts", e.timestamp)
            })
        }
        file.writeText(arr.toString())
    }

    /** 防抖落盘对话历史。 */
    private fun scheduleSaveHistory() {
        val dir = filesDir ?: return
        saveHistoryJob?.cancel()
        saveHistoryJob = scope.launch {
            delay(400)
            val snapshot = _state.value.messages
            withContext(Dispatchers.IO) {
                runCatching { TaskRepository.saveMessages(snapshot) }
                    .onFailure { Log.w(TAG, "Failed to save chat history", it) }
            }
        }
    }

    private fun updateMessages(transform: (List<ChatMessage>) -> List<ChatMessage>) {
        val updated = transform(_state.value.messages).takeLast(MAX_MESSAGES)
        if (updated == _state.value.messages) return
        _state.value = _state.value.copy(messages = updated)
        scheduleSaveHistory()
    }

    private fun readCredential(name: String): String = runCatching { settingsRepository?.getSecret(name).orEmpty() }
        .getOrElse {
            credentialError = "凭据无法解密，请重新配置；原数据已保留。"
            ""
        }

    private fun readKeywordTemplates(text: String): Map<String, String> = runCatching {
        val json = JSONObject(text)
        json.keys().asSequence().associateWith { json.getString(it) }
    }.getOrDefault(emptyMap())

    fun agentConfig(): AgentConfig {
        val st = _state.value
        val memory = st.longTermMemory.take(20000).let { if (it.isBlank()) "" else "\n用户明确保存的长期偏好与项目备注：\n$it" }
        return AgentConfig(st.aiBaseUrl, st.aiApiKey, st.aiModel,
            AgentPrompt.build(st.aiSystemPrompt, st.hasRoot) + memory,
            st.aiStreaming, maxSteps = st.taskMaxSteps, maxTaskSeconds = st.taskTimeoutMinutes * 60,
            supportsImages = st.aiVisionEnabled)
    }

    @Synchronized
    fun setBridgeConfig(baseUrl: String, token: String) {
        val normalized = BridgeClient.validateBaseUrl(baseUrl)
        settingsRepository?.putSecret("bridge_token", token.trim())
        prefs?.edit()?.putString("bridge_base_url", normalized)?.apply()
        _state.value = _state.value.copy(bridgeBaseUrl = normalized, bridgeToken = token.trim())
    }

    @Synchronized
    fun setBridgeContainer(name: String) {
        require(name.matches(Regex("[A-Za-z0-9_.-]{1,64}"))) { "无效的容器名称" }
        prefs?.edit()?.putString("bridge_container", name)?.apply()
        _state.value = _state.value.copy(bridgeContainer = name)
    }

    @Synchronized
    fun setExecutionPermissions(androidRoot: Boolean, debian: Boolean) {
        prefs?.edit()?.putBoolean("android_root_allowed", androidRoot)?.putBoolean("debian_execution_allowed", debian)?.apply()
        _state.value = _state.value.copy(androidRootAllowed = androidRoot, debianExecutionAllowed = debian)
    }

    @Synchronized
    fun setTaskBudget(steps: Int, minutes: Int) {
        val safeSteps = steps.coerceIn(1, 200)
        val safeMinutes = minutes.coerceIn(1, 1440)
        prefs?.edit()?.putInt("task_max_steps", safeSteps)?.putInt("task_timeout_minutes", safeMinutes)?.apply()
        _state.value = _state.value.copy(taskMaxSteps = safeSteps, taskTimeoutMinutes = safeMinutes)
    }

    @Synchronized
    fun setLongTermMemory(text: String) {
        val value = text.take(20000)
        prefs?.edit()?.putString("long_term_memory", value)?.apply()
        _state.value = _state.value.copy(longTermMemory = value)
    }

    @Synchronized
    fun setInterruptWords(words: Set<String>) {
        val cleaned = words.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        prefs?.edit()?.putStringSet("interrupt_words", cleaned)?.apply()
        _state.value = _state.value.copy(interruptWords = cleaned)
        kwsManager.updateInterruptWords(cleaned)
    }

    @Synchronized
    fun setVoiceExperience(followUp: Boolean, narration: Boolean, announce: Boolean) {
        prefs?.edit()?.putBoolean("follow_up_enabled", followUp)?.putBoolean("progress_narration", narration)
            ?.putBoolean("announce_background", announce)?.apply()
        _state.value = _state.value.copy(followUpEnabled = followUp, progressNarration = narration, announceBackground = announce)
    }

    @Synchronized
    fun setAlwaysInteractive(enabled: Boolean) {
        prefs?.edit()?.putBoolean("always_interactive", enabled)?.apply()
        _state.value = _state.value.copy(alwaysInteractive = enabled)
        appContext?.let { com.voice.actonsay.agent.PowerKeeper.setAlwaysInteractive(it, enabled) }
    }

    @Synchronized
    fun setMusic(apiKey: String, quality: String) {
        settingsRepository?.putSecret("music_api_key", apiKey.trim())
        val level = quality.takeIf { it in setOf("standard", "exhigh", "lossless", "hires") } ?: "exhigh"
        prefs?.edit()?.putString("music_quality", level)?.apply()
        _state.value = _state.value.copy(musicApiKey = apiKey.trim(), musicQuality = level)
    }

    @Synchronized
    fun setPhoneActionsConfirm(enabled: Boolean) {
        prefs?.edit()?.putBoolean("phone_actions_confirm", enabled)?.apply()
        _state.value = _state.value.copy(phoneActionsConfirm = enabled)
    }

    @Synchronized
    fun setTtsFullReply(enabled: Boolean) {
        prefs?.edit()?.putBoolean("tts_full_reply", enabled)?.apply()
        _state.value = _state.value.copy(ttsFullReply = enabled)
    }

    /** 代理留空表示不使用；格式不对时保持原值并抛出，由界面提示。 */
    @Synchronized
    fun setWebAccess(proxy: String, braveKey: String, exaKey: String, provider: String) {
        val normalized = proxy.trim().removePrefix("http://").trimEnd('/')
        require(normalized.isEmpty() || Regex("[A-Za-z0-9.-]{1,253}:\\d{1,5}").matches(normalized)) { "代理格式应为 主机:端口" }
        settingsRepository?.putSecret("brave_api_key", braveKey.trim())
        settingsRepository?.putSecret("exa_api_key", exaKey.trim())
        val choice = provider.takeIf { it in setOf("auto", "brave", "exa") } ?: "auto"
        prefs?.edit()?.putString("web_proxy", normalized)?.putString("search_provider", choice)?.apply()
        _state.value = _state.value.copy(webProxy = normalized, braveApiKey = braveKey.trim(), exaApiKey = exaKey.trim(), searchProvider = choice)
    }

    @Synchronized
    fun setWakeAck(enabled: Boolean) {
        prefs?.edit()?.putBoolean("wake_ack_enabled", enabled)?.apply()
        _state.value = _state.value.copy(wakeAckEnabled = enabled)
    }

    @Synchronized
    fun setFastDecision(enabled: Boolean, apiKey: String) {
        settingsRepository?.putSecret("fast_decision_api_key", apiKey.trim())
        prefs?.edit()?.putBoolean("fast_decision_enabled", enabled)?.apply()
        _state.value = _state.value.copy(fastDecisionEnabled = enabled, fastDecisionApiKey = apiKey.trim())
    }

    @Synchronized
    fun bindKeywordTemplate(keyword: String, target: String) {
        val updated = _state.value.keywordTemplates.toMutableMap().apply {
            if (target.isBlank()) remove(keyword) else put(keyword, target.trim())
        }.toMap()
        prefs?.edit()?.putString("keyword_templates", JSONObject(updated).toString())?.apply()
        _state.value = _state.value.copy(keywordTemplates = updated)
    }

    /** 只让模型返回虚拟工具调用，不执行设备或容器操作。 */
    suspend fun checkModelTools(): JSONObject {
        val st = _state.value
        require(st.aiReady) { "请先配置 AI 模型" }
        val tools = JSONArray().put(JSONObject().put("type", "function").put("function",
            JSONObject().put("name", "configuration_probe").put("description", "用于配置检查的虚拟函数")
                .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()))))
        val response = OpenAiClient.complete(st.aiBaseUrl, st.aiApiKey, st.aiModel,
            OpenAiClient.buildMessages("请调用 configuration_probe 一次，用于验证工具调用协议。", emptyList(), "检查工具调用能力"), tools, false, null).getOrThrow()
        return JSONObject().put("supported", response.toolCalls.any { it.name == "configuration_probe" })
            .put("model", st.aiModel).put("detail", if (response.hasToolCalls) "模型返回了工具调用" else "模型未返回工具调用，请检查模型与接口设置")
    }

    /** 导出不含凭据的配置快照：凭据只标记是否已配置，自由文本经过脱敏。 */
    fun exportConfigRedacted(): String {
        val st = _state.value
        fun configured(value: String) = if (value.isBlank()) "未配置" else "已配置"
        return JSONObject()
            .put("凭据", JSONObject().put("火山引擎 ASR", configured(st.apiKey)).put("AI 模型", configured(st.aiApiKey))
                .put("MiMo TTS", configured(st.mimoApiKey)).put("Debian 配对令牌", configured(st.bridgeToken))
                .put("快速决策模型", configured(st.fastDecisionApiKey)))
            .put("快速决策", JSONObject().put("enabled", st.fastDecisionEnabled))
            .put("语音识别", JSONObject().put("resourceId", st.resourceId).put("wakeWords", JSONArray(st.customWakeWords.sorted())))
            .put("AI", JSONObject().put("enabled", st.aiEnabled).put("baseUrl", CredentialRedactor.text(st.aiBaseUrl)).put("model", st.aiModel)
                .put("streaming", st.aiStreaming).put("contextTurns", st.aiContextTurns).put("vision", st.aiVisionEnabled)
                .put("agentEnabled", st.agentEnabled).put("systemPrompt", CredentialRedactor.text(st.aiSystemPrompt)))
            .put("执行环境", JSONObject().put("bridgeBaseUrl", st.bridgeBaseUrl).put("container", st.bridgeContainer)
                .put("androidRootAllowed", st.androidRootAllowed).put("debianExecutionAllowed", st.debianExecutionAllowed)
                .put("taskMaxSteps", st.taskMaxSteps).put("taskTimeoutMinutes", st.taskTimeoutMinutes))
            .put("自动化", JSONObject().put("keywords", JSONArray(keywordDetector.keywords.value.sorted()))
                .put("keywordTemplates", CredentialRedactor.json(JSONObject(st.keywordTemplates))))
            .put("长期备注", CredentialRedactor.text(st.longTermMemory))
            .put("播报", JSONObject().put("enabled", st.ttsEnabled).put("engine", st.ttsEngine.name).put("mimoVoice", st.mimoVoice)
                .put("mimoStyle", CredentialRedactor.text(st.mimoStylePrompt)).put("rate", st.ttsSpeechRate.toDouble()).put("pitch", st.ttsPitch.toDouble()))
            .put("外观与行为", JSONObject().put("theme", st.themeMode.name).put("dynamicColor", st.dynamicColor)
                .put("autoStart", st.autoStartOnLaunch).put("keepScreenOn", st.keepScreenOn))
            .toString(2)
    }

    private fun observeTasks() {
        if (taskObserver != null) return
        taskObserver = scope.launch {
            TaskRuntime.tasks.collect { tasks ->
                val byId = tasks.associateBy { it.id }
                foregroundBusy = TaskRuntime.activeForegroundTurn() != null
                announceFinishedTasks(tasks)
                // 模型在本次运行期间创建的后台任务在对话里有自己的气泡，结果回到对话；用户删除后不再补发。
                val fresh = tasks.filter { it.source == "background" && it.createdAt >= processStartedAt && bubbleTaskIds.add(it.id) }
                    .filter { task -> _state.value.messages.none { it.taskId == task.id } }.sortedBy { it.createdAt }
                if (fresh.isNotEmpty()) updateMessages { list ->
                    list + fresh.map { ChatMessage(role = "assistant", content = "", isLoading = true, taskId = it.id) }
                }
                val previouslyLoading = _state.value.messages.filter { it.isLoading && it.taskId.isNotBlank() }
                updateMessages { list -> list.map { msg ->
                    val task = byId[msg.taskId] ?: return@map msg
                    val terminal = task.state in setOf("SUCCEEDED", "FAILED", "CANCELLED")
                    // 普通回合思考时只显示输入动画，有实际工具进度才显示文字。
                    val quiet = task.progress.isBlank() || task.progress == "任务开始" || task.progress.startsWith("思考")
                    msg.copy(content = when {
                        task.state == "FAILED" -> (if (task.visible) "任务失败：" else "出错了：") + task.error
                        task.state == "CANCELLED" -> "已取消"
                        task.state == "RESULT_UNKNOWN" -> "结果待确认：${task.error}"
                        task.state == "WAITING_CONFIRMATION" -> "需要你的授权才能继续，请在下方确认本次工具操作。"
                        task.state == "WAITING_APP" -> "等待应用恢复：${task.error}"
                        task.answer.isNotBlank() -> task.answer
                        task.visible -> if (quiet) "后台任务执行中…" else task.progress
                        quiet -> ""
                        else -> task.progress
                    }, isLoading = !terminal && task.state !in setOf("RESULT_UNKNOWN", "WAITING_CONFIRMATION", "WAITING_APP"),
                        isError = task.state in setOf("FAILED", "RESULT_UNKNOWN"))
                } }
                if (previouslyLoading.any { it.taskId == foregroundTaskId && byId[it.taskId]?.state in setOf("SUCCEEDED", "FAILED", "CANCELLED") }) {
                    foregroundTaskId = null
                }
                speechStream?.let { stream -> byId[stream.taskId]?.let { feedSpeech(stream, it) } }
            }
        }
    }

    /**
     * 把回合已生成的文本切成短段送入朗读队列，回复还在生成时就开始播报。
     * 首段取第一个分句以尽快出声，之后每段约 [SPEECH_CHUNK_CHARS] 字并在标点处断开；各段并行合成、顺序播放。
     */
    private fun feedSpeech(stream: SpeechStream, task: com.voice.actonsay.tasks.TaskRecord) {
        val finished = task.state == "SUCCEEDED"
        if (task.state != "RUNNING" && task.state != "QUEUED" && !finished) { speechStream = null; return }
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
        val st = _state.value
        val speaking = st.engineState == EngineState.SPEAKING_TTS
        if (followUpPending && speechStream == null) {
            followUpPending = false
            if (st.followUpEnabled && st.isListening && (speaking || st.engineState == EngineState.WAITING_WAKEWORD)) {
                onWakeWordDetected(FOLLOW_UP_WORD, manual = true, ack = false, timeoutMs = FOLLOW_UP_TIMEOUT_MS)
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

    // ---------------------------------------------------------------------
    // 配置写入
    // ---------------------------------------------------------------------

    @Synchronized
    fun setApiKey(newKey: String) {
        val trimmed = newKey.trim()
        settingsRepository?.putSecret(KEY_API_KEY, trimmed)
        _state.value = _state.value.copy(apiKey = trimmed, errorMessage = "")
        Log.i(TAG, "Updated Volcengine API Key")
    }

    @Synchronized
    fun setResourceId(newId: String) {
        val trimmed = newId.trim()
        prefs?.edit()?.putString(KEY_RESOURCE_ID, trimmed)?.apply()
        _state.value = _state.value.copy(resourceId = trimmed, errorMessage = "")
        Log.i(TAG, "Updated Volcengine Resource ID: $trimmed")
    }

    @Synchronized
    fun setAiEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_AI_ENABLED, enabled)?.apply()
        _state.value = _state.value.copy(aiEnabled = enabled)
    }

    @Synchronized
    fun setAiEndpoint(baseUrl: String, apiKey: String, model: String) {
        settingsRepository?.putSecret(KEY_AI_API_KEY, apiKey.trim())
        prefs?.edit()?.apply {
            putString(KEY_AI_BASE_URL, baseUrl.trim())
            putString(KEY_AI_MODEL, model.trim())
        }?.apply()
        _state.value = _state.value.copy(
            aiBaseUrl = baseUrl.trim(),
            aiApiKey = apiKey.trim(),
            aiModel = model.trim()
        )
        Log.i(TAG, "Updated OpenAI endpoint: model=${model.trim()}")
    }

    @Synchronized
    fun setAiSystemPrompt(prompt: String) {
        prefs?.edit()?.putString(KEY_AI_SYSTEM_PROMPT, prompt.trim())?.apply()
        _state.value = _state.value.copy(aiSystemPrompt = prompt.trim())
    }

    @Synchronized
    fun setAiBehavior(streaming: Boolean, contextTurns: Int) {
        val turns = contextTurns.coerceIn(0, 20)
        prefs?.edit()?.apply {
            putBoolean(KEY_AI_STREAMING, streaming)
            putInt(KEY_AI_CONTEXT_TURNS, turns)
        }?.apply()
        _state.value = _state.value.copy(aiStreaming = streaming, aiContextTurns = turns)
    }

    @Synchronized
    fun setAiVisionEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean("ai_vision_enabled", enabled)?.apply()
        _state.value = _state.value.copy(aiVisionEnabled = enabled)
    }

    fun setAgentEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_AGENT_ENABLED, enabled)?.apply()
        _state.value = _state.value.copy(agentEnabled = enabled)
    }

    /** 在 IO 线程重新探测 root（会触发 KernelSU / Magisk 授权弹窗）。 */
    fun refreshRootStatus(force: Boolean = false) {
        scope.launch(Dispatchers.IO) {
            val has = RootShell.checkRoot(force)
            if (has && _state.value.androidRootAllowed) appContext?.let { DeviceHardening.applyIfPossible(it) }
            withContext(Dispatchers.Main) {
                _state.value = _state.value.copy(hasRoot = has)
            }
        }
    }

    @Synchronized
    fun setTtsEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_TTS_ENABLED, enabled)?.apply()
        _state.value = _state.value.copy(ttsEnabled = enabled)
        if (!enabled && _state.value.engineState == EngineState.SPEAKING_TTS) stopTts()
    }

    @Synchronized
    fun setTtsEngine(engineType: TtsEngineType) {
        prefs?.edit()?.putString(KEY_TTS_ENGINE, engineType.name)?.apply()
        TtsManager.engineType = engineType
        // 切回云端引擎时补齐当前音色的唤醒应答缓存。
        TtsManager.configureMimo(_state.value.mimoApiKey, _state.value.mimoVoice, _state.value.mimoStylePrompt)
        _state.value = _state.value.copy(ttsEngine = engineType)
    }

    @Synchronized
    fun setMimoConfig(apiKey: String, voice: String, stylePrompt: String) {
        settingsRepository?.putSecret(KEY_MIMO_API_KEY, apiKey.trim())
        prefs?.edit()?.apply {
            putString(KEY_MIMO_VOICE, voice.trim())
            putString(KEY_MIMO_STYLE, stylePrompt.trim())
        }?.apply()
        TtsManager.configureMimo(apiKey, voice, stylePrompt)
        _state.value = _state.value.copy(
            mimoApiKey = apiKey.trim(),
            mimoVoice = voice.trim(),
            mimoStylePrompt = stylePrompt.trim()
        )
    }

    @Synchronized
    fun setSystemTtsParams(rate: Float, pitch: Float) {
        prefs?.edit()?.apply {
            putFloat(KEY_TTS_SPEECH_RATE, rate)
            putFloat(KEY_TTS_PITCH, pitch)
        }?.apply()
        TtsManager.setSpeechRate(rate)
        TtsManager.setPitch(pitch)
        _state.value = _state.value.copy(ttsSpeechRate = rate, ttsPitch = pitch)
    }

    @Synchronized
    fun setAppearance(themeMode: ThemeMode, dynamicColor: Boolean) {
        prefs?.edit()?.apply {
            putString(KEY_THEME_MODE, themeMode.name)
            putBoolean(KEY_DYNAMIC_COLOR, dynamicColor)
        }?.apply()
        _state.value = _state.value.copy(themeMode = themeMode, dynamicColor = dynamicColor)
    }

    @Synchronized
    fun setBehavior(autoStartOnLaunch: Boolean, keepScreenOn: Boolean) {
        prefs?.edit()?.apply {
            putBoolean(KEY_AUTO_START, autoStartOnLaunch)
            putBoolean(KEY_KEEP_SCREEN_ON, keepScreenOn)
        }?.apply()
        _state.value = _state.value.copy(autoStartOnLaunch = autoStartOnLaunch, keepScreenOn = keepScreenOn)
    }

    @Synchronized
    fun addWakeWord(word: String) {
        val trimmed = word.trim()
        if (trimmed.isEmpty()) return
        val updated = _state.value.customWakeWords + trimmed
        prefs?.edit()?.putStringSet(KEY_WAKE_WORDS, updated)?.apply()
        _state.value = _state.value.copy(customWakeWords = updated)
        kwsManager.updateWakeWords(updated)
        Log.i(TAG, "Added wake word: $trimmed")
    }

    @Synchronized
    fun removeWakeWord(word: String) {
        val updated = _state.value.customWakeWords - word
        prefs?.edit()?.putStringSet(KEY_WAKE_WORDS, updated)?.apply()
        _state.value = _state.value.copy(customWakeWords = updated)
        kwsManager.updateWakeWords(updated)
        Log.i(TAG, "Removed wake word: $word")
    }

    @Synchronized
    fun resetWakeWords() {
        prefs?.edit()?.putStringSet(KEY_WAKE_WORDS, DEFAULT_WAKE_WORDS)?.apply()
        _state.value = _state.value.copy(customWakeWords = DEFAULT_WAKE_WORDS)
        kwsManager.updateWakeWords(DEFAULT_WAKE_WORDS)
    }

    @Synchronized
    fun addActionKeyword(word: String) {
        keywordDetector.addKeyword(word)
        prefs?.edit()?.putStringSet(KEY_ACTION_KEYWORDS, keywordDetector.keywords.value)?.apply()
    }

    @Synchronized
    fun removeActionKeyword(word: String) {
        keywordDetector.removeKeyword(word)
        bindKeywordTemplate(word, "")
        prefs?.edit()?.putStringSet(KEY_ACTION_KEYWORDS, keywordDetector.keywords.value)?.apply()
    }

    fun clearMessages() {
        updateMessages { emptyList() }
    }

    fun deleteMessage(id: String) {
        updateMessages { list -> list.filterNot { it.id == id } }
    }

    fun clearError() {
        _state.value = _state.value.copy(errorMessage = "")
    }

    /** 恢复所有配置为出厂默认（保留对话历史）。 */
    @Synchronized
    fun resetAllSettings() {
        settingsRepository?.clearSecrets()
        prefs?.edit()?.clear()?.apply()
        val keep = _state.value
        _state.value = AsrState(
            isListening = keep.isListening,
            engineState = keep.engineState,
            messages = keep.messages,
            hasRoot = keep.hasRoot,
            customWakeWords = DEFAULT_WAKE_WORDS
        )
        keywordDetector.setKeywords(emptySet())
        kwsManager.updateWakeWords(DEFAULT_WAKE_WORDS)
        TtsManager.engineType = TtsEngineType.MIMO
        TtsManager.configureMimo(MimoTtsClient.DEFAULT_KEY, MimoTtsClient.DEFAULT_VOICE, "")
        TtsManager.setSpeechRate(1f)
        TtsManager.setPitch(1f)
        Log.i(TAG, "All settings reset to defaults")
    }

    // ---------------------------------------------------------------------
    // 运行时控制
    // ---------------------------------------------------------------------

    @Synchronized
    fun setListening(listening: Boolean) {
        if (!listening) {
            volcClient.cancel()
            TtsManager.stop()
            _audioLevel.value = 0f
            _state.value = _state.value.copy(
                isListening = false,
                engineState = EngineState.IDLE,
                currentText = ""
            )
        } else {
            _state.value = _state.value.copy(
                isListening = true,
                engineState = EngineState.WAITING_WAKEWORD,
                currentText = "",
                errorMessage = ""
            )
            kwsManager.reset()
        }
    }

    fun stopTts() {
        // 用户打断后，本轮后续生成的内容不再朗读。
        speechStream?.let { it.muted = true; it.interrupted = true }
        followUpPending = false
        TtsManager.stop()
        if (_state.value.engineState != EngineState.SPEAKING_TTS) return
        returnToStandby()
    }

    /** 点击麦克风：待机中则手动唤醒；倾听中则提前结束本句。 */
    fun onMicTapped() {
        when (_state.value.engineState) {
            EngineState.WAITING_WAKEWORD -> onWakeWordDetected(MANUAL_WAKE_WORD, manual = true)
            EngineState.ACTIVE_LISTENING -> finishListeningEarly()
            EngineState.SPEAKING_TTS -> stopTts()
            else -> Unit
        }
    }

    @Synchronized
    private fun finishListeningEarly() {
        if (_state.value.engineState != EngineState.ACTIVE_LISTENING) return
        if (hasSpokenInSession || _state.value.currentText.isNotBlank()) {
            Log.i(TAG, "User ended the utterance manually. Finalizing...")
            _state.value = _state.value.copy(engineState = EngineState.PROCESSING_FINAL)
            volcClient.finish()
        } else {
            Log.i(TAG, "User cancelled listening before speaking")
            volcClient.cancel()
            returnToStandby()
        }
    }

    /** 取消当前 AI 请求并回到待机。 */
    fun cancelAi() {
        foregroundTaskId?.let { TaskRuntime.cancel(it); foregroundTaskId = null; returnToStandby(); return }
        val job = currentAiJob ?: return
        job.cancel()
        currentAiJob = null
        updateMessages { list ->
            list.map { if (it.isLoading) it.copy(content = if (it.content.isBlank()) "已取消" else it.content, isLoading = false) else it }
        }
        _state.value = _state.value.copy(agentActivity = "")
        returnToStandby()
    }

    private fun returnToStandby() {
        _audioLevel.value = 0f
        val listening = _state.value.isListening
        _state.value = _state.value.copy(
            engineState = if (listening) EngineState.WAITING_WAKEWORD else EngineState.IDLE,
            currentText = "",
            agentActivity = ""
        )
        kwsManager.reset()
        kwsManager.resetInterrupt()
        if (pendingAnnouncements.isNotEmpty()) scope.launch { flushAnnouncements() }
    }

    /** 打断：停止播报，立即开始收音听新的指令；正在执行的任务不受影响。 */
    private fun onInterrupt(word: String) {
        Log.i(TAG, "Interrupted by '$word'")
        kwsManager.resetInterrupt()
        onWakeWordDetected(INTERRUPT_SOURCE, manual = true, ack = false)
    }

    /** 由录音线程调用，提供当前缓冲区的电平（RMS，0..1）。 */
    fun reportAudioLevel(level: Float) {
        _audioLevel.value = level.coerceIn(0f, 1f)
    }

    @Synchronized
    fun acceptSamples(floatSamples: FloatArray, pcmBytes: ByteArray, sampleRate: Int) {
        if (!_state.value.isListening) return

        when (_state.value.engineState) {
            EngineState.WAITING_WAKEWORD -> {
                // 本地轻量级 KWS 监听
                val detectedKw = kwsManager.acceptSamples(floatSamples, sampleRate)
                if (detectedKw != null) {
                    onWakeWordDetected(detectedKw, manual = false)
                    return
                }
                // AI 正在干活或在放歌时，打断词同样能让它停下来听你说；任务本身继续执行。
                if (foregroundBusy || MusicPlayer.isPlaying.value) {
                    kwsManager.acceptInterruptSamples(floatSamples, sampleRate)?.let { onInterrupt(it) }
                }
            }

            EngineState.ACTIVE_LISTENING -> {
                // 唤醒应答还在播放（含扬声器余音）时不收音，否则应答会被识别成用户的话。
                if (ackPlaying || System.currentTimeMillis() < listenResumeAt) return

                // 1. 音频推流给火山引擎云端 WebSocket
                volcClient.sendAudio(pcmBytes)

                // 2. 本地 Silero VAD 监控停顿以自动断句
                processVad(floatSamples)
            }

            EngineState.SPEAKING_TTS -> {
                // 播报时只听打断词和唤醒词，不送云端识别。两个流都要喂满，保持各自的解码状态连续。
                val interrupt = kwsManager.acceptInterruptSamples(floatSamples, sampleRate)
                val wake = kwsManager.acceptSamples(floatSamples, sampleRate)
                val word = interrupt ?: wake
                // 播报内容本身含有这个词时多半是扬声器回声，忽略，避免自己打断自己。
                if (word != null && !TtsManager.speakingText.contains(word)) onInterrupt(word)
            }

            EngineState.PROCESSING_FINAL, EngineState.THINKING_AI, EngineState.IDLE -> {
                // 等待收尾或模型生成中，不处理录音输入
            }
        }
    }

    private fun onWakeWordDetected(keyword: String, manual: Boolean, ack: Boolean = !manual, timeoutMs: Long? = null) {
        Log.i(TAG, "Wake word triggered: $keyword (manual=$manual)")
        speechStream?.let { it.muted = true; it.interrupted = true }
        followUpPending = false
        TtsManager.stop()

        val apiKey = _state.value.apiKey
        if (apiKey.isBlank()) {
            _state.value = _state.value.copy(
                lastWakeWord = keyword,
                errorMessage = "请先在「设置 › 语音识别」中配置火山引擎 API Key，才能进行云端识别。"
            )
            return
        }

        hasSpokenInSession = false
        lastSpeechTimestamp = 0L
        sessionStartTimestamp = System.currentTimeMillis()
        sessionNoSpeechTimeoutMs = timeoutMs ?: if (manual) MANUAL_NO_SPEECH_TIMEOUT_MS else WAKE_NO_SPEECH_TIMEOUT_MS
        pendingVadSamples = FloatArray(0)
        vad?.clear()

        val wakeCount = _state.value.totalWakeCount + 1
        prefs?.edit()?.putInt(KEY_WAKE_COUNT, wakeCount)?.apply()

        _state.value = _state.value.copy(
            engineState = EngineState.ACTIVE_LISTENING,
            lastWakeWord = keyword,
            currentText = "",
            errorMessage = "",
            totalWakeCount = wakeCount
        )

        // 语音唤醒先应答再收音；点击麦克风是用户主动操作，不需要应答。云端会话同时建立，不占用应答时间。
        val token = ++ackToken
        ackPlaying = ack && _state.value.wakeAckEnabled && _state.value.ttsEnabled
        listenResumeAt = 0L
        if (ackPlaying) scope.launch {
            // 回调里只切换协程，不在播放器的锁内获取本对象的锁。
            TtsManager.speakAck { scope.launch { finishAck(token) } }
            // 播放回调丢失时的兜底，避免一直不收音。
            delay(2500)
            finishAck(token)
        }

        volcClient.startSession(apiKey, _state.value.resourceId, object : VolcengineStreamAsrClient.Listener {
            override fun onConnected() {
                Log.i(TAG, "Volcengine ASR session connected")
            }

            override fun onTranscriptionUpdate(text: String, isFinal: Boolean) {
                scope.launch {
                    val trimmed = text.trim()
                    if (trimmed.isNotEmpty()) {
                        if (isFinal) {
                            handleFinalAsrText(trimmed, _state.value.lastWakeWord)
                        } else {
                            _state.value = _state.value.copy(currentText = trimmed)
                        }
                    } else if (isFinal) {
                        returnToStandby()
                    }
                }
            }

            override fun onError(code: Int, message: String) {
                scope.launch {
                    Log.e(TAG, "Volcengine ASR Error ($code): $message")
                    _state.value = _state.value.copy(errorMessage = "云端识别错误($code): $message")
                    returnToStandby()
                }
            }

            override fun onClosed() {
                scope.launch {
                    if (_state.value.engineState == EngineState.ACTIVE_LISTENING ||
                        _state.value.engineState == EngineState.PROCESSING_FINAL
                    ) {
                        returnToStandby()
                    }
                }
            }
        })
    }

    @Synchronized
    private fun finishAck(token: Int) {
        if (token != ackToken || !ackPlaying) return
        ackPlaying = false
        // 应答占用的时间不计入“未说话”超时；留出扬声器余音的间隔。
        val now = System.currentTimeMillis()
        listenResumeAt = now + 150
        sessionStartTimestamp = now
        pendingVadSamples = FloatArray(0)
        vad?.clear()
    }

    private fun handleFinalAsrText(finalText: String, wakeWord: String) {
        val hits = keywordDetector.check(finalText)
        val userMessage = ChatMessage(
            role = "user",
            content = finalText,
            wakeWord = wakeWord,
            hitKeywords = hits
        )
        startTurn(userMessage)
    }

    fun sendManualMessage(userText: String) {
        val trimmed = userText.trim()
        if (trimmed.isEmpty()) return
        val hits = keywordDetector.check(trimmed)
        startTurn(ChatMessage(role = "user", content = trimmed, wakeWord = MANUAL_INPUT_WORD, hitKeywords = hits))
    }

    private fun startTurn(userMessage: ChatMessage) {
        val current = _state.value
        val turnCount = current.totalTurnCount + 1
        prefs?.edit()?.putInt(KEY_TURN_COUNT, turnCount)?.apply()

        if (current.aiReady) {
            val assistantLoadingMessage = ChatMessage(role = "assistant", content = "", isLoading = true)
            // 组装多轮上下文：最近 N 轮成功的对话
            val history = buildHistory(current.messages, current.aiContextTurns)

            _state.value = current.copy(
                engineState = EngineState.THINKING_AI,
                currentText = "",
                lastFinalText = userMessage.content,
                totalTurnCount = turnCount,
                messages = (current.messages + userMessage + assistantLoadingMessage).takeLast(MAX_MESSAGES)
            )
            scheduleSaveHistory()
            processAiAndTts(userMessage.content, history, assistantLoadingMessage.id, voice = userMessage.wakeWord != MANUAL_INPUT_WORD)
        } else {
            _state.value = current.copy(
                lastFinalText = userMessage.content,
                totalTurnCount = turnCount,
                messages = (current.messages + userMessage).takeLast(MAX_MESSAGES),
                errorMessage = if (!current.aiEnabled) "" else "AI 回复未配置：请在「设置 › AI 模型」中填写 Base URL 与 API Key。"
            )
            scheduleSaveHistory()
            returnToStandby()
        }
    }

    private fun buildHistory(messages: List<ChatMessage>, turns: Int): List<ChatTurn> {
        if (turns <= 0) return emptyList()
        val usable = messages.filter { !it.isLoading && !it.isError && it.content.isNotBlank() }
        // 每轮 = 一条 user + 一条 assistant
        return usable.takeLast(turns * 2).map { ChatTurn(it.role, it.content) }
    }

    private fun processAiAndTts(userText: String, history: List<ChatTurn>, assistantMsgId: String, voice: Boolean) {
        val snapshot = _state.value
        val boundTargets = snapshot.messages.lastOrNull { it.role == "user" }?.hitKeywords.orEmpty()
            .mapNotNull { snapshot.keywordTemplates[it] }.distinct()
        // 每条消息都是一个前台回合：直接回答、用工具还是转后台由模型决定；关闭工具时退回纯聊天。
        val source = if (snapshot.agentEnabled) "turn" else "chat"
        var goal = if (boundTargets.isEmpty()) userText
            else userText + "\n\n用户为这句话命中的关键词绑定了任务模板，请按模板完成：\n" + boundTargets.joinToString("\n\n")
        // 提交后立即回到唤醒待机；执行由独立服务负责。
        returnToStandby()
        scope.launch {
            try {
                val active = TaskRuntime.activeForegroundTurn()
                if (active != null) {
                    // 上一轮还没结束：开启快速决策时交给 Jev 判断，否则（或判断不可靠时）排队。
                    val decision = if (snapshot.fastDecisionEnabled && snapshot.fastDecisionApiKey.isNotBlank()) {
                        FastDecisionClient.decideOverlap(snapshot.fastDecisionApiKey, active.goal, userText) ?: OverlapDecision.QUEUE
                    } else OverlapDecision.QUEUE
                    Log.i(TAG, "Overlap decision: $decision")
                    when (decision) {
                        OverlapDecision.STOP -> {
                            TaskRuntime.cancel(active.id)
                            if (foregroundTaskId == active.id) foregroundTaskId = null
                            updateMessages { list -> list.map { if (it.id == assistantMsgId) it.copy(content = "好的，已停止。", isLoading = false) else it } }
                            return@launch
                        }
                        OverlapDecision.INTERRUPT -> TaskRuntime.cancel(active.id)
                        OverlapDecision.MERGE -> if (TaskRuntime.hasSideEffects(active.id)) {
                            // 已产生副作用的回合不打断重来，补充要求排在它之后处理。
                            goal += "\n（这是对上一条请求的补充；上一条已在执行，请在其结果基础上按补充要求处理。）"
                        } else {
                            TaskRuntime.cancel(active.id)
                            goal += "\n（这是对上一条请求「${active.goal.take(500)}」的补充，请合并成一件事完成。）"
                        }
                        OverlapDecision.QUEUE -> Unit
                    }
                }
                val id = TaskRuntime.submit(goal, history = history, source = source, promptSuffix = if (voice) VOICE_TURN_HINT else "")
                foregroundTaskId = id
                speechStream = SpeechStream(id, voice)
                if (voice && _state.value.progressNarration && _state.value.ttsEnabled) startNarration(id)
                updateMessages { list -> list.map { if (it.id == assistantMsgId) it.copy(taskId = id) else it } }
            } catch (error: Exception) {
                Log.e(TAG, "Task submission failed", error)
                updateMessages { list -> list.map { if (it.id == assistantMsgId) it.copy(content = "无法提交任务：${error.localizedMessage}", isLoading = false, isError = true) else it } }
            }
        }
    }
    /** 重新向模型发送某条用户消息（用于失败重试）。 */
    fun retryMessage(userMessageId: String) {
        val msgs = _state.value.messages
        val idx = msgs.indexOfFirst { it.id == userMessageId }
        if (idx < 0) return
        val userMsg = msgs[idx]
        // 删除该用户消息及其紧随的回复
        val trimmed = msgs.filterIndexed { i, m -> !(i == idx || (i == idx + 1 && m.role == "assistant")) }
        _state.value = _state.value.copy(messages = trimmed)
        startTurn(userMsg.copy(id = UUID.randomUUID().toString(), timestamp = System.currentTimeMillis()))
    }

    private fun processVad(samples: FloatArray) {
        val v = vad ?: return
        pendingVadSamples = pendingVadSamples + samples

        while (pendingVadSamples.size >= VAD_WINDOW_SIZE) {
            val chunk = pendingVadSamples.copyOfRange(0, VAD_WINDOW_SIZE)
            pendingVadSamples = pendingVadSamples.copyOfRange(VAD_WINDOW_SIZE, pendingVadSamples.size)
            v.acceptWaveform(chunk)
        }

        val now = System.currentTimeMillis()
        val isSpeaking = v.isSpeechDetected()

        if (isSpeaking) {
            hasSpokenInSession = true
            lastSpeechTimestamp = now
        }

        while (!v.empty()) {
            v.pop()
        }

        if (hasSpokenInSession) {
            if (!isSpeaking && (now - lastSpeechTimestamp) >= SPEECH_SILENCE_TIMEOUT_MS) {
                Log.i(TAG, "VAD detected end of speech (silence > ${SPEECH_SILENCE_TIMEOUT_MS}ms). Finalizing...")
                _state.value = _state.value.copy(engineState = EngineState.PROCESSING_FINAL)
                volcClient.finish()
            }
        } else {
            if ((now - sessionStartTimestamp) >= sessionNoSpeechTimeoutMs) {
                Log.i(TAG, "Wake session timed out without speech. Returning to wake word listening.")
                volcClient.cancel()
                returnToStandby()
            }
        }
    }

    @Synchronized
    fun release() {
        volcClient.cancel()
        TtsManager.shutdown()
        kwsManager.release()
        vad?.release()
        vad = null
        pendingVadSamples = FloatArray(0)
        _state.value = AsrState()
    }
}
