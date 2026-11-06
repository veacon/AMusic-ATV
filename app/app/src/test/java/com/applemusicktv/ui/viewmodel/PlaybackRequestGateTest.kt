package com.applemusicktv.ui.viewmodel

import org.junit.Assert.*
import org.junit.Test

class PlaybackRequestGateTest {
    @Test fun repeatedStationTapsKeepOneRequest() {
        val gate = PlaybackRequestGate()
        val first = gate.station("station-a")!!
        repeat(5) { assertNull(gate.station("station-a")) }
        assertTrue(gate.isCurrent(first))
    }

    @Test fun songSelectionRejectsLateStationResponse() {
        val gate = PlaybackRequestGate()
        val radio = gate.station("station-a")!!
        val song = gate.select()
        assertFalse(gate.isCurrent(radio))
        assertTrue(gate.isCurrent(song))
    }

    @Test fun olderStationCompletionCannotUnlockNewerStation() {
        val gate = PlaybackRequestGate()
        val old = gate.station("station-a")!!
        val latest = gate.station("station-b")!!
        gate.finishStation(old)
        assertFalse(gate.isCurrent(old))
        assertTrue(gate.isCurrent(latest))
        assertNull(gate.station("station-b"))
    }

    @Test fun completedStationCanBeSelectedAgain() {
        val gate = PlaybackRequestGate()
        val old = gate.station("station-a")!!
        gate.finishStation(old)
        val latest = gate.station("station-a")!!
        assertTrue(latest > old)
    }

    @Test fun pendingPauseSurvivesSourceCompletion() {
        val preparation = SourcePreparation()
        preparation.begin(1)
        preparation.toggle()
        assertTrue(preparation.isPending)
        assertEquals(false, preparation.finish(1))
        assertFalse(preparation.isPending)
    }

    @Test fun pendingPauseThenPlayStartsTheSelectedSource() {
        val preparation = SourcePreparation()
        preparation.begin(1)
        preparation.toggle()
        preparation.toggle()
        assertEquals(true, preparation.finish(1))
    }

    @Test fun skippedSourceCannotConsumeNewSongIntent() {
        val preparation = SourcePreparation()
        preparation.begin(1)
        preparation.begin(2)
        preparation.pause()
        assertNull(preparation.finish(1))
        assertTrue(preparation.isPending)
        assertEquals(false, preparation.finish(2))
    }

    @Test fun cancellationPreventsOldSourceFromStarting() {
        val preparation = SourcePreparation()
        preparation.begin(1)
        preparation.cancel()
        assertNull(preparation.finish(1))
    }
}
