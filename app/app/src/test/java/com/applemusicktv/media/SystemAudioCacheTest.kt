package com.applemusicktv.media

import android.app.Application
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.*
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import com.applemusicktv.data.cache.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[23],application=Application::class)
@androidx.annotation.OptIn(UnstableApi::class)
class SystemAudioCacheTest {
    private fun wb()=WebPlaybackResult("123","https://audio.example/album/list.m3u8",
        "data:;base64,AAAAAAAAAAAAAAAAAAAAAA==", """
        #EXTM3U
        #EXT-X-VERSION:7
        #EXT-X-TARGETDURATION:1
        #EXT-X-MEDIA-SEQUENCE:0
        #EXT-X-KEY:METHOD=ISO-23001-7,URI="data:;base64,AAAAAAAAAAAAAAAAAAAAAA=="
        #EXT-X-MAP:URI="audio.mp4?signature=preserved",BYTERANGE="4@0"
        #EXTINF:1,
        #EXT-X-BYTERANGE:4@4
        audio.mp4?signature=preserved
        #EXTINF:1,
        #EXT-X-BYTERANGE:4@8
        audio.mp4?signature=preserved
        #EXT-X-ENDLIST
        """.trimIndent())
    private fun store()=DiskBudgetStore(Files.createTempDirectory("system-audio").toFile(),20_000_000L,1_000_000L)
    private val payload=ByteArray(12){it.toByte()}

    @Test fun atomicBundlePersistsEncryptedPayloadAndRejectsAnotherAccountOrTruncation() {
        val disk=store();val cache=EncryptedAudioStore(disk)
        val fingerprint=EncryptedAudioStore.scope("test-account")
        val entry=cache.put("123",fingerprint,wb(),payload)!!
        assertEquals(12,entry.bytes)
        assertEquals(wb(),cache.read("123",fingerprint)!!.playback)
        assertNull(cache.read("123",EncryptedAudioStore.scope("other-account")))
        val bytes=disk.file(entry.name).readBytes()
        assertArrayEquals(payload,bytes.takeLast(12).toByteArray())
        assertFalse(String(bytes,Charsets.ISO_8859_1).contains("test-account"))
        disk.file(entry.name).writeBytes(bytes.dropLast(1).toByteArray())
        assertNull(cache.read("123",fingerprint))
    }

    @Test fun signedQueriesAreKeptAndMultipleResourcesOrLivePlaylistsAreNotBundled() {
        assertEquals("https://audio.example/album/audio.mp4?signature=preserved",EncryptedAudioStore.resource(wb()))
        assertNull(EncryptedAudioStore.resource(wb().copy(hlsText=wb().hlsText.replace("#EXT-X-ENDLIST",""))))
        assertNull(EncryptedAudioStore.resource(wb().copy(hlsText=wb().hlsText+"\nother.mp4")))
        assertEquals(12L,EncryptedAudioStore.requiredBytes(wb()))
        assertNull(EncryptedAudioStore(store()).put("123","account",wb(),ByteArray(8)))
    }

    @Test fun localHlsPreservesWidevineKeyAndByteRangesOnAndroidSix() {
        val local="amtv-audio://cache/encrypted_123.cache"
        val rewritten=AppleDirectClient().rewritePlaylistForExo(wb().hlsText,wb().hlsUrl)
        val playlist=EncryptedAudioStore.localPlaylist(rewritten,local)
        assertTrue(playlist.contains("METHOD=SAMPLE-AES-CTR"))
        val parsed=HlsPlaylistParser().parse(Uri.parse("amtv-playlist://audio/track.m3u8"),ByteArrayInputStream(playlist.toByteArray())) as HlsMediaPlaylist
        assertEquals(2,parsed.segments.size)
        assertEquals(local,parsed.segments[0].url)
        assertEquals(local,parsed.segments[0].initializationSegment!!.url)
        assertEquals(4L,parsed.segments[0].byteRangeOffset)
        assertEquals(4L,parsed.segments[0].byteRangeLength)
        assertNotNull(parsed.segments[0].drmInitData)
    }

