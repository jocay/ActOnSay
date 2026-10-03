package com.voice.actonsay.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.voice.actonsay.agent.ToolContext
import com.voice.actonsay.agent.tools.WebSearchTool
import com.voice.actonsay.asr.AsrManager
import com.voice.actonsay.ui.components.BridgeMessage
import com.voice.actonsay.ui.components.BridgeTextField
import com.voice.actonsay.ui.components.FieldSpacer
import com.voice.actonsay.ui.components.JsonResult
import com.voice.actonsay.ui.components.SecretTextField
import com.voice.actonsay.ui.components.SectionCard
import com.voice.actonsay.ui.components.SettingHint
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun WebSettingsScreen(onBack: () -> Unit) {
    val state by AsrManager.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var proxy by remember(state.webProxy) { mutableStateOf(state.webProxy) }
    var brave by remember(state.braveApiKey) { mutableStateOf(state.braveApiKey) }
    var exa by remember(state.exaApiKey) { mutableStateOf(state.exaApiKey) }
    var musicKey by remember(state.musicApiKey) { mutableStateOf(state.musicApiKey) }
    var error by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }

    // 代理写到一半时格式不完整：密钥和首选项照常保存，代理保持原值直到格式正确。
    fun commit(provider: String = state.searchProvider) {
        error = runCatching { AsrManager.setWebAccess(proxy, brave, exa, provider) }.fold(
            onSuccess = { "" },
            onFailure = {
                AsrManager.setWebAccess(state.webProxy, brave, exa, provider)
                "代理尚未保存：${it.message}"
            }
        )
    }

    SubPageScaffold("联网、搜索与音乐", onBack = { commit(); onBack() }) {
        SectionCard("搜索服务") {
            SettingHint("AI 通过 web_search 工具在应用内直接搜索，不依赖 Debian 容器，也不需要授权。两个服务都填时，首选的失败后自动换另一个。")
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                SecretTextField(brave, { brave = it; commit() }, "Brave Search API Key")
                FieldSpacer()
                SecretTextField(exa, { exa = it; commit() }, "Exa API Key")
                FieldSpacer()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("auto" to "自动", "brave" to "优先 Brave", "exa" to "优先 Exa").forEach { (value, label) ->
                        FilterChip(selected = state.searchProvider == value, onClick = { commit(value) }, label = { Text(label) })
                    }
                }
            }
        }
        SectionCard("音乐") {
            SettingHint("AI 通过 music 工具搜索并在应用内播放；歌曲由 ChKSz 在线解析（网易云为主，QQ 音乐、酷狗备选）。这是第三方服务，可用性由服务方决定。")
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                SecretTextField(musicKey, { musicKey = it; AsrManager.setMusic(it, state.musicQuality) }, "ChKSz API Key")
                FieldSpacer()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("standard" to "标准", "exhigh" to "高品质", "lossless" to "无损").forEach { (value, label) ->
                        FilterChip(selected = state.musicQuality == value, onClick = { AsrManager.setMusic(musicKey, value) }, label = { Text(label) })
                    }
                }
            }
        }
        SectionCard("代理（可选）") {
            SettingHint("仅供 web_search 和 fetch_url 使用：先直连，直连失败才走代理，并记住该网站下次优先走代理。留空表示不使用。")
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                BridgeTextField(proxy, { proxy = it; commit() }, "HTTP 代理，如 192.168.21.134:7890", singleLine = true)
            }
            BridgeMessage(error, error = true)
        }
        SectionCard("测试") {
            TextButton(
                modifier = Modifier.padding(horizontal = 8.dp),
                enabled = !testing && (brave.isNotBlank() || exa.isNotBlank()),
                onClick = {
                    commit()
                    testing = true
                    result = ""
                    scope.launch {
                        val outcome = runCatching {
                            WebSearchTool().execute(JSONObject().put("query", "今日新闻").put("count", 3), ToolContext(context.applicationContext, {}))
                        }
                        result = outcome.fold(
                            onSuccess = { "${if (it.ok) "搜索成功" else "搜索失败"}（${it.data?.optString("provider").orEmpty()}）\n${it.output.take(1200)}" },
                            onFailure = { "搜索失败：${it.message}" }
                        )
                        testing = false
                    }
                }
            ) { Text(if (testing) "搜索中…" else "用“今日新闻”试搜一次") }
            if (result.isNotBlank()) JsonResult(result)
        }
    }
}
