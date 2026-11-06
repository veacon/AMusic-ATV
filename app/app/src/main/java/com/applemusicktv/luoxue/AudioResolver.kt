package com.applemusicktv.luoxue

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 解析编排：搜索 → 匹配 → 脚本级联 → 直链校验。
 *
 * P0 结论直接固化在这里：
 *  - 单一脚本不可靠（15 个里 6 个已死），必须按源做脚本级联；
 *  - 音质必须逐级降（master → flac → 320k → 128k），第一个可用即停；
 *  - 每个结果都要用真实字节校验，否则会拿到"假无损"。
 */
class AudioResolver(
    private val search: PlatformSearch,
    private val verifier: StreamVerifier,
    private val engines: List<LxScriptEngine>,
    private val log: (String) -> Unit = {},
) {

    companion object {
        /** 按源指定脚本优先级（来自 P0 实测的最高码率排序）。 */
        private val PREF = mapOf(
            "wy" to listOf("洛雪音源14.js", "洛雪音源1.js", "洛雪音源15.js", "洛雪音源13.js"),
            "tx" to listOf("洛雪音源14.js", "洛雪音源13.js", "洛雪音源3.js"),
            "kg" to listOf("洛雪音源3.js", "洛雪音源14.js", "洛雪音源13.js"),
        )
        private val LADDER = listOf("master", "flac24bit", "hires", "flac", "320k", "128k")
        private const val ACCEPT_KBPS = 200
        private const val MIN_SCORE = 45
    }

    private var resolvedCount = 0
    private var attemptCount = 0

    fun hitRate(): String = if (attemptCount == 0) "-" else "$resolvedCount/$attemptCount"

    suspend fun resolve(
        title: String,
        artist: String,
        album: String = "",
        durationSec: Int = 0,
        onStep: (String) -> Unit = {},
    ): ResolveResult = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val attempts = mutableListOf<ResolveAttempt>()
        attemptCount++

        onStep("搜索平台…")
        var cands = runCatching { search.searchAll(title, artist) }.getOrDefault(emptyList())
        var ranked = TrackMatcher.rank(cands, title, artist, album, durationSec)
            .filter { TrackMatcher.isAcceptable(it) }

        // 跨语言曲名兜底：只按艺人搜，靠时长认歌
        if (ranked.isEmpty()) {
            onStep("标题未命中，按艺人+时长兜底…")
            val byArtist = runCatching { search.searchAll(title, artist) }.getOrDefault(emptyList())
            ranked = TrackMatcher.rankByDurationOnly(byArtist, artist, durationSec)
            attempts += ResolveAttempt("search", detail = "artist+duration fallback → ${ranked.size}")
        }

        attempts += ResolveAttempt("search", ok = ranked.isNotEmpty(), detail = "candidates=${ranked.size}")

        if (ranked.isEmpty()) {
            return@withContext ResolveResult(null, attempts, null, System.currentTimeMillis() - t0)
        }

        val shortlist = ranked.distinctBy { it.source }.take(3)
        onStep("命中 ${shortlist.joinToString { "${it.source}:${it.score}" }}")

        for (cand in shortlist) {
            val scriptOrder = PREF[cand.source] ?: emptyList()
            val ordered = engines.sortedBy { e ->
                scriptOrder.indexOf(e.fileName).let { if (it < 0) 99 else it }
            }
            for (engine in ordered) {
                val qualities = engine.sources[cand.source] ?: continue
                val ladder = LADDER.filter { it in qualities }.take(3)
                if (ladder.isEmpty()) continue
                for (q in ladder) {
                    val started = System.currentTimeMillis()
                    val url: String? = try {
                        engine.musicUrl(cand.source, q, musicInfo(cand, title, artist, album, durationSec))
                    } catch (e: Exception) {
                        attempts += ResolveAttempt(
                            "resolve", cand.source, engine.fileName, q, false,
                            e.message ?: "error", System.currentTimeMillis() - started,
                        )
                        null
                    }
                    if (url.isNullOrBlank()) continue
                    val tVerify = System.currentTimeMillis()
                    val probe = verifier.probe(url, durationSec)
                    attempts += ResolveAttempt(
                        "verify", cand.source, engine.fileName, q, probe.ok,
                        "${probe.kind}/${probe.kbps}kbps ${probe.bytes}B ${probe.err}",
                        System.currentTimeMillis() - tVerify,
                    )
                    if (probe.ok && (probe.kind == "flac" || probe.kbps >= ACCEPT_KBPS)) {
                        resolvedCount++
                        val stream = AudioStream(
                            url = url,
                            kind = probe.kind,
                            kbps = probe.kbps,
                            mimeType = probe.mimeType,
                            source = cand.source,
                            script = engine.fileName,
                            quality = q,
                            resolveMs = started - t0,
                            verifyMs = System.currentTimeMillis() - tVerify,
                        )
                        onStep("成功：${probe.kind} ${probe.kbps}kbps（${cand.source}/${engine.displayName}）")
                        return@withContext ResolveResult(stream, attempts, cand, System.currentTimeMillis() - t0)
                    }
                }
            }
        }

        onStep("全部源失败，跳过")
        ResolveResult(null, attempts, null, System.currentTimeMillis() - t0)
    }

    private fun musicInfo(
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
        o.put("albumName", album)
        o.put("interval", "%d:%02d".format(durationSec / 60, durationSec % 60))
        o.put("id", cand.id)
        o.put("songid", cand.id)
        o.put("songId", cand.id)
        cand.extra.forEach { (k, v) -> o.put(k, v) }
        return o.toString()
    }
}
