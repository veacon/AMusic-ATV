package com.applemusicktv.ui.viewmodel

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class LyricsFailureNoticeTest {
    @Test fun firstAndIntermediateFailuresCannotShowTheNotice()=runBlocking {
        val scope=CoroutineScope(coroutineContext+SupervisorJob());var timers=0
        val notice=LyricsFailureNotice(scope) {timers++}
        try {
            notice.beginSong("a")
            for(attempt in 1..3) {
                notice.report(attempt,0);notice.showWhenReady()
                assertEquals(LyricsNoticePhase.Idle,notice.phase.value)
            }
            yield();assertEquals(0,timers)
            notice.finishEmpty();assertEquals(LyricsNoticePhase.Pending,notice.phase.value)
        } finally {scope.cancel()}
    }
    @Test fun finalEmptyWaitsForReadinessThenShowsOneThreeSecondNotice()=runBlocking {
        val scope=CoroutineScope(coroutineContext+SupervisorJob());var timers=0
        val expiry=CompletableDeferred<Unit>();val notice=LyricsFailureNotice(scope) {ms->assertEquals(3000L,ms);timers++;expiry.await()}
        try {
            notice.beginSong("a");notice.finishEmpty();yield()
            assertEquals(LyricsNoticePhase.Pending,notice.phase.value);assertEquals(0,timers)
            notice.showWhenReady();notice.showWhenReady();notice.finishEmpty();yield()
            assertEquals(LyricsNoticePhase.Visible,notice.phase.value);assertEquals(1,timers)
            assertNull(notice.completedEmptySong.value)
            expiry.complete(Unit);yield()
            assertEquals(LyricsNoticePhase.Done,notice.phase.value);assertEquals("a",notice.completedEmptySong.value)
            notice.showWhenReady();yield();assertEquals(1,timers)
        } finally {scope.cancel()}
    }
    @Test fun retrySuccessNeverShowsEmptyNotice()=runBlocking {
        val scope=CoroutineScope(coroutineContext+SupervisorJob());val notice=LyricsFailureNotice(scope)
        try {
            notice.beginSong("a");notice.report(1,0);notice.report(2,55);notice.showWhenReady()
            assertEquals(LyricsNoticePhase.Done,notice.phase.value);assertNull(notice.completedEmptySong.value)
            notice.finishEmpty();assertEquals(LyricsNoticePhase.Done,notice.phase.value)
        } finally {scope.cancel()}
    }
    @Test fun staleUiRequestCannotStartAnotherSongsNoticeOrSameSongsNewSelection()=runBlocking {
        val scope=CoroutineScope(coroutineContext+SupervisorJob());val notice=LyricsFailureNotice(scope)
        try {
            notice.beginSong("a");val old=notice.generation
            notice.beginSong("b");notice.finishEmpty();notice.showWhenReady("a",old)
            assertEquals(LyricsNoticePhase.Pending,notice.phase.value)
            notice.beginSong("a");notice.finishEmpty();notice.showWhenReady("a",old)
            assertEquals(LyricsNoticePhase.Pending,notice.phase.value)
        } finally {scope.cancel()}
    }
    @Test fun cancelledOldTimerCannotPublishIntoNewSelectionEvenIfWaitIgnoresCancellation()=runBlocking {
        val scope=CoroutineScope(coroutineContext+SupervisorJob());val expiry=CompletableDeferred<Unit>()
        val notice=LyricsFailureNotice(scope) {withContext(NonCancellable){expiry.await()}}
        try {
            notice.beginSong("a");notice.finishEmpty();notice.showWhenReady();yield()
            val old=notice.generation;notice.beginSong("a");expiry.complete(Unit);yield();yield()
            assertEquals(LyricsNoticePhase.Idle,notice.phase.value);assertNull(notice.completedEmptySong.value)
            assertTrue(notice.status.value.generation>old)
        } finally {expiry.complete(Unit);scope.cancel()}
    }
    @Test fun statusAtomicallyOwnsPhaseAndCompletion()=runBlocking {
        val scope=CoroutineScope(coroutineContext+SupervisorJob());val expiry=CompletableDeferred<Unit>()
        val notice=LyricsFailureNotice(scope) {expiry.await()}
        try {
            notice.beginSong("a");notice.finishEmpty();notice.showWhenReady();yield()
            assertEquals("a",notice.status.value.songId);assertEquals(LyricsNoticePhase.Visible,notice.status.value.phase)
            expiry.complete(Unit);yield()
            assertEquals("a",notice.status.value.completedEmptySong)
            notice.beginSong("b")
            assertEquals("b",notice.status.value.songId);assertNull(notice.status.value.completedEmptySong)
            assertEquals(LyricsNoticePhase.Idle,notice.status.value.phase)
        } finally {scope.cancel()}
    }
}
