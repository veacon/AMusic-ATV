package com.applemusicktv.data.fonts

import java.io.File
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import org.junit.Assert.*
import org.junit.Test

class FontTlsTest {
    private fun rootFile() = File("src/main/assets/certificates/isrg-root-x1.pem")
    @Test fun legacyFontTrustRetainsSystemRootsAndAcceptsOnlyThePinnedPublicRoot() {
        val original = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as KeyStore?) }
            .trustManagers.filterIsInstance<X509TrustManager>().single()
        val trust = FontTls.trustManager(rootFile().inputStream())
        val before = original.acceptedIssuers.map { it.encoded.toList() }.toSet()
        val after = trust.acceptedIssuers.map { it.encoded.toList() }.toSet()
        assertTrue(after.containsAll(before))
        val root = rootFile().inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate }
        assertTrue(after.contains(root.encoded.toList()))
        // An unrelated otherwise-valid system CA must not be accepted as the bundled extra root.
        val other = original.acceptedIssuers.first { !it.encoded.contentEquals(root.encoded) }
        assertThrows(IllegalArgumentException::class.java) { FontTls.trustManager(ByteArrayInputStream(other.encoded)) }
    }
}
