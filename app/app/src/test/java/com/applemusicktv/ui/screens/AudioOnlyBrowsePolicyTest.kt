package com.applemusicktv.ui.screens

import com.applemusicktv.data.model.Album
import com.applemusicktv.data.model.Song
import org.junit.Assert.*
import org.junit.Test

class AudioOnlyBrowsePolicyTest {
    private val video=Song("v","video","artist","",1000,null,null,null,type="music-videos")
    private fun album(id: String,type: String="albums")=Album(id,id,"artist",artworkUrl=null,type=type)
    @Test fun cachedVideoShelvesCannotReturnDuringNetworkFailure() {
        assertTrue(BrowseFeedPolicy.select(emptyList(),listOf(BrowseShelf("Video",videos=listOf(video)))).isEmpty())
    }
    @Test fun mixedShelvesRetainAudioCardsAndRemoveVideoCardsAndRows() {
        val out=BrowseFeedPolicy.select(listOf(BrowseShelf("Mixed",listOf(album("a"),album("v","music-videos")),listOf(video))),emptyList())
        assertEquals(listOf("a"),out.single().albums.map { it.id });assertTrue(out.single().videos.isEmpty())
    }
    @Test fun validCachedAudioStillSurvivesEmptyRefresh() {
        val cached=BrowseShelf("Albums",listOf(album("a")))
        assertEquals(listOf(cached),BrowseFeedPolicy.select(emptyList(),listOf(cached)))
    }
}
