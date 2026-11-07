package com.veacon.veilmusic.luoxue

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 解析编排：搜索 → 匹配 → 脚本级联 → 直链校验。
 *
 * P0 结论直接固化在这里：
 *  - 单一脚本不可靠（15 个里 6 个已死），必须按源做脚本级联；
 *  - 音质按设置页的偏好逐级降（无损档 master → flac → 320k），第一个可用即停；
 *  - 每个结果都要用真实字节校验，否则会拿到"假无损"。
 */
class AudioResolver(
    private val search: PlatformSearch,
    private val verifier: StreamVerifier,
    private val engines: List<LxScriptEngine>,
    private val log: (String) -> Unit = {},
) {

    companion object {
        /**
         * 按源指定脚本优先级（2026-11 实测，3 首歌 × wy/tx/kg）。
         *
         * 10 条内置脚本按命中率排序（2026-11 复测，lx-hunter 收录仓库 + 端内 QuickJS 实测）：
         *  - lx-01-kulao（裤佬SVIP）：8/9，wy 322k / kg 320k / tx 偏低
         *  - lx-02-hyw（HYW公益版）：7/9，最快（250–400ms），tx 有 319k 样本
         *  - lx-03-fish（fish_music）：5/9，wy 稳定 322k，kg 124–201k
         *  - lx-09-ciallo（Ciallo～）：3/3，wy 稳定 319–322k（新增，wx 专项高分）
         *  - lx-10-qdy（星海GD精简 qdy-mini）：3/9，wy 319–322k（新增）
         *  - lx-04-xinghai（星海）：kg 兜底最快（320ms）
         *  - lx-05-quandou（全豆要）：wy 127–128k 兜底
         *  - lx-06-molan（墨澜聚合）：kg 128–320k
         *  - lx-07-hubhui（回避聚合）：kg 310–320k（替换星澜）
         *  - lx-08-jiexiang（杰翔聚合）：kg 320k
         * 后六个命中率/码率偏低，只作主力全挂时的兜底。
         */
        private val PREF = mapOf(
            "wy" to listOf(
                "lx-01-kulao.js", "lx-02-hyw.js", "lx-03-fish.js", "lx-09-ciallo.js",
                "lx-10-qdy.js", "lx-05-quandou.js",
            ),
            "tx" to listOf(
                "lx-01-kulao.js", "lx-02-hyw.js", "lx-03-fish.js",
            ),
            "kg" to listOf(
                "lx-01-kulao.js", "lx-04-xinghai.js", "lx-02-hyw.js", "lx-03-fish.js",
                "lx-06-molan.js", "lx-07-hubhui.js", "lx-08-jiexiang.js",
            ),
        )

        private const val MIN_SCORE = 45
        /** 码率未知时，小于这个体积基本是 30 秒试听片段。 */
        private const val MIN_COMPLETE_BYTES = 1_800_000L
        /** 单个脚本最多试几档音质：脚本内部已自带降级，多试只会重复跑后端的慢请求。 */
        private const val MAX_QUALITY_TRIES = 2
        /** 单曲解析总预算：P0 最坏 40s 不可接受，超时即放弃并交给上层换源/跳过。 */
        private const val TOTAL_BUDGET_MS = 40_000L
    }

    private var resolvedCount = 0
    private var attemptCount = 0

    fun hitRate(): String = if (attemptCount == 0) "-" else "$resolvedCount/$attemptCount"

    suspend fun resolve(
        title: String,
        artist: String,
        album: String = "",
        durationSec: Int = 0,
        plan: QualityPlan = QualityPlan.DEFAULT,
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
            val byArtist = runCatching { search.searchByArtist(artist) }.getOrDefault(emptyList())
            ranked = TrackMatcher.rankByDurationOnly(byArtist, artist, durationSec)
            attempts += ResolveAttempt("search", detail = "artist+duration fallback → ${ranked.size}")
        }

        attempts += ResolveAttempt("search", ok = ranked.isNotEmpty(), detail = "candidates=${ranked.size}")

        if (ranked.isEmpty()) {
            log("搜索无候选：${attempts.lastOrNull()?.detail ?: "-"}")
            return@withContext ResolveResult(null, attempts, null, System.currentTimeMillis() - t0)
        }

        val shortlist = ranked.distinctBy { it.source }.take(3)
        onStep("命中 ${shortlist.joinToString { "${it.source}:${it.score}" }}")

        for (cand in shortlist) {
            val scriptOrder = PREF[cand.source] ?: emptyList()
            val ordered = engines.sortedBy { e ->
                scriptOrder.indexOf(e.fileName).let { if (it < 0) 99 else it }
            }
            if (System.currentTimeMillis() - t0 > TOTAL_BUDGET_MS) {
                attempts += ResolveAttempt("budget", cand.source, detail = "exceeded ${TOTAL_BUDGET_MS}ms")
                break
            }
            for (engine in ordered) {
                val qualities = engine.sources[cand.source] ?: continue
                // 按用户选的档位取前两档：脚本内部自带 quality 降级表，
                // 逐档调用只会重复跑同一串后端（实测一档就要 10s+）。
                val ladder = plan.ladder.filter { it in qualities }.take(MAX_QUALITY_TRIES)
                if (ladder.isEmpty()) continue
                for (q in ladder) {
                    if (System.currentTimeMillis() - t0 > TOTAL_BUDGET_MS) break
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
                    if (probe.ok && acceptable(probe, plan.minKbps)) {
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
                            sourceName = engine.displayName,
                        )
                        onStep("成功：${probe.kind} ${probe.kbps}kbps（${cand.source}/${engine.displayName}）")
                        return@withContext ResolveResult(stream, attempts, cand, System.currentTimeMillis() - t0)
                    }
                }
            }
        }

        onStep("全部源失败，跳过")
        log("失败明细：" + attempts.takeLast(8).joinToString(" | ") {
            "${it.stage}/${it.source}/${it.script}/${it.quality} ${if (it.ok) "✓" else "✗"} ${it.detail}"
        })
        ResolveResult(null, attempts, null, System.currentTimeMillis() - t0)
    }

    /**
     * 试听片段 vs 完整曲目：
     *  - flac 直接接受；
     *  - 码率已知时按 [minKbps] 门槛（低码率试听被挡，宁可换源）；
     *  - 码率未知（脚本普遍不返回 bitrate；文本导入的歌也没有时长）时按文件体积判断：
     *    30 秒试听一般 < 1.5MB，完整曲目普遍 > 2MB。这块之前直接按 0kbps 拒掉，
     *    导致 5MB 的正常 mp3 被误杀。
     */
    private fun acceptable(probe: StreamVerifier.Probe, minKbps: Int): Boolean =
        probe.kind == "flac" || probe.kind == "flac24bit" ||
            probe.kbps >= minKbps ||
            (probe.kbps <= 0 && probe.bytes >= MIN_COMPLETE_BYTES)

    private fun musicInfo(
        cand: Candidate,
        title: String,
        artist: String,
        album: String,
        durationSec: Int,
    ): String = LxBench.musicInfoJson(cand, title, artist, album, durationSec)
}
