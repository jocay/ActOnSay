package com.voice.actonsay.agent.tools

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.Log
import com.voice.actonsay.agent.AgentTool
import com.voice.actonsay.agent.RootShell
import com.voice.actonsay.agent.ToolContext
import com.voice.actonsay.agent.ToolResult
import com.voice.actonsay.agent.DeviceOperationGate
import com.voice.actonsay.agent.ExecutionEnvironment
import com.voice.actonsay.agent.schemaObject
import com.voice.actonsay.agent.schemaProp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val TAG = "AppTools"

data class InstalledApp(val label: String, val packageName: String, val system: Boolean)

/** 列出已安装、可启动的应用。 */
internal fun listLaunchableApps(context: Context): List<InstalledApp> {
    val pm = context.packageManager
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(launcher, 0)
        .map { ri ->
            val ai = ri.activityInfo.applicationInfo
            InstalledApp(
                label = ri.loadLabel(pm).toString(),
                packageName = ai.packageName,
                system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            )
        }
        .distinctBy { it.packageName }
        .sortedWith(compareBy({ it.system }, { it.label }))
}

internal fun resolveApp(context: Context, query: String): InstalledApp? {
    val q = query.trim()
    if (q.isEmpty()) return null
    val apps = listLaunchableApps(context)
    apps.firstOrNull { it.packageName.equals(q, ignoreCase = true) }?.let { return it }
    apps.firstOrNull { it.label.equals(q, ignoreCase = true) }?.let { return it }
    apps.firstOrNull { it.label.contains(q, ignoreCase = true) }?.let { return it }
    return apps.firstOrNull { it.packageName.contains(q, ignoreCase = true) }
}

class ListAppsTool : AgentTool {
    override val name = "list_apps"
    override val description = "列出设备上已安装且可启动的应用（名称与包名）。可用关键字过滤。"
    override val parameters = schemaObject(
        "query" to schemaProp("string", "按应用名或包名过滤的关键字，留空返回全部（用户安装的排在前面）"),
        "limit" to schemaProp("integer", "最多返回条数，默认 60", default = 60)
    )

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val query = args.optString("query").trim()
        val limit = args.optInt("limit", 60).coerceIn(1, 400)
        val apps = listLaunchableApps(ctx.context)
            .filter { query.isEmpty() || it.label.contains(query, true) || it.packageName.contains(query, true) }
        if (apps.isEmpty()) return@withContext ToolResult.success("没有找到匹配的应用")
        val sb = StringBuilder()
        apps.take(limit).forEach { sb.append(it.label).append("  (").append(it.packageName).append(if (it.system) ", 系统" else "").append(")\n") }
        if (apps.size > limit) sb.append("…共 ${apps.size} 个，只显示前 $limit 个\n")
        ToolResult.success(sb.toString().trimEnd())
    }
}

class OpenAppTool : AgentTool {
    override val name = "open_app"
    override val description = "打开一个应用并把它切到前台。参数可以是应用名（支持模糊匹配，如“微信”）或包名。"
    override val parameters = schemaObject(
        "app" to schemaProp("string", "应用名或包名"),
        required = listOf("app")
    )

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult = DeviceOperationGate.run {
        val query = args.optString("app").trim()
        val app = withContext(Dispatchers.IO) { resolveApp(ctx.context, query) }
            ?: return@run ToolResult.failure("没有找到叫“$query”的应用，可先用 list_apps 查找", "APP_NOT_FOUND")
        ctx.onProgress("打开 ${app.label}")
        val pm = ctx.context.packageManager
        val intent = pm.getLaunchIntentForPackage(app.packageName)
            ?: return@run ToolResult.failure("应用 ${app.label} 没有可启动的入口", "APP_NOT_LAUNCHABLE")
        val component = intent.component

        // 前台服务在后台启动 Activity 会被系统拦截，root 可用时优先走 am start
        if (component != null && ctx.requireAndroidRoot()) {
            val cmd = "am start -n ${component.flattenToShortString()} -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"
            val r = runInterruptible(Dispatchers.IO) { RootShell.exec(cmd, timeoutMs = 10_000) }
            if (r.exitCode == 0 && !r.stdout.contains("Error", true) && !r.stderr.contains("Error", true)) {
                return@run ToolResult.success("已打开 ${app.label} (${app.packageName})")
            }
            Log.w(TAG, "am start failed, fallback to startActivity: ${r.render(500)}")
        }
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.context.startActivity(intent)
            ToolResult.success("已尝试打开 ${app.label} (${app.packageName})")
        } catch (e: Exception) {
            ToolResult.failure("打开 ${app.label} 失败：${e.message}")
        }
    }
}
