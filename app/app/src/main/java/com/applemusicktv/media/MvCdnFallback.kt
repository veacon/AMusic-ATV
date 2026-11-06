package com.applemusicktv.media

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** Media only. The original URL/Host/SNI, certificate checks and DRM stay unchanged. */
internal class MvCdnRoute(private val clock: () -> Long = { SystemClock.elapsedRealtime() }) {
    private var until = 0L
    @Synchronized fun active() = clock() < until
    @Synchronized fun recovered() { until = clock() + 180_000L }
    @Synchronized fun failed() { until = 0L }
    fun eligible(spec: DataSpec) = spec.uri.scheme == "https" && spec.uri.host.equals("mvod.itunes.apple.com", true)
}

/** Authenticated DNS asks only for the fixed public media hostname; no account data. */
internal class MvMediaDns : Dns {
    private val bootstrap = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS)
        .connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).followRedirects(false).build()
    private var expires = 0L
    private var cached: List<InetAddress> = emptyList()
    @Synchronized override fun lookup(hostname: String): List<InetAddress> {
        if (!hostname.equals("mvod.itunes.apple.com", true)) return Dns.SYSTEM.lookup(hostname)
        if (SystemClock.elapsedRealtime() < expires && cached.isNotEmpty()) return cached
        for (base in listOf("https://cloudflare-dns.com/dns-query", "https://dns.google/resolve")) {
            val result = runCatching {
                bootstrap.newCall(Request.Builder().url("$base?name=mvod.itunes.apple.com&type=A")
                    .header("Accept", "application/dns-json").build()).execute().use { response ->
                    if (!response.isSuccessful) throw UnknownHostException("Media DNS response ${response.code}")
                    val json = JSONObject(response.body?.string().orEmpty())
                    if (json.optInt("Status", -1) != 0) throw UnknownHostException("Media DNS lookup failed")
                    val answers = json.optJSONArray("Answer") ?: throw UnknownHostException("Media DNS has no answers")
                    (0 until answers.length()).mapNotNull { index ->
                        val item = answers.getJSONObject(index)
                        if (item.optInt("type") != 1) return@mapNotNull null
                        publicIpv4(item.optString("data"))
                    }.distinctBy { it.hostAddress }
                }
            }.getOrNull().orEmpty()
            if (result.isNotEmpty()) {
                cached = result; expires = SystemClock.elapsedRealtime() + 180_000L
                Log.i("MVRoute", "media DNS fallback resolved ${result.size} addresses")
                return result
            }
        }
        throw UnknownHostException("Media DNS fallback unavailable")
    }
    companion object {
        internal fun publicIpv4(value: String): InetAddress? {
            val parts = value.split('.')
            if (parts.size != 4 || parts.any { it.toIntOrNull() !in 0..255 }) return null
            val numbers = parts.map { it.toInt() }
            if (numbers[0] == 0 || numbers[0] == 127 || numbers[0] >= 224 ||
                (numbers[0] == 100 && numbers[1] in 64..127) ||
                (numbers[0] == 198 && numbers[1] in 18..19)) return null
            val address = InetAddress.getByAddress(parts.map { it.toInt().toByte() }.toByteArray())
            return address.takeUnless { it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress || it.isSiteLocalAddress || it.isMulticastAddress }
        }
    }
}

internal class MvCdnFallbackFactory(
    private val primary: DataSource.Factory,
    private val route: MvCdnRoute = MvCdnRoute(),
    private val fallback: DataSource.Factory = OkHttpDataSource.Factory(
        OkHttpClient.Builder().dns(MvMediaDns()).connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .followSslRedirects(false)
            .addNetworkInterceptor { chain ->
                val request = chain.request().newBuilder()
                listOf("Authorization", "Cookie", "Media-User-Token", "Music-User-Token", "X-Music-User-Token").forEach { request.removeHeader(it) }
                chain.proceed(request.build())
            }.build()),
) : DataSource.Factory {
    override fun createDataSource(): DataSource = object : DataSource {
        private var active: DataSource? = null
        private val listeners = mutableListOf<TransferListener>()
        private fun create(factory: DataSource.Factory) = factory.createDataSource().also { source -> listeners.forEach { source.addTransferListener(it) }; active = source }
        override fun open(spec: DataSpec): Long {
            var triedAlternative = false
            if (route.eligible(spec) && route.active()) {
                triedAlternative = true
                val source = create(fallback)
                try { return source.open(spec) }
                catch (error: java.io.IOException) { runCatching { source.close() }; active = null; route.failed() }
            }
            val source = create(primary)
            try { return source.open(spec) }
            catch (error: HttpDataSource.InvalidResponseCodeException) {
                if (error.responseCode != 403 || !route.eligible(spec) || triedAlternative) throw error
                runCatching { source.close() }
                MvHttpRecovery.cataloguePath(spec.uri.toString())?.let { Log.w("MVAsset", "catalogueAsset=$it") }
                Log.w("MVRoute", "media CDN HTTP403; trying alternative DNS route with original TLS host")
                val alternative = create(fallback)
                try {
                    val length = alternative.open(spec)
                    route.recovered()
                    Log.i("MVRoute", "media CDN route recovered; original URI and byte range preserved")
                    return length
                } catch (retryError: java.io.IOException) {
                    runCatching { alternative.close() }
                    active = null
                    Log.w("MVRoute", "alternative route failed: ${retryError.javaClass.simpleName}")
                    throw error
                }
            }
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int) = checkNotNull(active).read(buffer, offset, length)
        override fun getUri(): Uri? = active?.uri
        override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()
        override fun addTransferListener(listener: TransferListener) { listeners += listener; active?.addTransferListener(listener) }
        override fun close() { val source = active; active = null; source?.close() }
    }
}
