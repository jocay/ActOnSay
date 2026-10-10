package com.voice.actonsay.bridge

import com.voice.actonsay.agent.tools.BridgeExtensionCatalog
import com.voice.actonsay.asr.AsrManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 只连接手机本机 Debian 服务；请求失败由调用方按原请求 ID 查询，避免重放副作用。 */
object BridgeClient {
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    private val json = "application/json; charset=utf-8".toMediaType()

    fun validateBaseUrl(value: String): String {
        val url = value.trim().trimEnd('/').toHttpUrl()
        require(url.host in setOf("127.0.0.1", "localhost", "::1")) { "执行服务必须使用本机回环地址" }
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null && url.encodedPath == "/") {
            "执行服务地址只能包含协议、本机地址和端口"
        }
        return url.toString().trimEnd('/')
    }

    suspend fun health(): JSONObject = request("GET", "/v1/health")

    suspend fun request(method: String, path: String, body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        require(path.startsWith("/v1/") && !path.contains("..") && !path.contains('\\')) { "无效的桥接路径" }
        val state = AsrManager.state.value
        require(state.bridgeToken.isNotBlank()) { "请先配对 Debian 执行服务" }
        val request = Request.Builder().url(validateBaseUrl(state.bridgeBaseUrl) + path)
            .header("Authorization", "Bearer ${state.bridgeToken}")
            .method(method, if (method in setOf("GET", "HEAD")) null else (body ?: JSONObject()).toString().toRequestBody(json)).build()
        receive(client.newCall(request)) { response ->
            val source = response.body?.source() ?: throw IOException("执行服务没有返回内容")
            source.request(MAX_JSON_BYTES + 1)
            if (source.buffer.size > MAX_JSON_BYTES) throw IOException("执行服务响应过大，请分页读取")
            val text = source.readUtf8()
            val result = runCatching { JSONObject(text) }.getOrElse { throw IOException("执行服务返回了无效 JSON [HTTP ${response.code}]") }
            if (!response.isSuccessful) {
                val detail = result.optString("message").ifBlank { result.opt("detail")?.toString().orEmpty() }.take(400)
                throw BridgeException(response.code, result.optString("error_type", "bridge_error"), detail.ifBlank { "执行服务请求失败" })
            }
            BridgeExtensionCatalog.markReachable()
            if (method !in setOf("GET", "HEAD") && (path.startsWith("/v1/mcp") || path.startsWith("/v1/skills"))) {
                BridgeExtensionCatalog.invalidate()
            }
            result
        }
    }

    /** 产物保存到应用私有目录；接口路径和文件名都不能跳出其固定范围。 */
    suspend fun downloadArtifact(executionId: String, relativePath: String, directory: File): File = withContext(Dispatchers.IO) {
        require(executionId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        require(relativePath.isNotBlank() && !relativePath.startsWith('/') && relativePath.split('/').none { it in setOf("..", ".", "") })
        val encoded = relativePath.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        val root = File(directory, executionId).canonicalFile.apply { mkdirs() }
        val target = File(root, relativePath).canonicalFile
        require(target.path.startsWith(root.path + File.separator))
        download("/v1/executions/$executionId/artifacts/$encoded", target)
    }

    suspend fun download(path: String, target: File): File = withContext(Dispatchers.IO) {
        require(path.matches(Regex("/v1/(executions|tasks)/[A-Za-z0-9_-]{1,128}/artifacts/.+")) && !path.contains("..") && !path.contains('\\')) { "无效的产物路径" }
        val state = AsrManager.state.value
        require(state.bridgeToken.isNotBlank()) { "请先配对执行服务" }
        val request = Request.Builder().url(validateBaseUrl(state.bridgeBaseUrl) + path)
            .header("Authorization", "Bearer ${state.bridgeToken}").build()
        val call = client.newCall(request)
        receive(call) { response ->
            check(response.isSuccessful) { "产物下载失败 [HTTP ${response.code}]" }
            val temporary = File(target.parentFile, target.name + ".partial")
            target.parentFile?.mkdirs()
            try {
                    val input = response.body?.byteStream() ?: throw IOException("产物内容为空")
                    input.use { source -> temporary.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var bytes = 0L
                        while (true) {
                            if (Thread.currentThread().isInterrupted) throw InterruptedException()
                            val count = source.read(buffer)
                            if (count < 0) break
                            bytes += count
                            check(bytes <= 50L * 1024 * 1024) { "产物超过 50 MiB 下载限制" }
                            output.write(buffer, 0, count)
                        }
                    } }
                check(temporary.renameTo(target)) { "无法保存产物" }
                target
            } finally {
                call.cancel()
                temporary.delete()
            }
        }
    }

    /** 读取响应体期间仍保留取消钩子，取消会关闭底层连接。 */
    private suspend fun <T> receive(call: Call, consume: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWith(Result.failure(IOException("无法连接 Debian 执行服务；请查询原执行 ID 后再决定重跑", e)))
            }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching { response.use { consume(it) } }
                continuation.resumeWith(result)
            }
        })
    }

    private const val MAX_JSON_BYTES = 2L * 1024 * 1024
}

class BridgeException(val statusCode: Int, val errorType: String, message: String) : IOException(message)
