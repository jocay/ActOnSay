package com.voice.actonsay.ui.screens.settings

import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.voice.actonsay.asr.AsrManager
import com.voice.actonsay.ui.components.BridgeMessage
import com.voice.actonsay.ui.components.ArtifactResults
import com.voice.actonsay.ui.components.BridgeTextField
import com.voice.actonsay.ui.components.JsonResult
import com.voice.actonsay.ui.components.SecretTextField
import com.voice.actonsay.ui.components.SectionCard
import com.voice.actonsay.ui.components.SettingHint
import com.voice.actonsay.ui.components.bridgeRequest
import com.voice.actonsay.ui.components.encodeBridgePath
import com.voice.actonsay.ui.components.objectList
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

@Composable
fun McpSettingsScreen(onBack: () -> Unit) {
    val state by AsrManager.state.collectAsState()
    val scope = rememberCoroutineScope()
    var servers by remember { mutableStateOf(emptyList<JSONObject>()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<JSONObject?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<JSONObject?>(null) }
    var browsing by remember { mutableStateOf<JSONObject?>(null) }
    var capability by remember { mutableStateOf("tools") }
    var items by remember { mutableStateOf(emptyList<JSONObject>()) }
    var selectedItem by remember { mutableStateOf<JSONObject?>(null) }
    var arguments by rememberSaveable { mutableStateOf("{}") }
    var readUri by rememberSaveable { mutableStateOf("") }
    var manualTaskId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    var invocationRequestId by rememberSaveable { mutableStateOf("") }
    var lastCallRequestId by rememberSaveable { mutableStateOf("") }
    suspend fun refresh() { servers = bridgeRequest("GET", "/v1/mcp").objectList("servers") }
    fun run(action: suspend () -> Unit) {
        scope.launch {
            busy = true
            error = ""
            runCatching { action() }.onFailure { error = it.message ?: "MCP 操作失败" }
            busy = false
        }
    }
    LaunchedEffect(state.bridgeBaseUrl, state.bridgeToken) {
        busy = true
        runCatching { refresh() }.onFailure { error = it.message ?: "执行环境未连接" }
        busy = false
    }
    if (editorOpen) McpEditor(editing, busy, error, onDismiss = { if (!busy) editorOpen = false }) { body -> run {
        val path = editing?.let { "/v1/mcp/${encodeBridgePath(it.getString("id"))}" } ?: "/v1/mcp"
        bridgeRequest(if (editing == null) "POST" else "PATCH", path, body)
        refresh()
        editorOpen = false
    } }
    deleting?.let { selected -> DeleteExtensionDialog("删除 MCP 服务", selected.optString("name"), busy, { deleting = null }) { run {
        bridgeRequest("DELETE", "/v1/mcp/${encodeBridgePath(selected.getString("id"))}")
        refresh()
        deleting = null
    } } }
    selectedItem?.let { item ->
        AlertDialog(
            onDismissRequest = { selectedItem = null },
            title = { Text(item.optString("name", item.optString("uri"))) },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { JsonResult(item.toString(2)) }
                    if (capability == "resources") item { BridgeTextField(readUri, { readUri = it }, "资源 URI") }
                    else item { BridgeTextField(arguments, { arguments = it }, "参数 JSON") }
                    item { Text("调用会在 Debian 环境执行，结果保留 MCP 的错误标记与结构化内容。", style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = { TextButton(enabled = !busy, onClick = { run {
                val serverId = encodeBridgePath(browsing!!.getString("id"))
                val path: String
                val body: JSONObject
                when (capability) {
                    "resources" -> { path = "/v1/mcp/$serverId/resources/read"; body = JSONObject().put("uri", readUri) }
                    "prompts" -> { path = "/v1/mcp/$serverId/prompts/get"; body = JSONObject().put("name", item.getString("name")).put("arguments", JSONObject(arguments)) }
                    else -> { path = "/v1/mcp/$serverId/call"; body = JSONObject().put("name", item.getString("name")).put("arguments", JSONObject(arguments)) }
                }
                body.put("task_id", manualTaskId)
                if (capability == "tools") {
                    if (invocationRequestId.isBlank()) invocationRequestId = UUID.randomUUID().toString()
                    body.put("request_id", invocationRequestId)
                    lastCallRequestId = invocationRequestId
                }
                result = bridgeRequest("POST", path, body).toString(2)
                selectedItem = null
            } }) { Text(if (capability == "tools") "调用工具" else "读取") } },
            dismissButton = { TextButton(onClick = { selectedItem = null }) { Text("关闭") } }
        )
    }
    SubPageScaffold("MCP 服务", onBack, actions = {
        TextButton(enabled = !busy, onClick = { run { refresh() } }) { Text("刷新") }
        TextButton(onClick = { editing = null; editorOpen = true }) { Text("新增") }
    }) {
        SettingHint("支持 Debian stdio 与远程 Streamable HTTP。stdio 进程在容器内启动；Header 和环境变量可保存凭据，列表不回显其值。OAuth 登录与 sampling 暂不支持。")
        BridgeMessage(error, true)
        if (servers.isEmpty()) BridgeMessage(if (busy) "正在读取…" else "暂无 MCP 服务")
        servers.forEach { server ->
            SectionCard(server.optString("name")) {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${server.optString("transport")} · ${server.optString("url", server.optString("command"))}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Switch(enabled = !busy, checked = server.optBoolean("enabled"), onCheckedChange = { enabled -> run {
                        bridgeRequest("PATCH", "/v1/mcp/${encodeBridgePath(server.getString("id"))}", JSONObject().put("enabled", enabled))
                        refresh()
                    } })
                }
                Row(Modifier.padding(horizontal = 8.dp)) {
                    TextButton(enabled = !busy, onClick = { run { result = bridgeRequest("POST", "/v1/mcp/${encodeBridgePath(server.getString("id"))}/test").toString(2) } }) { Text("连接测试") }
                    TextButton(onClick = { editing = server; editorOpen = true }) { Text("编辑") }
                    TextButton(onClick = { deleting = server }) { Text("删除") }
                }
                Row(Modifier.padding(horizontal = 8.dp)) {
                    listOf("tools" to "工具", "resources" to "资源", "prompts" to "提示词").forEach { (kind, label) ->
                        TextButton(enabled = !busy, onClick = { run {
                            browsing = server
                            capability = kind
                            items = bridgeRequest("GET", "/v1/mcp/${encodeBridgePath(server.getString("id"))}/$kind").objectList(kind)
                        } }) { Text(label) }
                    }
                }
                server.optString("error").takeIf { it.isNotBlank() && it != "null" }?.let { BridgeMessage(it, true) }
            }
        }
        browsing?.let { server ->
            SectionCard("${server.optString("name")} · ${when (capability) { "tools" -> "工具"; "resources" -> "资源"; else -> "提示词" }}") {
                if (items.isEmpty()) SettingHint("此服务未返回该类条目")
                items.forEach { item ->
                    TextButton(onClick = {
                        selectedItem = item
                        arguments = "{}"
                        readUri = item.optString("uri")
                        invocationRequestId = UUID.randomUUID().toString()
                        manualTaskId = UUID.randomUUID().toString()
                    }) { Text(item.optString("qualified_name", item.optString("name", item.optString("uri")))) }
                    item.optString("description").takeIf { it.isNotBlank() }?.let { SettingHint(it) }
                }
            }
        }
        if (lastCallRequestId.isNotBlank()) TextButton(enabled = !busy, onClick = { run {
            result = bridgeRequest("GET", "/v1/mcp-calls/${encodeBridgePath(lastCallRequestId)}").toString(2)
        } }) { Text("查询上次工具调用结果") }
        if (result.isNotBlank()) SectionCard("返回结果") {
            JsonResult(result)
            val resultObject = remember(result) { runCatching { JSONObject(result) }.getOrNull() }
            ArtifactResults(resultObject?.objectList("artifacts") ?: emptyList())
        }
    }
}

@Composable
private fun McpEditor(server: JSONObject?, busy: Boolean, requestError: String, onDismiss: () -> Unit, onSave: (JSONObject) -> Unit) {
    val id = server?.optString("id").orEmpty()
    var name by rememberSaveable(id) { mutableStateOf(server?.optString("name").orEmpty()) }
    var transport by rememberSaveable(id) { mutableStateOf(server?.optString("transport") ?: "stdio") }
    var command by rememberSaveable(id) { mutableStateOf(server?.optString("command").orEmpty()) }
    var args by rememberSaveable(id) { mutableStateOf(server?.optJSONArray("args")?.toString() ?: "[]") }
    var cwd by rememberSaveable(id) { mutableStateOf(server?.optString("cwd").orEmpty()) }
    var url by rememberSaveable(id) { mutableStateOf(server?.optString("url").orEmpty()) }
    var env by rememberSaveable(id) { mutableStateOf(if (server == null) "{}" else "") }
    var headers by rememberSaveable(id) { mutableStateOf(if (server == null) "{}" else "") }
    var timeout by rememberSaveable(id) { mutableStateOf(server?.optInt("timeout_seconds", 60)?.toString() ?: "60") }
    var error by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (server == null) "新增 MCP 服务" else "编辑 MCP 服务") },
        text = { LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { BridgeTextField(name, { name = it }, "名称", true) }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = transport == "stdio", onClick = { transport = "stdio" }, label = { Text("stdio") })
                FilterChip(selected = transport == "streamable_http", onClick = { transport = "streamable_http" }, label = { Text("HTTP") })
            } }
            if (transport == "stdio") {
                item { BridgeTextField(command, { command = it }, "Debian 命令", true) }
                item { BridgeTextField(args, { args = it }, "参数 JSON 数组") }
                item { BridgeTextField(cwd, { cwd = it }, "工作目录（可选）", true) }
                item { SecretTextField(env, { env = it }, "环境变量 JSON", Modifier.fillMaxWidth(), supportingText = if (server != null) "留空保留原值；{} 清空" else null) }
            } else {
                item { BridgeTextField(url, { url = it }, "Streamable HTTP 地址", true) }
                item { SecretTextField(headers, { headers = it }, "认证 Header JSON", Modifier.fillMaxWidth(), supportingText = if (server != null) "留空保留原值；{} 清空" else null) }
            }
            item { BridgeTextField(timeout, { timeout = it }, "超时秒数", true) }
            item { BridgeMessage(error, true) }
            item { BridgeMessage(requestError, true) }
        } },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            runCatching {
                require(name.isNotBlank()) { "请填写服务名称" }
                require((timeout.toIntOrNull() ?: 0) in 1..3600) { "超时需在 1–3600 秒之间" }
                val body = JSONObject().put("name", name.trim()).put("transport", transport)
                    .put("enabled", server?.optBoolean("enabled") ?: true).put("timeout_seconds", timeout.toInt())
                if (transport == "stdio") {
                    require(command.isNotBlank()) { "请填写启动命令" }
                    body.put("command", command.trim()).put("args", JSONArray(args)).put("cwd", cwd.trim().ifBlank { null } ?: JSONObject.NULL)
                    if (env.isNotBlank()) body.put("env", JSONObject(env))
                } else {
                    require(url.startsWith("http://") || url.startsWith("https://")) { "请填写 HTTP 或 HTTPS 地址" }
                    body.put("url", url.trim())
                    if (headers.isNotBlank()) body.put("headers", JSONObject(headers))
                }
                onSave(body)
            }.onFailure { error = it.message ?: "参数无效" }
        }) { Text("保存") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
fun SkillsSettingsScreen(onBack: () -> Unit) {
    val state by AsrManager.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var skills by remember { mutableStateOf(emptyList<JSONObject>()) }
    var kind by rememberSaveable { mutableStateOf("git") }
    var source by rememberSaveable { mutableStateOf("") }
    var ref by rememberSaveable { mutableStateOf("") }
    var readPath by rememberSaveable { mutableStateOf("SKILL.md") }
    var selected by remember { mutableStateOf<JSONObject?>(null) }
    var deleting by remember { mutableStateOf<JSONObject?>(null) }
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    suspend fun refresh() { skills = bridgeRequest("GET", "/v1/skills").objectList("skills") }
    fun run(action: suspend () -> Unit) {
        scope.launch {
            busy = true
            error = ""
            runCatching { action() }.onFailure { error = it.message ?: "Skill 操作失败" }
            busy = false
        }
    }
    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) run {
            val encoded = withContext(Dispatchers.IO) {
                val stream = context.contentResolver.openInputStream(uri) ?: throw IllegalStateException("无法读取所选文件")
                stream.use { input ->
                    val bytes = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var count: Int
                    var total = 0
                    while (input.read(buffer).also { count = it } != -1) {
                        total += count
                        require(total <= 20 * 1024 * 1024) { "ZIP 文件不能超过 20 MiB" }
                        bytes.write(buffer, 0, count)
                    }
                    Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
                }
            }
            bridgeRequest("POST", "/v1/skills/import", JSONObject().put("kind", "zip").put("source", "android-import.zip").put("data_base64", encoded))
            refresh()
        }
    }
    LaunchedEffect(state.bridgeBaseUrl, state.bridgeToken) {
        busy = true
        runCatching { refresh() }.onFailure { error = it.message ?: "执行环境未连接" }
        busy = false
    }
    deleting?.let { skill -> DeleteExtensionDialog("删除 Skill", skill.optString("name"), busy, { deleting = null }) { run {
        bridgeRequest("DELETE", "/v1/skills/${encodeBridgePath(skill.getString("id"))}")
        refresh()
        deleting = null
    } } }
    SubPageScaffold("Skills", onBack, actions = { TextButton(enabled = !busy, onClick = { run { refresh() } }) { Text("刷新") } }) {
        SectionCard("导入") {
            SettingHint("支持包含 SKILL.md 的目录、ZIP 和 Git 仓库。路径指向 Debian 容器；导入不执行安装脚本，缺少依赖时由可见任务处理。")
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("directory" to "目录", "zip" to "ZIP", "git" to "Git").forEach { (value, label) ->
                        FilterChip(selected = kind == value, onClick = { kind = value }, label = { Text(label) })
                    }
                }
                BridgeTextField(source, { source = it }, if (kind == "git") "Git 地址" else "Debian 中的源路径", true)
                if (kind == "git") BridgeTextField(ref, { ref = it }, "版本 / 分支（可选）", true)
                if (kind == "zip") TextButton(enabled = !busy, onClick = { zipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }) { Text("从手机选择 ZIP（最多 20 MiB）") }
                FilledTonalButton(enabled = !busy && source.isNotBlank(), onClick = { run {
                    val body = JSONObject().put("kind", kind).put("source", source.trim())
                    if (ref.isNotBlank() && kind == "git") body.put("ref", ref.trim())
                    bridgeRequest("POST", "/v1/skills/import", body)
                    refresh()
                    source = ""
                } }) { Text("导入 Skill") }
            }
        }
        BridgeMessage(error, true)
        if (skills.isEmpty()) BridgeMessage(if (busy) "正在读取…" else "暂无 Skills")
        skills.forEach { skill ->
            SectionCard(skill.optString("name")) {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(skill.optString("description"), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Switch(enabled = !busy, checked = skill.optBoolean("enabled"), onCheckedChange = { enabled -> run {
                        bridgeRequest("PATCH", "/v1/skills/${encodeBridgePath(skill.getString("id"))}", JSONObject().put("enabled", enabled))
                        refresh()
                    } })
                }
                SettingHint("来源：${skill.optString("source")}\n版本：${skill.optString("version")}\n依赖：${skill.opt("dependencies") ?: "无"}")
                Row(Modifier.padding(horizontal = 8.dp)) {
                    TextButton(enabled = !busy, onClick = { run {
                        selected = skill
                        readPath = "SKILL.md"
                        text = bridgeRequest("GET", "/v1/skills/${encodeBridgePath(skill.getString("id"))}/read?path=SKILL.md").optString("text")
                    } }) { Text("查看") }
                    TextButton(enabled = !busy, onClick = { run { bridgeRequest("POST", "/v1/skills/${encodeBridgePath(skill.getString("id"))}/update"); refresh() } }) { Text("手动更新") }
                    TextButton(onClick = { deleting = skill }) { Text("删除") }
                }
            }
        }
        selected?.let { skill ->
            SectionCard("${skill.optString("name")} · 文件") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BridgeTextField(readPath, { readPath = it }, "相对路径：SKILL.md / references/...", true)
                    TextButton(enabled = !busy && readPath.isNotBlank(), onClick = { run {
                        text = bridgeRequest("GET", "/v1/skills/${encodeBridgePath(skill.getString("id"))}/read?path=${encodeBridgePath(readPath)}").optString("text")
                    } }) { Text("读取文件") }
                }
                JsonResult(text)
            }
        }
        SettingHint("任务只在需要时读取 Skill 正文、references 和脚本；运行中的任务使用固定内容快照。可在对话中明确输入“使用 <Skill 名称>”。")
    }
}

@Composable
private fun DeleteExtensionDialog(title: String, name: String, busy: Boolean, onDismiss: () -> Unit, onDelete: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text("删除 $name？已有任务仍保留自己的记录和快照。") },
        confirmButton = { TextButton(enabled = !busy, onClick = onDelete) { Text("删除") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
