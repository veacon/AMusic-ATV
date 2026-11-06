package com.applemusicktv.ui.viewmodel

import com.applemusicktv.data.model.Song
import com.applemusicktv.data.network.LyricLine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class PlayerLayoutTest {
    private fun song(id:String)=Song(id,"歌曲 $id","艺人","专辑",60_000,null,null,null)
    private fun state(id:String="a")=PlayerState(currentSong=song(id))
    private val lyrics=listOf(LyricLine(0,1000,"歌词"))

    @Test fun coldStartFailureBufferReadyAndSuccessfulRetryNeverCenter() {
        val initial=state()
        val sequence=listOf(initial,
            initial.copy(lyricsNotice=LyricsNoticePhase.Pending),
            initial.copy(lyricsNotice=LyricsNoticePhase.Visible),
            initial.copy(lyricsNotice=LyricsNoticePhase.Visible,isLoading=true),
            initial.copy(lyricsNotice=LyricsNoticePhase.Visible,isPlaying=true),
            // Success changes notice phase before publishing the nonempty lines.
            initial.copy(lyricsNotice=LyricsNoticePhase.Done),
            initial.copy(lyricsNotice=LyricsNoticePhase.Done,lyrics=lyrics))
        assertEquals(List(sequence.size){false},sequence.runningFold(false,::nextPlayerLayoutCentered).drop(1))
    }

    @Test fun unknownOnlyCentersAfterItsOwnEmptyNoticeExpires() {
        val a=state()
        for(phase in LyricsNoticePhase.entries)
            assertFalse(nextPlayerLayoutCentered(false,a.copy(lyricsNotice=phase)))
        assertFalse(nextPlayerLayoutCentered(false,a.copy(emptyLyricsNoticeCompletedFor="old")))
        assertTrue(nextPlayerLayoutCentered(false,a.copy(emptyLyricsNoticeCompletedFor="a",exhaustedEmptyLyricsFor="a")))
    }

    @Test fun emptyToEmptyKeepsCenterThroughoutUnknownBufferingAndNotice() {
        var centered=nextPlayerLayoutCentered(false,state().copy(emptyLyricsNoticeCompletedFor="a",exhaustedEmptyLyricsFor="a"))
        assertTrue(centered)
        for(phase in LyricsNoticePhase.entries) for(loading in listOf(false,true)) {
            centered=nextPlayerLayoutCentered(centered,state("b").copy(lyricsNotice=phase,isLoading=loading,
                emptyLyricsNoticeCompletedFor="a",exhaustedEmptyLyricsFor="a"))
            assertTrue(centered)
        }
        assertTrue(nextPlayerLayoutCentered(centered,state("b").copy(emptyLyricsNoticeCompletedFor="b",exhaustedEmptyLyricsFor="b")))
    }

    @Test fun emptyToLyricsWaitsForLinesEvenWhenNoticeReportsSuccessFirst() {
        val unknown=state("b").copy(lyricsNotice=LyricsNoticePhase.Done,emptyLyricsNoticeCompletedFor="a",exhaustedEmptyLyricsFor="a")
        assertTrue(nextPlayerLayoutCentered(true,unknown))
        assertFalse(nextPlayerLayoutCentered(true,unknown.copy(lyrics=lyrics)))
        assertFalse(nextPlayerLayoutCentered(false,unknown.copy(lyrics=lyrics,isLoading=true)))
    }

    @Test fun layoutSurvivesCollectorRemountAndNewSongWhilePageIsAbsent()=runBlocking {
        val scope=CoroutineScope(coroutineContext+SupervisorJob())
        try {
            val source=MutableStateFlow(state())
            val layout=source.stablePlayerLayout().stateIn(scope,SharingStarted.Eagerly,false)
            yield();assertFalse(layout.value)
            source.value=state().copy(emptyLyricsNoticeCompletedFor="a",exhaustedEmptyLyricsFor="a");yield();assertTrue(layout.value)
            source.value=state("b").copy(isLoading=true);yield()
            assertTrue(layout.first())
            source.value=state("b").copy(lyrics=lyrics);yield()
            assertFalse(layout.first())
        } finally {scope.cancel()}
    }

    @Test fun bufferingNeverChangesAResolvedLayoutAndStationPlaceholderKeepsIt() {
        for(previous in listOf(false,true)) {
            assertEquals(previous,nextPlayerLayoutCentered(previous,state().copy(isLoading=true)))
            assertFalse(nextPlayerLayoutCentered(previous,state().copy(
                stationLaunch=StationLaunch(1,"station","电台",null))))
        }
        assertTrue(nextPlayerLayoutCentered(false,state().copy(isLiveRadio=true)))
        assertFalse(nextPlayerLayoutCentered(true,PlayerState()))
    }

    @Test fun completionBelongsToTheSongAndRequiresTheThreeSecondNotice()=runBlocking {
        val scope=CoroutineScope(coroutineContext+SupervisorJob())
        val expiry=CompletableDeferred<Unit>();var waits=0
        val notice=LyricsFailureNotice(scope) { ms->assertEquals(3000L,ms);waits++;expiry.await() }
        try {
            notice.beginSong("a");notice.report(1,0)
            assertNull(notice.completedEmptySong.value)
            notice.finishEmpty();notice.showWhenReady();yield()
            assertEquals(LyricsNoticePhase.Visible,notice.phase.value)
            assertNull(notice.completedEmptySong.value)
            expiry.complete(Unit);yield()
            assertEquals(LyricsNoticePhase.Done,notice.phase.value)
            assertEquals("a",notice.completedEmptySong.value);assertEquals(1,waits)
            notice.beginSong("b")
            assertNull(notice.completedEmptySong.value)
            assertTrue(nextPlayerLayoutCentered(true,state("b")))
        } finally {scope.cancel()}
    }

    @Test fun successfulRetryOrSongChangeCancelsTheOldEmptyCompletion()=runBlocking {
        val scope=CoroutineScope(coroutineContext+SupervisorJob())
        val expiry=CompletableDeferred<Unit>()
        val notice=LyricsFailureNotice(scope) { expiry.await() }
        try {
            notice.beginSong("a");notice.report(1,0);notice.finishEmpty();notice.showWhenReady();yield()
            notice.report(2,39);expiry.complete(Unit);yield()
            assertNull(notice.completedEmptySong.value)
            notice.beginSong("b");notice.report(1,0);notice.finishEmpty();notice.showWhenReady();yield()
            assertEquals("b",notice.completedEmptySong.value)
            notice.report(3,12)
            assertNull(notice.completedEmptySong.value)
            notice.beginSong("c");notice.report(1,0);notice.finishEmpty();notice.showWhenReady()
            notice.beginSong("d");yield()
            assertNull(notice.completedEmptySong.value)
            assertEquals(LyricsNoticePhase.Idle,notice.phase.value)
        } finally {scope.cancel()}
    }
}
