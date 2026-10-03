package com.voice.actonsay.agent.tools

import com.voice.actonsay.agent.AgentTool
import com.voice.actonsay.agent.ToolContext
import com.voice.actonsay.agent.ToolResult
import com.voice.actonsay.agent.schemaObject
import com.voice.actonsay.agent.schemaProp
import com.voice.actonsay.agent.truncateOutput
import com.voice.actonsay.ai.SmartDns
import com.voice.actonsay.asr.AsrManager
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 联网工具共用的出口：只访问公网地址，先直连；直连失败且配置了代理时改走代理，并记住该主机下次优先走代理。
 */
internal object WebAccess {
    private const val MAX_BYTES = 1024L * 1024
    private class PrivateAddressException : IOException("不允许访问本机或局域网地址")

    private fun isPrivate(address: InetAddress): Boolean {
        val bytes = address.address
        return address.isLoopbackAddress || address.isAnyLocalAddress || address.isLinkLocalAddress || address.isSiteLocalAddress ||
            address.isMulticastAddress || (bytes.size == 16 && (bytes[0].toInt() and 0xFE) == 0xFC)
    }

    /** 解析结果里只要有一个非公网地址就拒绝，重定向后的主机同样经过这里。 */
    private val publicOnlyDns = object : okhttp3.Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            SmartDns.lookup(hostname).also { addresses -> if (addresses.any(::isPrivate)) throw PrivateAddressException() }
    }

    private val base = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true).followSslRedirects(false).retryOnConnectionFailure(false).build()
    private val direct = base.newBuilder().dns(publicOnlyDns).build()
    private val proxyFirstHosts = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var proxyClient: Pair<String, OkHttpClient>? = null

    private fun proxied(): OkHttpClient? {
        val setting = AsrManager.state.value.webProxy
        if (setting.isBlank()) return null
        proxyClient?.takeIf { it.first == setting }?.let { return it.second }
        val port = setting.substringAfterLast(':').toIntOrNull() ?: return null
        // 经代理时目标域名由代理解析：本机能解析出内网地址的主机仍然拒绝，解析不了的（被屏蔽的域名）放行。
        val client = base.newBuilder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(setting.substringBeforeLast(':'), port)))
            .addNetworkInterceptor { chain ->
                val host = chain.request().url.host
                try { if (SmartDns.lookup(host).any(::isPrivate)) throw PrivateAddressException() } catch (_: UnknownHostException) { }
                chain.proceed(chain.request())
            }.build()
        proxyClient = setting to client
        return client
    }

    suspend fun <T> execute(request: Request, consume: (Response) -> T): T {
        val host = request.url.host
        val proxy = proxied()
        val routes = if (proxy != null && host in proxyFirstHosts) listOf(proxy, direct) else listOfNotNull(direct, proxy)
        var failure: IOException? = null
        for (client in routes) {
            try {
                val result = receive(client.newCall(request), consume)
                if (client === proxy) proxyFirstHosts.add(host) else proxyFirstHosts.remove(host)
                return result
            } catch (error: PrivateAddressException) {
                throw error
            } catch (error: IOException) {
                failure = error
            }
        }
        throw failure ?: IOException("没有可用的网络出口")
    }

    /** 读取不超过 1 MiB 的响应正文；第二个值表示正文被截断。 */
    fun readText(response: Response): Pair<String, Boolean> {
        val source = response.body?.source() ?: throw IOException("响应没有内容")
        source.request(MAX_BYTES + 1)
        val truncated = source.buffer.size > MAX_BYTES
        val charset = response.body?.contentType()?.charset() ?: Charsets.UTF_8
        return source.buffer.readString(minOf(source.buffer.size, MAX_BYTES), charset) to truncated
    }

    private suspend fun <T> receive(call: Call, consume: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { continuation.resumeWith(Result.failure(e)) }
            override fun onResponse(call: Call, response: Response) {
                continuation.resumeWith(runCatching { response.use { consume(it) } })
            }
        })
    }

    private val dropBlocks = Regex("(?is)<(script|style|noscript|svg|head)[^>]*>.*?</\\1>")
    private val breaks = Regex("(?i)<(br|/p|/div|/li|/tr|/h[1-6])[^>]*>")
    private val tags = Regex("(?s)<[^>]+>")
    fun htmlToText(html: String): String = html.replace(dropBlocks, " ").replace(breaks, "\n").replace(tags, " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        .lines().map { it.replace(Regex("[ \\t\\u00A0]+"), " ").trim() }.filter { it.isNotEmpty() }.joinToString("\n")
}

