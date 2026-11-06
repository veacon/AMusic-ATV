package com.applemusicktv.ui.viewmodel

import android.app.Application
import com.applemusicktv.data.di.NetworkModule
import com.applemusicktv.data.model.Song
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[23],application=Application::class)
class PlaybackStateWriterTest {
    private val song=Song("a","A","Artist","Album",240000,null,null,null)
    private fun prefs()=RuntimeEnvironment.getApplication().getSharedPreferences("bookmark-test",0).apply {edit().clear().commit()}
    private fun bookmark(position:Long=0,queue:List<Song> = listOf(song))=PlaybackBookmark(queue.first(),queue,0,position,true)
    private val moshi get()=NetworkModule.provideMoshi()

    @Test fun repeatedClockUpdatesEncodeQueueOnceAndStayOffTheCallingThread()=runBlocking {
        val prefs=prefs();val encodes=AtomicInteger();val caller=Thread.currentThread()
        val adapter=moshi.adapter<List<Song>>(com.squareup.moshi.Types.newParameterizedType(List::class.java,Song::class.java))
        val writer=PlaybackStateWriter(prefs,moshi,encodeQueue={queue->
            assertNotSame(caller,Thread.currentThread());encodes.incrementAndGet();adapter.toJson(queue)
        })
        repeat(12) {index->writer.awaitSaved(writer.submit(bookmark(index*10000L)))}
        writer.close();writer.worker.join()
        assertEquals(1,encodes.get());assertEquals(110000L,prefs.getLong("position_ms",-1))
        assertEquals(listOf(song),adapter.fromJson(prefs.getString("queue",null)!!))
    }
    @Test fun queueReorderMetadataChangeAndNewSongArePersistedWithTheMatchingPosition()=runBlocking {
        val prefs=prefs();val writer=PlaybackStateWriter(prefs,moshi)
        val other=song.copy(id="b",title="B")
        writer.awaitSaved(writer.submit(bookmark(1000)))
        writer.awaitSaved(writer.submit(bookmark(23000,listOf(other,song))))
        val enriched=other.copy(artworkUrl="https://example.com/cover.jpg")
        writer.submit(bookmark(45000,listOf(enriched,song)));writer.close();writer.worker.join()
        assertEquals(enriched,moshi.adapter(Song::class.java).fromJson(prefs.getString("song",null)!!))
        assertTrue(prefs.getString("queue",null)!!.contains("cover.jpg"))
        assertEquals(45000L,prefs.getLong("position_ms",-1))
    }
    @Test fun shutdownDrainsLatestBookmarkAndCannotOverwriteNewerPreferences()=runBlocking {
        val prefs=prefs();val writer=PlaybackStateWriter(prefs,moshi)
        repeat(100) {writer.submit(bookmark(it.toLong()))}
        prefs.edit().putBoolean("background_play",true).putBoolean("crossfade_enabled",false).putFloat("beat_intensity",2f).commit()
        writer.close();writer.worker.join()
        assertEquals(99L,prefs.getLong("position_ms",-1))
        assertTrue(prefs.getBoolean("background_play",false));assertFalse(prefs.getBoolean("crossfade_enabled",true))
        assertEquals(2f,prefs.getFloat("beat_intensity",0f))
    }
    @Test fun encodingFailurePreservesOldQueueAndNextBookmarkCanRetry()=runBlocking {
        val prefs=prefs();prefs.edit().putString("queue","old").putLong("position_ms",7).commit()
        var fail=true;val errors=AtomicInteger()
        val writer=PlaybackStateWriter(prefs,moshi,onError={errors.incrementAndGet()},encodeQueue={
            if(fail) error("test failure") else "[]"
        })
        writer.awaitSaved(writer.submit(bookmark(20)))
        assertEquals("old",prefs.getString("queue",null));assertEquals(7L,prefs.getLong("position_ms",-1))
        fail=false;writer.submit(bookmark(40));writer.close();writer.worker.join()
        assertEquals(1,errors.get());assertEquals("[]",prefs.getString("queue",null));assertEquals(40L,prefs.getLong("position_ms",-1))
    }
}
