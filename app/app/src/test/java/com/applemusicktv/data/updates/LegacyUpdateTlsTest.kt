package com.applemusicktv.data.updates

import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager
import org.junit.Assert.*
import org.junit.Test

class LegacyUpdateTlsTest {
    @Test fun fallbackIsLimitedToAndroid6AndExactOfficialUpdateEndpoint() {
        assertTrue(needsLegacyUpdateTls("https://applemusictv.pages.dev/update.json", 23))
        for (url in listOf("http://applemusictv.pages.dev/update.json", "https://applemusictv.pages.dev.evil.test/update.json", "https://update-test.applemusictv.pages.dev/update.json", "https://applemusictv.pages.dev:444/update.json", "https://applemusictv.pages.dev/update.json?x=1")) assertFalse(url, needsLegacyUpdateTls(url, 23))
        assertFalse(needsLegacyUpdateTls("https://applemusictv.pages.dev/update.json", 24))
    }
    @Test fun systemTrustPassesAndAbsentPinnedCaFailsClosed() {
        val allow = object : X509TrustManager {
            override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        }
        LegacyUpdateTrustManager(allow).checkServerTrusted(emptyArray(), "RSA")
        val reject = object : X509TrustManager {
            override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) { throw CertificateException("reject") }
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) { throw CertificateException("reject") }
        }
        try { LegacyUpdateTrustManager(reject).checkServerTrusted(emptyArray(), "RSA"); fail("missing anchor must fail") } catch (_: CertificateException) {}
    }
}
