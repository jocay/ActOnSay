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
import com.voice.actonsay.ai.OpenAiClient
import com.voice.actonsay.tts.MimoTtsClient
import com.voice.actonsay.tts.TtsEngineType
import com.voice.actonsay.tts.TtsManager
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
    val steps: List<String> = emptyList()
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

    // 智能体（工具调用）
    val agentEnabled: Boolean = true,
    /** 正在执行的工具进度（THINKING_AI 时显示） */
    val agentActivity: String = "",
    val hasRoot: Boolean = false,

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
        "你是一个随身语音智能助手。请根据用户的语音指令或提问，给出简洁明了、口语化的回答或摘要，字数控制在80字以内，避免使用Markdown排版与复杂符号，方便语音播报。"
    const val MANUAL_WAKE_WORD = "点击唤醒"
    const val MANUAL_INPUT_WORD = "文字输入"
    const val MAX_MESSAGES = 300

    val DEFAULT_WAKE_WORDS = setOf("你好小智", "小爱同学")

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

    private const val HISTORY_FILE = "chat_history.json"
    private const val EVENTS_FILE = "keyword_events.json"

    private const val SPEECH_SILENCE_TIMEOUT_MS = 800L
    private const val WAKE_NO_SPEECH_TIMEOUT_MS = 6000L
    private const val MANUAL_NO_SPEECH_TIMEOUT_MS = 10000L

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var prefs: SharedPreferences? = null
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

    @Synchronized
    fun init(context: Context) {
        TtsManager.init(context)

        if (prefs == null) {
            val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs = p
            appContext = context.applicationContext
            filesDir = context.applicationContext.filesDir

            val savedTtsEngine = runCatching {
                TtsEngineType.valueOf(p.getString(KEY_TTS_ENGINE, TtsEngineType.MIMO.name) ?: TtsEngineType.MIMO.name)
            }.getOrDefault(TtsEngineType.MIMO)
            val savedThemeMode = runCatching {
                ThemeMode.valueOf(p.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name)
            }.getOrDefault(ThemeMode.SYSTEM)

            val savedMimoKey = p.getString(KEY_MIMO_API_KEY, MimoTtsClient.DEFAULT_KEY) ?: MimoTtsClient.DEFAULT_KEY
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
                apiKey = p.getString(KEY_API_KEY, "") ?: "",
                resourceId = p.getString(KEY_RESOURCE_ID, VolcengineStreamAsrClient.RESOURCE_ID_BIGASR_1)
                    ?: VolcengineStreamAsrClient.RESOURCE_ID_BIGASR_1,
                customWakeWords = p.getStringSet(KEY_WAKE_WORDS, DEFAULT_WAKE_WORDS) ?: DEFAULT_WAKE_WORDS,
                aiEnabled = p.getBoolean(KEY_AI_ENABLED, true),
                aiBaseUrl = p.getString(KEY_AI_BASE_URL, "https://api.deepseek.com/v1") ?: "https://api.deepseek.com/v1",
                aiApiKey = p.getString(KEY_AI_API_KEY, "") ?: "",
                aiModel = p.getString(KEY_AI_MODEL, "deepseek-chat") ?: "deepseek-chat",
                aiSystemPrompt = p.getString(KEY_AI_SYSTEM_PROMPT, DEFAULT_SYSTEM_PROMPT) ?: DEFAULT_SYSTEM_PROMPT,
                aiStreaming = p.getBoolean(KEY_AI_STREAMING, true),
                aiContextTurns = p.getInt(KEY_AI_CONTEXT_TURNS, 6),
                agentEnabled = p.getBoolean(KEY_AGENT_ENABLED, true),
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
                totalTurnCount = p.getInt(KEY_TURN_COUNT, 0)
            )

            loadPersistedData()
        }

        AgentRunner.init(context)
        refreshRootStatus()

        kwsManager.init(context.assets)
        kwsManager.updateWakeWords(_state.value.customWakeWords)

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
                val messages = runCatching { readMessages(File(dir, HISTORY_FILE)) }
                    .onFailure { Log.w(TAG, "Failed to load chat history", it) }
                    .getOrDefault(emptyList())
                val events = runCatching { readEvents(File(dir, EVENTS_FILE)) }
                    .onFailure { Log.w(TAG, "Failed to load keyword events", it) }
                    .getOrDefault(emptyList())
                messages to events
            }
            if (loaded.first.isNotEmpty()) {
                // 仅在期间没有产生新消息时合并历史，避免覆盖用户刚发出的消息
                _state.value = _state.value.copy(messages = loaded.first + _state.value.messages)
            }
            if (loaded.second.isNotEmpty() && keywordDetector.events.value.isEmpty()) {
                keywordDetector.setEvents(loaded.second)
            }
            Log.i(TAG, "Loaded ${loaded.first.size} messages and ${loaded.second.size} keyword events")

            // 之后任何命中记录的变化都会落盘
            keywordDetector.events.drop(1).collect { events ->
                withContext(Dispatchers.IO) {
                    runCatching { writeEvents(File(dir, EVENTS_FILE), events) }
                        .onFailure { Log.w(TAG, "Failed to save keyword events", it) }
                }
            }
        }
    }

    private fun readMessages(file: File): List<ChatMessage> {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        val result = ArrayList<ChatMessage>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val hits = o.optJSONArray("hits")?.let { h -> List(h.length()) { h.getString(it) } } ?: emptyList()
            val steps = o.optJSONArray("steps")?.let { h -> List(h.length()) { h.getString(it) } } ?: emptyList()
            result += ChatMessage(
                id = o.optString("id", UUID.randomUUID().toString()),
                role = o.optString("role", "user"),
                content = o.optString("content", ""),
                timestamp = o.optLong("ts", 0L),
                wakeWord = o.optString("wake", ""),
                isError = o.optBoolean("error", false),
                hitKeywords = hits,
                steps = steps
            )
        }
        return result
    }

    private fun writeMessages(file: File, messages: List<ChatMessage>) {
        val arr = JSONArray()
        messages.filterNot { it.isLoading }.takeLast(MAX_MESSAGES).forEach { m ->
            arr.put(JSONObject().apply {
                put("id", m.id)
                put("role", m.role)
                put("content", m.content)
                put("ts", m.timestamp)
                put("wake", m.wakeWord)
                put("error", m.isError)
                put("hits", JSONArray(m.hitKeywords))
                if (m.steps.isNotEmpty()) put("steps", JSONArray(m.steps))
            })
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(arr.toString())
            tmp.delete()
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
                runCatching { writeMessages(File(dir, HISTORY_FILE), snapshot) }
                    .onFailure { Log.w(TAG, "Failed to save chat history", it) }
            }
        }
    }

    private fun updateMessages(transform: (List<ChatMessage>) -> List<ChatMessage>) {
        _state.value = _state.value.copy(messages = transform(_state.value.messages).takeLast(MAX_MESSAGES))
        scheduleSaveHistory()
    }

    // ---------------------------------------------------------------------
    // 配置写入
    // ---------------------------------------------------------------------

    @Synchronized
    fun setApiKey(newKey: String) {
        val trimmed = newKey.trim()
        prefs?.edit()?.putString(KEY_API_KEY, trimmed)?.apply()
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
        prefs?.edit()?.apply {
            putString(KEY_AI_BASE_URL, baseUrl.trim())
            putString(KEY_AI_API_KEY, apiKey.trim())
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

    fun setAgentEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_AGENT_ENABLED, enabled)?.apply()
        _state.value = _state.value.copy(agentEnabled = enabled)
    }

    /** 在 IO 线程重新探测 root（会触发 KernelSU / Magisk 授权弹窗）。 */
    fun refreshRootStatus(force: Boolean = false) {
        scope.launch(Dispatchers.IO) {
            val has = RootShell.checkRoot(force)
            if (has) appContext?.let { DeviceHardening.applyIfPossible(it) }
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
        _state.value = _state.value.copy(ttsEngine = engineType)
    }

    @Synchronized
    fun setMimoConfig(apiKey: String, voice: String, stylePrompt: String) {
        prefs?.edit()?.apply {
            putString(KEY_MIMO_API_KEY, apiKey.trim())
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
                }
            }

            EngineState.ACTIVE_LISTENING -> {
                // 1. 音频推流给火山引擎云端 WebSocket
                volcClient.sendAudio(pcmBytes)

                // 2. 本地 Silero VAD 监控停顿以自动断句
                processVad(floatSamples)
            }

            EngineState.SPEAKING_TTS -> {
                // TTS 正在朗读中，防回声：丢弃拾音样本，防止自己唤醒自己
            }

            EngineState.PROCESSING_FINAL, EngineState.THINKING_AI, EngineState.IDLE -> {
                // 等待收尾或模型生成中，不处理录音输入
            }
        }
    }

    private fun onWakeWordDetected(keyword: String, manual: Boolean) {
        Log.i(TAG, "Wake word triggered: $keyword (manual=$manual)")
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
        sessionNoSpeechTimeoutMs = if (manual) MANUAL_NO_SPEECH_TIMEOUT_MS else WAKE_NO_SPEECH_TIMEOUT_MS
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
            processAiAndTts(userMessage.content, history, assistantLoadingMessage.id)
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

    private fun processAiAndTts(userText: String, history: List<ChatTurn>, assistantMsgId: String) {
        val snapshot = _state.value
        currentAiJob?.let { previous ->
            previous.cancel()
            // 上一轮还没结束就来了新指令：把旧的加载中气泡收尾，避免一直转圈
            updateMessages { list ->
                list.map { msg ->
                    if (msg.isLoading && msg.id != assistantMsgId) {
                        msg.copy(content = msg.content.ifBlank { "已取消（有新指令进来）" }, isLoading = false)
                    } else msg
                }
            }
        }
        currentAiJob = scope.launch {
            val onDelta: (String) -> Unit = { partial ->
                scope.launch {
                    _state.value = _state.value.copy(
                        messages = _state.value.messages.map { msg ->
                            if (msg.id == assistantMsgId) msg.copy(content = partial) else msg
                        }
                    )
                }
            }
            val result = if (snapshot.agentEnabled) {
                AgentRunner.run(
                    config = AgentConfig(
                        baseUrl = snapshot.aiBaseUrl,
                        apiKey = snapshot.aiApiKey,
                        model = snapshot.aiModel,
                        systemPrompt = AgentPrompt.build(snapshot.aiSystemPrompt, snapshot.hasRoot),
                        stream = snapshot.aiStreaming
                    ),
                    history = history,
                    userMessage = userText,
                    onDelta = onDelta,
                    onStep = { step ->
                        scope.launch {
                            _state.value = _state.value.copy(
                                agentActivity = "",
                                messages = _state.value.messages.map { msg ->
                                    if (msg.id == assistantMsgId) msg.copy(steps = msg.steps + step.render(), content = "") else msg
                                }
                            )
                        }
                    },
                    onProgress = { activity ->
                        scope.launch { _state.value = _state.value.copy(agentActivity = activity) }
                    }
                )
            } else OpenAiClient.chatCompletion(
                baseUrl = snapshot.aiBaseUrl,
                apiKey = snapshot.aiApiKey,
                model = snapshot.aiModel,
                systemPrompt = snapshot.aiSystemPrompt,
                userMessage = userText,
                history = history,
                stream = snapshot.aiStreaming,
                onDelta = onDelta
            )
            // 本轮已被取消（用户点了取消或有新指令）：不要再改状态，避免把新一轮的状态覆盖掉
            if (!isActive) return@launch
            currentAiJob = null
            _state.value = _state.value.copy(agentActivity = "")

            result.fold(
                onSuccess = { aiReply ->
                    updateMessages { list ->
                        list.map { msg -> if (msg.id == assistantMsgId) msg.copy(content = aiReply, isLoading = false) else msg }
                    }

                    if (_state.value.ttsEnabled) {
                        _state.value = _state.value.copy(engineState = EngineState.SPEAKING_TTS, currentText = "")
                        TtsManager.speak(aiReply) {
                            scope.launch {
                                if (_state.value.engineState == EngineState.SPEAKING_TTS) {
                                    returnToStandby()
                                }
                            }
                        }
                    } else {
                        returnToStandby()
                    }
                },
                onFailure = { error ->
                    Log.e(TAG, "AI request failed: ${error.message}")
                    updateMessages { list ->
                        list.map { msg ->
                            if (msg.id == assistantMsgId) {
                                msg.copy(
                                    content = "AI 请求失败：${error.localizedMessage ?: "未知错误"}",
                                    isLoading = false,
                                    isError = true
                                )
                            } else msg
                        }
                    }
                    _state.value = _state.value.copy(errorMessage = "AI 请求异常：${error.localizedMessage ?: "未知错误"}")
                    returnToStandby()
                }
            )
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
