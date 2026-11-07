package com.veacon.veilmusic.luoxue

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 音源批量跑测（"优中选 N" 的决策依据）。
 *
 * 为什么不能靠"脚本能不能加载"来选：实测大量脚本能正常 init、也能声明 wy/tx/kg，
 * 但后端早已失效——只有走一遍真实的 search → musicUrl → 字节校验 才知道能不能播。
 *
 * 产出：CSV（file,source,ok,ms,kind,kbps,quality,err），一行 = 一次真实取链尝试。
 */
class LxBench(
    private val dir: File,
    private val tracks: List<Track>,
    private val quality: String = "320k",
    private val perCallTimeoutMs: Long = 10_000,
    private val concurrency: Int = 5,
    private val onLog: (String) -> Unit = {},
) {

    data class Track(val title: String, val artist: String, val album: String, val durationSec: Int)

    data class Row(
        val file: String,
        val name: String,
        val source: String,
        val ok: Boolean,
        val ms: Long,
        val kind: String = "",
        val kbps: Int = 0,
        val detail: String = "",
        val track: String = "",
    )

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(14, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .build()
    }

    private val search by lazy { PlatformSearch(http) }
    private val verifier by lazy { StreamVerifier(http) }

    /** 每首歌单独搜索：同一脚本要用"这首歌自己的"平台 ID，否则复测等于只测了一首。 */
    private suspend fun candidatesPerTrack(): List<Pair<Track, Map<String, Candidate>>> =
        withContext(Dispatchers.IO) {
            tracks.map { t ->
                val cands = runCatching { search.searchAll(t.title, t.artist) }.getOrDefault(emptyList())
                val best = TrackMatcher.rank(cands, t.title, t.artist, t.album, t.durationSec)
                    .filter { TrackMatcher.isAcceptable(it) }
                    .distinctBy { it.source }
                    .associateBy { it.source }
                onLog("候选 ${t.title}：" + best.entries.joinToString { "${it.key}=${it.value.id}" })
                t to best
            }
        }

    suspend fun run(csv: File): String = withContext(Dispatchers.IO) {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".js", true) }
            ?.sortedBy { it.name }.orEmpty()
        if (files.isEmpty()) return@withContext "no scripts in ${dir.absolutePath}"

        val perTrack = candidatesPerTrack()
        val rows = mutableListOf<Row>()
        val sem = Semaphore(concurrency)
        val started = System.currentTimeMillis()

        coroutineScope {
            files.map { f ->
                async(Dispatchers.IO) {
                    sem.withPermit { runOne(f, perTrack) }
                }
            }.awaitAll().forEach { rows += it }
        }

        csv.parentFile?.mkdirs()
        csv.writeText(buildString {
            appendLine("file,name,source,ok,ms,kind,kbps,track,detail")
            rows.sortedWith(compareBy({ it.file }, { it.source })).forEach { r ->
                appendLine(
                    listOf(
                        r.file, r.name, r.source, if (r.ok) 1 else 0, r.ms,
                        r.kind, r.kbps, r.track, r.detail.replace(',', ' ').replace('\n', ' ').take(160),
                    ).joinToString(",")
                )
            }
        })
        val sum = "脚本 ${files.size} 条 / 尝试 ${rows.size} 次 / 成功 ${rows.count { it.ok }} 次 / " +
            "${(System.currentTimeMillis() - started) / 1000}s → ${csv.absolutePath}"
        onLog(sum)
        Log.i("LxBench", sum)
        sum
    }

    private suspend fun runOne(
        f: File,
        perTrack: List<Pair<Track, Map<String, Candidate>>>,
    ): List<Row> {
        val engine = runCatching { LxScriptEngine(f.name, f.readText(), http) { } }.getOrNull()
            ?: return listOf(Row(f.name, "", "-", false, 0, detail = "构造失败"))
        val t0 = System.currentTimeMillis()
        val ok = runCatching { withTimeoutOrNull(25_000) { engine.start() } ?: false }.getOrDefault(false)
        val initMs = System.currentTimeMillis() - t0
        if (!ok || engine.sources.isEmpty()) {
            runCatching { engine.close() }
            val r = listOf(Row(f.name, engine.displayName, "-", false, initMs, detail = "init失败/无源"))
            onLog("BENCH ${r[0]}")
            return r
        }
        val rows = mutableListOf<Row>()
        for ((track, cands) in perTrack) {
        for ((src, qualities) in engine.sources) {
            val cand = cands[src] ?: continue
            val q = pickQuality(quality, qualities)
            val started = System.currentTimeMillis()
            var resolved: String? = null
            var err = ""
            try {
                resolved = withTimeoutOrNull(perCallTimeoutMs) {
                    engine.musicUrl(
                        src, q,
                        musicInfoJson(cand, track.title, track.artist, track.album, track.durationSec),
                    )
                }
                if (resolved == null) err = "超时 ${perCallTimeoutMs}ms"
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                err = e.message ?: e.javaClass.simpleName
            }
            val ms = System.currentTimeMillis() - started
            val url = resolved
            if (url == null) {
                rows += Row(f.name, engine.displayName, src, false, ms, detail = err, track = track.title)
                continue
            }
            val probe = runCatching { verifier.probe(url, track.durationSec) }.getOrNull()
            rows += Row(
                f.name, engine.displayName, src, probe?.ok == true, ms,
                probe?.kind ?: "", probe?.kbps ?: 0,
                (probe?.err ?: "verify fail"), track.title,
            )
        }
        }
        runCatching { engine.close() }
        rows.forEach { onLog("BENCH $it") }
        if (rows.isEmpty()) {
            val r = listOf(Row(f.name, engine.displayName, "-", false, initMs, detail = "无匹配候选"))
            onLog("BENCH ${r[0]}")
            return r
        }
        return rows
    }

    private fun pickQuality(preferred: String, available: List<String>): String {
        if (preferred in available) return preferred
        for (q in listOf("320k", "128k", "flac", "flac24bit", "hires", "master")) {
            if (q in available) return q
        }
        return available.firstOrNull() ?: preferred
    }

    companion object {
        /** 固定测试曲目：中文流行 / 英文流行 / 粤语，覆盖常见的版权与匹配差异。 */
        val DEFAULT_TRACKS = listOf(
            Track("晴天", "周杰伦", "叶惠美", 269),
            Track("富士山下", "陈奕迅", "What's Going On...?", 259),
            Track("Anti-Hero", "Taylor Swift", "Midnights", 201),
        )

        fun musicInfoJson(
            cand: Candidate,
            title: String,
            artist: String,
            album: String,
            durationSec: Int,
        ): String {
            val o = JSONObject()
            o.put("source", cand.source)
            o.put("name", title)
            o.put("songName", title)
            o.put("singer", artist)
            o.put("artist", artist)
            o.put("albumName", album)
            o.put("interval", "%d:%02d".format(durationSec / 60, durationSec % 60))
            o.put("id", cand.id)
            o.put("songid", cand.id)
            o.put("songId", cand.id)
            o.put("rid", cand.id)
            o.put("songmid", cand.extra["songmid"] ?: cand.id)
            o.put("mediaMid", cand.extra["strMediaMid"] ?: "")
            o.put("strMediaMid", cand.extra["strMediaMid"] ?: "")
            o.put("media_mid", cand.extra["strMediaMid"] ?: "")
            o.put("hash", cand.extra["hash"] ?: "")
            o.put("albumId", cand.extra["album_id"] ?: "")
            o.put("albumAudioId", cand.extra["album_audio_id"] ?: "")
            cand.extra.forEach { (k, v) -> o.put(k, v) }
            return o.toString()
        }
    }
}
