package com.voice.actonsay.tasks

import android.content.Context
import androidx.room.withTransaction
import com.voice.actonsay.asr.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** 搜索结果：命中的消息及其所在会话。 */
data class MessageSearchHit(val conversationId: String, val conversationTitle: String, val message: ChatMessage)

/** Room 是聊天及 Android 任务的唯一写入端，旧 JSON 仅作为迁移来源保留。 */
object TaskRepository {
    const val DEFAULT_CONVERSATION = "default"
    private const val DEFAULT_TITLE = "新对话"
    private lateinit var context: Context
    val database: TaskDatabase get() = TaskDatabase.get(context)
    val dao: TaskDao get() = database.tasks()
    private val migrationLock = Mutex()
    fun init(context: Context) { this.context = context.applicationContext; TaskSecretVault.init(context) }

    fun observeConversations(): Flow<List<ConversationRecord>> = dao.observeConversations()

    suspend fun loadMessages(conversationId: String = DEFAULT_CONVERSATION): List<ChatMessage> = withContext(Dispatchers.IO) {
        migrateHistory()
        dao.messagesFor(conversationId).map { it.toChatMessage() }
    }

    suspend fun saveMessages(messages: List<ChatMessage>, conversationId: String = DEFAULT_CONVERSATION) = withContext(Dispatchers.IO) {
        migrateHistory()
        database.withTransaction {
            val existing = dao.messagesFor(conversationId).associateBy { it.id }
            val rows = messages.map { it.toRecord(conversationId) }
            val retained = rows.map { it.id }.toSet()
            val removed = existing.keys.filterNot { it in retained }
            if (removed.isNotEmpty()) dao.deleteMessages(removed)
            val changed = rows.filter { existing[it.id] != it }
            if (changed.isNotEmpty()) dao.messages(changed)
            // 标题取首条用户消息；没有内容变化时不刷新排序时间，避免切换会话就把旧会话顶到最前。
            val previous = dao.conversationById(conversationId)
            val title = titleFor(messages)
            if (previous == null || previous.title != title || changed.isNotEmpty() || removed.isNotEmpty()) {
                dao.conversation(ConversationRecord(conversationId, title,
                    if (previous == null || changed.isNotEmpty() || removed.isNotEmpty()) System.currentTimeMillis() else previous.updatedAt))
            }
        }
    }

    /** 新建一个空会话，立即落库以便出现在会话列表。 */
    suspend fun createConversation(): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        dao.conversation(ConversationRecord(id, DEFAULT_TITLE, System.currentTimeMillis()))
        id
    }

    /** 向不在前台的会话追加一条消息（例如其他会话创建的后台任务气泡）。 */
    suspend fun appendMessage(conversationId: String, message: ChatMessage) = withContext(Dispatchers.IO) {
        database.withTransaction {
            dao.messages(listOf(message.toRecord(conversationId)))
            val previous = dao.conversationById(conversationId)
            dao.conversation(ConversationRecord(conversationId, previous?.title ?: DEFAULT_TITLE, System.currentTimeMillis()))
        }
    }

    /** 删除会话及其消息；仍在运行的任务不受影响，记录保留在任务页。 */
    suspend fun deleteConversation(conversationId: String) = withContext(Dispatchers.IO) {
        database.withTransaction {
            dao.clearMessages(conversationId)
            dao.deleteConversation(conversationId)
        }
    }

    suspend fun deleteAllConversations() = withContext(Dispatchers.IO) {
        database.withTransaction {
            dao.deleteAllMessages()
            dao.deleteAllConversations()
        }
    }

    suspend fun search(query: String, limit: Int = 50): List<MessageSearchHit> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext emptyList()
        val escaped = trimmed.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val titles = mutableMapOf<String, String>()
        dao.searchMessages(escaped, limit).map { row ->
            val title = titles.getOrPut(row.conversationId) { dao.conversationById(row.conversationId)?.title ?: DEFAULT_TITLE }
            MessageSearchHit(row.conversationId, title, row.toChatMessage())
        }
    }

    private fun titleFor(messages: List<ChatMessage>): String =
        messages.firstOrNull { it.role == "user" && it.content.isNotBlank() }?.content?.trim()?.replace(Regex("\\s+"), " ")?.take(24)
            ?: DEFAULT_TITLE

    private fun MessageRecord.toChatMessage(): ChatMessage {
        val meta = JSONObject(metadata)
        return ChatMessage(id = id, role = role, content = content, timestamp = timestamp,
            wakeWord = meta.optString("wake"), isError = meta.optBoolean("error"), isLoading = meta.optBoolean("loading"),
            hitKeywords = strings(meta.optJSONArray("hits")), steps = strings(meta.optJSONArray("steps")), taskId = taskId)
    }

    private fun ChatMessage.toRecord(conversationId: String) = MessageRecord(id, conversationId, taskId, role, content, timestamp,
        JSONObject().put("wake", wakeWord).put("error", isError).put("loading", isLoading)
            .put("hits", JSONArray(hitKeywords)).put("steps", JSONArray(steps)).toString())

    private suspend fun migrateHistory() = migrationLock.withLock {
        if (dao.migration("chat_history_v1") != null) return@withLock
        val legacy = File(context.filesDir, "chat_history.json")
        val rows = if (legacy.exists()) {
            val input = JSONArray(legacy.readText())
            List(input.length()) { index ->
                val json = input.getJSONObject(index)
                MessageRecord(json.optString("id").ifBlank { UUID.randomUUID().toString() }, DEFAULT_CONVERSATION,
                    json.optString("taskId"), json.optString("role", "user"), json.optString("content"),
                    json.optLong("ts"), JSONObject().put("wake", json.optString("wake"))
                        .put("error", json.optBoolean("error"))
                        .put("hits", json.optJSONArray("hits") ?: JSONArray())
                        .put("steps", json.optJSONArray("steps") ?: JSONArray()).toString())
            }
        } else emptyList()
        database.withTransaction {
            if (dao.messagesFor(DEFAULT_CONVERSATION).isEmpty()) {
                dao.conversation(ConversationRecord(DEFAULT_CONVERSATION, "对话", System.currentTimeMillis()))
                dao.messages(rows)
            }
            dao.migration(MigrationMarker("chat_history_v1", System.currentTimeMillis()))
        }
        // 成功提交后再生成备份；无论备份结果如何均保留原文件。
        if (legacy.exists()) {
            val backup = File(context.filesDir, "chat_history.room-migrated.json")
            if (!backup.exists()) runCatching { legacy.copyTo(backup, overwrite = false) }
        }
    }
    private fun strings(array: JSONArray?): List<String> = if (array == null) emptyList() else List(array.length()) { array.optString(it) }
}
