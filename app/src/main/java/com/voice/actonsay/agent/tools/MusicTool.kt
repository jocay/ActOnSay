package com.voice.actonsay.agent.tools

import com.voice.actonsay.agent.AgentTool
import com.voice.actonsay.agent.ToolContext
import com.voice.actonsay.agent.ToolResult
import com.voice.actonsay.agent.schemaObject
import com.voice.actonsay.agent.schemaProp
import com.voice.actonsay.media.MusicPlayer
import com.voice.actonsay.media.MusicSource
import com.voice.actonsay.media.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** 应用内音乐播放；在线解析，播报时自动压低音量。 */
class MusicTool : AgentTool {
    override val name = "music"
    override val description = "播放和控制音乐（应用内置播放器，在线解析歌曲）。" +
        "action：play 搜索并直接播放（query 写歌名和歌手，例如“晴天 周杰伦”，其余结果排进队列）；search 只搜索返回候选；play_result 播放上次 search 的第 index 首（从 1 开始）；" +
        "pause、resume、next、previous、stop；volume 设置媒体音量（level 0–100）；status 查看正在播放和队列。播放后直接简短告诉用户在放什么，不要复述整个列表。"
    override val parameters = schemaObject(
        "action" to schemaProp("string", "动作", listOf("play", "search", "play_result", "pause", "resume", "next", "previous", "stop", "volume", "status")),
        "query" to schemaProp("string", "play / search：歌名、歌手或风格关键词"),
        "index" to schemaProp("integer", "play_result：上次搜索结果的序号，从 1 开始"),
        "level" to schemaProp("integer", "volume：0–100"),
        required = listOf("action")
    )

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.Main) {
        when (args.optString("action")) {
            "play", "search" -> {
                val query = args.optString("query").trim()
                if (query.isEmpty()) return@withContext ToolResult.failure("query 不能为空", "INVALID_ARGUMENT")
                ctx.onProgress("搜索歌曲：${query.take(20)}")
                val tracks = try { MusicSource.search(query) } catch (error: IOException) {
                    return@withContext ToolResult.failure("搜索歌曲失败：${error.message}", "MUSIC_UNAVAILABLE")
                }
                if (tracks.isEmpty()) return@withContext ToolResult.failure("没有找到“$query”，可以换个说法或加上歌手名", "NOT_FOUND")
                lastResults = tracks
                val list = tracks.mapIndexed { i, track -> "${i + 1}. ${track.label}" + if (track.album.isNotBlank()) "（${track.album}）" else "" }.joinToString("\n")
                if (args.optString("action") == "search") {
                    ToolResult.success("找到 ${tracks.size} 首：\n$list", JSONObject().put("results", JSONArray(tracks.map { it.label })))
                } else {
                    MusicPlayer.play(tracks, 0)
                    ToolResult.success("开始播放：${tracks.first().label}。其余 ${tracks.size - 1} 首已排入队列。\n候选：\n$list")
                }
            }
            "play_result" -> {
                val position = args.optInt("index", 0) - 1
                val results = lastResults
                if (position !in results.indices) return@withContext ToolResult.failure("序号超出上次搜索结果范围（共 ${results.size} 首）", "INVALID_ARGUMENT")
                MusicPlayer.play(results, position)
                ToolResult.success("开始播放：${results[position].label}")
            }
            "pause" -> { MusicPlayer.pause(); ToolResult.success("已暂停") }
            "resume" -> {
                if (MusicPlayer.current.value == null) ToolResult.failure("当前没有可继续的歌曲", "NOTHING_PLAYING")
                else { MusicPlayer.resume(); ToolResult.success("继续播放：${MusicPlayer.current.value?.label}") }
            }
            "next" -> { MusicPlayer.next(); ToolResult.success("下一首：${MusicPlayer.current.value?.label ?: "队列已结束"}") }
            "previous" -> { MusicPlayer.previous(); ToolResult.success("现在播放：${MusicPlayer.current.value?.label ?: "无"}") }
            "stop" -> { MusicPlayer.stop(); ToolResult.success("已停止播放") }
            "volume" -> {
                if (!args.has("level")) return@withContext ToolResult.failure("缺少 level", "INVALID_ARGUMENT")
                MusicPlayer.setVolume(args.optInt("level"))
                ToolResult.success("媒体音量已调到 ${MusicPlayer.volumePercent()}%")
            }
            "status" -> {
                val current = MusicPlayer.current.value
                if (current == null) ToolResult.success("当前没有播放音乐。媒体音量 ${MusicPlayer.volumePercent()}%")
                else ToolResult.success("${if (MusicPlayer.isPlaying.value) "正在播放" else "已暂停"}：${current.label}\n媒体音量 ${MusicPlayer.volumePercent()}%\n队列：\n${MusicPlayer.queueSummary()}")
            }
            else -> ToolResult.failure("不支持的 action", "INVALID_ARGUMENT")
        }
    }

    companion object {
        @Volatile private var lastResults: List<Track> = emptyList()
    }
}
