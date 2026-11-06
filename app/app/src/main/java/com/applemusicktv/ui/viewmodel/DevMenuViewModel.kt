package com.applemusicktv.ui.viewmodel

import com.applemusicktv.i18n.tr
import com.applemusicktv.R

import android.content.Context
import com.applemusicktv.data.cache.AppDiskCache
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.applemusicktv.data.CrossfadePreferences
import com.applemusicktv.data.LyricsOffsetPreferences
import com.applemusicktv.data.MutPreferences
import com.applemusicktv.data.ServerPreferences
import com.applemusicktv.data.StandalonePreferences
import com.applemusicktv.data.repository.MusicRepository
import com.applemusicktv.media.BeatAnalyzer
import com.applemusicktv.data.NetworkLog
import com.applemusicktv.media.InAppWebServer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

data class LogEntry(val level: String, val message: String)

data class DevMenuState(
    val serverOk:       Boolean        = false,
    val hasBearer:      Boolean        = false,
    val hasMUT:         Boolean        = false,
    val mutSetAt:       String?        = null,
    val webServerUrl:   String         = "",
    val pcServerIp:     String         = "",
    val standaloneMode: Boolean        = false,
    val standaloneOn:   Boolean        = false,
    val lyricsOffsetMs: Long           = 0L,
    val crossfadeMs:    Long           = 7_000L,
    val beatLatencyMs:  Long           = 0L,
    val cacheCapacityGb: Int = 1,
    val cacheUsedBytes: Long = 0,
    val cacheBusy: Boolean = false,
    val cacheMessage: String = "",
    val logs:           List<LogEntry> = emptyList(),
)

