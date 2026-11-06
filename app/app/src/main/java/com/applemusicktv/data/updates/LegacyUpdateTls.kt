package com.applemusicktv.data.updates

import android.os.Build
import android.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

// Public SPKI hash verified against Let's Encrypt's published ISRG Root X2.
// The server must supply the matching CA in its chain; no certificate or private key is bundled.
private const val ISRG_ROOT_X2_SPKI = "diGVwiVYbubAI3RW4hB9xU8e/CH2GnkuvVFZE8zmgzI="
private const val OFFICIAL_HOST = "applemusictv.pages.dev"

internal fun needsLegacyUpdateTls(url: String, sdk: Int): Boolean {
    val parsed = url.toHttpUrlOrNull() ?: return false
    return sdk == 23 && parsed.isHttps && parsed.host == OFFICIAL_HOST && parsed.port == 443 &&
        parsed.encodedPath == "/update.json" && parsed.username.isEmpty() && parsed.password.isEmpty() &&
        parsed.query == null && parsed.fragment == null
}

internal fun updateHttpClient(manifestUrl: String): OkHttpClient {
    val builder = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
    if (needsLegacyUpdateTls(manifestUrl, Build.VERSION.SDK_INT)) {
        val trust = LegacyUpdateTrustManager(defaultTrustManager(null))
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        builder.sslSocketFactory(tls.socketFactory, trust)
        // The compatibility trust is exclusively for the official update host.
        builder.addInterceptor { chain ->
            val url = chain.request().url
            if (!url.isHttps || url.host != OFFICIAL_HOST || url.port != 443 ||
                url.username.isNotEmpty() || url.password.isNotEmpty()) throw java.io.IOException("Untrusted update host")
            chain.proceed(chain.request())
        }
    }
    return builder.build()
}

private fun defaultTrustManager(store: KeyStore?): X509TrustManager {
    val f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    f.init(store)
    return f.trustManagers.filterIsInstance<X509TrustManager>().single()
}

internal class LegacyUpdateTrustManager(
    private val system: X509TrustManager,
    private val pin: String = ISRG_ROOT_X2_SPKI,
) : X509TrustManager {
    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = system.checkClientTrusted(chain, authType)
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        try { system.checkServerTrusted(chain, authType); return } catch (_: CertificateException) { }
        for (ca in chain) {
            val digest = MessageDigest.getInstance("SHA-256").digest(ca.publicKey.encoded)
            val keyHash = Base64.encodeToString(digest, Base64.NO_WRAP)
            if (ca.basicConstraints < 0 || keyHash != pin) continue
            try {
                ca.checkValidity()
                val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                    load(null, null)
                    setCertificateEntry("update-ca", ca)
                }
                defaultTrustManager(store).checkServerTrusted(chain, authType)
                return
            } catch (e: Exception) { throw CertificateException("Update certificate chain rejected", e) }
        }
        throw CertificateException("No trusted update CA in certificate chain")
    }
}
