package com.applemusicktv.data.datasource

import android.util.Log
import com.applemusicktv.data.network.LyricLine
import com.applemusicktv.media.awaitTextResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 网易云 / QQ 音乐歌词源。
 *
 * 为什么需要它：Apple 的 TTML 歌词关系（catalog songs/{id}/lyrics）必须带
 * Media-User-Token，未登录一律 404；而本 app 不再要求登录。所以歌词改由
 * 网易云、QQ 提供，用「标题 + 艺人 + 时长」在两家平台上匹配同名曲目。
 *
 * 设计取舍：
 *  - 独立 OkHttpClient：绝不能带上 Apple 的 Authorization / Cookie 拦截器。
 *  - 两段式（搜索 → 取词）：平台搜索接口返回的候选里有时长和艺人，能有效
 *    避免「同名不同曲」拿到错词。
 *  - 只依赖公开接口（不带 cookie / 不做登录），失败就交给下一个源。
 */
@Singleton
class ThirdPartyLyrics @Inject constructor() {

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private companion object {
        const val TAG = "ThirdPartyLyrics"
        const val MIN_SCORE = 42.0
        const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    }

    private data class Hit(
        val id: String,
        val title: String,
        val artists: String,
        val durationSec: Int,
        val score: Double,
        val extra: String = "",
    )

    // ── 对外 ────────────────────────────────────────────────────────────────

    /** 网易云 → QQ，返回第一条可用歌词；都没有则空列表。 */
    suspend fun fetch(title: String, artist: String, durationSec: Long): List<LyricLine> {
        if (title.isBlank()) return emptyList()
        netease(title, artist, durationSec).takeIf { it.isNotEmpty() }?.let { return it }
        qq(title, artist, durationSec).takeIf { it.isNotEmpty() }?.let { return it }
        return emptyList()
    }

    // ── 网易云 ──────────────────────────────────────────────────────────────

    private suspend fun netease(title: String, artist: String, durationSec: Long): List<LyricLine> =
        withContext(Dispatchers.IO) {
            try {
                val q = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")
                val url = "https://music.163.com/api/search/get/web?type=1&limit=10&s=" + enc(q)
                val body = http.newCall(
                    Request.Builder().url(url)
                        .addHeader("User-Agent", UA)
                        .addHeader("Referer", "https://music.163.com/")
                        .build()
                ).awaitTextResponse().body
                val songs = JSONObject(body).optJSONObject("result")?.optJSONArray("songs") ?: return@withContext emptyList()
                val hits = (0 until songs.length()).mapNotNull { i ->
                    val o = songs.optJSONObject(i) ?: return@mapNotNull null
                    val artists = o.optJSONArray("artists")?.let { arr ->
                        (0 until arr.length()).joinToString("/") { arr.optJSONObject(it)?.optString("name").orEmpty() }
                    }.orEmpty()
                    val duration = (o.optLong("duration", 0L) / 1000L).toInt()
                    Hit(
                        id = o.optString("id"),
                        title = o.optString("name"),
                        artists = artists,
                        durationSec = duration,
                        score = score(title, artist, durationSec, o.optString("name"), artists, duration),
                    )
                }.filter { it.id.isNotBlank() && it.score >= MIN_SCORE }
                    .sortedByDescending { it.score }

                for (h in hits.take(3)) {
                    val lrc = neteaseLyric(h.id) ?: continue
                    val lines = LyricsLrc.parse(lrc)
                    Log.i(TAG, "netease hit id=${h.id} score=${h.score.toInt()} lines=${lines.size} '$title'")
                    if (lines.isNotEmpty()) return@withContext lines
                }
                emptyList()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i(TAG, "netease failed: ${e.javaClass.simpleName} ${e.message}")
                emptyList()
            }
        }

