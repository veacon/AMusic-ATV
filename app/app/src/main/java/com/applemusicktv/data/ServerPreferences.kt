package com.applemusicktv.data

import android.content.Context
import androidx.core.content.edit
import com.applemusicktv.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServerPreferences @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("apple_music_tv", Context.MODE_PRIVATE)

    private val _proxyAddress = MutableStateFlow(getPcServerIp())
    val proxyAddress: StateFlow<String> = _proxyAddress

    // Unknown/unconfigured is never treated as an established proxy connection.
    @Volatile private var proxyReachable = false
    var serverReachable: Boolean
        get() = hasPcServer() && proxyReachable
        set(value) { proxyReachable = value && hasPcServer() }

    fun getPcServerIp(): String = prefs.getString("pc_server_ip", "")?.trim() ?: ""
    fun setPcServerIp(ip: String) {
        val address = ip.trim()
        if (address == getPcServerIp()) return
        proxyReachable = false
        prefs.edit { putString("pc_server_ip", address) }
        _proxyAddress.value = address
    }
    fun hasPcServer(): Boolean = getPcServerIp().isNotEmpty()

    fun effectiveBaseUrl(): String {
        val ip = getPcServerIp()
        return if (ip.isEmpty()) BuildConfig.PROXY_BASE_URL
        else if (ip.startsWith("http")) "${ip.trimEnd('/')}/"
        else "http://$ip:3000/"
    }
}
