package com.applemusicktv.ui.background

import org.junit.Assert.*
import org.junit.Test

class BackgroundFrameCadenceTest {
    @Test fun actualPadNominal60HzPeriodsNeverDropEveryOtherCallback() {
        for (period in listOf(16_658_000L,16_660_000L,16_661_000L,16_663_000L,16_666_666L,16_683_350L)) {
            val cadence=BackgroundFrameCadence()
            for (i in 0..3600) assertTrue("period=$period frame=$i",
                cadence.shouldRender(1_000_000_000L+i*period,60f))
        }
    }

    @Test fun real60HzJitterAndLateCallbacksStillFollowTheDisplay() {
        val cadence=BackgroundFrameCadence();var time=1_000_000_000L
        val intervals=listOf(16_610_000L,16_710_000L,16_661_000L,33_322_000L)
        repeat(600) { i ->time+=intervals[i%intervals.size];assertTrue(cadence.shouldRender(time,60f)) }
    }

    @Test fun fiftyAnd5994HzKeepEveryAvailableFrame() {
        for (hz in listOf(50f,59.94f,60.02f)) {
            val cadence=BackgroundFrameCadence()
            repeat(600) {assertTrue(cadence.shouldRender(1_000_000_000L+(it*1e9/hz).toLong(),hz))}
        }
    }

    @Test fun fasterDisplaysCarryRemaindersToMaintainSixtyRatherThanFortyFive() {
        for (hz in listOf(90,120,144,165)) {
            val cadence=BackgroundFrameCadence()
            val renders=(0 until hz*60).count {cadence.shouldRender(1_000_000_000L+it*1_000_000_000L/hz,hz.toFloat())}
            assertTrue("$hz Hz rendered $renders in 60s",renders in 3599..3601)
        }
    }

    @Test fun smallHighRefreshJitterDoesNotLoseTheFractionalBudget() {
        val cadence=BackgroundFrameCadence();var renders=0
        repeat(1200) {i ->
            val jitter=when(i%3){0->-80_000L;1->60_000L;else->20_000L}
            if(cadence.shouldRender(1_000_000_000L+i*1_000_000_000L/120+jitter,120f))renders++
        }
        assertTrue("renders=$renders",renders in 599..601)
    }

    @Test fun refreshRateSwitchesStartFreshAndPreserveTheTarget() {
        val cadence=BackgroundFrameCadence();var time=1_000_000_000L
        for(hz in listOf(120,60,90,144,50,165,60)) {
            var count=0
            repeat(hz*3) {time+=1_000_000_000L/hz;if(cadence.shouldRender(time,hz.toFloat()))count++}
            assertTrue("$hz Hz renders=$count",count in minOf(hz,60)*3-1..minOf(hz,60)*3+1)
        }
    }

    @Test fun pauseResetAndTimestampRegressionCannotLeaveAFrameBacklog() {
        val cadence=BackgroundFrameCadence()
        assertTrue(cadence.shouldRender(1_000_000_000L,120f))
        assertFalse(cadence.shouldRender(1_008_333_333L,120f))
        assertTrue(cadence.shouldRender(2_000_000_000L,120f))
        assertFalse(cadence.shouldRender(2_008_333_333L,120f))
        assertTrue(cadence.shouldRender(500_000_000L,120f))
        cadence.reset()
        assertTrue(cadence.shouldRender(600_000_000L,120f))
        assertFalse(cadence.shouldRender(608_333_333L,120f))
    }

    @Test fun unavailableOrInvalidDisplayRateSafelyFollowsNominalSixty() {
        for(hz in listOf(Float.NaN,Float.POSITIVE_INFINITY,0f,-1f)) {
            val cadence=BackgroundFrameCadence()
            repeat(120) {assertTrue(cadence.shouldRender(1_000_000_000L+it*16_661_000L,hz))}
        }
    }
}
