package com.veacon.veilmusic.data.local

import android.util.Log
import com.veacon.veilmusic.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * 歌单导入：把 QQ 音乐 / 网易云 / 酷狗 的公开歌单（或纯文本歌曲列表）解析成本地曲目。
 *
 * 与开源实现（GoMusic、music-api-server、Superheroff/musicapi）的做法一致：
 *  - QQ：`fcg_v8_playlist_cp.fcg`（无需登录，公开歌单直接返回 songlist）；
 *  - 网易云：`api/v6/playlist/detail`（一次最多 1000 首，含封面与时长）；
 *  - 酷狗：`mobilecdn.kugou.com/api/v3/special/{detail,song}`。
 *
 * 解析出的 Song 不带 Apple id，播放时由 [com.veacon.veilmusic.luoxue.AudioResolver]
 * 按「标题 + 歌手 + 时长」在各平台搜索，无需先匹配 Apple 曲库。
 */
@Singleton
class PlaylistImporter @Inject constructor(
    @Named("direct") http: OkHttpClient,
    private val store: LocalLibraryStore,
) {

    sealed interface Result {
        data class Ok(val playlist: LocalLibraryStore.LocalPlaylist, val imported: Int, val total: Int) : Result
        data class Err(val message: String) : Result
    }

    private val client = http.newBuilder()
        .callTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val urlRegex = Regex("""https?://[^\s"'<>，。、；：）】]+""")
    private val bareIdRegex = Regex("""[?&#](?:id|disstid|specialid)=(\d{4,})""")
    private val pathIdRegex = Regex("""/(?:playlist|taoge|single|diss)/(\d{4,})""")

    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/122.0 Safari/537.36"

    suspend fun import(raw: String, preferredName: String? = null): Result = withContext(Dispatchers.IO) {
        val input = raw.trim()
        if (input.isEmpty()) return@withContext Result.Err("请输入歌单链接或歌曲列表")

        val url = urlRegex.find(input)?.value
        if (url == null) return@withContext importText(input, preferredName)

        try {
            val resolved = resolveShortUrl(url)
            val id = extractId(input) ?: extractId(resolved) ?: scrapeIdFromPage(resolved)
            if (id == null) {
                return@withContext Result.Err("没有识别出歌单 ID，请确认是公开歌单链接")
            }
            when {
                isQQ(resolved) || isQQ(input) -> importQQ(id, preferredName)
                isNetease(resolved) || isNetease(input) -> importNetease(id, preferredName)
                isKugou(resolved) || isKugou(input) -> importKugou(id, preferredName)
                else -> Result.Err("暂不支持该平台，目前支持 QQ 音乐 / 网易云 / 酷狗")
            }
        } catch (e: Exception) {
            Log.w(TAG, "import failed: ${e.message}")
            Result.Err("导入失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ── 平台实现 ────────────────────────────────────────────────────────────

    private fun importQQ(id: String, preferredName: String?): Result {
        val url = "https://c.y.qq.com/v8/fcg-bin/fcg_v8_playlist_cp.fcg" +
            "?id=$id&format=json&newsong=1&platform=yqq&hostUin=0&needNewCode=0&utf8=1"
        val body = get(url, mapOf("Referer" to "https://y.qq.com/")) ?: return Result.Err("QQ 音乐接口无响应")
        val root = JSONObject(body)
        val cdlist = root.optJSONObject("data")?.optJSONArray("cdlist")
        val cd = if (cdlist != null && cdlist.length() > 0) cdlist.optJSONObject(0) else null
            ?: return Result.Err("歌单不存在或已设为私密")
        val list = cd.optJSONArray("songlist") ?: return Result.Err("该歌单没有公开曲目")
        val songs = ArrayList<Song>(list.length())
        for (i in 0 until list.length()) {
            val s = list.optJSONObject(i) ?: continue
            val title = s.optString("name").ifBlank { s.optString("title") }
            if (title.isBlank()) continue
            val mid = s.optString("mid").ifBlank { s.optString("songmid") }
            val singers = s.optJSONArray("singer")
            val artists = buildList {
                if (singers != null) for (j in 0 until singers.length()) {
                    singers.optJSONObject(j)?.optString("name")?.takeIf { it.isNotBlank() }?.let(::add)
                }
            }
            val album = s.optJSONObject("album")
            val albumName = album?.optString("name").orEmpty()
            val albumMid = album?.optString("mid").orEmpty()
            songs += Song(
                id = "imp-qq-${mid.ifBlank { title.hashCode().toString() }}",
                title = title,
                artistName = artists.joinToString(" / ").ifBlank { "未知艺人" },
                albumName = albumName,
                durationMs = s.optInt("interval", 0) * 1000L,
                artworkUrl = albumMid.takeIf { it.isNotBlank() }
                    ?.let { "https://y.qq.com/music/photo_new/T002R500x500M000$it.jpg" },
                artworkBgColor = null,
                previewUrl = null,
            )
        }
        val name = cd.optString("dissname").ifBlank { preferredName ?: "QQ 歌单" }
        val cover = cd.optString("logo").ifBlank { cd.optString("headurl") }.takeIf { it.isNotBlank() }
        return save(name, cover, LocalLibraryStore.SOURCE_QQ, "https://y.qq.com/n/ryqq/playlist/$id", songs, list.length())
    }

    private fun importNetease(id: String, preferredName: String?): Result {
        val url = "https://music.163.com/api/v6/playlist/detail?id=$id&n=1000&s=0"
        val body = get(url, mapOf("Referer" to "https://music.163.com/")) ?: return Result.Err("网易云接口无响应")
        val root = JSONObject(body)
        val pl = root.optJSONObject("playlist") ?: return Result.Err("歌单不存在或已设为私密")
        val tracks = pl.optJSONArray("tracks") ?: return Result.Err("该歌单没有公开曲目")
        val songs = ArrayList<Song>(tracks.length())
        for (i in 0 until tracks.length()) {
            val t = tracks.optJSONObject(i) ?: continue
            val title = t.optString("name")
            if (title.isBlank()) continue
            val artists = buildList {
                t.optJSONArray("ar")?.let { arr ->
                    for (j in 0 until arr.length()) {
                        arr.optJSONObject(j)?.optString("name")?.takeIf { it.isNotBlank() }?.let(::add)
                    }
                }
            }
            val album = t.optJSONObject("al")
            val pic = album?.optString("picUrl").orEmpty()
            songs += Song(
                id = "imp-ne-${t.optLong("id")}",
                title = title,
                artistName = artists.joinToString(" / ").ifBlank { "未知艺人" },
                albumName = album?.optString("name").orEmpty(),
                durationMs = t.optLong("dt", 0L),
                artworkUrl = pic.takeIf { it.isNotBlank() }?.let { "$it?param=500y500" },
                artworkBgColor = null,
                previewUrl = null,
            )
        }
        val name = pl.optString("name").ifBlank { preferredName ?: "网易云歌单" }
        val cover = pl.optString("coverImgUrl").takeIf { it.isNotBlank() }
        return save(name, cover, LocalLibraryStore.SOURCE_NETEASE, "https://music.163.com/#/playlist?id=$id", songs, tracks.length())
    }

    private fun importKugou(id: String, preferredName: String?): Result {
        var name = preferredName ?: "酷狗歌单"
        var cover: String? = null
        runCatching {
            val detail = get(
                "http://mobilecdn.kugou.com/api/v3/special/detail?specialid=$id&plat=0&version=8000",
                emptyMap(),
            )
            if (detail != null) {
                val d = JSONObject(detail).optJSONObject("data")
                d?.optString("specialname")?.takeIf { it.isNotBlank() }?.let { name = it }
                d?.optString("imgurl")?.takeIf { it.isNotBlank() }?.let { cover = it }
            }
        }
        // detail 接口常年返回空（27 字节），名字改从分享页 <title> 取。
        if (name == "酷狗歌单" || cover == null) {
            runCatching {
                val html = get("https://www.kugou.com/yy/special/single/$id.html", emptyMap())
                    ?: return@runCatching
                Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
                    .find(html)?.groupValues?.getOrNull(1)
                    ?.substringBefore("_")?.trim()?.takeIf { it.isNotBlank() }
                    ?.let { name = it }
            }
        }

        val songs = ArrayList<Song>()
        var total = 0
        var page = 1
        while (page <= 10) {
            val body = get(
                "http://mobilecdn.kugou.com/api/v3/special/song?specialid=$id&page=$page&pagesize=100&version=8000&plat=0",
                emptyMap(),
            ) ?: break
            val data = JSONObject(body).optJSONObject("data") ?: break
            total = data.optInt("total", total)
            val info = data.optJSONArray("info") ?: break
            if (info.length() == 0) break
            for (i in 0 until info.length()) {
                val s = info.optJSONObject(i) ?: continue
                val filename = s.optString("filename")
                if (filename.isBlank()) continue
                // 酷狗 filename 是「歌手 - 歌名」
                val dash = filename.indexOf(" - ")
                val artist = if (dash > 0) filename.substring(0, dash).trim() else "未知艺人"
                val title = if (dash > 0) filename.substring(dash + 3).trim() else filename.trim()
                songs += Song(
                    id = "imp-kg-${s.optString("hash").ifBlank { s.optString("audio_id") }}",
                    title = title,
                    artistName = artist,
                    albumName = s.optString("album_name"),
                    durationMs = s.optInt("duration", 0) * 1000L,
                    artworkUrl = null,
                    artworkBgColor = null,
                    previewUrl = null,
                )
            }
            if (songs.size >= total) break
            page++
        }
        if (songs.isEmpty()) return Result.Err("歌单不存在或没有公开曲目")
        return save(name, cover, LocalLibraryStore.SOURCE_KUGOU, "https://www.kugou.com/yy/special/single/$id.html", songs, total)
    }

    /** 纯文本：每行 `歌名 - 歌手[- 专辑]`、`歌名<TAB>歌手` 或只有歌名。 */
    private fun importText(text: String, preferredName: String?): Result {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        if (lines.isEmpty()) return Result.Err("没有可导入的歌曲行")
        val songs = ArrayList<Song>(lines.size)
        for (line in lines.take(MAX_TRACKS)) {
            val parts = when {
                line.contains('\t') -> line.split('\t').map { it.trim() }
                line.contains(" - ") -> line.split(" - ").map { it.trim() }
                line.contains(" – ") -> line.split(" – ").map { it.trim() }
                else -> listOf(line)
            }
            val title = parts.getOrNull(0).orEmpty()
            if (title.isBlank() || title.length > 120) continue
            // 跳过明显的表头/统计行
            if (title.startsWith("共") && title.endsWith("首")) continue
            songs += Song(
                id = "imp-txt-${title.hashCode()}-${parts.getOrNull(1)?.hashCode() ?: 0}",
                title = title,
                artistName = parts.getOrNull(1).orEmpty().ifBlank { "未知艺人" },
                albumName = parts.getOrNull(2).orEmpty(),
                durationMs = 0L,
                artworkUrl = null,
                artworkBgColor = null,
                previewUrl = null,
            )
        }
        if (songs.isEmpty()) return Result.Err("没有识别出歌曲，格式示例：歌名 - 歌手")
        val name = preferredName?.takeIf { it.isNotBlank() } ?: "文本歌单 ${songs.size} 首"
        return save(name, null, LocalLibraryStore.SOURCE_TEXT, null, songs, songs.size)
    }

    private fun save(
        name: String,
        cover: String?,
        source: String,
        sourceUrl: String?,
        songs: List<Song>,
        total: Int,
    ): Result {
        if (songs.isEmpty()) return Result.Err("歌单里没有可导入的曲目")
        val playlist = LocalLibraryStore.LocalPlaylist(
            id = "imp-$source-${sourceUrl?.hashCode() ?: name.hashCode()}",
            name = name,
            coverUrl = cover,
            source = source,
            sourceUrl = sourceUrl,
            songs = songs.take(MAX_TRACKS),
        )
        store.saveImported(playlist)
        Log.i(TAG, "imported '$name' source=$source songs=${songs.size}/$total")
        return Result.Ok(playlist, songs.size, total)
    }

    // ── 链接处理 ────────────────────────────────────────────────────────────

    private fun isQQ(url: String) = url.contains("y.qq.com") || url.contains("qq.com") && url.contains("taoge")
    private fun isNetease(url: String) = url.contains("music.163.com") || url.contains("163cn.tv") || url.contains("y.music.163.com")
    private fun isKugou(url: String) = url.contains("kugou.com")

    private fun extractId(raw: String): String? {
        bareIdRegex.find(raw)?.groupValues?.getOrNull(1)?.let { return it }
        pathIdRegex.find(raw)?.groupValues?.getOrNull(1)?.let { return it }
        return null
    }

    /** 短链（163cn.tv / c6.y.qq.com/base/u?__= / url.cn）展开成真实地址。 */
    private fun resolveShortUrl(url: String): String {
        if (extractId(url) != null) return url
        val looksShort = listOf("163cn.tv", "c6.y.qq.com", "url.cn", "t.cn", "u.y.qq.com").any { url.contains(it) }
        if (!looksShort) return url
        return runCatching {
            val req = Request.Builder().url(url).header("User-Agent", ua).build()
            client.newCall(req).execute().use { it.request.url.toString() }
        }.getOrDefault(url)
    }

    /** 短链落到 H5 页时，从 HTML 里捞歌单 id。 */
    private fun scrapeIdFromPage(url: String): String? = runCatching {
        val req = Request.Builder().url(url).header("User-Agent", ua)
            .header("Referer", "https://y.qq.com/").build()
        client.newCall(req).execute().use { resp ->
            val html = resp.body?.string().orEmpty()
            val patterns = listOf(
                Regex("""disstid["']?\s*[:=]\s*["']?(\d{5,})"""),
                Regex("""playlistId["']?\s*[:=]\s*["']?(\d{5,})"""),
                Regex("""/playlist/(\d{5,})"""),
                Regex("""taoge\.html\?id=(\d{5,})"""),
            )
            patterns.firstNotNullOfOrNull { it.find(html)?.groupValues?.getOrNull(1) }
        }
    }.getOrNull()

    private fun get(url: String, headers: Map<String, String>): String? = runCatching {
        val builder = Request.Builder().url(url).header("User-Agent", ua)
        headers.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "HTTP ${resp.code} $url")
                return@use null
            }
            resp.body?.string()
        }
    }.getOrElse {
        Log.w(TAG, "request failed $url: ${it.message}")
        null
    }

    companion object {
        private const val TAG = "PlaylistImporter"
        private const val MAX_TRACKS = 1000
    }
}