/** 只读联网：不需要 Root 或 Debian 授权，也不能访问本机和局域网地址。 */
class FetchUrlTool : AgentTool {
    override val name = "fetch_url"
    override val description = "用 HTTPS GET 读取一个公开网页或接口并返回文本，无需任何授权。用于天气、资料和读取搜索结果里的网页正文。" +
        "天气可读取 https://wttr.in/城市名?format=j2（JSON，含当前实况和未来三天的最高最低气温）；需要逐小时降雨概率时用 format=j1 并把 max_chars 设为 30000。" +
        "不能访问本机或局域网地址，不能提交表单或携带凭据。"
    override val parameters = schemaObject(
        "url" to schemaProp("string", "https:// 开头的完整地址"),
        "max_chars" to schemaProp("integer", "最多返回的字符数，默认 8000，最大 30000", default = 8000),
        required = listOf("url")
    )
    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val url = args.optString("url").trim().toHttpUrlOrNull()
            ?: return ToolResult.failure("url 不是合法地址", "INVALID_ARGUMENT")
        if (!url.isHttps) return ToolResult.failure("只支持 https 地址", "INVALID_ARGUMENT")
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return ToolResult.failure("地址不能携带凭据", "INVALID_ARGUMENT")
        val limit = args.optInt("max_chars", 8000).coerceIn(500, 30_000)
        ctx.onProgress("读取 ${url.host}")
        val request = Request.Builder().url(url).header("User-Agent", "curl/8.5.0")
            .header("Accept", "text/html,application/json,text/plain;q=0.9,*/*;q=0.5").header("Accept-Language", "zh-CN,zh;q=0.9").build()
        return try {
            WebAccess.execute(request) { response ->
                val type = response.header("Content-Type").orEmpty().lowercase()
                if (type.isNotBlank() && !(type.startsWith("text/") || "json" in type || "xml" in type || "javascript" in type)) {
                    return@execute ToolResult.failure("该地址返回的不是文本内容（$type）", "UNSUPPORTED_CONTENT")
                }
                val (raw, truncated) = WebAccess.readText(response)
                val text = if ("html" in type || raw.trimStart().startsWith("<")) WebAccess.htmlToText(raw) else raw
                val output = truncateOutput(text.trim(), limit) + if (truncated) "\n[响应超过 1 MiB，仅读取了开头部分]" else ""
                if (response.isSuccessful) ToolResult.success(output, JSONObject().put("status", response.code).put("url", response.request.url.toString()))
                else ToolResult("FAILED", "HTTP ${response.code}\n$output", errorType = "HTTP_${response.code}")
            }
        } catch (error: IOException) {
            ToolResult.failure("读取失败：${error.message ?: error.javaClass.simpleName}", "NETWORK_ERROR")
        }
    }
}

/** 应用内的网络搜索：Brave 与 Exa，密钥由用户在设置里填写；不依赖 Debian 容器。 */
class WebSearchTool : AgentTool {
    override val name = "web_search"
    override val description = "联网搜索，返回标题、链接和摘要，无需任何授权。需要最新消息、新闻、不确定的事实或要找某个网页时使用；" +
        "摘要不够时再用 fetch_url 读取其中的链接。查询词用最能命中结果的语言和关键词，不要整句照抄用户的话。"
    override val parameters = schemaObject(
        "query" to schemaProp("string", "搜索关键词"),
        "count" to schemaProp("integer", "返回条数，默认 5，最大 10", default = 5),
        required = listOf("query")
    )

