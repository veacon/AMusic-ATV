package com.applemusicktv.data.fonts

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FontFileStoreTest {
    private var previousLanguage = com.applemusicktv.i18n.LanguageChoice.System
    @org.junit.Before fun useChineseFixtureLanguage() {
        previousLanguage = com.applemusicktv.i18n.AppLanguage.choice
        com.applemusicktv.i18n.AppLanguage.select(com.applemusicktv.i18n.LanguageChoice.Chinese)
    }
    @org.junit.After fun restoreFixtureLanguage() {
        com.applemusicktv.i18n.AppLanguage.select(previousLanguage)
    }

    @get:Rule val temporary = TemporaryFolder()
    private val bytes = "complete font bytes".toByteArray()
    private fun asset(name: String = "font.otf", data: ByteArray = bytes) = FontAsset(name, data.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) },
        listOf("https://primary.test/$name", "https://backup.test/$name"))
    private fun response(request: Request, body: ByteArray = bytes, code: Int = 200, range: String? = null) =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
            .apply { if (range != null) header("Content-Range", range) }
            .body(body.toResponseBody("font/otf".toMediaType())).build()
    private fun store(assets: List<FontAsset> = listOf(asset()), validate: (File) -> Unit = {},
        reply: (Request) -> Response): FontFileStore = FontFileStore(temporary.newFolder(), assets,
        OkHttpClient.Builder().addInterceptor { reply(it.request()) }.build(), validate)

    @Test fun realManifestHasThreeUnchangedFontsAndOrderedDomesticSources() {
        assertEquals(49_963_244L, FontAssets.totalBytes)
        assertEquals(listOf("NotoSansCJKsc-Regular.otf", "NotoSansCJKsc-Medium.otf", "NotoSansCJKsc-Bold.otf"), FontAssets.all.map { it.fileName })
        FontAssets.all.forEach { a ->
            assertEquals(64, a.sha256.length)
            assertTrue(a.urls.all { it.startsWith("https://") && it.endsWith(a.fileName) })
            assertTrue(a.urls.last().contains(FontAssets.VERSION))
            assertEquals(listOf("mirrors.nju.edu.cn", "mirrors.tuna.tsinghua.edu.cn", "cdn.jsdelivr.net"), a.urls.map { Request.Builder().url(it).build().url.host })
        }
    }

    @Test fun allFontsMustValidateBeforeReadyAndProgressUsesTotalBytes() {
        val a = asset("a"); val b = asset("b", bytes + bytes)
        val seen = mutableListOf<Long>(); var validated = 0
        val store = store(listOf(a,b), { validated++ }) { r -> response(r, if (r.url.encodedPath == "/a") bytes else bytes + bytes) }
        val files = store.prepare(seen::add)
        assertEquals(2, validated); assertEquals(2, files.size)
        assertEquals(a.bytes + b.bytes, seen.last())
        assertTrue(seen.contains(a.bytes))
        assertEquals(50, FontDownloadState(25_000_000, 50_000_000).percent)
        assertEquals(99, FontDownloadState(50_000_000, 50_000_000).percent)
        assertEquals(100, FontDownloadState(1,1,FontDownloadPhase.Ready).percent)
    }

    @Test fun primaryFailureFallsBackWithoutLosingPartialBytes() {
        val requests = mutableListOf<Request>()
        val store = store { r ->
            requests += r
            if (r.url.host == "primary.test") throw IOException("offline")
            response(r, bytes.copyOfRange(4, bytes.size), 206, "bytes 4-${bytes.lastIndex}/${bytes.size}")
        }
        File(store.directory,"font.otf.part").writeBytes(bytes.copyOf(4))
        val files = store.prepare {}
        assertArrayEquals(bytes, files.single().readBytes())
        assertEquals(listOf("primary.test","backup.test"), requests.map { it.url.host })
        assertTrue(requests.all { it.header("Range") == "bytes=4-" && it.header("Accept-Encoding") == null })
    }

    @Test fun interruptedBodyRetainsActualCapacityAndBackupResumesAtThatByte() {
        val requests = mutableListOf<Request>()
        val store = store { r ->
            requests += r
            if (r.url.host == "backup.test") response(r, bytes.copyOfRange(5,bytes.size),206,"bytes 5-${bytes.lastIndex}/${bytes.size}")
            else response(r).newBuilder().body(object : ResponseBody() {
                override fun contentType() = "font/otf".toMediaType()
                override fun contentLength() = bytes.size.toLong()
                override fun source() = Buffer().write(bytes.copyOf(5))
            }).build()
        }
        assertArrayEquals(bytes, store.prepare {}.single().readBytes())
        assertEquals("bytes=5-", requests.last().header("Range"))
    }

    @Test fun ignoredRangeRestartsRatherThanAppending() {
        val store = store { r -> response(r) }
        File(store.directory,"font.otf.part").writeText("bad")
        assertArrayEquals(bytes, store.prepare {}.single().readBytes())
    }

    @Test fun rangeNotSatisfiableRetriesSameSourceInFullOnce() {
        val requests = mutableListOf<Request>()
        val store = store { r ->
            requests += r
            if (r.header("Range") != null) response(r,ByteArray(0),416) else response(r)
        }
        File(store.directory,"font.otf.part").writeBytes(bytes.copyOf(3))
        assertArrayEquals(bytes,store.prepare {}.single().readBytes())
        assertEquals(2,requests.size)
        assertTrue(requests.all { it.url.host == "primary.test" })
        assertEquals("bytes=3-",requests.first().header("Range"))
        assertNull(requests.last().header("Range"))
    }

    @Test fun wrongRangeIsRejectedBeforeWritingAndBackupCanContinue() {
        val store = store { r ->
            if (r.url.host == "primary.test") response(r, bytes.copyOfRange(3, bytes.size),206,"bytes 0-15/${bytes.size}")
            else response(r, bytes.copyOfRange(3,bytes.size),206,"bytes 3-${bytes.lastIndex}/${bytes.size}")
        }
        File(store.directory,"font.otf.part").writeBytes(bytes.copyOf(3))
        assertArrayEquals(bytes, store.prepare {}.single().readBytes())
    }

    @Test fun incorrectContentCannotBecomeReadyJustBecauseLengthMatches() {
        val seen = mutableListOf<String>()
        val store = store { r -> seen += r.url.host; response(r, if (r.url.host == "primary.test") ByteArray(bytes.size) else bytes) }
        assertArrayEquals(bytes, store.prepare {}.single().readBytes())
        assertEquals(listOf("primary.test","backup.test"),seen)
    }

    @Test fun completedButBadPartialIsDiscardedBeforeFallback() {
        val requests = mutableListOf<Request>()
        val store = store { r -> requests += r; response(r) }
        File(store.directory,"font.otf.part").writeBytes(ByteArray(bytes.size))
        assertArrayEquals(bytes, store.prepare {}.single().readBytes())
        assertEquals("backup.test",requests.single().url.host)
        assertNull(requests.single().header("Range"))
    }

    @Test fun validatedFontsAreReusedAfterProcessRestartWhileOffline() {
        val original = store { response(it) }; original.prepare {}
        var validations = 0
        val offline = FontFileStore(original.directory,listOf(asset()),OkHttpClient.Builder()
            .addInterceptor { throw AssertionError("Must not connect") }.build(), { validations++ })
        assertArrayEquals(bytes, offline.prepare {}.single().readBytes())
        assertEquals(1, validations)
    }

    @Test fun corruptedPersistentFileIsDetectedAfterRestart() {
        val original = store { response(it) }; original.prepare {}.single().writeBytes(ByteArray(bytes.size))
        var requests = 0
        val repaired = FontFileStore(original.directory,listOf(asset()),OkHttpClient.Builder()
            .addInterceptor { requests++; response(it.request()) }.build())
        assertArrayEquals(bytes,repaired.prepare {}.single().readBytes())
        assertEquals(1, requests)
    }

    @Test fun platformFontFailureNeverPublishesACompletedFile() {
        val store = store(validate = { throw IOException("Not a supported font") }) { response(it) }
        try { store.prepare {}; fail("Expected failure") } catch (_: IOException) { }
        assertFalse(File(store.directory,"font.otf").exists())
        assertFalse(File(store.directory,"font.otf.part").exists())
    }

    @Test fun allSourcesFailThenRetryExactlyOneMinuteLater() = runBlocking {
        var online = false; var calls = 0
        val store = store { r -> calls++; if (!online) throw IOException("No network"); response(r) }
        val waits = mutableListOf<Long>()
        lateinit var session: FontDownloadSession
        session = FontDownloadSession(store, waitToRetry = {
            waits += it
            assertEquals("字体下载失败，稍后将自动重试", session.state.value.lyricsMessage)
            assertFalse(session.state.value.ready)
            online = true
        })
        session.run()
        assertEquals(listOf(60_000L), waits)
        assertEquals(3, calls)
        assertTrue(session.state.value.ready)
    }

    @Test fun retryCancellationDoesNotLaunchAnotherRound() = runBlocking {
        var calls = 0
        val store = store { calls++; throw IOException("offline") }
        val session = FontDownloadSession(store, waitToRetry = { throw CancellationException("process ended") })
        try { session.run(); fail("Expected cancellation") } catch (_: CancellationException) { }
        assertEquals(2, calls)
        assertEquals(FontDownloadPhase.Failed,session.state.value.phase)
    }
}