    @Test fun dataSourceReadsEncryptedRangesAndPinsThemDuringCleanup() {
        val disk=store();val cache=EncryptedAudioStore(disk);val scope="account"
        val entry=cache.put("123",scope,wb(),payload)!!
        val source=SystemAudioDataSource(disk,scope,"#EXTM3U".toByteArray(),ByteArrayDataSource(byteArrayOf(0)))
        assertEquals(4L,source.open(DataSpec.Builder().setUri("amtv-audio://cache/${entry.name}").setPosition(4).setLength(4).build()))
        assertEquals(0,disk.clear().removedEntries)
        val bytes=ByteArray(4);assertEquals(4,source.read(bytes,0,4));assertArrayEquals(payload.copyOfRange(4,8),bytes)
        source.close();assertEquals(1,disk.clear().removedEntries)
    }

    @Test fun inlinePlaylistDoesNotNeedAQuotaWriteAndWrongAccountCannotReadAudio() {
        val disk=store();val entry=EncryptedAudioStore(disk).put("123","one",wb(),payload)!!
        val source=SystemAudioDataSource(disk,"two","#EXTM3U".toByteArray(),ByteArrayDataSource(byteArrayOf(0)))
        source.open(DataSpec(Uri.parse("amtv-playlist://audio/track.m3u8")))
        val bytes=ByteArray(7);assertEquals(7,source.read(bytes,0,7));source.close()
        try {source.open(DataSpec(Uri.parse("amtv-audio://cache/${entry.name}")));fail("must reject another account")}
        catch(expected:java.io.IOException) {}
        assertEquals(1,disk.clear().removedEntries)
    }

    @Test fun prefetchPromotesToForegroundAndReplayReusesOnePersistentDownload()=runBlocking {
        val disk=store();val cache=EncryptedAudioStore(disk)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val requests=AtomicInteger();val playback=AtomicInteger();val entered=CompletableDeferred<Unit>();val finish=java.util.concurrent.CountDownLatch(1)
        val http=OkHttpClient.Builder().addInterceptor {chain->
            assertEquals("preserved",chain.request().url.queryParameter("signature"))
            requests.incrementAndGet();entered.complete(Unit);finish.await()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(payload.toResponseBody()).build()
        }.build()
        val loader=SystemAudioLoader(scope,cache,{"account"},{"bearer"},{_,_,_->playback.incrementAndGet();wb()},{},http)
        try {
            withTimeout(10_000L) {
                val prefetch=async {loader.prepare("123",false)}
                entered.await()
                val foreground=async {loader.prepare("123",true)}
                delay(100);finish.countDown()
                assertNotNull(prefetch.await().entry);assertNotNull(foreground.await().entry)
                assertEquals(1,requests.get());assertEquals(1,playback.get())
                assertTrue(loader.prepare("123",true).hit)
                val restarted=SystemAudioLoader(scope,EncryptedAudioStore(disk),{"account"},{"bearer"},
                    {_,_,_->error("persistent cache must avoid webPlayback")},{},http)
                assertTrue(restarted.prepare("123",true).hit)
                assertEquals(1,requests.get())
            }
        } finally {finish.countDown();scope.cancel()}
    }

    @Test fun failedOrRefusedPrefetchDoesNotClaimReadyAndCanBeRetried()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val calls=AtomicInteger()
        val http=OkHttpClient.Builder().addInterceptor {chain->
            val good=calls.incrementAndGet()>1
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(if(good)200 else 503).message("test").body(payload.toResponseBody()).build()
        }.build()
        val loader=SystemAudioLoader(scope,EncryptedAudioStore(store()),{"account"},{"bearer"},{_,_,_->wb()},{},http)
        try {
            assertNull(loader.prepare("123",false).entry)
            assertNotNull(loader.prepare("123",false).entry)
            assertEquals(2,calls.get())
        } finally {scope.cancel()}
    }

    @Test fun foregroundDoesNotWaitIndefinitelyForWholeFileAndCancellationStopsObsoleteWork()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val entered=CompletableDeferred<Unit>();val finish=java.util.concurrent.CountDownLatch(1)
        val http=OkHttpClient.Builder().addInterceptor {chain->
            entered.complete(Unit);finish.await()
            if(chain.call().isCanceled()) throw java.io.IOException("cancelled")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(payload.toResponseBody()).build()
        }.build()
        val cache=EncryptedAudioStore(store())
        val loader=SystemAudioLoader(scope,cache,{"account"},{"bearer"},{_,_,_->wb()},{},http)
        try {
            withTimeout(5_000L) {
                val first=async {loader.prepare("123",true)};entered.await()
                assertNull(first.await().entry)
                loader.cancelExcept("different-song");finish.countDown();delay(100)
                assertNull(cache.read("123",EncryptedAudioStore.scope("account")))
            }
        } finally {finish.countDown();scope.cancel()}
    }
}
