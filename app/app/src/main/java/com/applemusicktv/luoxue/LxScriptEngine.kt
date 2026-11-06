package com.applemusicktv.luoxue

import android.util.Log
import com.quickjs.JSArray
import com.quickjs.JSContext
import com.quickjs.JavaCallback
import com.quickjs.JavaVoidCallback
import com.quickjs.QuickJS
import com.quickjs.plugin.ConsolePlugin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 一个落雪脚本 = 一个 QuickJS runtime + 一个 context。
 *
 * 为什么要独立 runtime：脚本通过 `lx.on('request', ...)` 注册全局 handler，
 * 多个脚本共用会互相覆盖。P0 实测 15 个脚本，串行/并行都需要隔离。
 *
 * 线程模型：所有 JS 调用都在单线程 `js` 上执行；HTTP 回调再切回该线程，
 * 避免 QuickJS 的跨线程访问问题。
 */
class LxScriptEngine(
    val fileName: String,
    private val code: String,
    private val http: OkHttpClient,
    private val onLog: (String) -> Unit = {},
) : Closeable {

    data class Meta(val name: String, val version: String, val author: String, val description: String)

    private val js = Executors.newSingleThreadExecutor { r -> Thread(r, "lx-js-$fileName") }
    private val net = Executors.newFixedThreadPool(4) { r -> Thread(r, "lx-http-$fileName") }
    private val timers = java.util.concurrent.ScheduledThreadPoolExecutor(2).apply {
        removeOnCancelPolicy = true
    }
    private val results = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val seq = AtomicInteger(0)
    private val initSignal = CompletableDeferred<Unit>()

    private var runtime: QuickJS? = null
    private var ctx: JSContext? = null

    @Volatile var meta: Meta = parseMeta(code)
        private set
    @Volatile var sources: Map<String, List<String>> = emptyMap()
        private set
    @Volatile var loaded = false
        private set

    val displayName: String get() = meta.name.ifBlank { fileName }

    // ── 生命周期 ────────────────────────────────────────────────────────────

    suspend fun start(timeoutMs: Long = 8000): Boolean {
        onJs {
            val rt = QuickJS.createRuntime()
            runtime = rt
            val c = rt.createContext()
            ctx = c
            runCatching { c.addPlugin(ConsolePlugin()) }
            registerBridge(c)
            c.executeVoidScript(BOOTSTRAP, "lx-bootstrap.js")
            c.executeVoidScript(code, fileName)
        }
        val ok = withTimeoutOrNull(timeoutMs) { initSignal.await() } != null
        loaded = ok
        if (!ok) onLog("[${displayName}] 初始化超时（未发送 inited）")
        return ok
    }

    override fun close() {
        js.execute {
            runCatching { ctx?.close() }
            runCatching { runtime?.close() }
            ctx = null
            runtime = null
        }
        js.shutdown()
        net.shutdown()
        timers.shutdownNow()
    }

    // ── 对外：取直链 ────────────────────────────────────────────────────────

    suspend fun musicUrl(source: String, quality: String, musicInfoJson: String, timeoutMs: Long = 9000): String {
        val token = "t" + seq.incrementAndGet()
        val deferred = CompletableDeferred<String>()
        results[token] = deferred
        try {
            onJs {
                val c = ctx ?: error("engine not started")
                val call = "__lxCallMusicUrl(" +
                    JSONObject.quote(token) + "," +
                    JSONObject.quote(source) + "," +
                    JSONObject.quote(quality) + "," +
                    JSONObject.quote(musicInfoJson) + ")"
                c.executeVoidScript(call, "call.js")
            }
            return withTimeoutOrNull(timeoutMs) { deferred.await() }
                ?: error("解析超时 ${timeoutMs}ms")
        } finally {
            results.remove(token)
        }
    }

    // ── 桥接注册 ────────────────────────────────────────────────────────────

    private fun registerBridge(c: JSContext) {
        // lx.request(id, url, optionsJson)
        c.registerJavaMethod(JavaCallback { _, args: JSArray ->
            val id = args.getString(0)
            val url = args.getString(1)
            val opts = args.getString(2) ?: "{}"
            handleRequest(id, url, opts)
            null
        }, "__lxRequest")

        // 脚本 inited
        c.registerJavaMethod(JavaVoidCallback { _, args: JSArray ->
            runCatching { applyInited(args.getString(0) ?: "") }
        }, "__lxInited")

        // musicUrl 结果回传
        c.registerJavaMethod(JavaVoidCallback { _, args: JSArray ->
            val token = args.getString(0) ?: return@JavaVoidCallback
            val payload = args.getString(1) ?: ""
            results.remove(token)?.complete(payload)
        }, "__lxBridgeResult")

        c.registerJavaMethod(JavaVoidCallback { _, args: JSArray ->
            onLog("[${displayName}] ${args.getString(0) ?: ""}")
        }, "__lxLog")

        c.registerJavaMethod(JavaCallback { _, args: JSArray ->
            md5(args.getString(0) ?: "")
        }, "__lxMd5")

        c.registerJavaMethod(JavaCallback { _, args: JSArray ->
            aes(args.getString(0) ?: "", args.getString(1) ?: "cbc", args.getString(2) ?: "", args.getString(3))
        }, "__lxAesEncrypt")

        // setTimeout 支持（脚本会用定时器做超时/重试）
        c.registerJavaMethod(JavaVoidCallback { _, args: JSArray ->
            scheduleTimer(args.getString(0) ?: "", args.getInteger(1).toLong())
        }, "__lxTimer")
    }

    private fun scheduleTimer(id: String, ms: Long) {
        if (id.isEmpty()) return
        runCatching {
            timers.schedule({
                js.execute {
                    runCatching {
                        ctx?.executeVoidScript("__lxTimerFire(${JSONObject.quote(id)})", "timer.js")
                    }
                }
            }, ms.coerceIn(0L, 30000L), java.util.concurrent.TimeUnit.MILLISECONDS)
        }
    }

    private fun applyInited(json: String) {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return
        val srcs = root.optJSONObject("sources") ?: return
        val map = mutableMapOf<String, List<String>>()
        srcs.keys().forEach { key ->
            val o = srcs.optJSONObject(key) ?: return@forEach
            val qs = o.optJSONArray("qualitys") ?: JSONArray()
            map[key] = (0 until qs.length()).map { qs.optString(it) }
        }
        sources = map
        root.optJSONObject("meta")?.let {
            meta = Meta(
                name = it.optString("name").ifBlank { meta.name },
                version = it.optString("version").ifBlank { meta.version },
                author = it.optString("author").ifBlank { meta.author },
                description = it.optString("desc").ifBlank { meta.description },
            )
        }
        if (!initSignal.isCompleted) initSignal.complete(Unit)
    }

    // ── HTTP（lx.request 的实现） ────────────────────────────────────────────

    private fun handleRequest(id: String, url: String, optsJson: String) {
        net.execute {
            val payload = try {
                val opts = runCatching { JSONObject(optsJson) }.getOrDefault(JSONObject())
                val method = (opts.optString("method", "GET")).uppercase()
                val timeout = opts.optLong("timeout", 12000L).coerceIn(2000L, 20000L)
                val builder = Request.Builder().url(url).header("User-Agent", PlatformSearch.UA)

                opts.optJSONObject("headers")?.let { h ->
                    h.keys().forEach { k -> runCatching { builder.header(k, h.optString(k)) } }
                }
                var bodyStr: String? = null
                var contentType: okhttp3.MediaType? = null
                when {
                    opts.has("form") -> {
                        val form = opts.optJSONObject("form")
                        val sb = StringBuilder()
                        form?.keys()?.forEach { k ->
                            if (sb.isNotEmpty()) sb.append('&')
                            sb.append(java.net.URLEncoder.encode(k, "UTF-8"))
                                .append('=').append(java.net.URLEncoder.encode(form.optString(k), "UTF-8"))
                        }
                        bodyStr = sb.toString()
                        contentType = "application/x-www-form-urlencoded".toMediaType()
                    }
                    opts.has("body") -> {
                        bodyStr = opts.optString("body")
                        contentType = (opts.optJSONObject("headers")?.optString("Content-Type")
                            ?.takeIf { it.isNotBlank() } ?: "application/json").toMediaType()
                    }
                }
                builder.method(method, bodyStr?.toRequestBody(contentType))

                val client = http.newBuilder()
                    .callTimeout(timeout, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .build()
                client.newCall(builder.build()).execute().use { resp ->
                    val body = resp.body?.string() ?: ""
                    JSONObject()
                        .put("statusCode", resp.code)
                        .put("headers", JSONObject(resp.headers.toMap()))
                        .put("body", body)
                        .put("bytes", body.toByteArray().size)
                        .toString()
                }
            } catch (e: Exception) {
                JSONObject()
                    .put("error", (e.message ?: e.javaClass.simpleName))
                    .toString()
            }

            js.execute {
                runCatching {
                    ctx?.executeVoidScript("__lxResponse(${JSONObject.quote(id)}, ${JSONObject.quote(payload)})", "resp.js")
                }.onFailure { Log.w("LxEngine", "dispatch failed", it) }
            }
        }
    }

    // ── 工具 ────────────────────────────────────────────────────────────────

    private suspend fun <T> onJs(block: () -> T): T = suspendCancellableCoroutine { cont ->
        js.execute {
            try {
                cont.resume(block())
            } catch (e: Throwable) {
                cont.resumeWithException(e)
            }
        }
    }

    private fun md5(input: String): String =
        MessageDigest.getInstance("MD5").digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /** data/base64 + mode(cbc|ecb|ctr) + key + iv，返回 base64。 */
    private fun aes(dataB64: String, mode: String, key: String, iv: String?): String {
        val keyBytes = key.toByteArray()
        val algo = "AES/" + mode.uppercase() + "/" + if (mode.equals("ecb", true)) "NoPadding" else "PKCS5Padding"
        val cipher = Cipher.getInstance(algo)
        val sk = SecretKeySpec(keyBytes.copyOf(if (keyBytes.size >= 32) 32 else if (keyBytes.size >= 24) 24 else 16), "AES")
        cipher.init(
            Cipher.ENCRYPT_MODE, sk,
            if (mode.equals("ecb", true)) null else IvParameterSpec((iv ?: "").toByteArray().copyOf(16))
        )
        val out = cipher.doFinal(android.util.Base64.decode(dataB64, android.util.Base64.DEFAULT))
        return android.util.Base64.encodeToString(out, android.util.Base64.NO_WRAP)
    }

    companion object {
        private fun parseMeta(code: String): Meta {
            val head = code.take(4000)
            fun g(k: String) = Regex("@" + k + "\\s+(.+)").find(head)?.groupValues?.get(1)?.trim().orEmpty()
            return Meta(g("name"), g("version"), g("author"), g("description"))
        }

        private val BOOTSTRAP = """
(function () {
  var B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  var pending = {};
  var seq = 0;

  function utf8Bytes(str) {
    var enc = encodeURIComponent(str), out = [];
    for (var i = 0; i < enc.length; i++) {
      if (enc.charAt(i) === '%') { out.push(parseInt(enc.substr(i + 1, 2), 16)); i += 2; }
      else out.push(enc.charCodeAt(i));
    }
    return new Uint8Array(out);
  }
  function b64encode(bytes) {
    var s = '', i, b0, b1, b2, t;
    for (i = 0; i < bytes.length; i += 3) {
      b0 = bytes[i]; b1 = bytes[i + 1]; b2 = bytes[i + 2];
      t = (b0 << 16) | ((b1 || 0) << 8) | (b2 || 0);
      s += B64.charAt((t >> 18) & 63) + B64.charAt((t >> 12) & 63);
      s += (i + 1 < bytes.length) ? B64.charAt((t >> 6) & 63) : '=';
      s += (i + 2 < bytes.length) ? B64.charAt(t & 63) : '=';
    }
    return s;
  }
  function b64decode(str) {
    var s = str.replace(/[^A-Za-z0-9+/=]/g, ''), out = [], i, n;
    for (i = 0; i < s.length; i += 4) {
      n = (B64.indexOf(s.charAt(i)) << 18) | (B64.indexOf(s.charAt(i + 1)) << 12);
      if (s.charAt(i + 2) !== '=') n |= B64.indexOf(s.charAt(i + 2)) << 6;
      if (s.charAt(i + 3) !== '=') n |= B64.indexOf(s.charAt(i + 3));
      out.push((n >> 16) & 255);
      if (s.charAt(i + 2) !== '=') out.push((n >> 8) & 255);
      if (s.charAt(i + 3) !== '=') out.push(n & 255);
    }
    return new Uint8Array(out);
  }
  function hexOf(bytes) {
    var s = '';
    for (var i = 0; i < bytes.length; i++) s += ('0' + (bytes[i] & 255).toString(16)).slice(-2);
    return s;
  }

  var timers = {};
  var tseq = 0;
  globalThis.setTimeout = function (fn, ms) {
    var id = 'tm' + (++tseq);
    timers[id] = fn;
    try { globalThis.__lxTimer(id, ms | 0); } catch (e) { delete timers[id]; }
    return id;
  };
  globalThis.clearTimeout = function (id) { delete timers[id]; };
  globalThis.__lxTimerFire = function (id) {
    var fn = timers[id];
    delete timers[id];
    if (fn) { try { fn(); } catch (e) { } }
  };
  globalThis.btoa = function (s) {
    var b = [];
    for (var i = 0; i < s.length; i++) b.push(s.charCodeAt(i) & 255);
    return b64encode(new Uint8Array(b));
  };
  globalThis.atob = function (s) {
    var b = b64decode(s), out = '';
    for (var i = 0; i < b.length; i++) out += String.fromCharCode(b[i]);
    return out;
  };

  globalThis.__lxResponse = function (id, payloadJson) {
    var cb = pending[id];
    if (!cb) return;
    delete pending[id];
    try {
      var r = JSON.parse(payloadJson);
      if (r.error) { cb(new Error(r.error), null); return; }
      cb(null, { statusCode: r.statusCode, headers: r.headers || {}, body: r.body, bytes: r.bytes });
    } catch (e) { cb(e, null); }
  };

  var lx = {
    version: '2.0.0',
    env: 'desktop',
    currentScriptInfo: { name: '', description: '', version: '', author: '', homepage: '', rawScript: '' },
    EVENT_NAMES: { inited: 'inited', request: 'request', updateAlert: 'updateAlert' },
    request: function (url, options, callback) {
      var id = 'r' + (++seq);
      pending[id] = callback || function () {};
      try { globalThis.__lxRequest(id, url, JSON.stringify(options || {})); }
      catch (e) { delete pending[id]; if (callback) callback(e, null); }
      return function () {};
    },
    on: function (name, handler) {
      if (name === 'request') globalThis.__lxRequestHandler = handler;
    },
    send: function (name, data) {
      if (name === 'inited') globalThis.__lxInited(JSON.stringify(data || {}));
    },
    utils: {
      buffer: {
        from: function (data, enc) {
          if (typeof data === 'string') return (enc === 'base64') ? b64decode(data) : utf8Bytes(data);
          return new Uint8Array(data);
        },
        bufToString: function (buf, enc) {
          var b = (buf && buf.buffer) ? new Uint8Array(buf) : new Uint8Array(buf || []);
          if (enc === 'hex') return hexOf(b);
          if (enc === 'base64') return b64encode(b);
          var out = '';
          for (var i = 0; i < b.length; i++) out += String.fromCharCode(b[i]);
          try { return decodeURIComponent(escape(out)); } catch (e) { return out; }
        }
      },
      crypto: {
        md5: function (str) { return globalThis.__lxMd5(String(str)); },
        aesEncrypt: function (data, mode, key, iv) {
          var b = (data && data.buffer) ? new Uint8Array(data) : (typeof data === 'string' ? utf8Bytes(data) : new Uint8Array(data || []));
          var b64 = globalThis.__lxAesEncrypt(b64encode(b), String(mode || 'cbc'), String(key || ''), iv ? String(iv) : '');
          return b64decode(b64);
        }
      }
    }
  };
  globalThis.lx = lx;

  globalThis.__lxCallMusicUrl = function (token, source, quality, infoJson) {
    var handler = globalThis.__lxRequestHandler;
    if (!handler) { globalThis.__lxBridgeResult(token, JSON.stringify({ ok: false, error: 'no handler' })); return; }
    try {
      Promise.resolve(handler({ source: source, action: 'musicUrl', info: { type: quality, musicInfo: JSON.parse(infoJson) } }))
        .then(function (url) { globalThis.__lxBridgeResult(token, JSON.stringify({ ok: true, url: String(url) })); })
        .catch(function (e) { globalThis.__lxBridgeResult(token, JSON.stringify({ ok: false, error: String((e && e.message) || e) })); });
    } catch (e) {
      globalThis.__lxBridgeResult(token, JSON.stringify({ ok: false, error: String((e && e.message) || e) }));
    }
  };
})();
        """.trimIndent()
    }
}
