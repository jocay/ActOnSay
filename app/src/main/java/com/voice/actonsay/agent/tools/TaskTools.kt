package com.voice.actonsay.agent.tools

import com.voice.actonsay.agent.AgentTool
import com.voice.actonsay.agent.ToolContext
import com.voice.actonsay.agent.ToolResult
import com.voice.actonsay.agent.schemaObject
import com.voice.actonsay.agent.schemaProp
import com.voice.actonsay.asr.AsrManager
import com.voice.actonsay.tasks.TaskRecord
import com.voice.actonsay.tasks.TaskRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private fun TaskRecord.summary(detail: Boolean = false): JSONObject = JSONObject()
    .put("task_id", id).put("state", state).put("source", source).put("goal", goal.take(if (detail) 2000 else 200))
    .put("progress", progress.take(300)).put("error", error.take(if (detail) 1000 else 200))
    .put("answer", answer.take(if (detail) 6000 else 300)).put("created_at", createdAt)

/** 是否把一件事放到后台由模型决定；应用只负责持久化、权限和预算。 */
class StartBackgroundTaskTool : AgentTool {
    override val name = "start_background_task"
    override val description = "把耗时较长或多步骤的工作创建为独立后台任务并立即返回任务ID，任务完成后应用会通知用户。" +
        "适用于预计超过半分钟、需要多次执行命令、生成报告或用户明确说“后台/稍后/慢慢做”的工作；能几步内完成的事直接用工具做完，不要创建任务。" +
        "goal 必须自包含：后台任务看不到本轮对话，把目录、文件、要求等必要信息都写进去。"
    override val parameters = schemaObject(
        "goal" to schemaProp("string", "完整、自包含的任务目标"),
        required = listOf("goal")
    )
    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val goal = args.optString("goal").trim()
        if (goal.isBlank()) return ToolResult.failure("goal 不能为空", "INVALID_ARGUMENT")
        val current = TaskRuntime.task(ctx.taskId)
        if (current != null && current.source != "turn") {
            return ToolResult.failure("后台任务内不能再创建后台任务，请直接完成目标", "NOT_ALLOWED")
        }
        // 任务 ID 由调用 ID 派生，回合恢复重放本调用时不会重复创建。
        val id = UUID.nameUUIDFromBytes("background:${ctx.taskId}:${ctx.callId}".toByteArray(Charsets.UTF_8)).toString()
        val created = TaskRuntime.submit(goal, source = "background", fixedId = id)
        return ToolResult.success("后台任务已创建：$created。告诉用户已开始，完成后会收到通知；不要声称任务已经完成。",
            JSONObject().put("task_id", created))
    }
}

class ListTasksTool : AgentTool {
    override val name = "list_tasks"
    override val description = "列出最近的后台任务及其状态、进度。用户问“刚才那个任务怎么样了”“有什么在跑”时使用。"
    override val parameters = schemaObject(
        "only_active" to schemaProp("boolean", "只返回未结束的任务，默认 false", default = false)
    )
    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val active = setOf("QUEUED", "RUNNING", "WAITING_APP", "WAITING_CONFIRMATION", "RESULT_UNKNOWN")
        val rows = TaskRuntime.tasks.value.filter { it.visible && it.id != ctx.taskId }
            .filter { !args.optBoolean("only_active") || it.state in active }.take(15)
        if (rows.isEmpty()) return ToolResult.success("没有符合条件的后台任务")
        val list = JSONArray().apply { rows.forEach { put(it.summary()) } }
        return ToolResult.success(list.toString(), JSONObject().put("tasks", list))
    }
}

class GetTaskTool : AgentTool {
    override val name = "get_task"
    override val description = "查询一个后台任务的状态、进度、错误与结果正文。"
    override val parameters = schemaObject("task_id" to schemaProp("string", "任务ID"), required = listOf("task_id"))
    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val task = TaskRuntime.task(args.optString("task_id").trim())
            ?: return ToolResult.failure("没有这个任务，可先用 list_tasks 查询", "NOT_FOUND")
        val detail = task.summary(detail = true)
        return ToolResult.success(detail.toString(), detail)
    }
}

class CancelTaskTool : AgentTool {
    override val name = "cancel_task"
    override val description = "取消一个排队或执行中的后台任务。仅在用户明确要求停止、取消该任务时使用。"
    override val parameters = schemaObject("task_id" to schemaProp("string", "任务ID"), required = listOf("task_id"))
    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val id = args.optString("task_id").trim()
        if (id == ctx.taskId) return ToolResult.failure("不能取消当前正在回复的这一轮", "NOT_ALLOWED")
        val task = TaskRuntime.task(id) ?: return ToolResult.failure("没有这个任务，可先用 list_tasks 查询", "NOT_FOUND")
        if (task.state in setOf("SUCCEEDED", "FAILED", "CANCELLED")) {
            return ToolResult.success("任务已经结束，状态 ${task.state}，无需取消")
        }
        TaskRuntime.cancel(id)
        return ToolResult.success("已请求取消任务 $id")
    }
}

/** 用户说“记住……”即是明确的保存操作；内容可在设置的长期备注里查看和修改。 */
class RememberTool : AgentTool {
    override val name = "remember"
    override val description = "把用户明确要求记住的长期偏好或项目备注保存下来，供以后的对话和任务使用。只在用户明确要求记住时调用；不要保存密钥、令牌或密码。"
    override val parameters = schemaObject("note" to schemaProp("string", "要记住的一条内容，简洁完整"), required = listOf("note"))
    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val note = args.optString("note").trim().take(1000)
        if (note.isBlank()) return ToolResult.failure("note 不能为空", "INVALID_ARGUMENT")
        val memory = AsrManager.state.value.longTermMemory
        if (memory.lineSequence().any { it.trim().removePrefix("- ") == note }) return ToolResult.success("已经记住过这条内容")
        withContext(Dispatchers.Main) { AsrManager.setLongTermMemory((memory.trimEnd() + "\n- " + note).trim()) }
        return ToolResult.success("已记住。用户可在“设置 › 长期偏好与项目备注”查看和修改。")
    }
}

