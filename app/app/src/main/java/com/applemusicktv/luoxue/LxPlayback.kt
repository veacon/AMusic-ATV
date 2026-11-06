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
) {

    companion object {
        /** 直链实测 TTL ≈21 分钟，留足余量。 */
        private const val CACHE_TTL_MS = 12 * 60_000L
        private const val CACHE_MAX = 24
        private const val SCRIPT_DIR = "luoxue"
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
            val dir = File(context.filesDir, SCRIPT_DIR).apply { mkdirs() }
            runCatching { seedScripts(dir) }
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".js", true) }?.sortedBy { it.name }
            if (files.isNullOrEmpty()) {
                initState = 2
                lastError = "没有可用脚本（${dir.absolutePath} 为空）"
                log(lastError!!)
                return false
            }
            for (f in files) {
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

    /** 只在目标不存在时落地，避免覆盖用户热更新的脚本。 */
    private fun seedScripts(dir: File) {
        val names = context.assets.list(SCRIPT_DIR) ?: return
        for (name in names) {
            val target = File(dir, name)
            if (target.exists() && target.length() > 0) continue
            runCatching {
                context.assets.open("$SCRIPT_DIR/$name").use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
            }
        }
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
        val result = r.resolve(
            title = song.title,
            artist = song.artistName,
            album = song.albumName,
            durationSec = durationSec,
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

    fun statsJson(): String {
        val o = JSONObject()
        o.put("enabled", isEnabled())
        o.put("ready", isReady())
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
