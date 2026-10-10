package com.voice.actonsay.tasks

import android.content.Context
import android.content.Intent
import android.os.Build
import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.pm.PackageManager
import com.voice.actonsay.MainActivity
import androidx.room.withTransaction
import com.voice.actonsay.agent.*
import com.voice.actonsay.ai.ChatTurn
import com.voice.actonsay.ai.OpenAiClient
import com.voice.actonsay.ai.ToolCall
import com.voice.actonsay.bridge.BridgeClient
import com.voice.actonsay.agent.tools.DebianExecutionTool
import com.voice.actonsay.agent.tools.McpCallExecution
import com.voice.actonsay.diagnostics.Diagnostics
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** 持久化任务的唯一运行入口，后台智能体与无工具聊天各占一个槽。 */
object TaskRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ready = CompletableDeferred<Unit>()
    private val queueMutex = Mutex()
    private val recordMutex = Mutex()
    private val bridgeSyncMutex = Mutex()
    private val ownerMutex = Mutex()
    /** 并发表：资源租约等会在锁外查询任务是否仍在运行。 */
    private val jobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    /** true 表示占用前台回合槽，false 表示占用后台任务槽。 */
    private val jobSlots = mutableMapOf<String, Boolean>()
    private val toolsStarted = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
    /** 前台回合的来源；其余来源都是后台任务。 */
    val FOREGROUND_SOURCES = setOf("turn", "chat")
    /** 中断后可安全重放的工具：只读，或以调用 ID 保证幂等。 */
    val REPLAYABLE_TOOLS = setOf("get_device_status", "list_apps", "screen_content", "read_skill",
        "fetch_url", "web_search", "list_tasks", "get_task", "start_background_task", "cancel_task", "remember",
        "find_contact", "list_calendar_events", "read_notifications")
    private const val FOREGROUND_TOOL_BUDGET = 6
    private const val FOREGROUND_SECONDS_BUDGET = 45
    private val _tasks = MutableStateFlow<List<TaskRecord>>(emptyList())
    private val taskPageSize = MutableStateFlow(100)
    private val liveAnswers = MutableStateFlow<Map<String, String>>(emptyMap())
    private val _taskCount = MutableStateFlow(0)
    val taskCount: StateFlow<Int> = _taskCount.asStateFlow()
    fun loadMoreTasks() { taskPageSize.update { it + 100 } }
    val tasks: StateFlow<List<TaskRecord>> = _tasks.asStateFlow()
    private val _preferences = MutableStateFlow<List<PreferenceRecord>>(emptyList())
    val preferences: StateFlow<List<PreferenceRecord>> = _preferences.asStateFlow()
    private val _serviceError = MutableStateFlow("")
    val serviceError: StateFlow<String> = _serviceError.asStateFlow()
    @Volatile private var context: Context? = null
    private var configProvider: (() -> AgentConfig)? = null
    @Volatile private var serviceRunning = false
    private val _automationPaused = MutableStateFlow(false)
    val automationPaused: StateFlow<Boolean> = _automationPaused.asStateFlow()
    private val dao get() = TaskRepository.dao

    @Synchronized fun init(context: Context, configProvider: () -> AgentConfig) {
        this.configProvider = configProvider
        if (this.context != null) return
        this.context = context.applicationContext
        TaskRepository.init(context)
        AgentRunner.init(context)
        scope.launch {
            try {
                TaskRepository.loadMessages()
                _automationPaused.value = dao.preference("_automation_paused")?.value == "true"
                dao.running().filter { it.source != "script" }.forEach { task ->
                    val calls = dao.calls(task.id)
                    val uncertain = calls.any { it.state == "RUNNING" && it.executionId.isBlank() && it.name !in REPLAYABLE_TOOLS }
                    dao.updateTask(task.copy(state = if (uncertain) TaskStates.RESULT_UNKNOWN else TaskStates.WAITING_APP,
                        progress = "任务中断，可检查记录后恢复", updatedAt = System.currentTimeMillis()))
                }
                ready.complete(Unit)
                launch {
                    taskPageSize.collectLatest { limit ->
                        combine(dao.observeTasks(limit), liveAnswers) { rows, answers ->
                            rows.map { row -> if (row.state == TaskStates.RUNNING && answers.containsKey(row.id)) row.copy(answer = answers.getValue(row.id)) else row }
                        }.collect { _tasks.value = it }
                    }
                }
                launch { dao.observeTaskCount().collect { _taskCount.value = it } }
                launch { dao.observePreferences().collect { _preferences.value = it.filterNot { pref -> pref.key.startsWith("_") } } }
            } catch (error: Exception) {
                _serviceError.value = "任务数据库初始化失败：${error.message}"
                ready.completeExceptionally(error)
            }
        }
    }

    fun startFromForeground() {
        val app = context ?: return
        // 服务已在运行时无需再次启动；后台重复启动被系统拒绝会把可执行的任务误标为等待应用。
        if (serviceRunning) return
        try {
            val intent = Intent(app, AgentTaskService::class.java)
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent) else app.startService(intent)
        } catch (error: Exception) {
            _serviceError.value = "系统暂不允许启动任务前台服务，请打开应用后恢复任务：${error.message}"
            scope.launch {
                ready.await()
                dao.queued().forEach { task -> update(task.id) { it.copy(state = TaskStates.WAITING_APP, error = _serviceError.value) } }
            }
        }
    }
    fun serviceStarted() {
        serviceRunning = true
        _serviceError.value = ""
        scope.launch {
            ready.await()
            dao.waitingApp().filter { it.source != "script" }.forEach { task ->
                update(task.id) { it.copy(state = TaskStates.QUEUED, error = "") }
            }
            schedule()
        }
    }
    fun serviceStartFailed(reason: String) {
        _serviceError.value = "系统拒绝任务服务：$reason"
        serviceRunning = false
        scope.launch { ready.await(); dao.queued().forEach { task -> update(task.id) { it.copy(state = TaskStates.WAITING_APP, error = _serviceError.value) } } }
    }
    fun serviceStopped() {
        serviceRunning = false
        scope.launch {
            queueMutex.withLock { jobs.values.forEach { it.cancel(CancellationException("SERVICE_STOPPED")) } }
        }
    }

    /** [fixedId] 让由工具调用创建的任务在恢复重放时保持幂等：同一 ID 只落库一次。 */
    suspend fun submit(goal: String, conversationId: String = "default", history: List<ChatTurn> = emptyList(),
                       source: String = "background", triggerId: String? = null, fixedId: String? = null, promptSuffix: String = ""): String {
        ready.await()
        require(goal.isNotBlank()) { "任务目标不能为空" }
        val config = configProvider?.invoke() ?: error("模型配置不可用")
        val id = fixedId ?: UUID.randomUUID().toString()
        val snapshot = snapshot(if (promptSuffix.isBlank()) config else config.copy(systemPrompt = config.systemPrompt + promptSuffix))
        val workDir = File(context!!.filesDir, "tasks/$id").apply { mkdirs() }.absolutePath
        val row = TaskRecord(id, goal.trim(), conversationId = conversationId, source = source,
            historyJson = JSONArray().apply { history.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) } }.toString(),
            configSnapshot = snapshot, workDir = workDir, triggerId = triggerId, visible = source !in FOREGROUND_SOURCES)
        var created = true
        val persistedId = TaskRepository.database.withTransaction {
            if (triggerId != null) dao.trigger(triggerId)?.id ?: run { dao.insertTask(row); dao.trigger(triggerId)?.id ?: id }
            else { created = dao.insertTask(row) != -1L; id }
        }
        if (persistedId == id && created) event(id, "CREATED", "任务已持久化")
        startFromForeground()
        if (serviceRunning) schedule()
        return persistedId
    }

    fun isRunning(id: String): Boolean = jobs[id]?.isActive == true

    /**
     * 取消分两步：本地立即停止模型与工具循环；涉及 Debian 或 MCP 的执行先标为“正在取消”，
     * 执行端确认已停止后才改为“已取消”。请求未送达时保留待确认状态，由周期同步重试。
     */
    fun cancel(id: String) { scope.launch {
        ready.await()
        val task = dao.task(id) ?: return@launch
        if (task.state in setOf(TaskStates.SUCCEEDED, TaskStates.FAILED, TaskStates.CANCELLED)) return@launch
        val remote = remoteExecutions(task)
        update(id) { if (remote.isEmpty()) it.copy(state = TaskStates.CANCELLED, progress = "已取消")
            else it.copy(state = TaskStates.CANCELLING, progress = "正在取消，等待执行环境确认", error = "") }
        queueMutex.withLock { jobs[id]?.cancel(CancellationException("USER_CANCELLED")) }
        event(id, if (remote.isEmpty()) "CANCELLED" else "CANCEL_REQUESTED", "用户取消任务")
        if (remote.isNotEmpty()) confirmRemoteCancel(id)
    } }

    /** 任务关联的远端执行：脚本任务的执行，以及尚未得到确定结果的工具调用。 */
    private suspend fun remoteExecutions(task: TaskRecord): List<String> = buildList {
        if (task.remoteExecutionId.isNotBlank()) add(task.remoteExecutionId)
        dao.calls(task.id).filter { it.executionId.isNotBlank() && it.state in listOf("RUNNING", TaskStates.RESULT_UNKNOWN) }
            .forEach { add(it.executionId) }
    }.distinct()

    /** 向执行端发送取消并核实结果；全部已结束才把任务改为已取消。 */
    private suspend fun confirmRemoteCancel(id: String) {
        val task = dao.task(id) ?: return
        if (task.state != TaskStates.CANCELLING) return
        var unconfirmed = 0
        var unreachable = false
        val finals = setOf("succeeded", "failed", "cancelled", "result_unknown")
        val confirmed = mutableMapOf<String, String>()
        for (executionId in remoteExecutions(task)) {
            val mcp = executionId.startsWith("mcp:")
            val base = if (mcp) "/v1/mcp-calls/${executionId.removePrefix("mcp:")}" else "/v1/executions/$executionId"
            try {
                val reply = BridgeClient.request("POST", "$base/cancel")
                // MCP 调用的完整结果可能不带状态字段，带内容即表示已经结束。
                val status = reply.optString("status").ifBlank { if (mcp && reply.has("content")) "succeeded" else "" }
                if (status in finals) confirmed[executionId] = status else unconfirmed++
            } catch (error: CancellationException) {
                throw error
            } catch (error: com.voice.actonsay.bridge.BridgeException) {
                // 执行端已没有这条记录，视为不在运行。
                if (error.statusCode == 404) confirmed[executionId] = "cancelled" else { unconfirmed++; unreachable = true }
            } catch (_: Exception) {
                unconfirmed++
                unreachable = true
            }
        }
        if (unconfirmed == 0) {
            dao.calls(id).filter { it.state == "RUNNING" && it.executionId in confirmed }.forEach { call ->
                dao.saveCall(call.copy(state = confirmed.getValue(call.executionId).uppercase(), updatedAt = System.currentTimeMillis()))
            }
            update(id) { if (it.state == TaskStates.CANCELLING) it.copy(state = TaskStates.CANCELLED, progress = "已取消", error = "") else it }
            event(id, "CANCELLED", "执行环境已确认停止")
        } else {
            update(id) { if (it.state == TaskStates.CANCELLING) it.copy(error = if (unreachable)
                "取消请求暂未送达执行环境，命令可能仍在运行；重连后会自动重试" else "执行环境仍在停止进程，稍后再次确认") else it }
        }
    }

    /** 周期同步时补发尚未确认的取消。 */
    private suspend fun retryPendingCancels() {
        dao.cancelling().forEach { confirmRemoteCancel(it.id) }
    }
    fun resume(id: String) { scope.launch {
        ready.await()
        val row = dao.task(id) ?: return@launch
        if (row.source == "script") {
            update(id) { it.copy(error = "固定脚本已经提交到 Debian，可查看真实执行结果；重跑请使用显式重跑") }
            return@launch
        }
        if (row.state in setOf(TaskStates.RUNNING, TaskStates.QUEUED, TaskStates.SUCCEEDED, TaskStates.CANCELLING)) return@launch
        if (row.state == TaskStates.RESULT_UNKNOWN) {
            val calls = dao.calls(id).filter { it.state == TaskStates.RESULT_UNKNOWN }
            if (calls.isEmpty() || calls.any { it.executionId.isBlank() }) {
                update(id) { it.copy(error = "存在无法确认结果的操作，请检查记录并显式重跑；恢复不会重放该调用") }
                return@launch
            }
            calls.forEach { dao.saveCall(it.copy(state = "RUNNING", result = "", encryptedResult = "")) }
            dao.checkpoint(id)?.let { checkpoint ->
                val messages = restoreCheckpoint(checkpoint.messages, id)
                val unresolved = calls.map { it.id.removePrefix("$id:") }.toSet()
                val updated = JSONArray()
                for (index in 0 until messages.length()) {
                    val message = messages.getJSONObject(index)
                    if (message.optString("tool_call_id") !in unresolved) updated.put(message)
                }
                dao.saveCheckpoint(CheckpointRecord(id, persistCheckpoint(updated, id), System.currentTimeMillis()))
            }
        }
        update(id) { it.copy(state = TaskStates.QUEUED, error = "", progress = "等待恢复") }
        event(id, "RESUMED", "从检查点恢复")
        startFromForeground(); if (serviceRunning) schedule()
    } }
    fun rerun(id: String) { scope.launch {
        ready.await()
        val row = dao.task(id) ?: return@launch
        if (row.source == "script") {
            if (!com.voice.actonsay.asr.AsrManager.state.value.debianExecutionAllowed) {
                update(id) { it.copy(error = "请先在权限设置允许 Debian 执行，再显式重跑") }
                return@launch
            }
            // 执行服务离线时把原因留在任务上，未捕获的异常会终止整个应用进程。
            try {
                BridgeClient.request("POST", "/v1/executions/${row.remoteExecutionId}/rerun", JSONObject().put("request_id", UUID.randomUUID().toString())
                    .put("task_id", UUID.randomUUID().toString()))
                update(id) { it.copy(error = "") }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                update(id) { it.copy(error = "重跑未提交：${CredentialRedactor.text(error.message.orEmpty())}") }
                return@launch
            }
            // 新执行已提交；同步失败时由服务的周期同步补齐。
            runCatching { syncBridge() }
            return@launch
        }
        try {
            submit(row.goal, row.conversationId, parseHistory(row.historyJson), "rerun")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            update(id) { it.copy(error = "重跑未提交：${CredentialRedactor.text(error.message.orEmpty())}") }
        }
    } }
    fun resolveUnknown(taskId: String, status: String, note: String) { scope.launch {
        ready.await()
        val decision = status.uppercase()
        if (decision !in listOf(TaskStates.SUCCEEDED, TaskStates.FAILED, TaskStates.CANCELLED) || note.isBlank()) return@launch
        val task = dao.task(taskId) ?: return@launch
        if (task.state != TaskStates.RESULT_UNKNOWN) return@launch
        try {
            val calls = dao.calls(taskId).filter { it.state in listOf("RUNNING", TaskStates.RESULT_UNKNOWN) }
            for (call in calls) {
                if (call.executionId.isNotBlank()) {
                    val path = if (call.executionId.startsWith("mcp:")) "/v1/mcp-calls/${call.executionId.removePrefix("mcp:")}/resolve"
                        else "/v1/executions/${call.executionId}/resolve"
                    BridgeClient.request("POST", path, JSONObject().put("status", status.lowercase()).put("note", note.take(2000)))
                }
                val result = ToolResult(decision, "用户人工核实：${note.take(2000)}", executionId = call.executionId.ifBlank { null },
                    data = JSONObject().put("source", "user_verification"))
                dao.saveCall(call.copy(state = decision, result = result.displayJson().toString(),
                    encryptedResult = TaskSecretVault.encrypt(result.toJson().toString())))
                dao.checkpoint(taskId)?.let { checkpoint ->
                    val messages = restoreCheckpoint(checkpoint.messages, taskId)
                    for (index in 0 until messages.length()) {
                        val message = messages.optJSONObject(index) ?: continue
                        if (message.optString("tool_call_id") == call.id.removePrefix("$taskId:")) message.put("content", result.modelContent())
                    }
                    dao.saveCheckpoint(CheckpointRecord(taskId, persistCheckpoint(messages, taskId), System.currentTimeMillis()))
                }
            }
            event(taskId, "USER_VERIFICATION", "${decision}：${note.take(2000)}")
            if (task.triggerId != null) dao.preference(PreferenceRecord("_trigger_resolution_${task.id}", TaskSecretVault.encrypt(note.take(2000))))
            if (task.source == "script" || decision == TaskStates.CANCELLED) {
                update(taskId) { it.copy(state = decision, progress = "已人工核实", error = "") }
                dao.task(taskId)?.let { notifyTrigger(it) }
            } else {
                update(taskId) { it.copy(state = TaskStates.QUEUED, progress = "根据人工核实结果继续", error = "") }
                startFromForeground(); if (serviceRunning) schedule()
            }
        } catch (error: Exception) {
            update(taskId) { it.copy(error = "人工核实结果尚未同步：${CredentialRedactor.text(error.message.orEmpty())}") }
        }
    } }
    fun grantAuthorization(id: String, callId: String, allowed: Boolean) { scope.launch {
        ready.await()
        val localCallId = callId.removePrefix("$id:")
        val authorization = dao.authorization(id, localCallId) ?: return@launch
        val task = dao.task(id) ?: return@launch
        if (task.state != TaskStates.WAITING_CONFIRMATION) return@launch
        dao.authorize(authorization.copy(decision = if (allowed) "ALLOWED" else "DENIED", updatedAt = System.currentTimeMillis()))
        val call = dao.call("$id:$localCallId")
        if (call != null) dao.saveCall(call.copy(state = "WAITING_CONFIRMATION"))
        event(id, "AUTHORIZATION", "本次调用${if (allowed) "已允许" else "已拒绝"}：$callId")
        update(id) { it.copy(state = TaskStates.QUEUED, error = "") }
        startFromForeground(); if (serviceRunning) schedule()
    } }
    fun savePreference(key: String, value: String) { scope.launch {
        ready.await()
        require(key.isNotBlank() && !key.startsWith("_"))
        dao.preference(PreferenceRecord(key.trim(), value.trim()))
    } }
    fun pauseAutomation(paused: Boolean) { scope.launch {
        ready.await(); _automationPaused.value = paused
        dao.preference(PreferenceRecord("_automation_paused", paused.toString()))
        dao.preference(PreferenceRecord("_automation_sync_pending", "true"))
        syncAutomationPause()
        if (!paused) schedule()
    } }
    fun events(id: String): Flow<List<TaskEvent>> = dao.observeEvents(id)
    fun toolCalls(id: String): Flow<List<ToolCallRecord>> = dao.observeCalls(id)
    fun memoryText(): String = preferences.value.filter { it.value.isNotBlank() }.joinToString("\n") { "${it.key}: ${it.value}" }

    /** 调度领取的幂等键必须沿用服务返回的触发 ID；确认只发生在提交已落库之后。 */
    suspend fun receiveTrigger(trigger: JSONObject): String {
        ready.await()
        val triggerId = trigger.getString("id")
        val owner = ownerId()
        val claimed = BridgeClient.request("POST", "/v1/ai-triggers/$triggerId/claim", JSONObject().put("owner_id", owner))
        val id = submit(trigger.optString("goal").ifBlank { trigger.getJSONObject("payload").getString("goal") },
            source = "automation", triggerId = triggerId)
        BridgeClient.request("POST", "/v1/ai-triggers/$triggerId/ack", JSONObject().put("owner_id", owner).put("task_id", id))
        update(id) { it.copy(triggerAcknowledged = true) }
        schedule()
        return id
    }

    private suspend fun schedule() {
        ready.await()
        if (!serviceRunning) return
        queueMutex.withLock {
            val completed = jobs.filterValues { it.isCompleted }.keys.toList()
            completed.forEach { jobs.remove(it); jobSlots.remove(it) }
            // 转后台只改变展示；后台槽空出后才迁移占用，始终最多运行两个任务。
            if (jobSlots.values.none { !it }) {
                for ((id, foreground) in jobSlots) {
                    if (foreground && dao.task(id)?.visible == true) {
                        jobSlots[id] = false
                        break
                    }
                }
            }
            val queued = dao.queued()
            for (task in queued) {
                if (task.source == "script") continue
                if (_automationPaused.value && task.source == "automation") continue
                if (task.triggerId != null && !task.triggerAcknowledged) continue
                // 已转入后台的回合恢复时按后台任务排队，不再占用前台槽。
                val slot = task.source in FOREGROUND_SOURCES && !task.visible
                val busy = jobSlots.values.any { it == slot }
                if (busy) continue
                jobSlots[task.id] = slot
                jobs[task.id] = scope.launch(start = CoroutineStart.LAZY) { runTask(task) }.also { it.start() }
            }
        }
    }

    private suspend fun runTask(task: TaskRecord) {
        update(task.id) { if (TaskStates.cancelRequested(it.state)) it else it.copy(state = TaskStates.RUNNING, progress = "任务开始", error = "") }
        if (dao.task(task.id)?.state?.let(TaskStates::cancelRequested) != false) {
            queueMutex.withLock { jobs.remove(task.id); jobSlots.remove(task.id) }
            schedule()
            return
        }
        event(task.id, "RUNNING", "任务开始")
        Diagnostics.taskStarted(task.id)
        var lastDelta = 0L
        var firstToken = true
        var lastPersistedAnswer: String? = null
        // 实时文本走内存；检查点及最终结果仍立即落库，中途文本每两秒保存一次。
        val answerWriter = scope.launch {
            while (isActive) {
                delay(2000)
                val answer = liveAnswers.value[task.id] ?: continue
                if (answer != lastPersistedAnswer) {
                    try {
                        update(task.id) { if (it.state == TaskStates.RUNNING) it.copy(answer = answer) else it }
                        lastPersistedAnswer = answer
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        _serviceError.value = "中途回答暂未保存，将在任务结束时再次保存。"
                        break
                    }
                }
            }
        }
        val foreground = task.source == "turn" && !task.visible
        val startedAt = System.currentTimeMillis()
        toolsStarted[task.id] = java.util.concurrent.atomic.AtomicInteger(0)
        // 是否转后台首先由模型决定；这里只是兜底，前台回合超出预算时原地转为后台任务，不中断也不重来。
        val promoter = if (!foreground) null else scope.launch {
            while (isActive) {
                delay(3000)
                val started = toolsStarted[task.id]?.get() ?: 0
                if (started >= FOREGROUND_TOOL_BUDGET || (started >= 1 && System.currentTimeMillis() - startedAt > FOREGROUND_SECONDS_BUDGET * 1000L)) {
                    promote(task.id)
                    break
                }
            }
        }
        try {
            val config = restoreConfig(task.configSnapshot)
            val memory = memoryText()
            val others = tasks.value.filter { it.visible && it.id != task.id }.take(8)
            val runContext = when {
                task.source == "turn" && others.isNotEmpty() -> "\n应用提供的后台任务快照（详情用 get_task 查询）：\n" +
                    others.joinToString("\n") { "- ${it.id} [${it.state}] ${it.goal.take(120)}" }
                task.source == "turn" -> ""
                else -> "\n本次是后台任务：用户不在等待即时回复，请直接完成目标并给出完整结果，不要再创建后台任务。"
            }
            val enrichedConfig = config.copy(systemPrompt = config.systemPrompt + runContext +
                if (memory.isBlank()) "" else "\n用户明确保存的长期偏好与项目备注：\n$memory")
            // 排队的回合在真正开始时才确定上下文，排队期间结束的前几轮结果在这里补齐。
            val history = currentHistory(task)
            val result = withTimeout(config.maxTaskSeconds.coerceIn(30, 86400) * 1000L) {
                val onDelta: (String) -> Unit = { answer ->
                    if (firstToken) { firstToken = false; Diagnostics.firstToken(task.id) }
                    val now = System.currentTimeMillis()
                    if (now - lastDelta > 200) {
                        lastDelta = now
                        liveAnswers.update { it + (task.id to CredentialRedactor.text(answer)) }
                    }
                }
                if (task.source == "chat") {
                    OpenAiClient.chatCompletion(config.baseUrl, config.apiKey, config.model, enrichedConfig.systemPrompt +
                        "\n本轮是持续交流，没有操作工具。可以解释已有任务状态；需要新操作时请说明应提交独立任务，不能声称已经执行。\n" +
                        "应用提供的任务状态快照：\n" + tasks.value.filter { it.visible }.take(5).joinToString("\n") {
                            "任务 ${it.id}: ${it.goal.take(200)}；状态${it.state}；进度${it.progress.take(200)}；错误${it.error.take(200)}；已有结果${it.answer.take(1000)}"
                        },
                        task.goal, history, config.stream, onDelta, config.apiFormat)
                } else {
                    AgentRunner.run(enrichedConfig, history, task.goal, onDelta,
                        onStep = { step -> scope.launch { event(task.id, "STEP", step.render()) } },
                        onProgress = { progress -> scope.launch { update(task.id) { if (it.state == TaskStates.RUNNING) it.copy(progress = progress) else it } } },
                        taskId = task.id, observer = observer(task.id),
                        resumeMessages = dao.checkpoint(task.id)?.messages?.let { restoreCheckpoint(it, task.id) })
                }
            }
            result.fold(onSuccess = { answer ->
                update(task.id) { if (TaskStates.cancelRequested(it.state)) it else it.copy(state = TaskStates.SUCCEEDED, answer = CredentialRedactor.text(answer), progress = "已完成") }
                if (dao.task(task.id)?.state == TaskStates.SUCCEEDED) event(task.id, "SUCCEEDED", answer)
            }, onFailure = { error -> fail(task.id, TaskStates.FAILED, error.message ?: "任务失败") })
        } catch (error: AuthorizationRequired) {
            dao.call("${task.id}:${error.callId}")?.let { dao.saveCall(it.copy(state = "WAITING_CONFIRMATION")) }
            update(task.id) { if (TaskStates.cancelRequested(it.state)) it else it.copy(state = TaskStates.WAITING_CONFIRMATION, progress = "等待确认", error = "请在任务页授权本次工具调用") }
            event(task.id, "WAITING_CONFIRMATION", error.callId)
        } catch (_: UncertainToolResult) {
            fail(task.id, TaskStates.RESULT_UNKNOWN, "上次操作结果待确认，不会自动重放")
        } catch (error: TimeoutCancellationException) {
            withContext(NonCancellable) { fail(task.id, TaskStates.FAILED, "任务超出时间预算") }
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                update(task.id) { if (TaskStates.cancelRequested(it.state)) it else it.copy(state = TaskStates.WAITING_APP, progress = "等待应用恢复") }
            }
        } catch (error: Exception) {
            fail(task.id, TaskStates.FAILED, error.message ?: "任务失败")
        } finally {
            promoter?.cancel()
            toolsStarted.remove(task.id)
            // 停止运行即交还浏览器与虚拟屏；等待授权后恢复时会重新申请，并按页面版本要求重新读取。
            ResourceLeases.releaseAll(task.id)
            withContext(NonCancellable) {
                answerWriter.cancelAndJoin()
                try {
                    val pendingAnswer = liveAnswers.value[task.id]
                    if (pendingAnswer != null) update(task.id) {
                        if (it.state != TaskStates.SUCCEEDED) it.copy(answer = pendingAnswer) else it
                    }
                    dao.task(task.id)?.let { notifyTrigger(it); Diagnostics.taskFinished(it.id, it.state) }
                } finally {
                    liveAnswers.update { it - task.id }
                    queueMutex.withLock { jobs.remove(task.id); jobSlots.remove(task.id) }
                    if (serviceRunning) schedule()
                }
            }
        }
    }

    private suspend fun promote(id: String) {
        update(id) { if (it.state == TaskStates.RUNNING) it.copy(visible = true) else it }
        if (dao.task(id)?.visible != true) return
        // 后台槽已占用时保留原槽，让当前操作继续完成，后续前台请求排队。
        queueMutex.withLock { if (jobSlots.containsKey(id) && jobSlots.values.none { !it }) jobSlots[id] = false }
        event(id, "PROMOTED", "前台回合超出预算，已转为后台任务继续执行")
        schedule()
    }

    /** 尚未结束、仍占用前台槽的回合；新消息据此决定排队、打断或合并。 */
    fun activeForegroundTurn(): TaskRecord? = tasks.value.filter {
        it.source in FOREGROUND_SOURCES && !it.visible && it.state in setOf(TaskStates.QUEUED, TaskStates.RUNNING)
    }.minByOrNull { it.createdAt }
    suspend fun hasSideEffects(id: String): Boolean { ready.await(); return dao.calls(id).any { it.name !in REPLAYABLE_TOOLS } }
    suspend fun task(id: String): TaskRecord? { ready.await(); return dao.task(id) }

    private fun observer(taskId: String) = object : AgentObserver {
        override suspend fun checkpoint(messages: JSONArray) {
            dao.saveCheckpoint(CheckpointRecord(taskId, persistCheckpoint(messages, taskId), System.currentTimeMillis()))
        }
        override suspend fun beginTool(call: ToolCall): ToolResult? {
            val old = dao.call("$taskId:${call.id}")
            if (old != null && old.result.isNotBlank()) return ToolResult.fromJson(JSONObject(if (old.encryptedResult.isNotBlank()) TaskSecretVault.decrypt(old.encryptedResult) else old.result))
            if (old != null && old.state == "RUNNING") {
                if (old.executionId.isNotBlank()) {
                    val toolContext = ToolContext(context!!, {}, taskId, call.id, currentCoroutineContext()[Job], observer = this)
                    if (old.executionId.startsWith("mcp:")) return McpCallExecution.await(old.executionId.removePrefix("mcp:"), toolContext)
                    return DebianExecutionTool.awaitExecution(old.executionId, toolContext)
                }
                if (old.name !in REPLAYABLE_TOOLS) throw UncertainToolResult()
            }
            toolsStarted[taskId]?.incrementAndGet()
            Diagnostics.toolStarted(taskId, call.id)
            val displayArguments = runCatching { CredentialRedactor.json(JSONObject(call.arguments)).toString() }.getOrElse { "参数无效" }
            dao.saveCall(ToolCallRecord("$taskId:${call.id}", taskId, call.name, displayArguments, "RUNNING",
                encryptedArguments = TaskSecretVault.encrypt(call.arguments)))
            event(taskId, "TOOL_STARTED", call.name)
            return null
        }
        override suspend fun finishTool(call: ToolCall, result: ToolResult) {
            Diagnostics.toolFinished(taskId, call.id, call.name, result.status)
            if (!result.ok && result.errorType != null) Diagnostics.failure("工具 ${call.name}", "${result.errorType}：${result.output.take(200)}")
            val old = dao.call("$taskId:${call.id}") ?: return
            dao.saveCall(old.copy(state = result.status, result = result.displayJson().toString(),
                encryptedResult = TaskSecretVault.encrypt(result.toJson().toString()),
                executionId = result.executionId ?: old.executionId, updatedAt = System.currentTimeMillis()))
            event(taskId, "TOOL_RESULT", result.displayJson().toString())
        }
        override suspend fun executionStarted(callId: String, executionId: String) {
            val old = dao.call("$taskId:$callId") ?: return
            dao.saveCall(old.copy(executionId = executionId, updatedAt = System.currentTimeMillis()))
        }
        override suspend fun authorize(callId: String, environment: ExecutionEnvironment): Boolean? {
            val authorization = dao.authorization(taskId, callId)
            if (authorization == null) {
                dao.authorize(AuthorizationRecord(taskId, callId, environment.name))
                return null
            }
            return when (authorization.decision) { "ALLOWED" -> true; "DENIED" -> false; else -> null }
        }
    }

    private suspend fun update(id: String, transform: (TaskRecord) -> TaskRecord) = recordMutex.withLock {
        dao.task(id)?.let { row -> dao.updateTask(transform(row).copy(updatedAt = System.currentTimeMillis())) }
    }
    private suspend fun event(id: String, type: String, content: String) {
        dao.event(TaskEvent(UUID.randomUUID().toString(), id, type, CredentialRedactor.text(content)))
    }
    private suspend fun fail(id: String, state: String, error: String) {
        update(id) { if (TaskStates.cancelRequested(it.state)) it else it.copy(state = state, error = CredentialRedactor.text(error), progress = CredentialRedactor.text(error)) }; event(id, state, error)
        Diagnostics.failure(if (state == TaskStates.RESULT_UNKNOWN) "结果待确认" else "任务执行", error)
    }

    /**
     * 前台回合按聊天记录重建上下文：排在前面的回合此时已经结束，用它的最终结果代替提交时还在加载的空回复；
     * 已转后台仍在执行的，说明其状态。找不到对应聊天记录时（例如刚提交就立即执行）沿用提交时的快照。
     */
    private suspend fun currentHistory(task: TaskRecord): List<ChatTurn> {
        val snapshot = parseHistory(task.historyJson)
        if (task.source !in FOREGROUND_SOURCES) return snapshot
        val rows = dao.messagesFor(task.conversationId)
        val own = rows.indexOfLast { it.taskId == task.id }
        val userIndex = (own - 1 downTo 0).firstOrNull { rows[it].role == "user" } ?: return snapshot
        val limit = maxOf(snapshot.size, com.voice.actonsay.asr.AsrManager.state.value.aiContextTurns * 2)
        if (limit <= 0) return emptyList()
        val turns = mutableListOf<ChatTurn>()
        for (row in rows.subList(0, userIndex)) {
            val meta = runCatching { JSONObject(row.metadata) }.getOrDefault(JSONObject())
            if (row.role == "user") {
                if (row.content.isNotBlank() && !meta.optBoolean("error")) turns += ChatTurn("user", row.content)
                continue
            }
            val linked = row.taskId.takeIf { it.isNotBlank() }?.let { dao.task(it) }
            val content = when {
                linked == null -> row.content.takeIf { !meta.optBoolean("loading") && !meta.optBoolean("error") }
                linked.state == TaskStates.SUCCEEDED -> linked.answer
                linked.visible && linked.state in setOf(TaskStates.QUEUED, TaskStates.RUNNING, TaskStates.WAITING_CONFIRMATION, TaskStates.WAITING_APP) ->
                    "（这条请求已转为后台任务 ${linked.id}，仍在执行，结果可用 get_task 查询）"
                else -> null
            }
            if (!content.isNullOrBlank()) turns += ChatTurn("assistant", content)
        }
        return turns.takeLast(limit)
    }
    private suspend fun notifyTrigger(task: TaskRecord) {
        if (task.triggerId == null || task.state !in listOf(TaskStates.SUCCEEDED, TaskStates.FAILED, TaskStates.CANCELLED, TaskStates.RESULT_UNKNOWN)) return
        val marker = "_trigger_synced_${task.id}"
        if (dao.preference(marker)?.value == task.state) return
        runCatching {
            val resolution = dao.preference("_trigger_resolution_${task.id}")
            val previouslyUnknown = dao.preference(marker)?.value == TaskStates.RESULT_UNKNOWN
            if (resolution != null && previouslyUnknown && task.state != TaskStates.RESULT_UNKNOWN) {
                BridgeClient.request("POST", "/v1/ai-triggers/${task.triggerId}/resolve", JSONObject().put("task_id", task.id)
                    .put("status", task.state.lowercase()).put("note", CredentialRedactor.text(TaskSecretVault.decrypt(resolution.value))))
            } else BridgeClient.request("POST", "/v1/ai-triggers/${task.triggerId}/complete",
                JSONObject().put("task_id", task.id).put("status", task.state.lowercase()).put("result", task.answer).put("error", task.error))
            dao.preference(PreferenceRecord(marker, task.state))
        }
    }
    suspend fun syncTriggerResults() {
        ready.await()
        dao.unsyncedTriggers().forEach { notifyTrigger(it) }
    }

    /** 只由数据管理页显式调用；保留活动任务、聊天引用及尚未同步的自动化结果。 */
    suspend fun clearFinishedHistory(olderThanDays: Int): Int {
        ready.await()
        require(olderThanDays in setOf(7, 30, 90))
        val app = context ?: return 0
        val root = File(app.filesDir, "tasks").canonicalFile
        val before = System.currentTimeMillis() - olderThanDays * 86_400_000L
        fun deleteFiles(file: File): Boolean {
            // 不跟随任务脚本留下的符号链接，也不触及其他任务目录。
            if (file.canonicalFile != file.absoluteFile) return false
            if (file.isDirectory) {
                val children = file.listFiles() ?: return false
                if (!children.all { deleteFiles(it) }) return false
            }
            return file.delete()
        }
        return queueMutex.withLock {
            recordMutex.withLock {
                var removed = 0
                for (candidate in dao.cleanupCandidates(before)) {
                    if (jobs.containsKey(candidate.id)) continue
                    // 数据库只提供 ID，绝不信任存储的 workDir 作为删除路径。
                    if (!candidate.id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) continue
                    val directory = File(root, candidate.id)
                    if (directory.canonicalFile != directory || directory.parentFile != root) continue
                    if (directory.exists() && !deleteFiles(directory)) continue
                    TaskRepository.database.withTransaction {
                        dao.deleteCalls(candidate.id)
                        dao.deleteEvents(candidate.id)
                        dao.deleteCheckpoint(candidate.id)
                        dao.deleteAuthorizations(candidate.id)
                        dao.deleteTask(candidate.id)
                    }
                    removed++
                }
                val references = dao.payloadReferences() + dao.configSnapshots().map { JSONObject(it).optString("encryptedApiKey") }
                TaskSecretVault.pruneUnreferenced(references, before)
                removed
            }
        }
    }
    fun announceCompletion(task: TaskRecord) { scope.launch {
        ready.await()
        if (task.state !in listOf(TaskStates.SUCCEEDED, TaskStates.FAILED)) return@launch
        val app = context ?: return@launch
        if (Build.VERSION.SDK_INT >= 33 && app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return@launch
        val marker = "_notified_${task.id}"
        if (dao.preference(marker)?.value == task.state) return@launch
        val open = PendingIntent.getActivity(app, task.id.hashCode(), Intent(app, MainActivity::class.java)
            .putExtra("open_tasks", true).putExtra("task_id", task.id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // 定时任务多是提醒：用带声音的高优先级渠道，正文直接显示结果。
        val reminder = task.source == "automation"
        if (reminder && Build.VERSION.SDK_INT >= 26) app.getSystemService(NotificationManager::class.java).createNotificationChannel(
            android.app.NotificationChannel("actonsay_reminders", "定时提醒", NotificationManager.IMPORTANCE_HIGH))
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(app, if (reminder) "actonsay_reminders" else "actonsay_tasks") else Notification.Builder(app)
        val body = if (reminder && task.answer.isNotBlank()) task.answer.replace(Regex("[#*`>_]"), "").trim().take(300) else task.goal.take(80)
        val notification = builder.setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(if (reminder) "定时提醒" else if (task.state == TaskStates.SUCCEEDED) "任务已完成" else "任务失败")
            .setContentText(body).setStyle(Notification.BigTextStyle().bigText(body)).setContentIntent(open).setAutoCancel(true).build()
        runCatching {
            app.getSystemService(NotificationManager::class.java).notify(task.id, task.id.hashCode(), notification)
            dao.preference(PreferenceRecord(marker, task.state))
        }
    } }
    private suspend fun ownerId(): String = ownerMutex.withLock {
        val previous = dao.preference("_bridge_owner")
        if (previous != null) return@withLock previous.value
        val id = UUID.randomUUID().toString()
        dao.preference(PreferenceRecord("_bridge_owner", id))
        id
    }
    private suspend fun syncAutomationPause() {
        if (dao.preference("_automation_sync_pending")?.value == "true") {
            runCatching {
                BridgeClient.request("PATCH", "/v1/automation", JSONObject().put("paused", _automationPaused.value))
                dao.preference(PreferenceRecord("_automation_sync_pending", "false"))
            }
        } else {
            val status = BridgeClient.request("GET", "/v1/automation")
            if (dao.preference("_automation_sync_pending")?.value != "true") {
                _automationPaused.value = status.optBoolean("paused")
                dao.preference(PreferenceRecord("_automation_paused", _automationPaused.value.toString()))
            }
        }
    }
    /** 重连补偿暂停状态、已完成 AI 触发、待领取触发和固定脚本执行。 */
    suspend fun syncBridge() = bridgeSyncMutex.withLock {
        ready.await()
        BridgeClient.health()
        retryPendingCancels()
        syncAutomationPause()
        syncTriggerResults()
        if (!_automationPaused.value) {
            val triggers = BridgeClient.request("GET", "/v1/ai-triggers?include_completed=false").optJSONArray("triggers") ?: JSONArray()
            val owner = ownerId()
            for (index in 0 until triggers.length()) {
                val trigger = triggers.getJSONObject(index)
                val status = trigger.optString("status")
                if (status == "pending" || (status == "claimed" && trigger.optString("owner_id") == owner)) {
                    runCatching { receiveTrigger(trigger) }
                } else if (status == "acknowledged" && trigger.optString("owner_id") == owner) {
                    dao.trigger(trigger.getString("id"))?.let { task ->
                        if (task.id == trigger.optString("task_id")) update(task.id) { it.copy(triggerAcknowledged = true) }
                    }
                }
            }
            schedule()
        }
        val executions = BridgeClient.request("GET", "/v1/executions?limit=200").optJSONArray("executions") ?: JSONArray()
        for (index in 0 until executions.length()) syncExecution(executions.getJSONObject(index))
        val cursorKey = "_event_cursor_${com.voice.actonsay.asr.AsrManager.state.value.bridgeToken.hashCode()}"
        var cursor = dao.preference(cursorKey)?.value?.toLongOrNull() ?: 0L
        repeat(4) {
            val page = BridgeClient.request("GET", "/v1/events?after=$cursor&limit=20")
            val events = page.optJSONArray("events") ?: JSONArray()
            if (events.length() == 0) return@withLock
            val results = mutableListOf<JSONObject>()
            val ids = linkedSetOf<String>()
            for (index in 0 until events.length()) {
                val event = events.getJSONObject(index)
                if (event.optString("type").startsWith("execution.")) ids.add(event.getString("entity_id"))
            }
            for (executionId in ids) results.add(BridgeClient.request("GET", "/v1/executions/$executionId"))
            val nextCursor = page.getLong("cursor")
            // 当前页所有 Android 记录成功落库后，才移动同一事务中的游标。
            TaskRepository.database.withTransaction {
                for (execution in results) syncExecution(execution)
                dao.preference(PreferenceRecord(cursorKey, nextCursor.toString()))
            }
            cursor = nextCursor
        }
    }
    private suspend fun syncExecution(execution: JSONObject) {
            val executionId = execution.getString("id")
            val taskId = execution.optString("task_id").ifBlank { "execution-$executionId" }
            val old = dao.task(taskId)
            if (old != null && old.source != "script") return
            val remoteState = execution.optString("status").uppercase().let { if (it == "STARTING") TaskStates.RUNNING else it }
            // 用户已请求取消时，执行端结束前保持“正在取消”，不被周期同步改回执行中。
            val state = if (old?.state == TaskStates.CANCELLING && remoteState !in setOf(TaskStates.SUCCEEDED, TaskStates.FAILED,
                    TaskStates.CANCELLED, TaskStates.RESULT_UNKNOWN)) TaskStates.CANCELLING else remoteState
            // 周期同步会重复拿到同一批执行；状态未变化时不重写记录，避免无意义的落库与界面刷新。
            if (old != null && old.state == state && old.remoteExecutionId == executionId && dao.call("$taskId:execution")?.state == state) return
            val row =(old ?: TaskRecord(taskId, CredentialRedactor.text(execution.optString("label", "Debian 执行").take(120)),
                source = "script", remoteExecutionId = executionId)).copy(state = state,
                progress = "Debian 执行：$state", error = CredentialRedactor.text(execution.optString("error_type")), updatedAt = System.currentTimeMillis())
            if (old == null) dao.insertTask(row) else dao.updateTask(row)
            val toolResult = ToolResult(state, "Debian 执行 $executionId",
                if (execution.has("exit_code") && !execution.isNull("exit_code")) execution.optInt("exit_code") else null,
                "/v1/executions/$executionId/log", execution.optString("error_type").ifBlank { null }, executionId,
                execution.optJSONArray("artifacts") ?: JSONArray(), execution)
            dao.saveCall(ToolCallRecord("$taskId:execution", taskId, "debian_execute", "{}", state,
                toolResult.displayJson().toString(), executionId,
                encryptedResult = TaskSecretVault.encrypt(toolResult.toJson().toString())))
            if (old?.state != state) event(taskId, "TOOL_RESULT", toolResult.displayJson().toString())
            if (old?.state != state && state in listOf(TaskStates.SUCCEEDED, TaskStates.FAILED)) announceCompletion(row)
    }
    private fun snapshot(config: AgentConfig): String {
        CredentialRedactor.remember(config.apiKey)
        return JSONObject().put("baseUrl", config.baseUrl).put("model", config.model)
        .put("systemPrompt", config.systemPrompt).put("stream", config.stream).put("apiFormat", config.apiFormat.name)
        .put("encryptedApiKey", TaskSecretVault.encrypt(config.apiKey)).put("maxSteps", config.maxSteps)
        .put("maxContextChars", config.maxContextChars).put("maxTaskSeconds", config.maxTaskSeconds).put("supportsImages", config.supportsImages).toString()
    }
    private fun restoreConfig(json: String): AgentConfig {
        val value = JSONObject(json)
        val key = TaskSecretVault.decrypt(value.optString("encryptedApiKey"))
        CredentialRedactor.remember(key)
        return AgentConfig(value.getString("baseUrl"), key,
            value.getString("model"), value.optString("systemPrompt"), value.optBoolean("stream"),
            value.optInt("maxSteps", 30), value.optInt("maxContextChars", 60000), value.optInt("maxTaskSeconds", 1800), value.optBoolean("supportsImages", false),
            com.voice.actonsay.ai.AiApiFormat.from(value.optString("apiFormat")))
    }
    private fun parseHistory(json: String): List<ChatTurn> {
        val rows = JSONArray(json)
        return List(rows.length()) { rows.getJSONObject(it).let { row -> ChatTurn(row.optString("role"), row.optString("content")) } }
    }
    /** 图片内容另存任务私有文件，Room 检查点只保存引用，避免单行超出 CursorWindow。 */
    private fun persistCheckpoint(messages: JSONArray, taskId: String): String {
        val copy = JSONArray(messages.toString())
        for (index in 0 until copy.length()) {
            val parts = copy.optJSONObject(index)?.optJSONArray("content") ?: continue
            for (partIndex in 0 until parts.length()) {
                val image = parts.optJSONObject(partIndex)?.optJSONObject("image_url") ?: continue
                val url = image.optString("url")
                if (!url.startsWith("data:image/")) continue
                val mime = url.substringAfter("data:").substringBefore(';')
                val encoded = url.substringAfter("base64,", "")
                if (encoded.isBlank()) continue
                val bytes = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
                require(bytes.size <= 2 * 1024 * 1024)
                val fileId = UUID.nameUUIDFromBytes(bytes).toString()
                val file = File(context!!.filesDir, "tasks/$taskId/vision/$fileId").apply { parentFile?.mkdirs() }
                if (!file.exists()) file.writeBytes(bytes)
                image.put("url", "actonsay-image:$fileId").put("mime_type", mime)
            }
        }
        return TaskSecretVault.encrypt(copy.toString())
    }
    private fun restoreCheckpoint(encrypted: String, taskId: String): JSONArray {
        val messages = JSONArray(TaskSecretVault.decrypt(encrypted))
        for (index in 0 until messages.length()) {
            val parts = messages.optJSONObject(index)?.optJSONArray("content") ?: continue
            for (partIndex in 0 until parts.length()) {
                val image = parts.optJSONObject(partIndex)?.optJSONObject("image_url") ?: continue
                val value = image.optString("url")
                if (!value.startsWith("actonsay-image:")) continue
                val id = value.removePrefix("actonsay-image:")
                require(id.matches(Regex("[A-Za-z0-9_-]{1,128}")))
                val file = File(context!!.filesDir, "tasks/$taskId/vision/$id")
                if (file.exists() && file.length() <= 2 * 1024 * 1024L) {
                    image.put("url", "data:${image.optString("mime_type")};base64,${android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP)}")
                } else {
                    parts.put(partIndex, JSONObject().put("type", "text").put("text", "先前图片文件不可用，请根据产物记录重新获取。"))
                }
                image.remove("mime_type")
            }
        }
        return messages
    }
}
