package com.applemusicktv.media

import android.content.Context
import com.applemusicktv.data.CrossfadePreferences
import com.applemusicktv.data.ServerPreferences
import com.applemusicktv.data.StandalonePreferences
import com.applemusicktv.data.LyricsOffsetPreferences
import com.applemusicktv.data.MutPreferences
import com.applemusicktv.data.NetworkLog
import com.applemusicktv.data.repository.MusicRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InAppWebServer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: MutPreferences,
    private val repo: MusicRepository,
    private val lyricsOffsetPrefs: LyricsOffsetPreferences,
    private val crossfadePrefs: CrossfadePreferences,
    private val standalonePrefs: StandalonePreferences,
    private val serverPrefs: ServerPreferences,
    private val beatAnalyzer: BeatAnalyzer,
    private val tokenVerifier: com.applemusicktv.data.auth.TokenVerifier,
) {
    private val logs = ArrayDeque<String>(300)
    private val sseClients = java.util.concurrent.CopyOnWriteArrayList<java.io.OutputStream>()
    // Port 8001: live event stream for curl
    private val eventClients = java.util.concurrent.CopyOnWriteArrayList<java.io.OutputStream>()
    private var job: Job? = null
    private var eventJob: Job? = null
    private var appScope: CoroutineScope? = null
    private var serverSocket: ServerSocket? = null
    private var eventSocket: ServerSocket? = null
    private val _endpoint = MutableStateFlow(PhoneServerEndpoint())
    internal val endpoint = _endpoint.asStateFlow()
    private val networkAddress = PhoneNetworkAddress(context) { lan ->
        _endpoint.update { it.copy(address = lan.host, networkConnected = lan.connected) }
    }
    internal fun setupHintId(): Int = phoneSetupHintId(endpoint.value) ?: com.applemusicktv.R.string.phone_network_waiting
    val port: Int get() = endpoint.value.port
    val eventPort = 8081

    private val wsPrefs = context.getSharedPreferences("webserver_prefs", Context.MODE_PRIVATE)
    /** User toggle (Dev → Web Server). Default ON. */
    val isEnabled: Boolean get() = wsPrefs.getBoolean("web_enabled", true)
    val running: Boolean get() = endpoint.value.phase == PhoneServerPhase.READY

    /** Called once at app start: remember the scope and start only if enabled. */
    fun boot(scope: CoroutineScope) {
        appScope = scope
        if (isEnabled) start(scope)
    }

    /** Toggle from Settings — starts or stops the phone web server and persists the choice. */
    fun setEnabled(on: Boolean) {
        wsPrefs.edit().putBoolean("web_enabled", on).apply()
        if (on) appScope?.let { start(it) } else stop()
    }

    @Synchronized fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        appScope = scope
        _endpoint.update { it.copy(phase = PhoneServerPhase.STARTING, port = 0) }
        networkAddress.start()
        NetworkLog.listener = { line -> broadcastLive(line) }
        job = scope.launch(Dispatchers.IO) {
            var listener: ServerSocket? = null
            try {
                val bound = PhoneServerSocket.bind()
                listener = bound
                synchronized(this@InAppWebServer) {
                    if (!isActive) return@launch
                    serverSocket = bound
                    _endpoint.update { it.copy(phase = PhoneServerPhase.READY, port = bound.localPort) }
                }
                if (bound.localPort != 8080) addLog("WARN", "Port 8080 occupied; phone configuration uses ${bound.localPort}")
                addLog("OK", "Web server listening on port ${bound.localPort}; open ${serverUrl()} on your phone")
                while (isActive) {
                    val client = bound.accept()
                    launch { handle(client) }
                }
            } catch (e: Exception) {
                synchronized(this@InAppWebServer) {
                    if (isActive) {
                        _endpoint.update { it.copy(phase = PhoneServerPhase.FAILED, port = 0) }
                        addLog("ERROR", "Phone web server failed: ${e.javaClass.simpleName}: ${e.message}")
                    }
                }
            } finally {
                runCatching { listener?.close() }
                synchronized(this@InAppWebServer) {
                    if (serverSocket === listener) {
                        serverSocket = null
                        if (_endpoint.value.phase == PhoneServerPhase.READY)
                            _endpoint.update { it.copy(phase = PhoneServerPhase.FAILED, port = 0) }
                    }
                }
            }
        }
        // Diagnostic streaming is optional; its failure never disables the phone page.
        eventJob?.cancel()
        runCatching { eventSocket?.close() }
        eventJob = scope.launch(Dispatchers.IO) {
            var listener: ServerSocket? = null
            try {
                val bound = ServerSocket(eventPort)
                listener = bound
                synchronized(this@InAppWebServer) {
                    if (!isActive) return@launch
                    eventSocket = bound
                }
                addLog("OK", "Event stream listening on port $eventPort")
                while (isActive) {
                    val client = bound.accept()
                    launch { handleEvent(client) }
                }
            } catch (e: Exception) {
                if (isActive) addLog("WARN", "Event stream unavailable on $eventPort: ${e.javaClass.simpleName}")
            } finally {
                runCatching { listener?.close() }
                synchronized(this@InAppWebServer) {
                    if (eventSocket === listener) eventSocket = null
                }
            }
        }
    }

    // Invalidate the advertised endpoint before releasing the listener.
    @Synchronized fun stop() {
        networkAddress.stop()
        _endpoint.value = PhoneServerEndpoint()
        job?.cancel(); eventJob?.cancel()
        runCatching { serverSocket?.close() }; serverSocket = null
        runCatching { eventSocket?.close() }; eventSocket = null
        addLog("OK", "Web server stopped")
    }

    fun addLog(level: String, msg: String) {
        val t = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "[$t][$level] $msg"
        synchronized(logs) {
            if (logs.size >= 300) logs.removeFirst()
            logs.addLast(line)
        }
        broadcastLive(line)
        // Debug mirror to the PC proxy console. SKIP when the proxy isn't reachable —
        // otherwise every log line blocks an IO thread up to connectTimeout (1s) trying
        // to reach a dead host. In standalone-primary mode the app logs constantly during
        // playback, which saturated the IO dispatcher and starved Coil image decode →
        // stuttery menu scroll. The :8081 SSE stream still carries logs live.
        if (!serverPrefs.serverReachable) return
        appScope?.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val body = """{"level":"$level","msg":${org.json.JSONObject.quote(msg)}}"""
                val conn = java.net.URL("${repo.serverBaseUrl()}/api/log")
                    .openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 1000
                conn.readTimeout = 1000
                conn.outputStream.write(body.toByteArray())
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {}
        }
    }

    /** Push one line to every live listener — SSE (web page) and the raw :8081 event stream. */
    private fun broadcastLive(line: String) {
        val sseData = "data: ${line.replace("\n", " ")}\n\n".toByteArray()
        val deadSse = mutableListOf<java.io.OutputStream>()
        for (out in sseClients) { try { out.write(sseData); out.flush() } catch (_: Exception) { deadSse.add(out) } }
        sseClients.removeAll(deadSse)
        val eventLine = "${line.replace("\n", " ")}\n".toByteArray()
        appScope?.launch(kotlinx.coroutines.Dispatchers.IO) {
            val dead = mutableListOf<java.io.OutputStream>()
            for (out in eventClients) { try { out.write(eventLine); out.flush() } catch (_: Exception) { dead.add(out) } }
            eventClients.removeAll(dead)
        }
    }

    fun getLogs(): List<String> = synchronized(logs) { logs.toList() }

    fun serverUrl(): String {
        val current = endpoint.value
        if (current.phase != PhoneServerPhase.READY || current.address.isEmpty()) return ""
        return "http://${current.address}:${current.port}"
    }

    private fun handle(socket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val out = BufferedOutputStream(socket.getOutputStream())
            val reqLine = reader.readLine() ?: return
            val parts = reqLine.split(" ")
            val method = parts.getOrNull(0) ?: "GET"
            val target = parts.getOrNull(1) ?: "/"
            val path = target.substringBefore('?')
            val hdrs = mutableMapOf<String, String>()
            var line = reader.readLine()
            while (!line.isNullOrBlank()) {
                val idx = line.indexOf(": ")
                if (idx > 0) hdrs[line.substring(0, idx).lowercase()] = line.substring(idx + 2)
                line = reader.readLine()
            }
            var body = ""
            if (method == "POST") {
                val len = hdrs["content-length"]?.toIntOrNull() ?: 0
                val buf = CharArray(len); reader.read(buf, 0, len); body = String(buf)
            }
            val query = android.net.Uri.parse("http://localhost$target")
            val browserLanguage = com.applemusicktv.i18n.PhonePage.resolveLanguage(
                query.getQueryParameter("lang"), hdrs["cookie"], hdrs["accept-language"])
            if (path == "/" && query.getQueryParameter("lang") in listOf("zh", "en")) {
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                    "Set-Cookie: amtv_lang=$browserLanguage; Path=/; SameSite=Lax\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
                out.write(html(language = browserLanguage).toByteArray(Charsets.UTF_8)); out.flush()
                return
            }
            when {
                method == "GET"  && path == "/"            -> send(out, 200, "text/html", html(tokenSaved = query.getQueryParameter("token") == "saved" && prefs.hasMUT(), tokenError = query.getQueryParameter("token") == "invalid", tokenUnavailable = query.getQueryParameter("token") == "unavailable", language = browserLanguage))
                method == "GET"  && path == "/status"      -> send(out, 200, "application/json", status())
                method == "GET"  && path == "/logs"        -> send(out, 200, "application/json", logsJson())
                method == "GET"  && path == "/stream"      -> { handleSse(socket, out); return }

                method == "GET"  && path == "/netlogs"     -> send(out, 200, "application/json", netLogsJson())
                method == "GET"  && path == "/report"      -> send(out, 200, "text/plain", bugReport(), "Content-Disposition: attachment; filename=\"amtv-bugreport.txt\"\r\n")
                method == "POST" && path == "/clear-crash"        -> { com.applemusicktv.util.CrashReporter.clear(context); addLog("OK", "Crash log cleared"); redirect(out, "/") }
                method == "POST" && path == "/set-token"          -> { val checked = applyToken(parseField(body, "mut").trim()); redirect(out, when (checked) {
                    com.applemusicktv.data.auth.TokenVerification.Valid -> "/?token=saved"
                    com.applemusicktv.data.auth.TokenVerification.Invalid -> "/?token=invalid"
                    else -> "/?token=unavailable" }) }
                method == "POST" && path == "/clear-token"         -> { prefs.setMUT(""); addLog("WARN","Token cleared"); redirect(out, "/") }
                method == "POST" && path == "/set-lyrics-offset"   -> { applyLyricsOffset(parseField(body, "offset")); redirect(out, "/") }
                method == "POST" && path == "/set-beat-latency"    -> { applyBeatLatency(parseField(body, "latency")); redirect(out, "/") }
                method == "POST" && path == "/set-crossfade"       -> { applyCrossfade(parseField(body, "crossfade")); redirect(out, "/") }
            method == "POST" && path == "/set-standalone"      -> { applyStandalone(parseField(body, "standalone")); redirect(out, "/") }
            method == "POST" && path == "/set-server-ip"       -> { applyServerIp(parseField(body, "serverip")); redirect(out, "/") }
                method == "POST" && path == "/set-volume-leveling" -> { applyVolumeLeveling(parseField(body, "vol")); redirect(out, "/") }
                method == "POST" && path == "/set-background-play" -> { applyBackgroundPlay(parseField(body, "bg")); redirect(out, "/") }
                else -> send(out, 404, "text/plain", "Not found")
            }
            out.flush(); socket.close()
        } catch (_: Exception) {}
    }

    private fun applyBeatLatency(raw: String) {
        val ms = raw.trim().toLongOrNull()
        if (ms == null || ms < 0) { addLog("ERROR", "Invalid beat latency: $raw"); return }
        beatAnalyzer.latencyMs = ms
        beatAnalyzer.resetBeat()
        addLog("OK", "Beat latency set to ${ms}ms — buffer reset")
    }

    /** Player settings live in the "player_state" prefs the ViewModel owns. */
    private fun playerPrefs() = context.getSharedPreferences("player_state", Context.MODE_PRIVATE)

    private fun applyVolumeLeveling(raw: String) {
        val on = raw.trim() == "1"
        GainProcessor.enabled = on   // takes effect on the audio thread immediately
        playerPrefs().edit().putBoolean("volume_leveling", on).apply()
        addLog("OK", "Volume leveling ${if (on) "ON" else "OFF"}")
    }

    private fun applyBackgroundPlay(raw: String) {
        val on = raw.trim() == "1"
        playerPrefs().edit().putBoolean("background_play", on).apply()
        addLog("OK", "Background play ${if (on) "ON" else "OFF"} — applies on next launch")
    }

    private fun applyServerIp(raw: String) {
        val ip = raw.trim()
        serverPrefs.setPcServerIp(ip)
        addLog("OK", if (ip.isEmpty()) "Proxy IP cleared — local mode, no proxy polling"
                     else "Proxy IP set to $ip")
    }

    private fun applyStandalone(raw: String) {
        if (!serverPrefs.hasPcServer()) return
        val on = raw.trim() == "1" || raw.trim().equals("true", ignoreCase = true)
        standalonePrefs.setEnabled(on)
        addLog("OK", "Standalone playback ${if (on) "ON" else "OFF"} — applies from next song")
    }

    private fun applyCrossfade(raw: String) {
        val ms = raw.trim().toLongOrNull()
        if (ms == null) { addLog("ERROR", "Invalid crossfade: $raw"); return }
        crossfadePrefs.setDuration(ms)
        addLog("OK", "Crossfade set to ${crossfadePrefs.getDuration()}ms — applies from next song")
    }

    private fun applyLyricsOffset(raw: String) {
        val ms = raw.trim().toLongOrNull()
        if (ms == null) { addLog("ERROR", "Invalid offset: $raw"); return }
        lyricsOffsetPrefs.setOffset(ms)
        addLog("OK", "Lyrics offset set to ${ms}ms")
    }

    private fun applyToken(mut: String): com.applemusicktv.data.auth.TokenVerification {
        val checked = if (mut.length !in 20..8192 || mut.any { it.isWhitespace() || it.code < 33 || it.code > 126 })
            com.applemusicktv.data.auth.TokenVerification.Invalid
        else runBlocking { tokenVerifier.verify(mut) }
        if (checked != com.applemusicktv.data.auth.TokenVerification.Valid) {
            addLog("WARN", "Token validation ${checked.name}; saved token unchanged")
            return checked
        }
        prefs.setMUT(mut)
        prefs.markVerified(mut)
        addLog("OK", "Music-User-Token saved (${mut.length} chars)")
        appScope?.launch {
            runCatching { repo.syncMUTToServer(mut) }.onFailure { addLog("WARN", "Server MUT sync failed: ${it.message}") }
        }
        return com.applemusicktv.data.auth.TokenVerification.Valid
    }

    private fun handleEvent(socket: Socket) {
        try {
            socket.soTimeout = 0
            socket.tcpNoDelay = true
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val out = socket.getOutputStream()
            // Drain HTTP request headers
            var line = reader.readLine()
            while (!line.isNullOrBlank()) { line = reader.readLine() }
            val header = "HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nCache-Control: no-cache\r\nConnection: keep-alive\r\nAccess-Control-Allow-Origin: *\r\n\r\n"
            out.write(header.toByteArray()); out.flush()
            // Dump EVERYTHING on connect: network request log + full app log backlog,
            // then keep streaming live app logs. `curl http://<firetv-ip>:8081`.
            out.write("===== NETWORK REQUESTS =====\n".toByteArray())
            for (n in NetworkLog.getAll()) { out.write("${n}\n".toByteArray()) }
            out.write("===== APP LOG =====\n".toByteArray())
            val backlog = synchronized(logs) { logs.toList() }
            for (l in backlog) { out.write("${l}\n".toByteArray()) }
            out.write("===== LIVE =====\n".toByteArray()); out.flush()
            eventClients.add(out)
            try { while (!socket.isClosed) Thread.sleep(500) } catch (_: Exception) {}
            eventClients.remove(out)
        } catch (_: Exception) {}
        try { socket.close() } catch (_: Exception) {}
    }

    private fun handleSse(socket: Socket, out: BufferedOutputStream) {
        val header = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: keep-alive\r\nAccess-Control-Allow-Origin: *\r\n\r\n"
        out.write(header.toByteArray()); out.flush()
        sseClients.add(out)
        try { while (!socket.isClosed) Thread.sleep(1000) } catch (_: Exception) {}
        sseClients.remove(out)
        try { socket.close() } catch (_: Exception) {}
    }

    private fun send(out: BufferedOutputStream, code: Int, type: String, body: String, extra: String = "") {
        val bytes = body.toByteArray()
        val status = mapOf(200 to "OK", 302 to "Found", 303 to "See Other", 400 to "Bad Request", 404 to "Not Found")[code] ?: "Error"
        val resp = "HTTP/1.1 $code $status\r\nContent-Type: $type; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n$extra\r\n"
        out.write(resp.toByteArray()); out.write(bytes)
    }

    private fun redirect(out: BufferedOutputStream, to: String) = send(out, 303, "text/plain", "", "Location: $to\r\n")

    private fun parseField(body: String, field: String) =
        body.split("&").find { it.startsWith("$field=") }?.removePrefix("$field=")
            ?.replace("+", " ")?.let { URLDecoder.decode(it, "UTF-8") } ?: ""

    /** A single self-contained text dump for bug reports: environment + crash + logs. */
    private fun bugReport(): String {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val crash = com.applemusicktv.util.CrashReporter.lastCrash(context)
        return buildString {
            appendLine("===== Apple Music — bug report =====")
            appendLine("Generated: $ts")
            appendLine("App: v${com.applemusicktv.BuildConfig.VERSION_NAME} (${com.applemusicktv.BuildConfig.VERSION_CODE})")
            appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
            appendLine("Standalone: ${standalonePrefs.isEnabled()} · Token set: ${prefs.hasMUT()}")
            appendLine()
            appendLine("===== LAST CRASH =====")
            appendLine(crash ?: "(none recorded)")
            appendLine()
            appendLine("===== NETWORK REQUESTS =====")
            NetworkLog.getAll().forEach { appendLine(it) }
            appendLine()
            appendLine("===== APP LOG =====")
            getLogs().forEach { appendLine(it) }
        }
    }

    private fun status() = """{"hasMUT":${prefs.hasMUT()},"mutLen":${prefs.getMUT().length},"url":"${serverUrl()}","hasCrash":${com.applemusicktv.util.CrashReporter.lastCrash(context) != null}}"""
    private fun logsJson() = "[${getLogs().joinToString(",") { "\"${it.replace("\"","'")}\"" }}]"
    private fun netLogsJson() = "[${NetworkLog.getAll().joinToString(",") { "\"${it.replace("\"","'")}\"" }}]"

    internal fun html(tokenSaved: Boolean = false, tokenError: Boolean = false, tokenUnavailable: Boolean = false, language: String = "zh"): String {
        val has = prefs.hasMUT()
        val currentOffset = lyricsOffsetPrefs.getOffset()
        val currentBeatLatency = beatAnalyzer.latencyMs
        val standaloneOn = !serverPrefs.hasPcServer() || standalonePrefs.isEnabled()
        val serverIp = serverPrefs.getPcServerIp()
        val pp = context.getSharedPreferences("player_state", Context.MODE_PRIVATE)
        val volLevelOn = pp.getBoolean("volume_leveling", false)
        val bgPlayOn = pp.getBoolean("background_play", false)
        val currentCrossfade = crossfadePrefs.getDuration()
        val crossfadeSec = "%.1f".format(currentCrossfade / 1000f)
        val logRows = getLogs().reversed().take(80).joinToString("") { log ->
            val cls = when { log.contains("[OK]") -> "g"; log.contains("[ERROR]") -> "r"; log.contains("[WARN]") -> "y"; else -> "d" }
            "<div class=$cls>${log.replace("<","&lt;")}</div>"
        }.ifEmpty { "<div class=d>No logs yet.</div>" }
        val netRows = NetworkLog.getAll().reversed().take(100).joinToString("") { log ->
            val cls = when { log.contains("✓") -> "g"; log.contains("✗") -> "r"; else -> "d" }
            "<div class=$cls>${log.replace("<","&lt;")}</div>"
        }.ifEmpty { "<div class=d>No requests yet.</div>" }
        val page = """<!DOCTYPE html><html lang="${if (language == "zh") "zh-CN" else "en"}"><head><meta charset=UTF-8><meta name=viewport content="width=device-width,initial-scale=1">
<title>Apple Music 音乐令牌</title><style>
*{box-sizing:border-box;margin:0;padding:0}body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;background:#fff;color:#1d1d1f;padding:32px 20px;max-width:620px;margin:auto;line-height:1.6}
h1{font-size:28px;font-weight:700;margin-bottom:8px;letter-spacing:-.5px}
.brand{color:#fa233b;font-size:14px;font-weight:600;margin-bottom:8px}
.sub{font-size:15px;color:#6e6e73;margin-bottom:24px}
.card{background:#f5f5f7;border-radius:18px;padding:20px;margin-bottom:16px}
h2{font-size:18px;font-weight:600;color:#1d1d1f;margin-bottom:12px}
.help{font-size:14px;color:#6e6e73;margin-bottom:14px}
.notice{border-radius:14px;padding:16px;margin-bottom:20px;font-size:15px;background:#edf7ef;color:#216e34}
.notice.error{background:#fff0f1;color:#b22222}
.more{margin-top:24px;border-top:1px solid #e5e5ea;padding-top:20px}
summary{cursor:pointer;font-size:16px;font-weight:600;min-height:48px;display:flex;align-items:center;justify-content:space-between;list-style:none}
summary::-webkit-details-marker{display:none}summary::after{content:'＋';font-size:22px;color:#6e6e73}details[open]>summary::after{content:'−'}
.more[open]>summary{margin-bottom:16px}.tutorial a{color:#fa233b}.tutorial summary{min-height:32px}.tutorial ol{margin-top:14px}
button:focus-visible,a:focus-visible,summary:focus-visible,textarea:focus-visible{outline:3px solid #fa233b;outline-offset:4px}
.token-label{display:block;font-size:14px;font-weight:500;margin-bottom:8px}
.row{display:flex;align-items:center;gap:10px;margin-bottom:10px}
.dot{width:9px;height:9px;border-radius:50%;flex-shrink:0}
.dot-g{background:#34c759}.dot-r{background:#ff3b30}
.g{color:#248a3d}.r{color:#ff3b30}.y{color:#ffd60a}.d{color:#555}
.label{font-size:14px;color:#1d1d1f}
.sub2{font-size:12px;color:#555;font-family:monospace;word-break:break-all;margin-top:2px}
textarea{width:100%;background:#e8e8ed;border:1.5px solid #d2d2d7;border-radius:8px;color:#1d1d1f;font-family:monospace;font-size:14px;padding:14px;min-height:120px;outline:none;resize:vertical}
textarea:focus{border-color:#fa233b}
.btn{display:block;width:100%;padding:12px;border-radius:8px;font-size:14px;font-weight:600;border:none;cursor:pointer;margin-top:8px;text-align:center}
.btn-p{background:#fa233b;color:#fff}.btn-s{background:#e8e8ed;color:#555}
.btn:active{opacity:.7}
ol{font-size:14px;color:#6e6e73;line-height:1.9;padding-left:18px}code{color:#fa233b;font-family:monospace;font-size:11px}
.logs{background:#e8e8ed;border-radius:8px;padding:10px;max-height:240px;overflow-y:auto;font-family:monospace;font-size:10px;line-height:1.8}
.rbar{display:flex;justify-content:space-between;align-items:center;margin-bottom:8px}
.rbtn{background:none;border:1px solid #222;color:#555;padding:4px 12px;border-radius:6px;font-size:11px;cursor:pointer}
.rbtn:active{color:#6e6e73}

.tutorial ol{list-style:none;padding:0;margin:14px 0 0;counter-reset:step;font-size:13px}
.tutorial li{counter-increment:step;margin:0;padding:12px 0;border-top:1px solid #dedee3;line-height:1.8}
.tutorial li:first-child{border-top:0;padding-top:0}.tutorial li:last-child{padding-bottom:0}
.tutorial h3{font-size:14px;font-weight:400;color:#1d1d1f;margin:0 0 4px;line-height:1.6}
.tutorial h3::before{content:counter(step) ". ";}.tutorial .highlight{color:#fa233b;font-weight:400}
.tutorial code{font-size:11px;overflow-wrap:anywhere}.tutorial .btn{min-height:44px;font-size:14px;text-decoration:none;margin:8px 0 2px}
.tutorial .btn-p{color:white}.tutorial .btn-s{color:#555}.tutorial p{overflow-wrap:anywhere}
.tutorial .instruction-code{display:block;margin-top:8px;padding:10px;background:#e8e8ed;border-radius:8px;height:96px;overflow-y:auto;overscroll-behavior:contain;-webkit-overflow-scrolling:touch;white-space:pre-wrap;overflow-wrap:anywhere;word-break:break-word;font:11px/1.6 monospace;color:#555;user-select:text;-webkit-user-select:text}.tutorial .instruction-code code{font:inherit;color:inherit}
.tutorial .web-open-hint{font-size:12px;color:#6e6e73;line-height:1.6;margin-top:8px}
.copy-feedback{font-size:12px;color:#6e6e73;margin-top:6px}.copy-feedback:empty{display:none}
.brand-row{display:flex;align-items:center;justify-content:space-between;gap:16px;margin-bottom:8px}.brand-row .brand{margin:0}.language-switch{display:inline-flex;align-items:center;gap:6px;color:#fa233b;text-decoration:none;font-size:14px;font-weight:500;line-height:1.6;white-space:nowrap}.language-switch svg{flex:none}.language-switch:active{opacity:.65}
</style>
<script id="bookmark-instruction" type="application/json">${com.applemusicktv.i18n.PhonePage.bookmarkJson(context, language)}</script>
<script>
const bookmarkInstruction = JSON.parse(document.getElementById('bookmark-instruction').textContent);

function legacyCopyInstruction() {
  const previousFocus = document.activeElement;
  const field = document.createElement('textarea');
  field.value = bookmarkInstruction;
  field.readOnly = true;
  field.style.cssText = 'position:fixed;top:0;left:0;width:1px;height:1px;opacity:0;font-size:16px;';
  document.body.appendChild(field);
  let copied = false;
  try {
    field.focus({preventScroll:true});
    field.select();
    field.setSelectionRange(0, field.value.length);
    copied = document.execCommand('copy');
  } finally {
    field.remove();
    if (previousFocus && previousFocus.focus) previousFocus.focus({preventScroll:true});
  }
  if (!copied) throw new Error('Copy was not allowed');
}

async function copyBookmarkInstruction(button) {
  const feedback = document.getElementById('copy-feedback');
  try {
    if (window.isSecureContext && navigator.clipboard) {
      try { await navigator.clipboard.writeText(bookmarkInstruction); }
      catch (error) { legacyCopyInstruction(); }
    } else {
      // LAN HTTP has no Clipboard API: copy synchronously inside the tap event.
      legacyCopyInstruction();
    }
    button.textContent = '已复制';
    feedback.textContent = '';
  } catch (error) {
    button.textContent = '复制指令';
    feedback.textContent = '复制未成功，请长按上方指令，全选后复制。';
  }
}
function refresh(){
  fetch('/status').then(r=>r.json()).then(s=>{
    const el=document.getElementById('status');
    if(el)el.innerHTML=s.hasMUT?'<div class="row"><div class="dot dot-g"></div><div><div class=label>用户令牌已配置</div></div></div>':'<div class="row"><div class="dot dot-r"></div><div><div class=label>尚未配置令牌</div></div></div>';
  });
  fetch('/logs').then(r=>r.json()).then(logs=>{
    const el=document.getElementById('applogs');
    if(el)el.innerHTML=logs.reverse().slice(0,80).map(l=>{const c=l.includes('[OK]')?'g':l.includes('[ERROR]')?'r':l.includes('[WARN]')?'y':'d';return'<div class='+c+'>'+l.replace(/</g,'&lt;')+'</div>';}).join('')||'<div class=d>暂无日志。</div>';
  });
  fetch('/netlogs').then(r=>r.json()).then(logs=>{
    const el=document.getElementById('netlogs');
    if(el)el.innerHTML=logs.reverse().slice(0,100).map(l=>{const c=l.includes('✓')?'g':l.includes('✗')?'r':'d';return'<div class='+c+'>'+l.replace(/</g,'&lt;')+'</div>';}).join('')||'<div class=d>暂无请求。</div>';
  });
}
setInterval(refresh,3000);
function copyLogs(id){
  fetch(id==='applogs'?'/logs':'/netlogs').then(r=>r.json()).then(logs=>{
    const text=logs.reverse().join('\n');
    const b=document.getElementById('copy-'+id);
    const done=()=>{const orig=b.textContent;b.textContent='已复制';setTimeout(()=>b.textContent=orig,1500);};
    // navigator.clipboard only exists in a secure context (HTTPS/localhost). This
    // page is served over plain HTTP on a LAN IP, so it's usually undefined — fall
    // back to a hidden textarea + execCommand, which works in insecure contexts.
    if(navigator.clipboard&&window.isSecureContext){
      navigator.clipboard.writeText(text).then(done).catch(()=>legacyCopy(text,done,b));
    } else { legacyCopy(text,done,b); }
  });
}
function legacyCopy(text,done,b){
  try{
    const ta=document.createElement('textarea');
    ta.value=text;ta.style.position='fixed';ta.style.top='0';ta.style.left='0';ta.style.opacity='0';
    document.body.appendChild(ta);ta.focus();ta.select();
    const ok=document.execCommand('copy');
    document.body.removeChild(ta);
    if(ok)done();else{b.textContent='复制失败';setTimeout(()=>b.textContent='复制',1500);}
  }catch(e){b.textContent='复制失败';setTimeout(()=>b.textContent='复制',1500);}
}
</script>
</head><body>
<header class="brand-row"><p class="brand">Apple Music</p><a class="language-switch" href="?lang=${if (language == "zh") "en" else "zh"}" lang="${if (language == "zh") "en" else "zh"}" aria-label="${if (language == "zh") "Switch to English" else "切换为中文"}"><svg viewBox="0 0 20 20" width="16" height="16" fill="none" aria-hidden="true"><path d="M3 6h13m0 0-3-3m3 3-3 3M17 14H4m0 0 3-3m-3 3 3 3" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></svg><span>${if (language == "zh") "English" else "中文"}</span></a></header>
<h1>登录你的音乐</h1>
<p class=sub>在下方保存 Apple Music 用户个人令牌，即可在电视上聆听。</p>
${if (tokenSaved) "<div class=notice role=status><strong>令牌已保存</strong><br>请回到电视端，点击“开始聆听”继续操作。</div>" else ""}
${if (tokenUnavailable) "<div class='notice error' role=alert>暂时无法连接 Apple Music 完成验证，令牌未更改。请检查网络后重试。</div>" else ""}
${if (tokenError) "<div class='notice error' role=alert>令牌验证失败，令牌未保存，请重新复制完整的用户个人令牌。</div>" else ""}
<div id=status aria-live=polite>
<div class=row><div class="dot ${if(has)"dot-g" else "dot-r"}"></div><div class=label>${if(has)"已配置用户个人令牌" else "等待配置用户个人令牌"}</div></div>
</div>

<details class="card tutorial">
<summary>如何获取令牌</summary>
<ol>
<li><h3>保存本页面至书签</h3><p>将当前页面添加到 Safari 书签。</p></li>
<li><h3>复制执行指令</h3><p>点击<span class="highlight">复制指令</span>。</p>
<pre class="instruction-code" aria-label="书签指令代码" tabindex="0"><code id="instruction-text">${com.applemusicktv.i18n.PhonePage.bookmarkHtml(context, language)}</code></pre>
<button class="btn btn-p" id="copy-instruction" type="button" onclick="copyBookmarkInstruction(this)">复制指令</button>
<p class="copy-feedback" id="copy-feedback" role="status" aria-live="polite"></p></li>
<li><h3>修改书签地址</h3><p>打开书签列表，长按刚才保存的书签，选择<span class="highlight">编辑</span>。将原网址删除，粘贴刚才复制的指令代码，然后保存。</p></li>
<li><h3>登录并获取令牌</h3><p>点击<span class="highlight">打开 Apple Music 网页版</span>，在 Safari 中登录账号，等待页面加载完成。然后从 Safari 书签列表中点击<span class="highlight">Apple Music 音乐令牌</span>，再点击弹窗中的<span class="highlight">复制令牌</span>。</p>
<a class="btn btn-p" href="https://music.apple.com/" target="_blank" rel="noopener noreferrer" aria-describedby="web-open-hint">打开 Apple Music 网页版</a>
<p class="web-open-hint" id="web-open-hint">若自动跳转 App，请长按此按钮，选择在新标签页中打开。</p></li>
<li><h3>返回本页保存</h3><p>切回这个配置页面，将令牌粘贴到下方输入框，点击<span class="highlight">保存令牌</span>。</p></li>
</ol>
</details>
<div class=card>
<h2>配置令牌</h2>
<form method=POST action=/set-token>
<label class=token-label for=mut>Apple Music 用户个人令牌</label>
<textarea id=mut name=mut required minlength=20 autocomplete=off autocapitalize=off spellcheck=false placeholder="在此粘贴完整令牌"></textarea>
<button class="btn btn-p" type=submit>保存令牌</button>
</form>
</div>

<details class=more>
<summary>更多功能设置</summary>
${if(has)"<form method=POST action=/clear-token><button class='btn btn-s' type=submit>清除令牌</button></form>" else ""}
<div class=card>
<h2>歌词偏移</h2>
<div class=row><div class=label>当前偏移</div><div class=sub2 style=color:#6e6e73>${currentOffset}ms</div></div>
<form method=POST action=/set-lyrics-offset>
<input name=offset type=number value="$currentOffset" placeholder="0" style="width:100%;background:#e8e8ed;border:1.5px solid #d2d2d7;border-radius:8px;color:#1d1d1f;font-family:monospace;font-size:13px;padding:10px;outline:none;margin-top:4px">
<button class="btn btn-p" type=submit style=margin-top:8px>设置偏移（毫秒）</button>
</form>
<div style="font-size:10px;color:#555;margin-top:8px">正数提前显示歌词，负数延后；歌词偏慢时可尝试 +200。</div>
</div>

<div class=card>
<h2>节拍延迟</h2>
<div class=row><div class=label>当前延迟</div><div class=sub2 style=color:#6e6e73>${currentBeatLatency}ms</div></div>
<form method=POST action=/set-beat-latency>
<input name=latency type=range min=0 max=500 step=10 value="$currentBeatLatency" oninput="document.getElementById('bv').textContent=this.value+'ms'" style="width:100%;accent-color:#fa233b;margin-top:8px">
<div style="font-size:12px;color:#6e6e73;text-align:center;margin-top:4px"><span id=bv>${currentBeatLatency}ms</span></div>
<button class="btn btn-p" type=submit style=margin-top:8px>应用并重置</button>
</form>
<div style="font-size:10px;color:#555;margin-top:8px">电视扬声器可设为 0，蓝牙通常约 200 毫秒；应用后重置节拍缓冲。</div>
</div>

<div class=card>
<h2>电脑代理服务器</h2>
<div class=row><div class=label>当前</div><div class=sub2 style=color:#6e6e73>${if (serverIp.isEmpty()) "未设置" else serverIp}</div></div>
<form method=POST action=/set-server-ip>
<input name=serverip type=text inputmode=decimal placeholder="192.168.1.190" value="$serverIp" style="width:100%;box-sizing:border-box;padding:10px;margin-top:8px;border-radius:6px;border:1px solid #333;background:#e8e8ed;color:#1d1d1f;font-family:monospace;font-size:15px">
<button class="btn btn-p" type=submit style=margin-top:8px>设置电脑 IP</button>
</form>
<div style="font-size:10px;color:#555;margin-top:8px">默认端口为 3000。使用代理模式时由电脑提供服务；留空仅使用本机模式，不检查电脑代理。</div>
</div>

<div class=card>
<h2>本机模式（无需电脑）</h2>
<div class=row><div class=label>状态</div><div class=sub2 style="color:${if (standaloneOn) "#6bcb77" else "#aaa"}">${if (standaloneOn) "已开启，全部在本机处理" else "已关闭，使用电脑服务器"}</div></div>
<form method=POST action=/set-standalone>
<input type=hidden name=standalone value="${if (standaloneOn) "0" else "1"}">
<button class="btn ${if (standaloneOn) "" else "btn-p"}" type=submit ${if (!serverPrefs.hasPcServer()) "disabled" else ""} style=margin-top:8px>${if (!serverPrefs.hasPcServer()) "未配置代理，仅使用本机" else if (standaloneOn) "关闭" else "开启"}</button>
</form>
<div style="font-size:10px;color:#555;margin-top:8px">直接连接 Apple，提供浏览、资料库、搜索、封面、歌词和播放，无需电脑。部分歌曲可能出现分段间隙；电脑代理模式可修复此类间隙。设置从下一首生效。</div>
</div>

<div class=card>
<h2>音量均衡</h2>
<div class=row><div class=label>状态</div><div class=sub2 style="color:${if (volLevelOn) "#6bcb77" else "#aaa"}">${if (volLevelOn) "已开启，均衡歌曲响度" else "已关闭，保留原有响度"}</div></div>
<form method=POST action=/set-volume-leveling>
<input type=hidden name=vol value="${if (volLevelOn) "0" else "1"}">
<button class="btn ${if (volLevelOn) "" else "btn-p"}" type=submit style=margin-top:8px>${if (volLevelOn) "关闭" else "开启"}</button>
</form>
<div style="font-size:10px;color:#555;margin-top:8px">自动缓慢调整增益，使不同歌曲的响度更均衡。立即生效，可在应用日志的 VOL 条目中查看。</div>
</div>

<div class=card>
<h2>后台播放</h2>
<div class=row><div class=label>状态</div><div class=sub2 style="color:${if (bgPlayOn) "#6bcb77" else "#aaa"}">${if (bgPlayOn) "已开启，后台继续播放" else "已关闭，离开时暂停"}</div></div>
<form method=POST action=/set-background-play>
<input type=hidden name=bg value="${if (bgPlayOn) "0" else "1"}">
<button class="btn ${if (bgPlayOn) "" else "btn-p"}" type=submit style=margin-top:8px>${if (bgPlayOn) "关闭" else "开启"}</button>
</form>
<div style="font-size:10px;color:#555;margin-top:8px">下次启动应用时生效。</div>
</div>

<div class=card>
<h2>交叉渐变</h2>
<div class=row><div class=label>当前时长</div><div class=sub2 style=color:#6e6e73>${crossfadeSec}s</div></div>
<form method=POST action=/set-crossfade>
<input id=crossfade-ms name=crossfade type=hidden value="$currentCrossfade">
<input type=range min=0 max=${CrossfadePreferences.MAX_STEP} step=1 value="${CrossfadePreferences.sliderStep(currentCrossfade)}" oninput="const ms=this.value==0?0:1000+(Number(this.value)-1)*500;document.getElementById('crossfade-ms').value=ms;document.getElementById('cv').textContent=(ms/1000).toFixed(1)+'s'" style="width:100%;accent-color:#fa233b;margin-top:8px">
<div style="font-size:12px;color:#6e6e73;text-align:center;margin-top:4px"><span id=cv>${crossfadeSec}s</span></div>
<button class="btn btn-p" type=submit style=margin-top:8px>设置渐变时长</button>
</form>
<div style="font-size:10px;color:#555;margin-top:8px">下一首歌曲生效，已经开始的渐变保持原有时长。较长渐变需要更早预加载下一首。</div>
</div>

<div class=card>
<h2>问题报告</h2>
<div class=row><div class=label>最近崩溃</div><div class=sub2 style="color:${if (com.applemusicktv.util.CrashReporter.lastCrash(context) != null) "#ff3b30" else "#6bcb77"}">${if (com.applemusicktv.util.CrashReporter.lastCrash(context) != null) "已记录崩溃" else "无"}</div></div>
<a class="btn btn-p" href=/report download style=text-decoration:none>下载问题报告</a>
<form method=POST action=/clear-crash><button class="btn btn-s" type=submit>清除崩溃日志</button></form>
<div style="font-size:10px;color:#555;margin-top:8px">将应用版本、设备信息、最近崩溃、应用和网络日志汇总为文本文件。</div>
</div>


<div class=card>
<div class=rbar><h2 style=margin:0>网络活动</h2><div style=display:flex;gap:6px><button class=rbtn id=copy-netlogs onclick="copyLogs('netlogs')">复制</button><button class=rbtn onclick=refresh()>↻</button></div></div>
<div class=logs id=netlogs>@AMTV_NETWORK_LOGS@</div>
</div>

<div class=card>
<div class=rbar><h2 style=margin:0>应用日志</h2><div style=display:flex;gap:6px><button class=rbtn id=copy-applogs onclick="copyLogs('applogs')">复制</button><button class=rbtn onclick=refresh()>↻</button></div></div>
<div class=logs id=applogs>@AMTV_APP_LOGS@</div>
</div>
</details>
</body></html>"""
        return com.applemusicktv.i18n.PhonePage.render(context, page, language)
            .replace("@AMTV_NETWORK_LOGS@", netRows).replace("@AMTV_APP_LOGS@", logRows)
    }
}
