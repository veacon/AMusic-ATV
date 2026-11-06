package com.applemusicktv.media

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import java.lang.reflect.InvocationTargetException
import java.net.ServerSocket
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28], manifest = Config.NONE)
class MvHttpRecoveryTest {
    @Test fun onlyEligible403ConsumesOneRecoveryUntilExplicitNewSelection() {
        val recovery = MvHttpRecovery()
        assertFalse(recovery.claimRefresh(401, "mvod.itunes.apple.com"))
        assertFalse(recovery.claimRefresh(403, "mvod.itunes.apple.com.attacker.test"))
        assertTrue(recovery.claimRefresh(403, "mvod.itunes.apple.com"))
        assertFalse(recovery.claimRefresh(403, "mvod.itunes.apple.com"))
        recovery.reset()
        assertTrue(recovery.claimRefresh(403, "mvod.itunes.apple.com"))
    }

    @Test fun ioAndDrmErrorsCannotEnterDecoderFallback() {
        assertFalse(MvHttpRecovery.isDecoderError(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
        assertFalse(MvHttpRecovery.isDecoderError(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))
        assertFalse(MvHttpRecovery.isDecoderError(PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED))
        assertTrue(MvHttpRecovery.isDecoderError(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
        assertTrue(MvHttpRecovery.isDecoderError(PlaybackException.ERROR_CODE_DECODING_FAILED))
    }

    @Test fun diagnosticsIdentifyTrackWithoutExposingPrivatePathOrSignedQuery() {
        ShadowLog.clear()
        val uri = "https://mvod.itunes.apple.com/private-path/segment.mp4?token=SECRET_TOKEN&signature=SECRET_SIGNATURE"
        val spec = DataSpec.Builder().setUri(uri).setPosition(1024).setLength(512).build()
        val failure = HttpDataSource.InvalidResponseCodeException(403, "Forbidden", null, emptyMap(), spec, byteArrayOf())
        val mv = MusicVideoResult("id", "", "#EXTM3U\n$uri\n", "", keyUri = "")
        MvHttpRecovery.logFailure(failure, mv)
        val text = ShadowLog.getLogsForTag("MVHTTP").joinToString { it.msg }
        assertTrue(text.contains("track=video"))
        assertTrue(text.contains("offset=1024 length=512"))
        listOf("SECRET_TOKEN", "SECRET_SIGNATURE", "private-path", "segment.mp4").forEach { assertFalse(text.contains(it)) }
        assertSame(failure, MvHttpRecovery.httpFailure(IllegalStateException("wrapper", failure)))
    }

    @Test fun catalogueDiagnosticsRejectSignedAndNonCatalogueUrls() {
        val public = "https://mvod.itunes.apple.com/itunes-assets/Video125/v4/aa/bb/cc/seg_1.mp4"
        assertNotNull(MvHttpRecovery.cataloguePath(public))
        assertNull(MvHttpRecovery.cataloguePath("$public?token=secret"))
        assertNull(MvHttpRecovery.cataloguePath(public.replace("mvod.itunes.apple.com", "other.test")))
        assertNull(MvHttpRecovery.cataloguePath("https://mvod.itunes.apple.com/private/token"))
    }

    private fun serve(status: Int, body: String, call: (String) -> Unit) {
        ServerSocket(0).use { server ->
            server.soTimeout = 5000
            val task = Thread {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    val bytes = body.toByteArray()
                    socket.getOutputStream().write(("HTTP/1.1 $status Response\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
                    socket.getOutputStream().write(bytes)
                }
            }.apply { isDaemon = true; start() }
            try { call("http://127.0.0.1:${server.localPort}/resource") }
            finally { task.join(6000) }
            assertFalse(task.isAlive)
        }
    }

    private fun fetchText(url: String): String {
        val method = AppleDirectClient::class.java.getDeclaredMethod("fetchText", String::class.java, Map::class.java)
        method.isAccessible = true
        return method.invoke(AppleDirectClient(), url, emptyMap<String,String>()) as String
    }

    @Test fun deniedPlaylistNeverBecomesAParsedErrorPage() = serve(403, "<html>Forbidden</html>") { url ->
        try { fetchText(url); fail("Expected HTTP failure") }
        catch (error: InvocationTargetException) {
            assertTrue(error.cause is java.io.IOException)
            assertEquals("Apple playlist HTTP 403", error.cause?.message)
        }
    }

    @Test fun successfulHtmlIsRejectedWhileValidHlsRemainsIntact() {
        serve(200, "<html>not a playlist</html>") { url ->
            try { fetchText(url); fail("Expected invalid playlist") }
            catch (error: InvocationTargetException) { assertEquals("Apple returned an invalid playlist", error.cause?.message) }
        }
        serve(200, "#EXTM3U\n#EXT-X-ENDLIST\n") { url -> assertEquals("#EXTM3U\n#EXT-X-ENDLIST\n", fetchText(url)) }
    }
}
