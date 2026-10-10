package com.voice.actonsay.tasks

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

object TaskStates {
    const val QUEUED = "QUEUED"
    const val RUNNING = "RUNNING"
    const val WAITING_APP = "WAITING_APP"
    const val WAITING_CONFIRMATION = "WAITING_CONFIRMATION"
    const val SUCCEEDED = "SUCCEEDED"
    const val FAILED = "FAILED"
    const val CANCELLED = "CANCELLED"
    const val RESULT_UNKNOWN = "RESULT_UNKNOWN"
    /** 已请求取消，等待执行环境确认真正停止；确认前不显示为“已取消”。 */
    const val CANCELLING = "CANCELLING"
    /** 用户已取消（含等待执行端确认），本地结果不再覆盖。 */
    fun cancelRequested(state: String) = state == CANCELLED || state == CANCELLING
}

@Entity(tableName = "tasks", indices = [Index(value = ["triggerId"], unique = true)])
data class TaskRecord(
    @PrimaryKey val id: String,
    val goal: String,
    val state: String = TaskStates.QUEUED,
    val progress: String = "",
    val answer: String = "",
    val error: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val conversationId: String = "default",
    val source: String = "manual",
    val historyJson: String = "[]",
    val configSnapshot: String = "{}",
    val workDir: String = "",
    val triggerId: String? = null,
    val remoteExecutionId: String = "",
    val triggerAcknowledged: Boolean = false,
    /** 前台回合默认不作为任务展示；模型创建后台任务或回合超出前台预算后才可见。 */
    @ColumnInfo(defaultValue = "1") val visible: Boolean = true
)
@Entity(tableName = "conversations")
data class ConversationRecord(@PrimaryKey val id: String, val title: String, val updatedAt: Long)
@Entity(tableName = "messages", indices = [Index("conversationId"), Index("taskId")])
data class MessageRecord(@PrimaryKey val id: String, val conversationId: String, val taskId: String,
                         val role: String, val content: String, val timestamp: Long, val metadata: String)
@Entity(tableName = "tool_calls", indices = [Index("taskId")])
data class ToolCallRecord(@PrimaryKey val id: String, val taskId: String, val name: String, val arguments: String,
                          val state: String, val result: String = "", val executionId: String = "", val updatedAt: Long = System.currentTimeMillis(),
                          val encryptedArguments: String = "", val encryptedResult: String = "")
@Entity(tableName = "checkpoints")
data class CheckpointRecord(@PrimaryKey val taskId: String, val messages: String, val updatedAt: Long)
@Entity(tableName = "authorizations", primaryKeys = ["taskId", "callId"])
data class AuthorizationRecord(val taskId: String, val callId: String, val environment: String,
                               val decision: String = "PENDING", val updatedAt: Long = System.currentTimeMillis())
@Entity(tableName = "task_events", indices = [Index("taskId")])
data class TaskEvent(@PrimaryKey val id: String, val taskId: String, val type: String, val content: String,
                     val createdAt: Long = System.currentTimeMillis())
@Entity(tableName = "preferences")
data class PreferenceRecord(@PrimaryKey val key: String, val value: String, val updatedAt: Long = System.currentTimeMillis())
@Entity(tableName = "migrations")
data class MigrationMarker(@PrimaryKey val name: String, val completedAt: Long)

