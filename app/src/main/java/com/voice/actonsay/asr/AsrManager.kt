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
import com.voice.actonsay.ai.AiApiFormat
import com.voice.actonsay.ai.AiProvider
import com.voice.actonsay.ai.ProviderPreset
import com.voice.actonsay.ai.FastDecisionClient
import com.voice.actonsay.ai.OverlapDecision
import com.voice.actonsay.ai.OpenAiClient
import com.voice.actonsay.bridge.BridgeClient
import com.voice.actonsay.config.SettingsRepository
import com.voice.actonsay.diagnostics.Diagnostics
import com.voice.actonsay.tasks.ConversationRecord
import com.voice.actonsay.tasks.CredentialRedactor
import com.voice.actonsay.tasks.MessageSearchHit
import com.voice.actonsay.tasks.TaskRecord
import com.voice.actonsay.tasks.TaskRepository
import com.voice.actonsay.tasks.TaskRuntime
import com.voice.actonsay.tasks.TaskStates
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
    val aiProviders: List<AiProvider> = emptyList(),
    val aiProviderId: String = "",
    val aiApiFormat: AiApiFormat = AiApiFormat.CHAT_COMPLETIONS,
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
    val avatarStyle: AvatarStyle = AvatarStyle.OUO,
    val ouoSensorEnabled: Boolean = true,
    val autoStartOnLaunch: Boolean = false,
    val keepScreenOn: Boolean = false,

    // 统计
    val totalWakeCount: Int = 0,
    val totalTurnCount: Int = 0,

    /** 唤醒词与 VAD 模型已在后台加载完成；未就绪时文字对话照常可用。 */
    val voiceReady: Boolean = false,
    /** 当前显示的会话。 */
    val conversationId: String = TaskRepository.DEFAULT_CONVERSATION,
    /** 用户主动开启的耗时与失败诊断记录。 */
    val diagnosticsEnabled: Boolean = false
) {
    /** 语音链路是否已具备 AI 回复条件。 */
    val aiReady: Boolean get() = aiEnabled && aiBaseUrl.isNotBlank() && aiModel.isNotBlank()
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

    val DEFAULT_WAKE_WORDS = setOf("你好墨宝")
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
    private const val KEY_AVATAR_STYLE = "avatar_style"
    private const val KEY_OUO_SENSOR = "ouo_sensor_enabled"
    private const val KEY_AUTO_START = "auto_start"
    private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
    private const val KEY_WAKE_COUNT = "stat_wake_count"
    private const val KEY_TURN_COUNT = "stat_turn_count"
    private const val KEY_CONVERSATION = "current_conversation"

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
    /** VAD 窗口缓冲：录音帧逐段拷入，凑满一个窗口送一次，不再每帧拼接新数组。 */
    private val vadWindow = FloatArray(VAD_WINDOW_SIZE)
    private var vadFill = 0
    @Volatile private var voiceReady = false
    private var voicePreparing = false

    private val _conversations = MutableStateFlow<List<ConversationRecord>>(emptyList())
    /** 会话列表，按最近更新排序。 */
    val conversations: StateFlow<List<ConversationRecord>> = _conversations.asStateFlow()

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
    @Volatile private var recognitionGeneration = 0L

    private val speech by lazy {
        SpeechCoordinator(_state, scope, ::returnToStandby) {
            onWakeWordDetected(FOLLOW_UP_WORD, manual = true, ack = false, timeoutMs = 5000L)
        }
    }
    /** 有前台回合在执行；录音线程据此决定是否监听打断词，避免每帧扫描任务列表。 */
    @Volatile private var foregroundBusy = false
    private val STOP_SPEAKING_PHRASES = setOf("别说了", "不要说了", "别念了", "不用念了", "不要念了", "停止朗读", "停止播报", "闭嘴", "安静", "安静点")
    private val STOP_TASK_PHRASES = setOf("停", "停止", "停下", "停下来", "停止任务", "取消任务", "取消", "别做了", "不用做了", "不要做了", "算了", "停止执行")
    private const val VOICE_TURN_HINT = "\n本条消息来自语音，回答会被完整朗读：用口语化的句子先直接给出结论，" +
        "除非用户要求详细否则保持简短，不要使用表格、代码块和长列表。"
    @Synchronized
    fun initControlPlane(context: Context) {
        TtsManager.init(context)

        if (prefs == null) {
            credentialError = ""
            val repository = SettingsRepository(context)
            settingsRepository = repository
            scope.launch {
                repository.saveError.collect { error ->
                    if (error.isNotBlank()) _state.value = _state.value.copy(errorMessage = error)
                }
            }
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
            val savedAvatarStyle = runCatching {
                AvatarStyle.valueOf(p.getString(KEY_AVATAR_STYLE, AvatarStyle.OUO.name) ?: AvatarStyle.OUO.name)
            }.getOrDefault(AvatarStyle.OUO)
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
                avatarStyle = savedAvatarStyle,
                ouoSensorEnabled = p.getBoolean(KEY_OUO_SENSOR, true),
                autoStartOnLaunch = p.getBoolean(KEY_AUTO_START, false),
                keepScreenOn = p.getBoolean(KEY_KEEP_SCREEN_ON, false),
                totalWakeCount = p.getInt(KEY_WAKE_COUNT, 0),
                totalTurnCount = p.getInt(KEY_TURN_COUNT, 0),
                conversationId = p.getString(KEY_CONVERSATION, TaskRepository.DEFAULT_CONVERSATION).orEmpty()
                    .ifBlank { TaskRepository.DEFAULT_CONVERSATION },
                diagnosticsEnabled = p.getBoolean("diagnostics_enabled", false),
                errorMessage = credentialError
            )
            loadAiProviders()
            Diagnostics.enabled = _state.value.diagnosticsEnabled

            TaskRepository.init(context)
            TaskRuntime.init(context) { agentConfig() }
            MusicPlayer.init(context)
            com.voice.actonsay.agent.PowerKeeper.init(context)
            if (_state.value.alwaysInteractive) com.voice.actonsay.agent.PowerKeeper.setAlwaysInteractive(context, true)
            loadPersistedData()
        }

    }

    /** 界面和服务都调用；控制面同步就绪，唤醒词与 VAD 模型在后台加载，不阻塞首屏。 */
    @Synchronized
    fun init(context: Context) {
        initControlPlane(context)

        AgentRunner.init(context)
        refreshRootStatus()
        if (voiceReady || voicePreparing) return
        voicePreparing = true
        val assets = context.applicationContext.assets
        scope.launch(Dispatchers.Default) {
            val started = System.currentTimeMillis()
            kwsManager.init(assets)
            kwsManager.updateWakeWords(_state.value.customWakeWords)
            kwsManager.updateInterruptWords(_state.value.interruptWords)
            val created = runCatching {
                Vad(assetManager = assets, config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = "silero_vad.onnx",
                        threshold = 0.5f,
                        minSilenceDuration = 0.25f,
                        minSpeechDuration = 0.25f,
                        windowSize = VAD_WINDOW_SIZE,
                    ),
                    sampleRate = SAMPLE_RATE,
                    numThreads = 1,
                ))
            }.onFailure { Log.e(TAG, "Failed to initialize Silero VAD", it) }.getOrNull()
            synchronized(this@AsrManager) {
                vad = created
                voiceReady = true
                voicePreparing = false
            }
            Log.i(TAG, "Voice models ready in ${System.currentTimeMillis() - started} ms")
            withContext(Dispatchers.Main) { _state.value = _state.value.copy(voiceReady = true) }
        }
    }

    // ---------------------------------------------------------------------
    // 持久化：对话历史 / 关键词命中记录
    // ---------------------------------------------------------------------

    private fun loadPersistedData() {
        val dir = filesDir ?: return
        scope.launch { TaskRepository.observeConversations().collect { _conversations.value = it } }
        scope.launch {
            val conversation = _state.value.conversationId
            val loaded = withContext(Dispatchers.IO) {
                val messages = runCatching { TaskRepository.loadMessages(conversation) }
                    .onFailure { Log.w(TAG, "Failed to load chat history", it) }
                    .getOrDefault(emptyList())
                val events = runCatching { readEvents(File(dir, EVENTS_FILE)) }
                    .onFailure { Log.w(TAG, "Failed to load keyword events", it) }
                    .getOrDefault(emptyList())
                messages to events
            }
            if (loaded.first.isNotEmpty() && _state.value.conversationId == conversation) {
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
        if (filesDir == null) return
        saveHistoryJob?.cancel()
        saveHistoryJob = scope.launch {
            delay(400)
            saveNow(_state.value.messages, _state.value.conversationId)
        }
    }

    private suspend fun saveNow(messages: List<ChatMessage>, conversationId: String) {
        withContext(Dispatchers.IO) {
            runCatching { TaskRepository.saveMessages(messages, conversationId) }
                .onFailure { Log.w(TAG, "Failed to save chat history", it) }
        }
    }

    // ---------------------------------------------------------------------
    // 多会话
    // ---------------------------------------------------------------------

    /** 新建并切换到一个空会话；当前会话为空时直接复用。 */
    fun newConversation() {
        if (_state.value.messages.isEmpty()) return
        scope.launch {
            val id = TaskRepository.createConversation()
            openConversation(id)
        }
    }

    fun switchConversation(id: String) {
        if (id == _state.value.conversationId) return
        scope.launch { openConversation(id) }
    }

    /** 先把当前会话未落盘的改动写入，再载入目标会话；空会话离开时删除，避免列表里堆积空白项。 */
    private suspend fun openConversation(id: String) {
        val previous = _state.value.conversationId
        val previousMessages = _state.value.messages
        saveHistoryJob?.cancel()
        if (previousMessages.isEmpty()) runCatching { TaskRepository.deleteConversation(previous) }
        else saveNow(previousMessages, previous)
        val messages = runCatching { TaskRepository.loadMessages(id) }.getOrDefault(emptyList())
        prefs?.edit()?.putString(KEY_CONVERSATION, id)?.apply()
        _state.value = _state.value.copy(conversationId = id, messages = messages.takeLast(MAX_MESSAGES))
        // 载入的回复可能停在“加载中”：按任务的最新状态补齐。
        applyTasks(TaskRuntime.tasks.value)
    }

    /** 删除会话；删除的是当前会话时切到最近的其他会话或新建一个。 */
    fun deleteConversation(id: String) {
        scope.launch {
            if (id == _state.value.conversationId) {
                saveHistoryJob?.cancel()
                _state.value = _state.value.copy(messages = emptyList())
                val next = _conversations.value.firstOrNull { it.id != id }?.id ?: TaskRepository.createConversation()
                openConversation(next)
            }
            runCatching { TaskRepository.deleteConversation(id) }
        }
    }

    /** 清空所有会话及消息（关于与数据页）。 */
    fun clearAllConversations() {
        scope.launch {
            saveHistoryJob?.cancel()
            runCatching { TaskRepository.deleteAllConversations() }
            prefs?.edit()?.putString(KEY_CONVERSATION, TaskRepository.DEFAULT_CONVERSATION)?.apply()
            _state.value = _state.value.copy(conversationId = TaskRepository.DEFAULT_CONVERSATION, messages = emptyList())
        }
    }

    suspend fun searchMessages(query: String): List<MessageSearchHit> = runCatching { TaskRepository.search(query) }.getOrDefault(emptyList())

    /** 当前会话中尚未结束的前台回合（执行中或排队），用于展示可取消的队列。 */
    fun pendingTurns(tasks: List<TaskRecord>): List<TaskRecord> = tasks.filter {
        it.source in TaskRuntime.FOREGROUND_SOURCES && !it.visible && it.conversationId == _state.value.conversationId &&
            it.state in setOf(TaskStates.QUEUED, TaskStates.RUNNING, TaskStates.WAITING_CONFIRMATION)
    }.sortedBy { it.createdAt }

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
            supportsImages = st.aiVisionEnabled, apiFormat = st.aiApiFormat)
    }

    @Synchronized
    fun setBridgeConfig(baseUrl: String, token: String) {
        val normalized = BridgeClient.validateBaseUrl(baseUrl)
        settingsRepository?.queueSecret("bridge_token", token.trim())
        prefs?.edit()?.putString("bridge_base_url", normalized)?.apply()
        _state.value = _state.value.copy(bridgeBaseUrl = normalized, bridgeToken = token.trim())
    }

    @Synchronized
    fun setBridgeContainer(name: String) {
        require(name.matches(Regex("[A-Za-z0-9_.-]{1,64}"))) { "无效的容器名称" }
        prefs?.edit()?.putString("bridge_container", name)?.apply()
        _state.value = _state.value.copy(bridgeContainer = name)
    }

    suspend fun setPairedBridgeConfig(container: String, baseUrl: String, token: String) {
        withContext(Dispatchers.IO) { settingsRepository?.persistSecret("bridge_token", token.trim()) }
        withContext(Dispatchers.Main) {
            setBridgeContainer(container)
            setBridgeConfig(baseUrl, token)
        }
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
        settingsRepository?.queueSecret("music_api_key", apiKey.trim())
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
        settingsRepository?.queueSecret("brave_api_key", braveKey.trim())
        settingsRepository?.queueSecret("exa_api_key", exaKey.trim())
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
        settingsRepository?.queueSecret("fast_decision_api_key", apiKey.trim())
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
            OpenAiClient.buildMessages("请调用 configuration_probe 一次，用于验证工具调用协议。", emptyList(), "检查工具调用能力"), tools, false, null, st.aiApiFormat).getOrThrow()
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
                .put("avatarStyle", st.avatarStyle.name).put("ouoSensorEnabled", st.ouoSensorEnabled)
                .put("autoStart", st.autoStartOnLaunch).put("keepScreenOn", st.keepScreenOn))
            .toString(2)
    }

    private fun observeTasks() {
        if (taskObserver != null) return
        taskObserver = scope.launch {
            TaskRuntime.tasks.collect { tasks ->
                foregroundBusy = TaskRuntime.activeForegroundTurn() != null
                speech.observe(tasks)
                // 模型在本次运行期间创建的后台任务在对话里有自己的气泡，结果回到对话；用户删除后不再补发。
                // 气泡放进创建它的会话：不在当前会话时直接写入那个会话的记录。
                val fresh = tasks.filter { it.source == "background" && it.createdAt >= processStartedAt && bubbleTaskIds.add(it.id) }
                    .filter { task -> _state.value.messages.none { it.taskId == task.id } }.sortedBy { it.createdAt }
                val (here, elsewhere) = fresh.partition { it.conversationId == _state.value.conversationId }
                if (here.isNotEmpty()) updateMessages { list ->
                    list + here.map { ChatMessage(role = "assistant", content = "", isLoading = true, taskId = it.id) }
                }
                elsewhere.forEach { task ->
                    launch { runCatching { TaskRepository.appendMessage(task.conversationId,
                        ChatMessage(role = "assistant", content = "", isLoading = true, taskId = task.id)) } }
                }
                applyTasks(tasks)
            }
        }
    }

    /** 把任务的最新状态映射到当前会话的回复气泡。 */
    private fun applyTasks(tasks: List<TaskRecord>) {
        val byId = tasks.associateBy { it.id }
        val busyTurn = tasks.any { it.source in TaskRuntime.FOREGROUND_SOURCES && !it.visible && it.state == TaskStates.RUNNING }
        val previouslyLoading = _state.value.messages.filter { it.isLoading && it.taskId.isNotBlank() }
        updateMessages { list -> list.map { msg ->
            val task = byId[msg.taskId] ?: return@map msg
            val terminal = task.state in setOf("SUCCEEDED", "FAILED", "CANCELLED")
            // 普通回合思考时只显示输入动画，有实际工具进度才显示文字。
            val quiet = task.progress.isBlank() || task.progress == "任务开始" || task.progress.startsWith("思考")
            msg.copy(content = when {
                task.state == "FAILED" -> (if (task.visible) "任务失败：" else "出错了：") + task.error
                task.state == "CANCELLED" -> "已取消"
                task.state == TaskStates.CANCELLING -> "正在取消，等待执行环境确认停止…" + if (task.error.isNotBlank()) "\n${task.error}" else ""
                task.state == "RESULT_UNKNOWN" -> "结果待确认：${task.error}"
                task.state == "WAITING_CONFIRMATION" -> "需要你的授权才能继续，请在下方确认本次工具操作。"
                task.state == "WAITING_APP" -> "等待应用恢复：${task.error}"
                task.answer.isNotBlank() -> task.answer
                task.state == TaskStates.QUEUED && !task.visible && busyTurn -> "排队中，等上一条处理完…"
                task.visible -> if (quiet) "后台任务执行中…" else task.progress
                quiet -> ""
                else -> task.progress
            }, isLoading = !terminal && task.state !in setOf("RESULT_UNKNOWN", "WAITING_CONFIRMATION", "WAITING_APP", TaskStates.CANCELLING),
                isError = task.state in setOf("FAILED", "RESULT_UNKNOWN"))
        } }
        if (previouslyLoading.any { it.taskId == foregroundTaskId && byId[it.taskId]?.state in setOf("SUCCEEDED", "FAILED", "CANCELLED") }) {
            foregroundTaskId = null
        }
    }

    // ---------------------------------------------------------------------
    // 配置写入
    // ---------------------------------------------------------------------

    @Synchronized
    fun setApiKey(newKey: String) {
        val trimmed = newKey.trim()
        settingsRepository?.queueSecret(KEY_API_KEY, trimmed)
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
    fun setAiEndpoint(baseUrl: String, apiKey: String, model: String, format: AiApiFormat = _state.value.aiApiFormat) {
        val st = _state.value
        val selected = st.aiProviders.firstOrNull { it.id == st.aiProviderId }
        if (selected != null) {
            val updated = selected.copy(baseUrl = baseUrl.trim(), apiKey = apiKey.trim(), model = model.trim(), format = format)
            saveAiProviders(st.aiProviders.map { if (it.id == selected.id) updated else it }, selected.id)
            return
        }
        settingsRepository?.queueSecret(KEY_AI_API_KEY, apiKey.trim())
        prefs?.edit()?.apply {
            putString(KEY_AI_BASE_URL, baseUrl.trim())
            putString(KEY_AI_MODEL, model.trim())
        }?.apply()
        _state.value = _state.value.copy(
            aiBaseUrl = baseUrl.trim(),
            aiApiKey = apiKey.trim(),
            aiModel = model.trim(), aiApiFormat = format
        )
        Log.i(TAG, "Updated OpenAI endpoint: model=${model.trim()}")
    }

    /** 从旧单接口配置迁移，密钥继续使用系统 Keystore 加密。 */
    private fun loadAiProviders() {
        val st = _state.value
        val saved = prefs?.getString("ai_providers", null)
        if (saved == null) {
            // 首次迁移先确保密钥落盘，再写供应商元数据，避免进程退出丢失旧凭据。
            runCatching { settingsRepository?.persistSecret("ai_provider_key_legacy", st.aiApiKey) }.getOrElse {
                _state.value = st.copy(errorMessage = "供应商密钥迁移失败，原有配置已保留。")
                return
            }
        }
        val providers = if (saved == null) listOf(AiProvider("legacy", "原有供应商", st.aiBaseUrl, st.aiModel, apiKey = st.aiApiKey)) else runCatching {
            val rows = JSONArray(saved)
            List(rows.length()) { index ->
                val row = rows.getJSONObject(index)
                val id = row.getString("id")
                AiProvider(id, row.getString("name"), row.getString("baseUrl"), row.getString("model"),
                    AiApiFormat.from(row.optString("format")), readCredential("ai_provider_key_$id"))
            }
        }.getOrElse {
            _state.value = _state.value.copy(errorMessage = "供应商配置读取失败，已保留原有配置。")
            return
        }
        val selected = prefs?.getString("ai_provider_id", "").orEmpty()
        saveAiProviders(providers, providers.firstOrNull { it.id == selected }?.id ?: providers.firstOrNull()?.id.orEmpty())
    }

    private fun saveAiProviders(providers: List<AiProvider>, selectedId: String) {
        val selected = providers.firstOrNull { it.id == selectedId }
        providers.forEach { settingsRepository?.queueSecret("ai_provider_key_${it.id}", it.apiKey) }
        settingsRepository?.queueSecret(KEY_AI_API_KEY, selected?.apiKey.orEmpty())
        prefs?.edit()?.apply {
            putString("ai_providers", JSONArray().apply { providers.forEach { put(it.metadata()) } }.toString())
            putString("ai_provider_id", selectedId)
            putString(KEY_AI_BASE_URL, selected?.baseUrl.orEmpty())
            putString(KEY_AI_MODEL, selected?.model.orEmpty())
        }?.apply()
        _state.value = _state.value.copy(aiProviders = providers, aiProviderId = selectedId,
            aiBaseUrl = selected?.baseUrl.orEmpty(), aiApiKey = selected?.apiKey.orEmpty(), aiModel = selected?.model.orEmpty(),
            aiApiFormat = selected?.format ?: AiApiFormat.CHAT_COMPLETIONS)
    }

    @Synchronized
    fun setAiProvider(id: String) {
        if (_state.value.aiProviders.none { it.id == id }) return
        saveAiProviders(_state.value.aiProviders, id)
    }

    @Synchronized
    fun setAiProviderName(name: String) {
        val st = _state.value
        saveAiProviders(st.aiProviders.map { if (it.id == st.aiProviderId) it.copy(name = name) else it }, st.aiProviderId)
    }

    @Synchronized
    fun addAiProvider(preset: ProviderPreset? = null) {
        val provider = AiProvider(UUID.randomUUID().toString(), preset?.name ?: "自定义供应商",
            preset?.baseUrl.orEmpty(), preset?.defaultModel.orEmpty(), preset?.format ?: AiApiFormat.CHAT_COMPLETIONS)
        saveAiProviders(_state.value.aiProviders + provider, provider.id)
    }

    @Synchronized
    fun deleteAiProvider(id: String) {
        val st = _state.value
        if (st.aiProviders.none { it.id == id }) return
        val remaining = st.aiProviders.filterNot { it.id == id }
        settingsRepository?.queueSecret("ai_provider_key_$id", "")
        saveAiProviders(remaining, if (st.aiProviderId == id) remaining.firstOrNull()?.id.orEmpty() else st.aiProviderId)
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
        settingsRepository?.queueSecret(KEY_MIMO_API_KEY, apiKey.trim())
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
    fun setAvatarConfig(style: AvatarStyle, sensorEnabled: Boolean) {
        prefs?.edit()?.apply {
            putString(KEY_AVATAR_STYLE, style.name)
            putBoolean(KEY_OUO_SENSOR, sensorEnabled)
        }?.apply()
        _state.value = _state.value.copy(avatarStyle = style, ouoSensorEnabled = sensorEnabled)
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

    /** 由界面或系统权限流程显示一条提示横幅。 */
    fun showError(message: String) {
        _state.value = _state.value.copy(errorMessage = message)
    }

    @Synchronized
    fun setDiagnosticsEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean("diagnostics_enabled", enabled)?.apply()
        Diagnostics.enabled = enabled
        _state.value = _state.value.copy(diagnosticsEnabled = enabled)
    }

    /** 重播历史回复，走与正常回答相同的播报状态；正在收音时不打断用户。 */
    fun replayMessage(content: String): Boolean {
        val accepted = speech.replay(content)
        if (!accepted) _state.value = _state.value.copy(errorMessage = "正在听你说话，稍后再重播。")
        return accepted
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
            customWakeWords = DEFAULT_WAKE_WORDS,
            voiceReady = keep.voiceReady,
            conversationId = keep.conversationId
        )
        prefs?.edit()?.putString(KEY_CONVERSATION, keep.conversationId)?.apply()
        loadAiProviders()
        Diagnostics.enabled = false
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
            cancelRecognition()
            speech.interrupt()
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
        speech.interrupt()
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
            Diagnostics.speechEnded()
            _state.value = _state.value.copy(engineState = EngineState.PROCESSING_FINAL)
            volcClient.finish()
        } else {
            Log.i(TAG, "User cancelled listening before speaking")
            cancelRecognition()
            returnToStandby()
        }
    }

    /**
     * 停止当前任务：取消正在执行的前台回合并停止朗读。与只停止朗读的 [stopTts]、
     * 停止朗读后接着听的打断词区分开；后台任务不受影响。
     */
    fun stopCurrentTask() {
        val id = foregroundTaskId ?: TaskRuntime.activeForegroundTurn()?.id
        speech.interrupt()
        TtsManager.stop()
        if (id != null) {
            TaskRuntime.cancel(id)
            foregroundTaskId = null
        }
        val engine = _state.value.engineState
        if (engine == EngineState.SPEAKING_TTS || engine == EngineState.THINKING_AI) returnToStandby()
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
        speech.standby()
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

    /**
     * 录音线程每帧调用。[pcm] 的前 [length] 个采样是本帧原始数据，只在推送云端识别时才转成字节，
     * 待机时不做这份拷贝。模型还在后台加载时直接丢弃，不在这里等待加载锁。
     */
    @Synchronized
    fun acceptSamples(floatSamples: FloatArray, pcm: ShortArray, length: Int, sampleRate: Int) {
        if (!_state.value.isListening || !voiceReady) return

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

                // 1. 音频推流给火山引擎云端 WebSocket（握手前会排队，因此每帧单独分配字节数组）
                val pcmBytes = ByteArray(length * 2)
                for (i in 0 until length) {
                    val s = pcm[i].toInt()
                    pcmBytes[i * 2] = (s and 0xFF).toByte()
                    pcmBytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                }
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

    @Synchronized
    private fun cancelRecognition() {
        recognitionGeneration++
        ackToken++
        volcClient.cancel()
    }

    private fun receiveRecognition(token: Long, action: () -> Unit) {
        scope.launch {
            synchronized(this@AsrManager) {
                if (token == recognitionGeneration && _state.value.isListening) action()
            }
        }
    }

    @Synchronized
    private fun onWakeWordDetected(keyword: String, manual: Boolean, ack: Boolean = !manual, timeoutMs: Long? = null) {
        Log.i(TAG, "Wake word triggered: $keyword (manual=$manual)")
        if (!voiceReady || vad == null) {
            _state.value = _state.value.copy(lastWakeWord = keyword,
                errorMessage = if (voiceReady) "语音断句模型加载失败，请重启应用后再试；文字对话不受影响。" else "语音模型准备中，请稍候再说话。")
            return
        }
        speech.interrupt()
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
        vadFill = 0
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
            synchronized(this@AsrManager) {
                if (token != ackToken || !ackPlaying) return@launch
                TtsManager.speakAck { scope.launch { finishAck(token) } }
            }
            // 播放回调丢失时的兜底，避免一直不收音。
            delay(2500)
            finishAck(token)
        }

        val recognitionToken = ++recognitionGeneration
        volcClient.startSession(apiKey, _state.value.resourceId, object : VolcengineStreamAsrClient.Listener {
            override fun onConnected() {
                Log.i(TAG, "Volcengine ASR session connected")
            }

            override fun onTranscriptionUpdate(text: String, isFinal: Boolean) {
                receiveRecognition(recognitionToken) {
                    val trimmed = text.trim()
                    if (isFinal) cancelRecognition()
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
                receiveRecognition(recognitionToken) {
                    cancelRecognition()
                    Log.e(TAG, "Volcengine ASR Error ($code): $message")
                    Diagnostics.failure("语音识别", "($code) $message")
                    _state.value = _state.value.copy(errorMessage = "云端识别错误($code): $message")
                    returnToStandby()
                    // 纯语音使用时看不到横幅，说一句原因；点击麦克风的用户看着屏幕，不再出声。
                    if (_state.value.lastWakeWord != MANUAL_WAKE_WORD) speech.notice("语音识别连接失败，请检查网络。")
                }
            }

            override fun onClosed() {
                receiveRecognition(recognitionToken) {
                    cancelRecognition()
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
        vadFill = 0
        vad?.clear()
    }

    private fun handleFinalAsrText(finalText: String, wakeWord: String) {
        Diagnostics.asrFinal()
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

    private enum class LocalCommand { STOP_SPEAKING, STOP_TASK }

    /** 只有整句就是这些短指令时才在本地处理，较长的句子照常交给模型。 */
    private fun localCommand(text: String): LocalCommand? {
        val normalized = text.replace(Regex("[\\s，,。.！!？?、~～…]"), "").removeSuffix("吧").removeSuffix("啊")
        return when (normalized) {
            in STOP_SPEAKING_PHRASES -> LocalCommand.STOP_SPEAKING
            in STOP_TASK_PHRASES -> LocalCommand.STOP_TASK
            else -> null
        }
    }

    /**
     * “别说了”只停止朗读；“停止/取消任务”停止当前会话里正在执行和排队的前台回合。
     * 没有可停止的回合时返回 false，按普通消息交给模型。
     */
    private fun handleLocalCommand(command: LocalCommand, userMessage: ChatMessage): Boolean {
        val reply = when (command) {
            LocalCommand.STOP_SPEAKING -> {
                speech.interrupt()
                TtsManager.stop()
                "好的，不念了。"
            }
            LocalCommand.STOP_TASK -> {
                val pending = pendingTurns(TaskRuntime.tasks.value)
                if (pending.isEmpty()) return false
                speech.interrupt()
                TtsManager.stop()
                pending.forEach { TaskRuntime.cancel(it.id) }
                foregroundTaskId = null
                "好的，已停止${if (pending.size > 1) "当前和排队中的 ${pending.size} 条请求" else ""}。"
            }
        }
        updateMessages { it + userMessage + ChatMessage(role = "assistant", content = reply) }
        returnToStandby()
        if (command == LocalCommand.STOP_TASK && userMessage.wakeWord != MANUAL_INPUT_WORD) speech.notice("好的，已停止。")
        return true
    }

    private fun startTurn(userMessage: ChatMessage) {
        localCommand(userMessage.content)?.let { if (handleLocalCommand(it, userMessage)) return }
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
        val conversation = snapshot.conversationId
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
                val id = TaskRuntime.submit(goal, conversationId = conversation, history = history, source = source,
                    promptSuffix = if (voice) VOICE_TURN_HINT else "")
                Diagnostics.turnSubmitted(id, voice, userText)
                foregroundTaskId = id
                speech.start(id, voice)
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
        // 凑满一个窗口送一次；VAD 会复制传入的数据，窗口缓冲可以反复使用。
        var offset = 0
        while (offset < samples.size) {
            val count = minOf(VAD_WINDOW_SIZE - vadFill, samples.size - offset)
            System.arraycopy(samples, offset, vadWindow, vadFill, count)
            vadFill += count
            offset += count
            if (vadFill == VAD_WINDOW_SIZE) {
                v.acceptWaveform(vadWindow)
                vadFill = 0
            }
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
                Diagnostics.speechEnded()
                _state.value = _state.value.copy(engineState = EngineState.PROCESSING_FINAL)
                volcClient.finish()
            }
        } else {
            if ((now - sessionStartTimestamp) >= sessionNoSpeechTimeoutMs) {
                Log.i(TAG, "Wake session timed out without speech. Returning to wake word listening.")
                cancelRecognition()
                returnToStandby()
                // 喊了唤醒词却没听到下文：说一句让人知道已回到待机。连续对话、打断和点击唤醒的超时保持安静。
                val source = _state.value.lastWakeWord
                if (source !in setOf(MANUAL_WAKE_WORD, FOLLOW_UP_WORD, INTERRUPT_SOURCE)) scope.launch { speech.notice("没听到你说话，需要时再叫我。") }
            }
        }
    }

    @Synchronized
    fun release() {
        cancelRecognition()
        speech.reset()
        TtsManager.shutdown()
        kwsManager.release()
        vad?.release()
        vad = null
        vadFill = 0
        voiceReady = false
        _state.value = AsrState()
    }
}
