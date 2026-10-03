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
    @Query("SELECT * FROM tasks ORDER BY createdAt DESC") fun observeTasks(): Flow<List<TaskRecord>>
    @Query("SELECT * FROM tasks WHERE id = :id") suspend fun task(id: String): TaskRecord?
    @Query("SELECT * FROM tasks WHERE triggerId = :id LIMIT 1") suspend fun trigger(id: String): TaskRecord?
    @Query("SELECT * FROM tasks WHERE state = 'QUEUED' ORDER BY createdAt") suspend fun queued(): List<TaskRecord>
    @Query("SELECT * FROM tasks WHERE state = 'RUNNING'") suspend fun running(): List<TaskRecord>
    @Query("SELECT * FROM tasks WHERE state = 'WAITING_APP'") suspend fun waitingApp(): List<TaskRecord>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertTask(task: TaskRecord): Long
    @Update suspend fun updateTask(task: TaskRecord)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun conversation(row: ConversationRecord)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun messages(rows: List<MessageRecord>)
    @Query("SELECT * FROM messages WHERE conversationId = :id ORDER BY timestamp") suspend fun messagesFor(id: String): List<MessageRecord>
    @Query("DELETE FROM messages WHERE conversationId = :id") suspend fun clearMessages(id: String)
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
