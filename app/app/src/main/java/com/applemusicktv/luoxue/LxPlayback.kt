package com.applemusicktv.luoxue

import android.content.Context
import com.applemusicktv.data.model.Song
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 落雪音源播放层（主链路与 PoC 共用的唯一入口）。
 *
 * 职责：
 *  - 脚本生命周期：首次使用时把 assets 里的脚本落地到 filesDir，再逐个启动 QuickJS；
 *    filesDir 里已存在的脚本优先（支持 adb push 热更新）。
 *  - 解析缓存：song.id -> AudioStream，TTL 12 分钟（直链实测 TTL ≈21 分钟）；
 *    同一首歌并发解析去重，预取与正在播放的请求共享同一个 Deferred。
 *  - 失效重解析：直链过期后由调用方 invalidate() + resolve(force=true)。
 *
 * 注意：这里不碰 ExoPlayer，也不碰 Apple DRM——只负责"给我一首歌，还你一条可播直链"。
 */
@Singleton
class LxPlayback @Inject constructor(
    @ApplicationContext private val context: Context,
    private val qualityPrefs: com.applemusicktv.data.AudioQualityPreferences,
) {

    companion object {
        /** 直链实测 TTL ≈21 分钟，留足余量。 */
        private const val CACHE_TTL_MS = 12 * 60_000L
        private const val CACHE_MAX = 24
    }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .build()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("lx_prefs", Context.MODE_PRIVATE)

    private val search by lazy { PlatformSearch(http) }
    private val verifier by lazy { StreamVerifier(http) }
    private val store by lazy { LxScriptStore(context) }
    private val engines = mutableListOf<LxScriptEngine>()
    @Volatile private var resolver: AudioResolver? = null

    private val initLock = Any()
    @Volatile private var initState = 0          // 0=未初始化 1=就绪 2=失败
    @Volatile private var lastInitAttempt = 0L

    private data class Entry(val stream: AudioStream, val at: Long)
    private val cache = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > CACHE_MAX
    }
    private val songs = ConcurrentHashMap<String, Song>()
    private val inflight = ConcurrentHashMap<String, Deferred<AudioStream?>>()
    @Volatile private var lastError: String? = null

    /** 主 App 注入日志出口（webServer.addLog("LX", …)）。 */
    @Volatile var logger: ((String) -> Unit)? = null

    /** 同时写 logcat，方便 adb 侧不依赖 UI 排查。 */
    private fun log(line: String) {
        android.util.Log.i("LxPlayback", line)
        logger?.invoke(line)
    }

    fun isEnabled(): Boolean = prefs.getBoolean("enabled", true)

    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean("enabled", on).apply()
        if (!on) {
            synchronized(cache) { cache.clear() }
            log("音源已关闭（下次播放走 Apple 直连/代理）")
        } else {
            log("音源已开启")
        }
    }

    /** 脚本是否已成功初始化。 */
    fun isReady(): Boolean = initState == 1

    // ── 脚本禁用名单 ────────────────────────────────────────────────────────
    // 停用某个音源脚本 = 从解析级联里剔除。名单持久化在 lx_prefs，
    // 下次 ensureReadyBlocking 加载时跳过被禁用的文件，即时生效（重载后）。

    fun isDisabled(fileName: String): Boolean =
        prefs.getStringSet("disabled_scripts", emptySet())?.contains(fileName) == true

    fun disabledScripts(): Set<String> =
        prefs.getStringSet("disabled_scripts", emptySet()) ?: emptySet()

    /** 停用 / 启用一个脚本文件，返回操作后的状态（true=已停用）。 */
    fun setScriptDisabled(fileName: String, disabled: Boolean): Boolean {
        val set = disabledScripts().toMutableSet()
        if (disabled) set.add(fileName) else set.remove(fileName)
        prefs.edit().putStringSet("disabled_scripts", set).apply()
        reload("${if (disabled) "停用" else "启用"} $fileName")
        return disabled
    }

    // ── 连通性检测 ──────────────────────────────────────────────────────────
    // 用一首固定热门曲在每个平台搜出候选，再逐个脚本试 musicUrl，
    // 返回非空直链即视为「连通」。结果按脚本+平台汇总，供设置页展示。

    data class SourceProbe(
        val file: String,
        val source: String,
        val ok: Boolean,
        val ms: Long,
        val detail: String,
    )

    suspend fun probeSource(fileName: String, source: String): SourceProbe {
        val engine = engines.firstOrNull { it.fileName == fileName }
            ?: return SourceProbe(fileName, source, false, 0, "脚本未加载")
        val t0 = System.currentTimeMillis()
        return runCatching {
            val candidates = search.searchAll("晴天", "周杰伦")
            val cand = candidates.firstOrNull { it.source == source }
                ?: candidates.firstOrNull()
                ?: return SourceProbe(fileName, source, false, System.currentTimeMillis() - t0, "搜索无候选")
            val qualities = engine.sources[source]
                ?: return SourceProbe(fileName, source, false, System.currentTimeMillis() - t0, "脚本不支持该源")
            val q = qualities.firstOrNull() ?: "128k"
            val url = engine.musicUrl(
                source, q,
                com.applemusicktv.luoxue.LxBench.musicInfoJson(
                    cand, cand.title, cand.artist, "", cand.durationSec,
                ),
            )
            if (url.isNullOrBlank()) {
                SourceProbe(fileName, source, false, System.currentTimeMillis() - t0, "未返回直链")
            } else {
                SourceProbe(fileName, source, true, System.currentTimeMillis() - t0, "已连通")
            }
        }.getOrElse {
            SourceProbe(fileName, source, false, System.currentTimeMillis() - t0, it.message ?: "异常")
        }
    }

    // ── 音质 ────────────────────────────────────────────────────────────────

    fun quality(): com.applemusicktv.data.AudioQuality = qualityPrefs.get()

    /**
     * 切档后必须清解析缓存：缓存里存的是按旧档位解出来的直链，
     * 不清会让用户以为设置没生效（尤其从无损切回 320k）。
     */
    fun setQuality(quality: com.applemusicktv.data.AudioQuality): com.applemusicktv.data.AudioQuality {
        if (quality != qualityPrefs.get()) {
            qualityPrefs.set(quality)
            synchronized(cache) { cache.clear() }
            synchronized(inflight) { inflight.clear() }
            log("音质切换为 ${quality.key}（档位 ${quality.ladder.joinToString("→")}）")
        }
        return qualityPrefs.get()
    }

    fun stepQuality(direction: Int): com.applemusicktv.data.AudioQuality =
        setQuality(quality().next(direction))

    // ── 脚本初始化 ──────────────────────────────────────────────────────────

    suspend fun ensureReady(): Boolean = withContext(Dispatchers.IO) { ensureReadyBlocking() }

    private fun ensureReadyBlocking(): Boolean {
        if (initState == 1) return true
        // 失败后 60s 内不重试，避免每首歌都重复启动一遍脚本；
        // 之后自动重试（用户 adb push 了新脚本也能生效）。
        if (initState == 2 && System.currentTimeMillis() - lastInitAttempt < 60_000L) return false
        synchronized(initLock) {
            if (initState == 1) return true
            lastInitAttempt = System.currentTimeMillis()
            engines.forEach { runCatching { it.close() } }
            engines.clear()
            // 先把 assets 同步到 filesDir（能感知 APK 升级后脚本更新），
            // 再按目录里的文件启动——目录即事实来源，用户导入的直接生效。
            val synced = runCatching { store.syncFromAssets() }.getOrDefault(emptyList())
            if (synced.isNotEmpty()) log("脚本同步 ${synced.size} 个：${synced.joinToString()}")
            val dir = store.dir()
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".js", true) }?.sortedBy { it.name }
            if (files.isNullOrEmpty()) {
                initState = 2
                lastError = "没有可用脚本（${dir.absolutePath} 为空）"
                log(lastError!!)
                return false
            }
            for (f in files) {
                if (isDisabled(f.name)) {
                    log("脚本已停用，跳过 ${f.name}")
                    continue
                }
                val engine = runCatching {
                    LxScriptEngine(f.name, f.readText(), http) { line -> log(line) }
                }.getOrNull() ?: continue
                val ok = runCatching { runBlocking { engine.start() } }.getOrDefault(false)
                if (ok && engine.sources.isNotEmpty()) {
                    engines += engine
                    log("脚本就绪 ${engine.displayName} ${engine.meta.version} 源=${engine.sources.keys}")
                } else {
                    runCatching { engine.close() }
                    log("脚本不可用，已跳过 ${f.name}")
                }
            }
            if (engines.isEmpty()) {
                initState = 2
                lastError = "所有脚本初始化失败"
                return false
            }
            resolver = AudioResolver(search, verifier, engines.toList()) { line -> log(line) }
            initState = 1
            lastError = null
            return true
        }
    }

    /** 脚本重载：关引擎、清缓存，下一次解析时重建（脚本换掉不用重装 APK）。 */
    fun reload(reason: String) {
        synchronized(initLock) {
            engines.forEach { runCatching { it.close() } }
            engines.clear()
            resolver = null
            synchronized(cache) { cache.clear() }
            synchronized(inflight) { inflight.clear() }
            initState = 0
            lastInitAttempt = 0
        }
        log("脚本重载：$reason")
    }

    /** 手动重载：重建引擎并返回结果（设置页「重载脚本」用它，避免只清不建）。 */
    suspend fun forceReload(): String = withContext(Dispatchers.IO) {
        reload("手动重载")
        val ok = ensureReadyBlocking()
        "已重载：${engines.size} 个脚本${if (ok) "" else "（初始化失败，见日志）"}"
    }

    /** 导入脚本（文本或已下载内容），成功后热重载。 */
    suspend fun importScript(name: String, text: String): String = withContext(Dispatchers.IO) {
        val f = store.import(name, text)
        reload("导入 ${f.name}")
        val ok = ensureReadyBlocking()
        if (ok) "已导入 ${f.name}（${f.length()}B），引擎已重建" else "已导入 ${f.name}，但引擎初始化失败"
    }

    suspend fun deleteScript(name: String): String = withContext(Dispatchers.IO) {
        val ok = store.delete(name)
        if (!ok) return@withContext "未找到 $name"
        reload("删除 $name")
        ensureReadyBlocking()
        "已删除 $name"
    }

    suspend fun resetScripts(): String = withContext(Dispatchers.IO) {
        val n = store.resetToAssets()
        reload("恢复随包脚本（清理 $n 个）")
        val ok = ensureReadyBlocking()
        "已清理 $n 个本地脚本，恢复为随包版本${if (ok) "" else "（引擎未就绪）"}"
    }

    /** 从 URL 拉脚本并导入（自用场景：音源作者发新脚本，直接贴链接）。 */
    suspend fun importFromUrl(name: String, url: String): String = withContext(Dispatchers.IO) {
        val req = okhttp3.Request.Builder().url(url).build()
        val body = http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            resp.body?.string() ?: error("空响应")
        }
        importScript(name, body)
    }

    // ── 解析 ────────────────────────────────────────────────────────────────

    /** 缓存命中（未过期）的直链；不触发网络。 */
    fun cached(songId: String): AudioStream? {
        val e = synchronized(cache) { cache[songId] } ?: return null
        if (System.currentTimeMillis() - e.at > CACHE_TTL_MS) {
            synchronized(cache) { cache.remove(songId) }
            return null
        }
        return e.stream
    }

    fun invalidate(songId: String) {
        synchronized(cache) { cache.remove(songId) }
        lastError = null
    }

    /** 解析一首歌；[force] 跳过缓存（直链过期重试）。失败返回 null，由调用方降级。 */
    suspend fun resolve(song: Song, force: Boolean = false): AudioStream? {
        if (!isEnabled()) return null
        songs[song.id] = song
        if (!force) cached(song.id)?.let { return it }
        val job = synchronized(inflight) {
            inflight[song.id] ?: scope.async { doResolve(song) }.also { inflight[song.id] = it }
        }
        return try {
            job.await()
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            lastError = "${e.javaClass.simpleName}: ${e.message}"
            log("解析异常 ${song.title}：$lastError")
            null
        } finally {
            synchronized(inflight) { if (inflight[song.id] === job) inflight.remove(song.id) }
        }
    }

    /** 后台预解析（跨淡/下一首用）。不阻塞调用方。 */
    fun prefetch(song: Song) {
        if (!isEnabled()) return
        if (cached(song.id) != null) return
        songs[song.id] = song
        scope.launch { runCatching { resolve(song) } }
    }

    private suspend fun doResolve(song: Song): AudioStream? = try {
        doResolveInner(song)
    } catch (e: Throwable) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        lastError = "${e.javaClass.simpleName}: ${e.message}"
        log("解析流程异常 ${song.title}：$lastError")
        null
    }

    private suspend fun doResolveInner(song: Song): AudioStream? {
        if (!ensureReadyBlocking()) return null
        val r = resolver ?: return null
        val durationSec = (song.durationMs / 1000).toInt()
        val q = qualityPrefs.get()
        val result = r.resolve(
            title = song.title,
            artist = song.artistName,
            album = song.albumName,
            durationSec = durationSec,
            plan = QualityPlan(q.ladder, q.minKbps),
        ) { step -> log("${song.title} · $step") }

        val stream = result.stream
        if (stream == null) {
            lastError = "未命中：${song.title}（${result.attempts.size} 次尝试 / ${result.totalMs}ms）"
            log(lastError!!)
            return null
        }
        synchronized(cache) { cache[song.id] = Entry(stream, System.currentTimeMillis()) }
        log(
            "命中 ${song.title} → ${stream.kind} ${stream.kbps}kbps " +
                "(${stream.source}/${stream.quality}/${stream.script}) " +
                "解析 ${result.totalMs}ms 校验 ${stream.verifyMs}ms"
        )
        return stream
    }

    // ── 诊断 ────────────────────────────────────────────────────────────────

    /** 给设置页用的一行脚本视图（origin=asset 表示随包且未改动）。 */
    data class ScriptView(
        val file: String,
        val origin: String,
        val loaded: Boolean,
        val size: Long,
        val disabled: Boolean = false,
        val displayName: String = "",
        val sources: Set<String> = emptySet(),
    )

    fun scriptViews(): List<ScriptView> {
        val loaded = engines.map { it.fileName }.toSet()
        val disabled = disabledScripts()
        return runCatching { store.list() }.getOrDefault(emptyList())
            .map {
                val engine = engines.firstOrNull { e -> e.fileName == it.name }
                ScriptView(
                    file = it.name,
                    origin = it.origin,
                    loaded = it.name in loaded,
                    size = it.size,
                    disabled = it.name in disabled,
                    displayName = engine?.displayName ?: "",
                    sources = engine?.sources?.keys ?: emptySet(),
                )
            }
    }

    fun loadedCount(): Int = engines.size
    fun hitRate(): String = resolver?.hitRate() ?: "-"
    fun lastErrorText(): String? = lastError
    fun cacheSize(): Int = synchronized(cache) { cache.size }
    fun scriptDir(): String = runCatching { store.dir().absolutePath }.getOrDefault("-")

    fun statsJson(): String {
        val o = JSONObject()
        o.put("enabled", isEnabled())
        o.put("ready", isReady())
        o.put("quality", qualityPrefs.get().key)
        o.put("hitRate", resolver?.hitRate() ?: "-")
        o.put("lastError", lastError ?: "")
        o.put("cached", synchronized(cache) { cache.size })
        val arr = JSONArray()
        for (e in engines) {
            arr.put(
                JSONObject()
                    .put("file", e.fileName)
                    .put("name", e.displayName)
                    .put("version", e.meta.version)
                    .put("sources", JSONArray(e.sources.keys.toList()))
                    .put("qualities", JSONObject().also { q ->
                        e.sources.forEach { (src, list) -> q.put(src, JSONArray(list)) }
                    })
            )
        }
        o.put("engines", arr)
        // 脚本仓库视角：目录里有什么、谁托管的、有没有真正加载成功。
        val loaded = engines.map { it.fileName }.toSet()
        val scripts = JSONArray()
        for (s in store.list()) {
            scripts.put(
                JSONObject()
                    .put("file", s.name)
                    .put("origin", s.origin)
                    .put("size", s.size)
                    .put("loaded", s.name in loaded)
            )
        }
        o.put("scripts", scripts)
        o.put("dir", store.dir().absolutePath)
        return o.toString()
    }

    fun describe(): String = buildString {
        append("脚本 ${engines.size} 个")
        if (engines.isNotEmpty()) {
            append("：")
            append(engines.joinToString("、") { "${it.displayName}(v${it.meta.version},${it.sources.keys.joinToString("/")})" })
        }
        resolver?.hitRate()?.let { append(" · 命中率 $it") }
    }
}

/** 供非 Hilt 入口（LxPocActivity）拿同一个单例。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface LxEntryPoint {
    fun lxPlayback(): LxPlayback
}