@HiltViewModel
class DevMenuViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: MusicRepository,
    private val webServer: InAppWebServer,
    private val mutPrefs: MutPreferences,
    private val serverPrefs: ServerPreferences,
    private val lyricsOffsetPrefs: LyricsOffsetPreferences,
    private val crossfadePrefs: CrossfadePreferences,
    private val standalonePrefs: StandalonePreferences,
    private val beatAnalyzer: BeatAnalyzer,
) : ViewModel() {

    data class UpdateState(val phase: String = "idle", val info: com.applemusicktv.data.updates.UpdateInfo? = null,
        val progress: Int = 0, val manualResult: Boolean = false, val ready: java.io.File? = null)
    private val updater = com.applemusicktv.data.updates.AppUpdates(context)
    private val _update = MutableStateFlow(UpdateState())
    val update: StateFlow<UpdateState> = _update
    fun dismissUpdateResult() { _update.update { it.copy(manualResult = false) } }
    fun showUpdateResult() { _update.update { it.copy(manualResult = true) } }
    fun checkUpdates(manual: Boolean = false) {
        if (_update.value.phase == "checking" || _update.value.phase == "downloading") return
        _update.value = UpdateState(phase = "checking")
        viewModelScope.launch {
            try {
                val info = updater.check()
                val phase = when { info.code <= com.applemusicktv.BuildConfig.VERSION_CODE -> "latest"; info.minSdk > android.os.Build.VERSION.SDK_INT -> "incompatible"; else -> "available" }
                _update.value = UpdateState(phase = if (!manual && phase != "available") "idle" else phase, info = info, manualResult = manual)
            } catch (_: Exception) { _update.value = UpdateState(phase = if (manual) "error" else "idle", manualResult = manual) }
        }
    }
    fun downloadUpdate() {
        val info = _update.value.info ?: return
        if (_update.value.phase != "available") return
        _update.update { it.copy(phase = "downloading", manualResult = false, progress = 0) }
        viewModelScope.launch {
            try {
                val file = updater.download(info) { progress -> _update.update { it.copy(progress = progress) } }
                _update.update { it.copy(phase = "ready", ready = file) }
                installUpdate()
            } catch (_: Exception) { _update.update { it.copy(phase = "downloadError", manualResult = true) } }
        }
    }
    fun installUpdate() {
        val file = _update.value.ready ?: return
        try {
            if (!updater.install(file)) _update.update { it.copy(phase = "permission", manualResult = true) }
        } catch (_: Exception) { _update.update { it.copy(phase = "installError", manualResult = true) } }
    }

    private val diskCache = AppDiskCache.get(context)
    private val _state = MutableStateFlow(DevMenuState(
        hasMUT = mutPrefs.hasMUT(),
        cacheCapacityGb = diskCache.capacityGb,
        webServerUrl   = webServer.serverUrl(),
        pcServerIp     = serverPrefs.getPcServerIp(),
        lyricsOffsetMs = lyricsOffsetPrefs.getOffset(),
        crossfadeMs    = crossfadePrefs.getDuration(),
        beatLatencyMs  = beatAnalyzer.latencyMs,
        standaloneOn   = standalonePrefs.isEnabled(),
    ))
    val state: StateFlow<DevMenuState> = _state

    init {
        viewModelScope.launch {
            webServer.endpoint.collect { _state.update { it.copy(webServerUrl = webServer.serverUrl()) } }
        }
        viewModelScope.launch {
            mutPrefs.accountChanges.collect { _state.update { state -> state.copy(hasMUT = mutPrefs.hasMUT()) } }
        }
        refresh()
        viewModelScope.launch {
            serverPrefs.proxyAddress.collect { address ->
                _state.update { it.copy(pcServerIp = address,
                    serverOk = it.serverOk && it.pcServerIp == address && address.isNotEmpty()) }
            }
        }
    }

    suspend fun refreshCacheUsage() {
        val bytes = withContext(Dispatchers.IO) { diskCache.sizeBytes() }
        _state.update { it.copy(cacheUsedBytes = bytes) }
    }

    fun setCacheCapacity(gb: Int) = cacheOperation {
        diskCache.setCapacityGb(gb)
        ""
    }

    fun clearCache() = cacheOperation {
        diskCache.clear()
        tr(R.string.msg_045)
    }

    private fun cacheOperation(operation: () -> String) {
        if (_state.value.cacheBusy) return
        _state.update { it.copy(cacheBusy = true, cacheMessage = "") }
        viewModelScope.launch {
            try {
                val message = withContext(Dispatchers.IO) { operation() }
                refreshCacheUsage()
                _state.update { it.copy(cacheCapacityGb = diskCache.capacityGb, cacheMessage = message) }
            } catch (_: Exception) {
                _state.update { it.copy(cacheMessage = tr(R.string.msg_046)) }
            } finally {
                _state.update { it.copy(cacheBusy = false) }
            }
        }
    }

    fun refresh(onDone: () -> Unit = {}) = viewModelScope.launch {
        _state.update { it.copy(webServerUrl = webServer.serverUrl(), hasMUT = mutPrefs.hasMUT()) }
        if (!serverPrefs.hasPcServer()) {
            _state.update { it.copy(serverOk = false, hasBearer = false, hasMUT = mutPrefs.hasMUT(),
                standaloneMode = true, pcServerIp = "") }
            log("INFO", tr(R.string.msg_047))
            onDone()
            return@launch
        }
        log("INFO", tr(R.string.msg_048))
        runCatching { repo.getAuthStatus() }.onSuccess { s ->
            val localHasMUT = mutPrefs.hasMUT()
            _state.update { it.copy(serverOk = true, hasBearer = s.hasBearer, hasMUT = localHasMUT, mutSetAt = s.mutSetAt, standaloneMode = false) }
            log(if (localHasMUT) "OK" else "WARN", tr(R.string.msg_049, if (localHasMUT) tr(R.string.msg_050) else tr(R.string.msg_051)))
            log(if (s.hasBearer) "OK" else "WARN", tr(R.string.msg_052, if (s.hasBearer) tr(R.string.msg_053) else tr(R.string.msg_054)))
        }.onFailure {
            // Standalone mode (no PC server) is the normal case — the token still lives locally, so read
            // it from prefs here too. Otherwise the Account section shows "Music-User-Token: not set" even
            // when it's saved, because hasMUT was only populated on the server-reachable path.
            _state.update { s -> s.copy(serverOk = false, standaloneMode = true, hasMUT = mutPrefs.hasMUT()) }
            log(if (mutPrefs.hasMUT()) "OK" else "WARN", tr(R.string.msg_055, if (mutPrefs.hasMUT()) tr(R.string.msg_050) else tr(R.string.msg_051)))
        }
        onDone()   // reload Home/Library AFTER status (incl. reachability) is settled
    }

    fun recheckServer(playerVm: PlayerViewModel, onDone: () -> Unit = {}) = viewModelScope.launch {
        log("INFO", tr(R.string.msg_056))
        playerVm.recheckServer().join()   // flips serverReachable FIRST
        refresh()
        onDone()                          // …then reload Home/Library on the fresh path (was a race)
    }

    fun setPcServerIp(ip: String) {
        serverPrefs.setPcServerIp(ip)
        _state.update { it.copy(pcServerIp = ip) }
        log("INFO", if (ip.isEmpty()) tr(R.string.msg_057) else tr(R.string.msg_058, ip, serverPrefs.effectiveBaseUrl()))
    }

    fun setMUT(token: String) = viewModelScope.launch {
        log("INFO", tr(R.string.msg_059, token.length))
        runCatching { repo.setMUT(token) }.onSuccess {
            log("OK", tr(R.string.msg_060))
            refresh()
        }.onFailure { log("ERROR", tr(R.string.msg_061, it.message)) }
    }

    fun clearMUT() = viewModelScope.launch {
        runCatching { repo.clearMUT() }.onSuccess {
            log("WARN", tr(R.string.msg_062))
            refresh()
        }.onFailure { log("ERROR", it.message ?: tr(R.string.msg_063)) }
    }

    // ── User settings (mirror of the phone web page) ──────────────────────
    fun setCrossfade(ms: Long) {
        crossfadePrefs.setDuration(ms)
        val v = crossfadePrefs.getDuration()
        _state.update { it.copy(crossfadeMs = v) }
        log("OK", tr(R.string.msg_064, "%.1f".format(v / 1000f)))
    }

    fun setLyricsOffset(ms: Long) {
        lyricsOffsetPrefs.setOffset(ms)
        _state.update { it.copy(lyricsOffsetMs = lyricsOffsetPrefs.getOffset()) }
    }


    fun setBeatLatency(ms: Long) {
        val v = ms.coerceIn(0L, 500L)
        beatAnalyzer.latencyMs = v
        beatAnalyzer.resetBeat()
        _state.update { it.copy(beatLatencyMs = v) }
    }

    fun toggleStandalone() {
        if (!serverPrefs.hasPcServer()) return
        val on = !standalonePrefs.isEnabled()
        standalonePrefs.setEnabled(on)
        _state.update { it.copy(standaloneOn = on) }
        log("OK", tr(R.string.msg_065, if (on) tr(R.string.msg_066) else tr(R.string.msg_067)))
    }

    /** Pre-formatted HTTP lines from the direct client's interceptor. */
    fun networkLogs(): List<String> = NetworkLog.getAll()

    fun clearLogs() = _state.update { it.copy(logs = emptyList()) }

    private fun log(level: String, message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        _state.update { it.copy(logs = it.logs + LogEntry(level, "[$time] $message")) }
    }
}