    private class Outcome(val results: JSONArray? = null, val error: String = "")

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val query = args.optString("query").trim()
        if (query.isBlank()) return ToolResult.failure("query 不能为空", "INVALID_ARGUMENT")
        val count = args.optInt("count", 5).coerceIn(1, 10)
        val state = AsrManager.state.value
        val available = listOf("brave" to state.braveApiKey, "exa" to state.exaApiKey).filter { it.second.isNotBlank() }
        if (available.isEmpty()) {
            return ToolResult.failure("尚未配置搜索服务：请用户在“设置 › 联网、搜索与音乐”填写 Brave 或 Exa 的 API Key。", "NOT_CONFIGURED")
        }
        // 首选服务排在前面，失败时换另一个。
        val providers = available.sortedBy { if (it.first == state.searchProvider) 0 else 1 }
        val errors = mutableListOf<String>()
        for ((provider, key) in providers) {
            ctx.checkCancelled()
            ctx.onProgress("搜索：${query.take(30)}")
            val outcome = try {
                if (provider == "brave") brave(query, count, key) else exa(query, count, key)
            } catch (error: IOException) {
                Outcome(error = error.message ?: error.javaClass.simpleName)
            }
            val results = outcome.results
            if (results == null) { errors += "$provider：${outcome.error}"; continue }
            if (results.length() == 0) return ToolResult.success("没有搜索到结果，可以换一组关键词再试。", JSONObject().put("provider", provider))
            val text = (0 until results.length()).joinToString("\n\n") { index ->
                val item = results.getJSONObject(index)
                "[${index + 1}] ${item.optString("title")}\n${item.optString("url")}" +
                    item.optString("date").let { if (it.isBlank()) "" else "\n$it" } + "\n${item.optString("snippet")}"
            }
            return ToolResult.success(text, JSONObject().put("provider", provider).put("results", results))
        }
        return ToolResult.failure("搜索失败（${errors.joinToString("；")}）。直连不通时可在“设置 › 联网、搜索与音乐”配置代理。", "SEARCH_FAILED")
    }

    private fun failure(response: Response, body: String): Outcome =
        Outcome(error = "HTTP ${response.code} ${body.take(160).replace('\n', ' ')}")

    private suspend fun brave(query: String, count: Int, key: String): Outcome {
        val url = "https://api.search.brave.com/res/v1/web/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query).addQueryParameter("count", count.toString()).build()
        val request = Request.Builder().url(url).header("Accept", "application/json").header("X-Subscription-Token", key).build()
        return WebAccess.execute(request) { response ->
            val body = WebAccess.readText(response).first
            if (!response.isSuccessful) return@execute failure(response, body)
            val items = JSONObject(body).optJSONObject("web")?.optJSONArray("results") ?: JSONArray()
            Outcome(JSONArray().apply {
                for (index in 0 until minOf(items.length(), count)) {
                    val item = items.optJSONObject(index) ?: continue
                    put(JSONObject().put("title", WebAccess.htmlToText(item.optString("title"))).put("url", item.optString("url"))
                        .put("snippet", WebAccess.htmlToText(item.optString("description")).take(600))
                        .put("date", item.optString("page_age").ifBlank { item.optString("age") }))
                }
            })
        }
    }

    private suspend fun exa(query: String, count: Int, key: String): Outcome {
        val payload = JSONObject().put("query", query).put("numResults", count).put("type", "auto")
            .put("contents", JSONObject().put("text", JSONObject().put("maxCharacters", 600)))
        val request = Request.Builder().url("https://api.exa.ai/search").header("x-api-key", key).header("Accept", "application/json")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        return WebAccess.execute(request) { response ->
            val body = WebAccess.readText(response).first
            if (!response.isSuccessful) return@execute failure(response, body)
            val items = JSONObject(body).optJSONArray("results") ?: JSONArray()
            Outcome(JSONArray().apply {
                for (index in 0 until minOf(items.length(), count)) {
                    val item = items.optJSONObject(index) ?: continue
                    put(JSONObject().put("title", item.optString("title")).put("url", item.optString("url"))
                        .put("snippet", item.optString("text").replace(Regex("\\s+"), " ").trim().take(600))
                        .put("date", item.optString("publishedDate").take(10)))
                }
            })
        }
    }
}
