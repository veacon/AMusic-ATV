package com.applemusicktv.ui.viewmodel

import com.applemusicktv.data.model.Song
import com.applemusicktv.media.RecoveredCatalogSong
import org.junit.Assert.*
import org.junit.Test

class RecoveredArtworkTest {
    private val song=Song("i.old","除此之外","范逸臣","范逸臣第一张个人专辑",269707,null,null,null)
    private val replacement=RecoveredCatalogSong("1854817602","https://is1-ssl.mzstatic.com/art/{w}x{h}bb.jpg","aabbcc")
    @Test fun fillsMissingArtAcrossDisplayAndQueuesWithoutReplacingIdentityOrPlaybackState() {
        val state=PlayerState(currentSong=song,song=song,queue=listOf(song),originalQueue=listOf(song),userQueue=listOf(song),
            isPlaying=true,progressMs=123456,isLoading=false)
        val patched=state.withRecoveredArtwork(song.id,replacement)
        assertEquals(listOf(song.id),patched.queue.map { it.id })
        assertEquals(song.id,patched.currentSong!!.id)
        assertEquals(song.title,patched.currentSong!!.title)
        assertEquals(song.durationMs,patched.currentSong!!.durationMs)
        val moshi=com.applemusicktv.data.di.NetworkModule.provideMoshi()
        val adapter=moshi.adapter(Song::class.java)
        assertEquals(patched.currentSong,adapter.fromJson(adapter.toJson(patched.currentSong)))
        val queueAdapter=moshi.adapter<List<Song>>(com.squareup.moshi.Types.newParameterizedType(List::class.java,Song::class.java))
        assertEquals(patched.queue,queueAdapter.fromJson(queueAdapter.toJson(patched.queue)))
        assertTrue(patched.isPlaying);assertFalse(patched.isLoading);assertEquals(123456L,patched.progressMs)
        for(row in listOf(patched.currentSong,patched.song)+patched.queue+patched.originalQueue+patched.userQueue) {
            assertEquals(replacement.artworkUrl,row!!.artworkUrl)
            assertEquals("aabbcc",row.artworkBgColor)
        }
    }
    @Test fun preservesExistingCoverAndColorAndDoesNotPatchOtherSongs() {
        val custom=song.copy(artworkUrl="https://example.com/custom.jpg",artworkBgColor="112233")
        val other=song.copy(id="i.other")
        val state=PlayerState(currentSong=custom,song=custom,queue=listOf(custom,other))
        val result=state.withRecoveredArtwork(song.id,replacement)
        assertEquals(custom,result.currentSong)
        assertEquals(other,result.queue[1])
        val existingColor=song.copy(artworkBgColor="112233")
        assertEquals("112233",PlayerState(currentSong=existingColor).withRecoveredArtwork(song.id,replacement).currentSong!!.artworkBgColor)
    }
    @Test fun staleSongOrMissingCatalogArtworkCannotChangeCurrentState() {
        val state=PlayerState(currentSong=song,song=song,queue=listOf(song))
        assertSame(state,state.withRecoveredArtwork("i.previous",replacement))
        assertSame(state,state.withRecoveredArtwork(song.id,replacement.copy(artworkUrl=null)))
    }
}
