package com.applemusicktv.ui.screens

import org.junit.Assert.*
import org.junit.Test

class LyricDotsMotionTest {
    @Test fun fadeIsContinuousAndInvisibleOutsideGap() {
        assertEquals(0f, lyricDotsFrame(-1, 0, 4000).groupAlpha, 0f)
        assertEquals(0f, lyricDotsFrame(0, 0, 4000).groupAlpha, 0f)
        assertEquals(1f, lyricDotsFrame(400, 0, 4000).groupAlpha, .0001f)
        assertEquals(0f, lyricDotsFrame(4000, 0, 4000).groupAlpha, 0f)
        assertEquals(0f, lyricDotsFrame(4001, 0, 4000).groupAlpha, 0f)
        assertEquals(0f, lyricDotsFrame(1, 2, 2).groupAlpha, 0f)
        var previous = 0f
        for (t in 0L..400L step 16) {
            val next = lyricDotsFrame(t, 0, 4000).groupAlpha
            assertTrue(next >= previous)
            assertTrue(next - previous < .07f)
            previous = next
        }
    }
    @Test fun dotsLightInSequenceAndRemainBoundedAcrossGap() {
        val frame = lyricDotsFrame(1200, 0, 4000)
        assertTrue(frame.dotAlphas[0] > frame.dotAlphas[1])
        assertTrue(frame.dotAlphas[1] > frame.dotAlphas[2])
        for (t in 0L..4000L step 16) {
            val state = lyricDotsFrame(t, 0, 4000)
            assertTrue(state.groupAlpha in 0f..1f)
            assertTrue(state.dotAlphas.all { it in .30f..1f })
        }
        assertEquals(lyricDotsFrame(1234, 0, 4000), lyricDotsFrame(1234, 0, 4000))
    }
    @Test fun longAndShortGapsKeepFadesBoundedAndSeekUsesAbsolutePosition() {
        for (duration in listOf(4000L, 8000L, 60000L)) {
            val start = 10000L
            val end = start + duration
            assertEquals(0f, lyricDotsFrame(start - 1, start, end).groupAlpha, 0f)
            assertEquals(0f, lyricDotsFrame(end, start, end).groupAlpha, 0f)
            assertEquals(0f, lyricDotsFrame(end + 500, start, end).groupAlpha, 0f)
            var last = 0f
            for (elapsed in 0L..400L step 20) {
                val current = lyricDotsFrame(start + elapsed, start, end).groupAlpha
                assertTrue(current >= last)
                last = current
            }
            last = 1f
            for (remaining in 500L downTo 0L step 20) {
                val current = lyricDotsFrame(end - remaining, start, end).groupAlpha
                assertTrue(current <= last)
                last = current
            }
            val paused = lyricDotsFrame(start + duration / 2, start, end)
            lyricDotsFrame(end - 200, start, end) // seeking away and back
            assertEquals(paused, lyricDotsFrame(start + duration / 2, start, end))
        }
    }
}
