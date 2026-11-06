package com.applemusicktv.media

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28], manifest = Config.NONE)
class MvCdnFallbackTest {
    private class FakeFactory(val status: Int = 200, val failure: IOException? = null) : DataSource.Factory {
        val opened = mutableListOf<DataSpec>()
        var closed = 0
        override fun createDataSource(): DataSource = object : DataSource {
            private var spec: DataSpec? = null
            override fun open(dataSpec: DataSpec): Long {
                spec = dataSpec; opened += dataSpec
                failure?.let { throw it }
                if (status != 200) throw HttpDataSource.InvalidResponseCodeException(status, "fixture", null, emptyMap(), dataSpec, byteArrayOf())
                return 512
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int { buffer[offset] = 7; return 1 }
            override fun getUri(): Uri? = spec?.uri
            override fun addTransferListener(listener: TransferListener) { }
            override fun close() { closed++ }
        }
    }
    private fun media(host: String = "mvod.itunes.apple.com") = DataSpec.Builder()
        .setUri("https://$host/segment.mp4?signature=unchanged").setPosition(128).setLength(512).build()

    @Test fun alternateRouteKeepsOriginalUriAndByteRangeThenCachesSuccessfulRoute() {
        val primary = FakeFactory(403); val alternative = FakeFactory(); val route = MvCdnRoute { 100L }
        val factory = MvCdnFallbackFactory(primary, route, alternative)
        val spec = media()
        factory.createDataSource().apply {
            assertEquals(512L, open(spec)); assertEquals(spec.uri, uri)
            assertEquals(1, read(ByteArray(4), 0, 4)); close()
        }
        assertSame(spec, primary.opened.single())
        assertSame(spec, alternative.opened.single())
        assertEquals(128L, alternative.opened.single().position)
        assertEquals(512L, alternative.opened.single().length)
        factory.createDataSource().apply { open(spec); close() }
        assertEquals(1, primary.opened.size)
        assertEquals(2, alternative.opened.size)
        assertEquals(1, primary.closed)
    }

    @Test fun authorizationFailuresAndOtherHostsDoNotUseAlternativeDns() {
        for ((status, spec) in listOf(401 to media(), 403 to media("api.music.apple.com"), 403 to media("mvod.itunes.apple.com.attacker.test"))) {
            val primary = FakeFactory(status); val alternative = FakeFactory()
            val source = MvCdnFallbackFactory(primary, MvCdnRoute(), alternative).createDataSource()
            try { source.open(spec); fail("Expected original refusal") }
            catch (error: HttpDataSource.InvalidResponseCodeException) { assertEquals(status, error.responseCode) }
            finally { source.close() }
            assertTrue(alternative.opened.isEmpty())
        }
    }

    @Test fun bothRoutesFailWithoutRepeatedAlternativeAttempts() {
        val primary = FakeFactory(403); val alternative = FakeFactory(403)
        val source = MvCdnFallbackFactory(primary, MvCdnRoute(), alternative).createDataSource()
        try { source.open(media()); fail("Expected original refusal") }
        catch (error: HttpDataSource.InvalidResponseCodeException) { assertEquals(403, error.responseCode) }
        finally { source.close() }
        assertEquals(1, primary.opened.size)
        assertEquals(1, alternative.opened.size)
    }

    @Test fun expiredOrFailedPreferredRouteCanReturnToSystemRoute() {
        var time = 0L
        val route = MvCdnRoute { time }; route.recovered()
        val primary = FakeFactory(); val alternative = FakeFactory(failure = IOException("temporary"))
        MvCdnFallbackFactory(primary, route, alternative).createDataSource().apply { open(media()); close() }
        assertEquals(1, alternative.opened.size); assertEquals(1, primary.opened.size)
        time = 180_001L
        assertFalse(route.active())
    }

    @Test fun cachedFailureDoesNotTryTheAlternativeTwiceForOneRequest() {
        val route = MvCdnRoute { 100L }; route.recovered()
        val primary = FakeFactory(403); val alternative = FakeFactory(failure = IOException("temporary"))
        val source = MvCdnFallbackFactory(primary, route, alternative).createDataSource()
        try { source.open(media()); fail("Expected refusal") }
        catch (error: HttpDataSource.InvalidResponseCodeException) { assertEquals(403, error.responseCode) }
        finally { source.close() }
        assertEquals(1, alternative.opened.size)
        assertEquals(1, primary.opened.size)
        assertFalse(route.active())
    }

    @Test fun dnsAnswersCannotPointAtPrivateOrNonIpv4Targets() {
        for (value in listOf("0.0.0.0", "0.1.2.3", "100.64.0.1", "198.18.1.1", "127.0.0.1", "192.168.1.4", "10.0.0.1", "169.254.1.1", "224.0.0.1", "999.1.2.3", "::1", "media.test")) {
            assertNull(value, MvMediaDns.publicIpv4(value))
        }
        assertEquals("17.253.61.148", MvMediaDns.publicIpv4("17.253.61.148")?.hostAddress)
    }
}
