package com.applemusicktv.ui.viewmodel

import java.io.IOException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class LyricsRetryTest {
    @Test fun immediateSuccessDoesNotStartRetryClockOrShowNotice()=runBlocking {
        var calls=0;var published=0
        fetchLyricsWithRetries({true},{calls++;listOf("ok")},{published++},wait={error("no wait")},now={error("no retry clock")})
        assertEquals(1,calls);assertEquals(1,published)
    }
    @Test fun emptyResponsesUseAtMostTwoBackgroundRetriesWithinThreeSeconds()=runBlocking {
        var calls=0;var exhausted=0;var clock=0L
        val waits=mutableListOf<Long>()
        fetchLyricsWithRetries<String>({true},{calls++;emptyList()},{fail("no lyrics")},
            exhausted={exhausted++},wait={waits+=it;clock+=it},now={clock})
        assertEquals(3,calls);assertEquals(1,exhausted)
        assertEquals(listOf(150L,150L),waits);assertTrue(clock<3000L)
    }
    @Test fun successOnFirstRetryStopsRemainingAttempt()=runBlocking {
        var calls=0;var published=0;var exhausted=0
        fetchLyricsWithRetries({true},{calls++;if(calls==2)listOf("ok")else emptyList()},{published++},exhausted={exhausted++},wait={})
        assertEquals(2,calls);assertEquals(1,published);assertEquals(0,exhausted)
    }
    @Test fun finalAllowedRetryCanSucceed()=runBlocking {
        var calls=0;var published=0
        fetchLyricsWithRetries({true},{calls++;if(calls==3)listOf("ok")else emptyList()},{published++},wait={})
        assertEquals(3,calls);assertEquals(1,published)
    }
    @Test fun transientExceptionsAreRetriedWithoutChangingPlayback()=runBlocking {
        var calls=0;var errors=0;var published=0
        fetchLyricsWithRetries({true},{calls++;if(calls<3)throw IOException("temporary")else listOf("ok")},{published++},
            report={_,_,e->if(e is IOException)errors++},wait={})
        assertEquals(3,calls);assertEquals(2,errors);assertEquals(1,published)
    }
    @Test fun retriesFinishWhilePanelIsAbsentAndNoticeStaysIdleUntilExhausted()=runBlocking {
        val scope=CoroutineScope(coroutineContext+SupervisorJob());val notice=LyricsFailureNotice(scope)
        var calls=0
        try {
            notice.beginSong("a")
            fetchLyricsWithRetries<String>({true},{calls++;emptyList()},{},
                report={attempt,count,_->notice.report(attempt,count);assertEquals(LyricsNoticePhase.Idle,notice.phase.value)},
                exhausted={notice.finishEmpty()},wait={})
            assertEquals(3,calls);assertEquals(LyricsNoticePhase.Pending,notice.phase.value)
        } finally {scope.cancel()}
    }
    @Test fun expiredBackgroundBudgetPreventsAnyAdditionalRequest()=runBlocking {
        var calls=0;var exhausted=0;var clock=0L
        fetchLyricsWithRetries<String>({true},{calls++;emptyList()},{},exhausted={exhausted++},wait={clock+=3001L},now={clock})
        assertEquals(1,calls);assertEquals(1,exhausted)
    }
    @Test fun inFlightRetryIsCancelledAtTheRemainingBackgroundDeadline()=runBlocking {
        var calls=0;var cancelled=0;var exhausted=0;var clock=0L
        fetchLyricsWithRetries<String>({true},{
            calls++;if(calls>1)try{delay(10_000)}finally{cancelled++;clock=3001L};emptyList()
        },{fail("no late publish")},exhausted={exhausted++},wait={clock=2980L},now={clock})
        assertEquals(2,calls);assertEquals(1,cancelled);assertEquals(1,exhausted)
    }
    @Test fun songChangeBeforeRetryNeverFetchesOrPublishesForOldSong()=runBlocking {
        var current=true;var calls=0;var exhausted=0
        fetchLyricsWithRetries<String>({current},{calls++;emptyList()},{fail("stale")},
            exhausted={exhausted++},wait={current=false})
        assertEquals(1,calls);assertEquals(0,exhausted)
    }
    @Test fun lateUncooperativeResultAfterWindowDoesNotPublish()=runBlocking {
        var calls=0;var clock=0L;var published=0
        fetchLyricsWithRetries({true},{calls++;if(calls==1)emptyList()else {clock=4000L;listOf("late")}},
            {published++},wait={},now={clock})
        assertEquals(2,calls);assertEquals(0,published)
    }
    @Test fun cancellingSongJobCancelsAnInFlightBackgroundFetch()=runBlocking {
        val waiting=CompletableDeferred<Unit>();var calls=0;var cancelled=0
        val job=launch {
            fetchLyricsWithRetries<String>({true},{calls++;if(calls>1)try{waiting.complete(Unit);awaitCancellation()}finally{cancelled++};emptyList()},{},wait={})
        }
        waiting.await();job.cancelAndJoin();assertEquals(2,calls);assertEquals(1,cancelled)
    }
    @Test fun alreadySupersededSongNeverStartsOrTouchesClock()=runBlocking {
        var calls=0
        fetchLyricsWithRetries<String>({false},{calls++;emptyList()},{},wait={},now={error("no clock")})
        assertEquals(0,calls)
    }
}