    private suspend fun neteaseLyric(id: String): String? = try {
        val url = "https://music.163.com/api/song/lyric?id=$id&lv=1&kv=1&tv=-1"
        val body = http.newCall(
            Request.Builder().url(url)
                .addHeader("User-Agent", UA)
                .addHeader("Referer", "https://music.163.com/")
                .build()
        ).awaitTextResponse().body
        JSONObject(body).optJSONObject("lrc")?.optString("lyric")?.takeIf { it.isNotBlank() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    // ── QQ 音乐 ─────────────────────────────────────────────────────────────

    private suspend fun qq(title: String, artist: String, durationSec: Long): List<LyricLine> =
        withContext(Dispatchers.IO) {
            try {
                val q = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")
                val url = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp" +
                    "?format=json&n=10&cr=1&g_tk=5381&w=" + enc(q)
                val body = http.newCall(
                    Request.Builder().url(url)
                        .addHeader("User-Agent", UA)
                        .addHeader("Referer", "https://y.qq.com/")
                        .build()
                ).awaitTextResponse().body
                val list = JSONObject(jsonOnly(body)).optJSONObject("data")
                    ?.optJSONObject("song")?.optJSONArray("list") ?: return@withContext emptyList()
                val hits = (0 until list.length()).mapNotNull { i ->
                    val o = list.optJSONObject(i) ?: return@mapNotNull null
                    val singers = o.optJSONArray("singer")?.let { arr ->
                        (0 until arr.length()).joinToString("/") { arr.optJSONObject(it)?.optString("name").orEmpty() }
                    }.orEmpty()
                    val duration = o.optInt("interval", 0)
                    Hit(
                        id = o.optString("songmid"),
                        title = o.optString("songname"),
                        artists = singers,
                        durationSec = duration,
                        score = score(title, artist, durationSec, o.optString("songname"), singers, duration),
                    )
                }.filter { it.id.isNotBlank() && it.score >= MIN_SCORE }
                    .sortedByDescending { it.score }

                for (h in hits.take(3)) {
                    val lrc = qqLyric(h.id) ?: continue
                    val lines = LyricsLrc.parse(lrc)
                    Log.i(TAG, "qq hit mid=${h.id} score=${h.score.toInt()} lines=${lines.size} '$title'")
                    if (lines.isNotEmpty()) return@withContext lines
                }
                emptyList()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i(TAG, "qq failed: ${e.javaClass.simpleName} ${e.message}")
                emptyList()
            }
        }

    private suspend fun qqLyric(songmid: String): String? = try {
        val url = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg" +
            "?songmid=$songmid&format=json&nobase64=1&g_tk=5381"
        val body = http.newCall(
            Request.Builder().url(url)
                .addHeader("User-Agent", UA)
                .addHeader("Referer", "https://y.qq.com/portal/player.html")
                .build()
        ).awaitTextResponse().body
        JSONObject(jsonOnly(body)).optString("lyric").takeIf { it.isNotBlank() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    // ── 匹配打分 ────────────────────────────────────────────────────────────

    private fun score(
        wantTitle: String, wantArtist: String, wantDur: Long,
        gotTitle: String, gotArtists: String, gotDur: Int,
    ): Double {
        val t = similarity(norm(wantTitle), norm(gotTitle))
        val a = similarity(norm(wantArtist), norm(gotArtists))
        val d = when {
            wantDur <= 0L || gotDur <= 0 -> 0.5
            kotlin.math.abs(wantDur - gotDur) <= 2 -> 1.0
            kotlin.math.abs(wantDur - gotDur) <= 5 -> 0.7
            kotlin.math.abs(wantDur - gotDur) <= 12 -> 0.35
            else -> 0.0
        }
        // 标题是硬指标，艺人/时长只在标题接近时才加权
        return (t * 60.0) + (a * 25.0) + (d * 15.0)
    }

    /** 去掉括号内的版本说明、空格与标点，小写化。 */
    private fun norm(s: String): String = s.lowercase()
        .replace(Regex("""[（(\[【].*?[）)\]】]"""), "")
        .replace(Regex("""[\s\-_·・.,，。!！?？'"“”‘’]"""), "")
        .trim()

    private fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        if (a.contains(b) || b.contains(a)) return 0.85
        return dice(a, b)
    }

    /** 字符二元组 Dice 系数，对中英文都还算稳。 */
    private fun dice(a: String, b: String): Double {
        if (a.length < 2 || b.length < 2) return if (a == b) 1.0 else 0.0
        val ga = grams(a)
        val gb = grams(b)
        if (ga.isEmpty() || gb.isEmpty()) return 0.0
        var inter = 0
        val pool = gb.toMutableMap()
        for ((g, n) in ga) {
            val have = pool[g] ?: 0
            if (have > 0) {
                val use = minOf(n, have)
                inter += use
                pool[g] = have - use
            }
        }
        return 2.0 * inter / (a.length - 1 + b.length - 1)
    }

    private fun grams(s: String): Map<String, Int> {
        val m = HashMap<String, Int>()
        for (i in 0 until s.length - 1) {
            val g = s.substring(i, i + 2)
            m[g] = (m[g] ?: 0) + 1
        }
        return m
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    /** QQ 的 fcg 接口偶尔套一层 callback(...)，截出纯 JSON。 */
    private fun jsonOnly(body: String): String {
        val start = body.indexOf('{')
        val end = body.lastIndexOf('}')
        return if (start >= 0 && end > start) body.substring(start, end + 1) else body
    }
}

/** LRC → LyricLine。与 Apple 通道共用同一套时间轴语义。 */
internal object LyricsLrc {

    private val tagRe = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")

    /** 过滤 [ti:] / [ar:] / [offset:] 这类元信息行，只保留带时间戳的正文。 */
    fun parse(lrc: String): List<LyricLine> {
        val out = mutableListOf<Pair<Long, String>>()
        for (raw in lrc.lines()) {
            val text = raw.replace(tagRe, "").trim()
            if (text.isEmpty()) continue
            if (!tagRe.containsMatchIn(raw)) continue
            for (m in tagRe.findAll(raw)) {
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val frac = m.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
                out.add((min * 60000 + sec * 1000 + frac) to text)
            }
        }
        out.sortBy { it.first }
        return out.mapIndexed { i, (startMs, text) ->
            val endMs = if (i + 1 < out.size) out[i + 1].first else startMs + 5000
            LyricLine(startMs, endMs, text, emptyList(), null)
        }
    }
}
