package com.voice.actonsay.tasks

import android.content.Context
import androidx.room.withTransaction
import com.voice.actonsay.asr.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Room 是聊天及 Android 任务的唯一写入端，旧 JSON 仅作为迁移来源保留。 */
object TaskRepository {
    private lateinit var context: Context
    val database: TaskDatabase get() = TaskDatabase.get(context)
    val dao: TaskDao get() = database.tasks()
    private val migrationLock = Mutex()
    fun init(context: Context) { this.context = context.applicationContext; TaskSecretVault.init(context) }

    suspend fun loadMessages(): List<ChatMessage> = withContext(Dispatchers.IO) {
        migrateHistory()
        dao.messagesFor("default").map { row ->
            val meta = JSONObject(row.metadata)
            ChatMessage(id = row.id, role = row.role, content = row.content, timestamp = row.timestamp,
                wakeWord = meta.optString("wake"), isError = meta.optBoolean("error"), isLoading = meta.optBoolean("loading"),
                hitKeywords = strings(meta.optJSONArray("hits")), steps = strings(meta.optJSONArray("steps")), taskId = row.taskId)
        }
    }

    suspend fun saveMessages(messages: List<ChatMessage>) = withContext(Dispatchers.IO) {
        migrateHistory()
        database.withTransaction {
            dao.conversation(ConversationRecord("default", "对话", System.currentTimeMillis()))
            dao.clearMessages("default")
            dao.messages(messages.map { message ->
                MessageRecord(message.id, "default", message.taskId, message.role, message.content, message.timestamp,
                    JSONObject().put("wake", message.wakeWord).put("error", message.isError).put("loading", message.isLoading)
                        .put("hits", JSONArray(message.hitKeywords)).put("steps", JSONArray(message.steps)).toString())
            })
        }
    }

    private suspend fun migrateHistory() = migrationLock.withLock {
        if (dao.migration("chat_history_v1") != null) return@withLock
        val legacy = File(context.filesDir, "chat_history.json")
        val rows = if (legacy.exists()) {
            val input = JSONArray(legacy.readText())
            List(input.length()) { index ->
                val json = input.getJSONObject(index)
                MessageRecord(json.optString("id").ifBlank { UUID.randomUUID().toString() }, "default",
                    json.optString("taskId"), json.optString("role", "user"), json.optString("content"),
                    json.optLong("ts"), JSONObject().put("wake", json.optString("wake"))
                        .put("error", json.optBoolean("error"))
                        .put("hits", json.optJSONArray("hits") ?: JSONArray())
                        .put("steps", json.optJSONArray("steps") ?: JSONArray()).toString())
            }
        } else emptyList()
        database.withTransaction {
            if (dao.messagesFor("default").isEmpty()) {
                dao.conversation(ConversationRecord("default", "对话", System.currentTimeMillis()))
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