@Dao
interface TaskDao {
    // 列表只读取展示字段；执行和恢复仍通过 task()/queued() 读取完整快照。
    @Query("""SELECT id, goal, state, progress, answer, error, createdAt, updatedAt, conversationId, source,
        '[]' AS historyJson, '{}' AS configSnapshot, workDir, triggerId, remoteExecutionId, triggerAcknowledged, visible
        FROM tasks WHERE id IN (SELECT id FROM tasks ORDER BY createdAt DESC LIMIT :limit)
        OR state NOT IN ('SUCCEEDED','FAILED','CANCELLED')
        OR id IN (SELECT taskId FROM messages WHERE taskId != '') ORDER BY createdAt DESC""")
    fun observeTasks(limit: Int): Flow<List<TaskRecord>>
    @Query("SELECT COUNT(*) FROM tasks") fun observeTaskCount(): Flow<Int>
    @Query("""SELECT * FROM tasks WHERE triggerId IS NOT NULL AND state IN ('SUCCEEDED','FAILED','CANCELLED','RESULT_UNKNOWN')
        AND NOT EXISTS (SELECT 1 FROM preferences WHERE `key` = '_trigger_synced_' || tasks.id AND value = tasks.state)""")
    suspend fun unsyncedTriggers(): List<TaskRecord>
    @Query("SELECT * FROM tasks WHERE id = :id") suspend fun task(id: String): TaskRecord?
    @Query("SELECT * FROM tasks WHERE triggerId = :id LIMIT 1") suspend fun trigger(id: String): TaskRecord?
    @Query("SELECT * FROM tasks WHERE state = 'QUEUED' ORDER BY createdAt") suspend fun queued(): List<TaskRecord>
    @Query("SELECT * FROM tasks WHERE state = 'RUNNING'") suspend fun running(): List<TaskRecord>
    @Query("SELECT * FROM tasks WHERE state = 'WAITING_APP'") suspend fun waitingApp(): List<TaskRecord>
    @Query("SELECT * FROM tasks WHERE state = 'CANCELLING'") suspend fun cancelling(): List<TaskRecord>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertTask(task: TaskRecord): Long
    @Update suspend fun updateTask(task: TaskRecord)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun conversation(row: ConversationRecord)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun messages(rows: List<MessageRecord>)
    @Query("SELECT * FROM messages WHERE conversationId = :id ORDER BY timestamp") suspend fun messagesFor(id: String): List<MessageRecord>
    @Query("DELETE FROM messages WHERE conversationId = :id") suspend fun clearMessages(id: String)
    @Query("DELETE FROM messages WHERE id IN (:ids)") suspend fun deleteMessages(ids: List<String>)
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC") fun observeConversations(): Flow<List<ConversationRecord>>
    @Query("SELECT * FROM conversations WHERE id = :id") suspend fun conversationById(id: String): ConversationRecord?
    @Query("DELETE FROM conversations WHERE id = :id") suspend fun deleteConversation(id: String)
    @Query("DELETE FROM conversations") suspend fun deleteAllConversations()
    @Query("DELETE FROM messages") suspend fun deleteAllMessages()
    @Query("""SELECT * FROM messages WHERE content LIKE '%' || :query || '%' ESCAPE '\'
        AND content != '' ORDER BY timestamp DESC LIMIT :limit""")
    suspend fun searchMessages(query: String, limit: Int): List<MessageRecord>
    @Query("""SELECT * FROM tasks WHERE updatedAt < :before AND source != 'script'
        AND state IN ('SUCCEEDED','FAILED','CANCELLED')
        AND id NOT IN (SELECT taskId FROM messages WHERE taskId != '')
        AND (triggerId IS NULL OR EXISTS (SELECT 1 FROM preferences WHERE `key` = '_trigger_synced_' || tasks.id AND value = tasks.state))""")
    suspend fun cleanupCandidates(before: Long): List<TaskRecord>
    @Query("DELETE FROM tasks WHERE id = :id") suspend fun deleteTask(id: String)
    @Query("DELETE FROM tool_calls WHERE taskId = :id") suspend fun deleteCalls(id: String)
    @Query("DELETE FROM task_events WHERE taskId = :id") suspend fun deleteEvents(id: String)
    @Query("DELETE FROM checkpoints WHERE taskId = :id") suspend fun deleteCheckpoint(id: String)
    @Query("DELETE FROM authorizations WHERE taskId = :id") suspend fun deleteAuthorizations(id: String)
    @Query("""SELECT messages FROM checkpoints UNION SELECT encryptedArguments FROM tool_calls
        UNION SELECT encryptedResult FROM tool_calls""") suspend fun payloadReferences(): List<String>
    @Query("SELECT configSnapshot FROM tasks") suspend fun configSnapshots(): List<String>
    @Query("SELECT * FROM tool_calls WHERE taskId = :id ORDER BY updatedAt") fun observeCalls(id: String): Flow<List<ToolCallRecord>>
    @Query("SELECT * FROM tool_calls WHERE taskId = :id") suspend fun calls(id: String): List<ToolCallRecord>
    @Query("SELECT * FROM tool_calls WHERE id = :id") suspend fun call(id: String): ToolCallRecord?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveCall(row: ToolCallRecord)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveCheckpoint(row: CheckpointRecord)
    @Query("SELECT * FROM checkpoints WHERE taskId = :id") suspend fun checkpoint(id: String): CheckpointRecord?
    @Query("SELECT * FROM authorizations WHERE taskId = :taskId AND callId = :callId") suspend fun authorization(taskId: String, callId: String): AuthorizationRecord?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun authorize(row: AuthorizationRecord)
    @Query("SELECT * FROM task_events WHERE taskId = :id ORDER BY createdAt") fun observeEvents(id: String): Flow<List<TaskEvent>>
    @Insert suspend fun event(row: TaskEvent)
    @Query("SELECT * FROM preferences ORDER BY `key`") fun observePreferences(): Flow<List<PreferenceRecord>>
    @Query("SELECT * FROM preferences WHERE `key` = :key") suspend fun preference(key: String): PreferenceRecord?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun preference(row: PreferenceRecord)
    @Query("SELECT * FROM migrations WHERE name = :name") suspend fun migration(name: String): MigrationMarker?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun migration(row: MigrationMarker)
}

@Database(entities = [TaskRecord::class, ConversationRecord::class, MessageRecord::class, ToolCallRecord::class,
    CheckpointRecord::class, AuthorizationRecord::class, TaskEvent::class, PreferenceRecord::class, MigrationMarker::class],
    version = 2, exportSchema = true)
abstract class TaskDatabase : RoomDatabase() {
    abstract fun tasks(): TaskDao
    companion object {
        @Volatile private var instance: TaskDatabase? = null
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN visible INTEGER NOT NULL DEFAULT 1")
                db.execSQL("UPDATE tasks SET visible = 0 WHERE source = 'chat'")
            }
        }
        fun get(context: Context): TaskDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, TaskDatabase::class.java, "actonsay_tasks.db")
                .addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
    }
}
