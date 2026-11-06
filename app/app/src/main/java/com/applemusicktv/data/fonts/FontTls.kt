package com.applemusicktv.data.fonts

import java.io.InputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient

/** Android 6 predates ISRG Root X1. Add this verified public root only to the
 * font client's private trust store, retaining every existing system root.
 * Hostname verification and certificate-chain validation stay enabled. */
internal object FontTls {
    private const val ROOT_SHA256 = "96bcec06264976f37460779acf28c5a7cfe8a3c0aae11a8ffcee05c0bddf08c6"
    fun trustManager(rootPem: InputStream): X509TrustManager {
        val root = rootPem.use { CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate }
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(root.encoded).joinToString("") { "%02x".format(it) }
        require(fingerprint == ROOT_SHA256) { "Unexpected font TLS root certificate" }
        root.checkValidity()
        root.verify(root.publicKey)
        val system = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as KeyStore?) }
            .trustManagers.filterIsInstance<X509TrustManager>().single()
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            system.acceptedIssuers.forEachIndexed { index, cert -> setCertificateEntry("system-$index", cert) }
            setCertificateEntry("isrg-root-x1", root)
        }
        return TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            .trustManagers.filterIsInstance<X509TrustManager>().single()
    }
    fun configure(builder: OkHttpClient.Builder, rootPem: InputStream): OkHttpClient.Builder {
        val trust = trustManager(rootPem)
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        return builder.sslSocketFactory(tls.socketFactory, trust)
    }
}
