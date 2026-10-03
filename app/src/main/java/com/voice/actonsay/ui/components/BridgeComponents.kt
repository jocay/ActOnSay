package com.voice.actonsay.ui.components

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.voice.actonsay.bridge.BridgeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.io.File
import java.util.UUID

/** 扩展管理仅在用户操作或打开页面时请求，不在界面中持续轮询。 */
suspend fun bridgeRequest(method: String, path: String, body: JSONObject? = null): JSONObject =
    withContext(Dispatchers.IO) { BridgeClient.request(method, path, body) }

fun JSONObject.objectList(key: String): List<JSONObject> {
    val values = optJSONArray(key) ?: JSONArray()
    return (0 until values.length()).mapNotNull { values.optJSONObject(it) }
}

fun encodeBridgePath(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

@Composable
fun BridgeTextField(value: String, onChange: (String) -> Unit, label: String, singleLine: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium
    )
}

@Composable
fun BridgeMessage(message: String, error: Boolean = false) {
    if (message.isNotEmpty()) {
        Text(
            text = message,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        )
    }
}

@Composable
fun JsonResult(value: String) {
    SelectionContainer {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** 手工 MCP 测试产生的文件也通过经过认证的产物接口读取。 */
@Composable
fun ArtifactResults(artifacts: List<JSONObject>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val artifact = selected
        if (uri != null && artifact != null) scope.launch {
            busy = true
            message = runCatching { withContext(Dispatchers.IO) {
                val file = File(context.cacheDir, "mcp-${UUID.randomUUID()}")
                try {
                    BridgeClient.download(artifact.getString("reference"), file)
                    val output = context.contentResolver.openOutputStream(uri) ?: throw IllegalStateException("无法写入文件")
                    output.use { destination -> file.inputStream().use { it.copyTo(destination) } }
                } finally { file.delete() }
            }; "已保存 ${artifact.optString("name")}" }.getOrElse { "保存失败：${it.message}" }
            busy = false
        }
    }
    artifacts.forEach { artifact ->
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(artifact.optString("name"), style = MaterialTheme.typography.titleSmall)
            TextButton(enabled = !busy, onClick = {
                selected = artifact
                save.launch(artifact.optString("name", "artifact").substringAfterLast('/'))
            }) { Text("保存文件到手机") }
            if (artifact.optString("mime_type").startsWith("image/")) TextButton(enabled = !busy, onClick = { scope.launch {
                busy = true
                runCatching { withContext(Dispatchers.IO) {
                    val file = File(context.cacheDir, "mcp-image-${UUID.randomUUID()}")
                    try {
                        BridgeClient.download(artifact.getString("reference"), file)
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(file.path, bounds)
                        var sample = 1
                        while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
                        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
                            ?: throw IllegalStateException("图片无法解码")
                        bitmap.asImageBitmap()
                    } finally { file.delete() }
                } }.onSuccess { image = it }.onFailure { message = "图片预览失败：${it.message}" }
                busy = false
            } }) { Text("预览图片") }
        }
    }
    BridgeMessage(message)
    image?.let { bitmap ->
        Image(bitmap, contentDescription = "MCP 图片产物", modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp), contentScale = ContentScale.Fit)
        TextButton(onClick = { image = null }) { Text("关闭预览") }
    }
}
