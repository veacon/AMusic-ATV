package com.veacon.veilmusic.luoxue

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * 平台搜索层：Apple 侧只给歌名/歌手，这里补上平台曲目 ID。
 *
 * 为什么必须自建：落雪自定义源协议只有 musicUrl（ID -> 直链），不含搜索。
 * P0 实测：只给歌名时 15 个脚本仅 1 个可用且只出 128k；补上 ID 后 7 个脚本可出 FLAC。
 */
class PlatformSearch(private val http: OkHttpClient) {

    fun searchAll(title: String, artist: String): List<Candidate> {
        val q = listOf(title.trim(), primaryArtist(artist))
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        if (q.isEmpty()) return emptyList()
        val out = mutableListOf<Candidate>()
        out += runCatching { netease(q) }.getOrDefault(emptyList())
        out += runCatching { qq(q) }.getOrDefault(emptyList())
        out += runCatching { kugou(q) }.getOrDefault(emptyList())
        return out
    }

    /** 跨语言曲名兜底：只按艺人搜，匹配层再用时长认歌。 */
    fun searchByArtist(artist: String, limit: Int = 12): List<Candidate> {
        val q = primaryArtist(artist)
        if (q.isEmpty()) return emptyList()
        val out = mutableListOf<Candidate>()
        out += runCatching { netease(q, limit) }.getOrDefault(emptyList())
        out += runCatching { qq(q, limit) }.getOrDefault(emptyList())
        out += runCatching { kugou(q, limit) }.getOrDefault(emptyList())
        return out
    }

    private fun primaryArtist(artist: String) =
        artist.split("&", ",", "、", "/").first().trim()

    private fun get(url: String, referer: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Referer", referer)
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            return resp.body?.string() ?: ""
        }
    }

    // ── 网易云 ──────────────────────────────────────────────────────────────
    fun netease(q: String, limit: Int = 8): List<Candidate> {
        val url = "https://music.163.com/api/search/get/web".toHttpUrl().newBuilder()
            .addQueryParameter("s", q)
            .addQueryParameter("type", "1")
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("offset", "0")
            .build().toString()
        val root = JSONObject(get(url, "https://music.163.com/"))
        val songs = root.optJSONObject("result")?.optJSONArray("songs") ?: return emptyList()
        return (0 until songs.length()).mapNotNull { i ->
            val s = songs.optJSONObject(i) ?: return@mapNotNull null
            val artists = s.optJSONArray("artists")
            val artistName = (0 until (artists?.length() ?: 0))
                .mapNotNull { artists?.optJSONObject(it)?.optString("name") }
                .joinToString(" / ")
            Candidate(
                source = "wy",
                id = s.optString("id"),
                title = s.optString("name"),
                artist = artistName,
                album = s.optJSONObject("album")?.optString("name").orEmpty(),
                durationSec = (s.optLong("duration") / 1000).toInt(),
                extra = mapOf("songmid" to s.optString("id"), "songId" to s.optString("id")),
            )
        }
    }

    // ── QQ 音乐 ─────────────────────────────────────────────────────────────
    fun qq(q: String, limit: Int = 8): List<Candidate> {
        val url = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp".toHttpUrl().newBuilder()
            .addQueryParameter("w", q)
            .addQueryParameter("format", "json")
            .addQueryParameter("n", limit.toString())
            .addQueryParameter("p", "1")
            .build().toString()
        val root = JSONObject(get(url, "https://y.qq.com/"))
        val list = root.optJSONObject("data")?.optJSONObject("song")?.optJSONArray("list") ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val s = list.optJSONObject(i) ?: return@mapNotNull null
            val singers = s.optJSONArray("singer")
            val artistName = (0 until (singers?.length() ?: 0))
                .mapNotNull { singers?.optJSONObject(it)?.optString("name") }
                .joinToString(" / ")
            Candidate(
                source = "tx",
                id = s.optString("songmid"),
                title = s.optString("songname"),
                artist = artistName,
                album = s.optString("albumname"),
                durationSec = s.optInt("interval"),
                extra = mapOf(
                    "songmid" to s.optString("songmid"),
                    "strMediaMid" to s.optString("media_mid"),
                    "mid" to s.optString("songmid"),
                ),
            )
        }
    }

    // ── 酷狗 ────────────────────────────────────────────────────────────────
    fun kugou(q: String, limit: Int = 8): List<Candidate> {
        val url = "https://songsearch.kugou.com/song_search_v2".toHttpUrl().newBuilder()
            .addQueryParameter("keyword", q)
            .addQueryParameter("page", "1")
            .addQueryParameter("pagesize", limit.toString())
            .build().toString()
        val root = JSONObject(get(url, "https://www.kugou.com/"))
        val list = root.optJSONObject("data")?.optJSONArray("lists") ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val s = list.optJSONObject(i) ?: return@mapNotNull null
            Candidate(
                source = "kg",
                id = s.optString("FileHash"),
                title = s.optString("SongName").replace(Regex("<[^>]+>"), ""),
                artist = s.optString("SingerName").replace(Regex("<[^>]+>"), ""),
                album = s.optString("AlbumName"),
                durationSec = s.optInt("Duration"),
                extra = mapOf(
                    "hash" to s.optString("FileHash"),
                    "album_id" to s.optString("AlbumID"),
                    "album_audio_id" to s.optString("AlbumAudioID"),
                ),
            )
        }
    }

    companion object {
        const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
