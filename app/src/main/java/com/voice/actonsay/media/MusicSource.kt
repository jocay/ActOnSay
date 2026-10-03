package com.voice.actonsay.media

import com.voice.actonsay.agent.tools.WebAccess
import com.voice.actonsay.asr.AsrManager
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** 一首可播放的歌；[id] 在各自来源内唯一，播放地址有时效，播放前再解析。 */
data class Track(
    val source: String,
    val id: String,
    val name: String,
    val artist: String,
    val album: String = "",
    val cover: String = "",
    val durationMs: Long = 0
) {
    val label: String get() = if (artist.isBlank()) name else "$name - $artist"
}

/**
 * ChKSz 在线解析：网易云为主，QQ 音乐和酷狗作备选。第三方解析服务的可用性随时可能变化，
 * 所以每个来源失败都只跳到下一个来源，不影响播放器本身。
 */
object MusicSource {
    private const val BASE = "https://api.chksz.com/api"

    private fun key(): String = AsrManager.state.value.musicApiKey.ifBlank {
        throw IOException("尚未配置音乐解析密钥：请在“设置 › 联网、搜索与音乐”填写")
    }

    private suspend fun get(path: String, params: Map<String, String>): JSONObject {
        val url = "$BASE/$path".toHttpUrl().newBuilder().apply {
            params.forEach { (name, value) -> addQueryParameter(name, value) }
            addQueryParameter("apikey", key())
        }.build()
        return WebAccess.execute(Request.Builder().url(url).header("Accept", "application/json").build()) { response ->
            val (text, _) = WebAccess.readText(response)
            val json = runCatching { JSONObject(text) }.getOrElse { throw IOException("音乐接口返回了无效内容 [HTTP ${response.code}]") }
            val code = json.optInt("code", response.code)
            if (code != 200) throw IOException("音乐接口：${json.optString("msg").ifBlank { "HTTP $code" }}")
            json
        }
    }

    /** 搜索并按与查询词的匹配度排序：歌手和歌名都对上的排在最前，翻唱靠后。 */
    suspend fun search(query: String, limit: Int = 10): List<Track> {
        val errors = mutableListOf<String>()
        val primary = runCatching { netease(query, limit) }.onFailure { errors += it.message.orEmpty() }.getOrDefault(emptyList())
        var results = rank(query, primary)
        // 网易云没有原唱（常见于版权歌曲）时，再问另外两个来源。
        if (results.none { matchesArtist(query, it) } && query.trim().contains(' ').or(results.isEmpty())) {
            for (source in listOf("qq", "kugou")) {
                val extra = runCatching { if (source == "qq") qq(query, limit) else kugou(query, limit) }
                    .onFailure { errors += it.message.orEmpty() }.getOrDefault(emptyList())
                results = rank(query, results + extra)
                if (results.any { matchesArtist(query, it) }) break
            }
        }
        if (results.isEmpty() && errors.isNotEmpty()) throw IOException(errors.distinct().joinToString("；"))
        return results.take(limit)
    }

    private fun tokens(query: String) = query.split(Regex("[\\s,，、/\\-]+")).map { it.trim() }.filter { it.isNotEmpty() }
    private fun matchesArtist(query: String, track: Track) = tokens(query).any { it.length >= 2 && track.artist.contains(it, ignoreCase = true) }
    private fun rank(query: String, tracks: List<Track>): List<Track> {
        val words = tokens(query)
        fun score(track: Track): Int {
            var value = 0
            for (word in words) {
                if (track.artist.contains(word, true)) value += 3
                if (track.name.equals(word, true)) value += 3 else if (track.name.contains(word, true)) value += 1
            }
            // 歌名里带“翻唱”“DJ”“伴奏”之类的通常不是用户要的版本。
            if (Regex("(?i)翻唱|cover|伴奏|dj|remix|铃声|片段|深情版|女声版|男声版").containsMatchIn(track.name)) value -= 2
            return value
        }
        return tracks.withIndex().sortedWith(compareByDescending<IndexedValue<Track>> { score(it.value) }.thenBy { it.index })
            .map { it.value }.distinctBy { it.source + ":" + it.id }
    }

    private suspend fun netease(query: String, limit: Int): List<Track> {
        val data = get("163_search", mapOf("keyword" to query, "limit" to limit.toString())).opt("data")
        val songs = when (data) {
            is JSONObject -> data.optJSONArray("songs")
            is JSONArray -> data
            else -> null
        } ?: JSONArray()
        return (0 until songs.length()).mapNotNull { index ->
            val song = songs.optJSONObject(index) ?: return@mapNotNull null
            Track("netease", song.optString("id"), song.optString("name"), song.optString("artists"), song.optString("album"),
                song.optString("picUrl"), song.optLong("duration"))
        }.filter { it.id.isNotBlank() }
    }

    private suspend fun qq(query: String, limit: Int): List<Track> {
        val list = get("qq_music", mapOf("msg" to query, "num" to limit.coerceAtMost(50).toString())).optJSONArray("list") ?: JSONArray()
        return (0 until list.length()).mapNotNull { index ->
            val song = list.optJSONObject(index) ?: return@mapNotNull null
            Track("qq", song.optString("mid"), song.optString("name"), song.optString("singer"), song.optString("album"))
        }.filter { it.id.isNotBlank() }
    }

    private suspend fun kugou(query: String, limit: Int): List<Track> {
        val list = get("kugou_music", mapOf("msg" to query)).optJSONArray("list") ?: JSONArray()
        return (0 until minOf(list.length(), limit)).mapNotNull { index ->
            val song = list.optJSONObject(index) ?: return@mapNotNull null
            Track("kugou", song.optString("id"), song.optString("name"), song.optString("singer"), song.optString("album"),
                durationMs = song.optLong("duration") * 1000)
        }.filter { it.id.isNotBlank() }
    }

    /** 播放地址有时效，每次播放前解析。 */
    suspend fun resolve(track: Track): String {
        val quality = AsrManager.state.value.musicQuality
        val url = when (track.source) {
            "netease" -> get("163_music", mapOf("id" to track.id, "level" to quality)).optJSONObject("data")?.optString("url")
            "qq" -> get("qq_music", mapOf("mid" to track.id, "size" to if (quality == "standard") "128k" else "320k")).optString("url")
            "kugou" -> get("kugou_music", mapOf("id" to track.id, "size" to if (quality == "standard") "128k" else "320k")).optString("url")
            else -> null
        }
        if (url.isNullOrBlank()) throw IOException("这首歌暂时无法播放（可能需要版权或已下架）")
        return url
    }
}
