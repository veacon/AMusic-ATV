package com.applemusicktv.luoxue

import android.util.Log
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.AsyncFunctionBinding
import com.dokar.quickjs.binding.FunctionBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.security.MessageDigest
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 一个落雪脚本 = 一个 QuickJs 实例（隔离 runtime/context）。
 *
 * 为什么必须隔离：脚本通过 `lx.on('request', ...)` 注册全局 handler，
 * 多脚本共用会互相覆盖。
 *
 * 为什么用 quickjs-kt 而不是 taoweiji/quickjs-android：
 * 后者只做 JS_Eval，不跑 Promise 微任务——脚本里 `await lx.request(...)` 的续体
 * 永远不会恢复。实机日志可复现：HTTP 已 200 回包、`__lxResponse` 已执行，
 * 但 bridge 仍一路超时。quickjs-kt 在求值期间跑任务泵，并把 suspend 绑定
 * 桥接成 JS Promise，`await` 才能正常返回。
 *
 * 线程模型：每个实例有专属单线程 dispatcher；所有 evaluate 与绑定回调都在该线程上，
 * 网络/IO 通过 `Dispatchers.IO` 切出去，避免阻塞任务泵。
 */
class LxScriptEngine(
    val fileName: String,
    private val code: String,
    private val http: OkHttpClient,
    private val onLog: (String) -> Unit = {},
) : Closeable {

    data class Meta(val name: String, val version: String, val author: String, val description: String)

    private val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "lx-js-$fileName") }
        .asCoroutineDispatcher()
    private val evalLock = Mutex()

    @Volatile private var js: QuickJs? = null

    @Volatile var meta: Meta = parseMeta(code)
        private set
    @Volatile var sources: Map<String, List<String>> = emptyMap()
        private set
    @Volatile var loaded = false
        private set

    val displayName: String get() = meta.name.ifBlank { fileName }

    // ── 生命周期 ────────────────────────────────────────────────────────────

    @Suppress("UNUSED_PARAMETER")
    suspend fun start(timeoutMs: Long = 12_000): Boolean = withContext(dispatcher) {
        try {
            val runtime = QuickJs.create(dispatcher)
            // 启动阶段禁用定时器：不少脚本 init 时用 setTimeout 起一个永不结束的轮询，
            // 求值器会一直等到超时中断（实测：poll 循环直接把 start 拖死）。
            runtime.evaluationTimeoutMillis = 8_000L
            js = runtime
            defineBindings(runtime)
            runtime.evaluate<Any?>(BOOTSTRAP, "lx-bootstrap.js")
            runtime.evaluate<Any?>(code, fileName)
            // 脚本加载完再放开定时器（musicUrl 里的重试/超时保护需要它）。
            runtime.evaluate<Any?>("__lxEnableTimers()", "enable-timers.js")
            loaded = sources.isNotEmpty()
            if (!loaded) onLog("[${displayName}] 脚本未上报 inited（sources 为空）")
            runtime.evaluationTimeoutMillis = 30_000L
            loaded
        } catch (e: Throwable) {
            // 中断/超时不等于脚本不可用：inited 可能已经上报过了。
            if (sources.isNotEmpty()) {
                Log.w("LxEngine", "[$fileName] init interrupted but inited already sent: ${e.message}")
                runCatching { js?.evaluationTimeoutMillis = 20_000L }
                loaded = true
                return@withContext true
            }
            Log.w("LxEngine", "[$fileName] start failed", e)
            onLog("[${displayName}] 初始化失败：${e.message}")
            false
        }
    }

    override fun close() {
        runCatching { js?.close() }
        js = null
        runCatching { dispatcher.close() }
    }

    // ── 对外：取直链 ────────────────────────────────────────────────────────

    /**
     * 调用脚本的 musicUrl，返回**裸直链**。
     * quickjs-kt 的 evaluate 会自动 await 返回的 Promise，脚本抛错则这里抛异常。
     */
    suspend fun musicUrl(source: String, quality: String, musicInfoJson: String, timeoutMs: Long = 16_000): String {
        val runtime = js ?: error("engine not started")
        // quickjs-kt 的 evaluate 会把 Promise **对象本身**返回（实测 toString 成
        // `Promise { <state>: "fulfilled" }`），并不会替我们取 resolve 值。
        // 所以这里"先跑、把结果落到全局、再读出来"：evaluate 会一直等到所有异步绑定
        // （__lxHttp / __lxSleep）跑完，天然就是一次完整的 Promise 泵。
        val run = "__lxOk=null;__lxErr=null;" +
            "__lxMusicUrl(" +
            JSONObject.quote(source) + "," +
            JSONObject.quote(quality) + "," +
            JSONObject.quote(musicInfoJson) +
            ").then(function(v){__lxOk=v},function(e){__lxErr=(e&&e.message)||String(e)});0"
        val done = withTimeoutOrNull(timeoutMs) {
            evalLock.withLock {
                withContext(dispatcher) {
                    runtime.evaluate<Any?>(run, "call.js")
                    val err = runtime.evaluate<String?>("__lxErr==null?null:String(__lxErr)", "err.js")
                    val ok = if (err == null) {
                        runtime.evaluate<String?>("__lxOk==null?null:String(__lxOk)", "ok.js")
                    } else null
                    err to ok
                }
            }
        }
        if (done == null) {
            // 只取消协程不会停掉 JS：脚本还在串行跑它的 20+ 个后端，
            // 后续每一档都会排在它后面继续超时（实测一条链路被拖到 30s+）。
            runCatching { runtime.interruptEvaluation() }
            error("解析超时 ${timeoutMs}ms")
        }
        val (err, ok) = done
        if (!err.isNullOrBlank()) error(err)
        if (ok.isNullOrBlank()) error("脚本返回空直链")
        return ok.trim()
    }

    // ── 绑定 ────────────────────────────────────────────────────────────────

    private fun defineBindings(runtime: QuickJs) {
        // HTTP：suspend 绑定 → JS 侧是 Promise，脚本 await 才能真正恢复
        runtime.defineBinding("__lxHttp", object : AsyncFunctionBinding<String> {
            override suspend fun invoke(args: Array<Any?>): String {
                val url = args.getOrNull(0)?.toString().orEmpty()
                val opts = args.getOrNull(1)?.toString() ?: "{}"
                return request(url, opts)
            }
        })

        runtime.defineBinding("__lxInited", object : FunctionBinding<Unit> {
            override fun invoke(args: Array<Any?>) {
                runCatching { applyInited(args.getOrNull(0)?.toString() ?: "") }
            }
        })

        runtime.defineBinding("__lxLog", object : FunctionBinding<Unit> {
            override fun invoke(args: Array<Any?>) {
                onLog("[${displayName}] ${args.getOrNull(0)?.toString() ?: ""}")
            }
        })

        runtime.defineBinding("__lxMd5", object : FunctionBinding<String> {
            override fun invoke(args: Array<Any?>): String = md5(args.getOrNull(0)?.toString() ?: "")
        })

        runtime.defineBinding("__lxAesEncrypt", object : FunctionBinding<String> {
            override fun invoke(args: Array<Any?>): String = aes(
                args.getOrNull(0)?.toString() ?: "",
                args.getOrNull(1)?.toString() ?: "cbc",
                args.getOrNull(2)?.toString() ?: "",
                args.getOrNull(3)?.toString(),
            )
        })

        // setTimeout 的实现：JS 侧 setTimeout(fn, ms) → __lxSleep(ms).then(fn)
        runtime.defineBinding("__lxSleep", object : AsyncFunctionBinding<Unit> {
            override suspend fun invoke(args: Array<Any?>) {
                val ms = (args.getOrNull(0) as? Number)?.toLong() ?: 0L
                delay(ms.coerceIn(0L, 30_000L))
            }
        })
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
    }

    // ── HTTP（lx.request 的实现） ────────────────────────────────────────────

    private suspend fun request(url: String, optsJson: String): String = withContext(Dispatchers.IO) {
        Log.i("LxEngine", "[$fileName] REQ ${url.take(120)}")
        try {
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
                Log.i("LxEngine", "[$fileName] RESP code=${resp.code} bytes=${body.length}")
                JSONObject()
                    .put("statusCode", resp.code)
                    .put("headers", JSONObject(resp.headers.toMap()))
                    .put("body", body)
                    .put("bytes", body.toByteArray().size)
                    .toString()
            }
        } catch (e: Exception) {
            Log.w("LxEngine", "[$fileName] HTTP failed: ${e.message}")
            JSONObject()
                .put("error", (e.message ?: e.javaClass.simpleName))
                .toString()
        }
    }

    // ── 工具 ────────────────────────────────────────────────────────────────

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

        /**
         * lx 协议 shim（ES2020，QuickJS 全支持）。
         * 与旧实现的关键差别：request 返回 Promise、setTimeout 用 __lxSleep 的 Promise 链实现，
         * 这样等待期间任务泵才有活干。
         */
        private val BOOTSTRAP = """
(function () {
  var B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  var tseq = 0;

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

  var timersEnabled = false;
  globalThis.__lxEnableTimers = function () { timersEnabled = true; };

  globalThis.setTimeout = function (fn, ms) {
    var id = 'tm' + (++tseq);
    // 启动阶段不排定时器：避免脚本的常驻轮询把初始化求值拖到超时。
    if (!timersEnabled) return id;
    try {
      globalThis.__lxSleep(ms | 0).then(function () {
        try { fn(); } catch (e) {}
      }, function () {});
    } catch (e) {}
    return id;
  };
  globalThis.clearTimeout = function () {};

  globalThis.console = {
    log: function () { try { globalThis.__lxLog(Array.prototype.join.call(arguments, ' ')); } catch (e) {} },
    info: function () { try { globalThis.__lxLog(Array.prototype.join.call(arguments, ' ')); } catch (e) {} },
    warn: function () { try { globalThis.__lxLog(Array.prototype.join.call(arguments, ' ')); } catch (e) {} },
    error: function () { try { globalThis.__lxLog(Array.prototype.join.call(arguments, ' ')); } catch (e) {} },
    debug: function () {}
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

  function toResponse(payload) {
    var r = JSON.parse(payload);
    if (r.error) throw new Error(String(r.error));
    return { statusCode: r.statusCode, headers: r.headers || {}, body: r.body, bytes: r.bytes };
  }

  var lx = {
    version: '2.0.0',
    env: 'desktop',
    currentScriptInfo: { name: '', description: '', version: '', author: '', homepage: '', rawScript: '' },
    EVENT_NAMES: { inited: 'inited', request: 'request', updateAlert: 'updateAlert' },
    request: function (url, options, callback) {
      var p = globalThis.__lxHttp(url, JSON.stringify(options || {})).then(toResponse);
      if (typeof callback === 'function') {
        p.then(function (r) { callback(null, r); }, function (e) { callback(e, null); });
        return function () {};
      }
      return p;
    },
    on: function (name, handler) {
      if (name === 'request') globalThis.__lxRequestHandler = handler;
    },
    send: function (name, data) {
      if (name === 'inited') {
        try { globalThis.__lxInited(JSON.stringify(data || {})); } catch (e) {}
      }
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

  // 返回值是 Promise：quickjs-kt 的 evaluate 会 await 它，reject 则直接抛到 Kotlin。
  globalThis.__lxMusicUrl = function (source, quality, infoJson) {
    var handler = globalThis.__lxRequestHandler;
    if (!handler) throw new Error('no handler');
    return Promise.resolve(handler({
      source: source,
      action: 'musicUrl',
      info: { type: quality, musicInfo: JSON.parse(infoJson) }
    })).then(function (u) {
      if (!u) throw new Error('empty url');
      return String(u);
    });
  };
})();
        """.trimIndent()
    }
}
