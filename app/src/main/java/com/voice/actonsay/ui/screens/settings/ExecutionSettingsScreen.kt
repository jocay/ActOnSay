package com.voice.actonsay.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.voice.actonsay.asr.AsrManager
import com.voice.actonsay.bridge.BridgeClient
import com.voice.actonsay.bridge.DeploymentManager
import com.voice.actonsay.ui.components.BridgeMessage
import com.voice.actonsay.ui.components.BridgeTextField
import com.voice.actonsay.ui.components.JsonResult
import com.voice.actonsay.ui.components.SecretTextField
import com.voice.actonsay.ui.components.SectionCard
import com.voice.actonsay.ui.components.SettingHint
import com.voice.actonsay.ui.components.SettingSwitchRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ExecutionSettingsScreen(onBack: () -> Unit) {
    val state by AsrManager.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf("") }
    var baseUrl by rememberSaveable { mutableStateOf(state.bridgeBaseUrl) }
    var container by rememberSaveable { mutableStateOf(state.bridgeContainer) }
    var connectionError by remember { mutableStateOf("") }
    androidx.compose.runtime.LaunchedEffect(state.bridgeBaseUrl) { baseUrl = state.bridgeBaseUrl }
    androidx.compose.runtime.LaunchedEffect(state.bridgeContainer) { container = state.bridgeContainer }
    val connectionDraftValid = runCatching { BridgeClient.validateBaseUrl(baseUrl) == state.bridgeBaseUrl }.getOrDefault(false) &&
        container == state.bridgeContainer && container.matches(Regex("[A-Za-z0-9_.-]{1,64}"))
    fun run(action: suspend () -> String) {
        scope.launch {
            busy = true
            error = ""
            runCatching { action() }.onSuccess { status = it }.onFailure { error = it.message ?: "操作失败" }
            busy = false
        }
    }
    SubPageScaffold("Debian 执行环境", onBack) {
        SectionCard("连接与配对") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BridgeTextField(baseUrl, { value ->
                    baseUrl = value
                    runCatching { AsrManager.setBridgeConfig(value, state.bridgeToken) }
                        .onSuccess { connectionError = "" }.onFailure { connectionError = "地址尚未保存：请输入完整本机回环地址" }
                }, "本机服务地址", true)
                SecretTextField(state.bridgeToken, { AsrManager.setBridgeConfig(state.bridgeBaseUrl, it) }, "配对令牌", Modifier.fillMaxWidth())
                BridgeTextField(container, { value ->
                    container = value
                    runCatching { AsrManager.setBridgeContainer(value) }
                        .onSuccess { connectionError = "" }.onFailure { connectionError = "容器名尚未保存：仅支持字母、数字、点、下划线和短横线" }
                }, "Droidspaces 容器名", true)
                BridgeMessage(connectionError, true)
                FilledTonalButton(enabled = !busy && connectionDraftValid, onClick = { run { withContext(Dispatchers.IO) { BridgeClient.health().toString(2) } } }) { Text("测试连接") }
            }
            SettingHint("服务仅监听 127.0.0.1:18765。容器不可用时直接报告失败，不回退到 Android Shell。令牌保存后加密。")
        }
        SectionCard("部署向导") {
            SettingHint("检查 Droidspaces、应用 Root 授权、容器 Python、systemd 和共享目录。安装会在指定容器创建 ActOnSay 独立目录及服务，保留已有数据库、Skills 和文件；依赖安装需要网络。")
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = !busy && connectionDraftValid, onClick = { run {
                    withContext(Dispatchers.IO) { DeploymentManager.inspect(state.bridgeContainer).toString(2) }
                } }) { Text("检查容器与身份") }
                FilledTonalButton(enabled = !busy && connectionDraftValid, onClick = { run {
                    withContext(Dispatchers.IO) {
                        DeploymentManager.install(context, state.bridgeContainer) { line -> scope.launch { progress = line } }.toString(2)
                    }
                } }) { Text("安装 / 升级服务") }
            }
            TextButton(modifier = Modifier.padding(horizontal = 8.dp), enabled = !busy && connectionDraftValid, onClick = { run {
                withContext(Dispatchers.IO) { DeploymentManager.pair(state.bridgeContainer).toString(2) }
            } }) { Text("服务已安装，仅重新配对") }
            if (progress.isNotBlank()) BridgeMessage(progress)
        }
        SectionCard("首次配置检查") {
            SettingHint("确认模型支持工具调用、通知可用，并测试执行服务。模型检查发起一个无设备副作用的工具能力请求，不执行返回的工具。")
            TextButton(enabled = !busy && connectionDraftValid, onClick = { run {
                val notifications = NotificationManagerCompat.from(context).areNotificationsEnabled()
                val model = runCatching { withContext(Dispatchers.IO) { AsrManager.checkModelTools() } }
                    .getOrElse { org.json.JSONObject().put("supported", false).put("error", it.message ?: "模型未配置") }
                val container = runCatching { withContext(Dispatchers.IO) { DeploymentManager.inspect(state.bridgeContainer) } }
                    .getOrElse { org.json.JSONObject().put("available", false).put("error", it.message ?: "容器或 Root 不可用") }
                val health = runCatching { withContext(Dispatchers.IO) { BridgeClient.health() } }
                    .getOrElse { org.json.JSONObject().put("status", "unavailable").put("error", it.message ?: "执行服务未连接") }
                org.json.JSONObject().put("通知可用", notifications).put("模型工具调用", model)
                    .put("容器与 Root", container).put("执行服务", health).toString(2)
            } }) { Text("检查配置") }
        }
        BridgeMessage(error, true)
        if (status.isNotBlank()) SectionCard("检查结果") { JsonResult(status) }
    }
}

