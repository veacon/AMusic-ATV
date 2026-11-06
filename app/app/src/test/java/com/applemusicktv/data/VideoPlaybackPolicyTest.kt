package com.applemusicktv.data

import com.applemusicktv.data.model.Song
import com.applemusicktv.data.network.*
import org.junit.Assert.*
import org.junit.Test

class VideoPlaybackPolicyTest {
    private fun song(id: String, type: String = "songs") = Song(id,id,"artist","album",1000,null,null,null,type=type)
    private fun card(id: String, type: String = "albums") = AlbumDto(id,id,"artist",null,type,null,null)
    private fun dto(id: String, type: String = "songs") = SongDto(id,type,id,"artist",albumName="",durationMs=1000,artworkUrl=null,artworkBgColor=null,previewUrl=null,previewHlsUrl=null)
    @Test fun blocksAllVideoTypesButKeepsAudioAndMotionMetadata() {
        for (type in listOf("music-videos","library-music-videos","uploaded-videos","videos")) assertFalse(VideoPlaybackPolicy.allows("123",type))
        for (type in listOf("songs","albums","playlists","stations")) assertTrue(VideoPlaybackPolicy.allows("123",type))
        assertTrue(VideoPlaybackPolicy.allows(song("Video Games")))
    }
    @Test fun groupingIsHiddenWithoutHidingUnrelatedCharts() {
        assertFalse(VideoPlaybackPolicy.allows("34","grouping"));assertFalse(VideoPlaybackPolicy.allows("grouping-34","curators"))
        assertTrue(VideoPlaybackPolicy.allows("6503108310","room"))
    }
    @Test fun mixedQueuesPreserveSelectedAudioIndexAfterVideosAreRemoved() {
        val s=VideoPlaybackPolicy.selection(listOf(song("v","music-videos"),song("a"),song("b")),2)
        assertEquals(listOf("a","b"),s.songs.map { it.id });assertEquals(1,s.index);assertEquals("b",s.song?.id)
    }
    @Test fun selectedVideoMovesToNextAudioWithoutChangingOrder() {
        val s=VideoPlaybackPolicy.selection(listOf(song("a"),song("v","uploaded-videos"),song("b")),1)
        assertEquals("b",s.song?.id);assertEquals(1,s.index)
    }
    @Test fun duplicateAudioOccurrencesKeepTheExactSelectedOccurrence() {
        val a=song("a");val s=VideoPlaybackPolicy.selection(listOf(a,song("v","videos"),a),2)
        assertEquals(1,s.index);assertEquals(2,s.songs.size)
    }
    @Test fun allVideoOrEmptyQueueHasNoPlayableItem() {
        for (q in listOf(emptyList(),listOf(song("v","music-videos")))) {
            val s=VideoPlaybackPolicy.selection(q,50);assertTrue(s.songs.isEmpty());assertNull(s.song)
        }
    }
    @Test fun restoreVideoBookmarkKeepsAudioAndDoesNotRestoreVideo() {
        val video=song("v","library-music-videos");val s=VideoPlaybackPolicy.restored(video,listOf(video,song("a")),0)
        assertEquals("a",s.song?.id);assertTrue(s.songs.all(VideoPlaybackPolicy::allows))
        assertNull(VideoPlaybackPolicy.restored(video,listOf(video),0).song)
    }
    @Test fun restoresAudioEvenWhenOldQueueIsEmpty() {
        val a=song("a");assertEquals(a,VideoPlaybackPolicy.restored(a,emptyList(),0).song)
    }
    @Test fun editorialSectionsDropVideoOnlyRowsAndVideoCardsInsideMixedRows() {
        val source=HomeResponse(listOf(HomeSection("Videos",videos=listOf(dto("v","music-videos"))),
            HomeSection("Mixed",albums=listOf(card("a"),card("v","uploaded-videos")),videos=listOf(dto("v","music-videos")),roomId="123",style="spotlight")))
        val out=VideoPlaybackPolicy.home(source)
        assertEquals(1,out.sections.size);assertEquals(listOf("a"),out.sections.single().albums.map { it.id })
        assertTrue(out.sections.single().videos.isEmpty());assertEquals("123",out.sections.single().roomId);assertEquals("spotlight",out.sections.single().style)
    }
    @Test fun artistKeepsAlbumsAndTopSongsButOmitsVideos() {
        val album=card("a");val out=VideoPlaybackPolicy.artist(ArtistFullDto("1","artist",null,topSongs=listOf(dto("s"),dto("v","music-videos")),musicVideos=listOf(dto("v","music-videos")),albums=listOf(album)))
        assertTrue(out.musicVideos.isEmpty());assertEquals(listOf(album),out.albums);assertEquals(listOf("s"),out.topSongs.map { it.id })
    }
}