@Composable
fun PermissionsSettingsScreen(onBack: () -> Unit) {
    val state by AsrManager.state.collectAsState()
    var steps by rememberSaveable(state.taskMaxSteps) { mutableStateOf(state.taskMaxSteps.toString()) }
    var minutes by rememberSaveable(state.taskTimeoutMinutes) { mutableStateOf(state.taskTimeoutMinutes.toString()) }
    SubPageScaffold("权限与任务预算", onBack) {
        SectionCard("高度自主模式") {
            SettingHint("启用后，智能体可按你的配置自动执行。Android Root 能修改手机本身；Debian 执行以容器账户运行，可能访问容器共享的手机目录。关闭后需要与具体工具调用绑定的界面授权。")
            SettingSwitchRow("允许 Android Root 操作", "包括手机 Shell 与设备设置", state.androidRootAllowed, {
                AsrManager.setExecutionPermissions(it, state.debianExecutionAllowed)
            })
            SettingSwitchRow("允许 Debian 执行", "包括容器命令、Skills 脚本和 MCP 执行", state.debianExecutionAllowed, {
                AsrManager.setExecutionPermissions(state.androidRootAllowed, it)
            })
            SettingSwitchRow("打电话、发短信需要确认", "开启时每次拨号或发短信都要在任务卡片上点“允许本次”，不受上面两个开关影响", state.phoneActionsConfirm, {
                AsrManager.setPhoneActionsConfirm(it)
            })
        }
        SectionCard("常驻交互模式") {
            SettingHint("系统一直保持醒着、屏幕面板断电，AI 随时能在虚拟屏上操作应用，不用每次先唤醒。黑屏时按电源键会正常亮屏；亮屏时再按一次回到黑屏但醒着。耗电比普通待机高，需要 Root。")
            SettingSwitchRow("开启常驻交互", if (state.alwaysInteractive) "已开启" else "关闭时仅在 AI 操作虚拟屏期间临时保持", state.alwaysInteractive, {
                AsrManager.setAlwaysInteractive(it)
            })
        }
        SectionCard("每个 AI 任务的预算") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BridgeTextField(steps, { text ->
                    steps = text
                    text.toIntOrNull()?.takeIf { it in 1..200 }?.let { AsrManager.setTaskBudget(it, state.taskTimeoutMinutes) }
                }, "最大工具步骤（1–200）", true)
                BridgeTextField(minutes, { text ->
                    minutes = text
                    text.toIntOrNull()?.takeIf { it in 1..1440 }?.let { AsrManager.setTaskBudget(state.taskMaxSteps, it) }
                }, "最长运行分钟（1–1440）", true)
            }
            SettingHint("默认一个后台 AI 任务、两个 Debian 执行任务。手机界面操作串行；超时后保留任务记录。")
        }
    }
}

@Composable
fun MemorySettingsScreen(onBack: () -> Unit) {
    val state by AsrManager.state.collectAsState()
    var text by rememberSaveable(state.longTermMemory) { mutableStateOf(state.longTermMemory) }
    var remembered by remember { mutableStateOf(false) }
    SubPageScaffold("长期偏好与项目备注", onBack) {
        SectionCard("备注") {
            SettingHint("只有点击“记住”才保存并用于后续任务。请勿在备注中填写密钥或令牌。")
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BridgeTextField(text, { text = it; remembered = false }, "偏好与项目说明")
                FilledTonalButton(onClick = { AsrManager.setLongTermMemory(text); remembered = true }) { Text("记住") }
                if (remembered) Text("已记住", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
