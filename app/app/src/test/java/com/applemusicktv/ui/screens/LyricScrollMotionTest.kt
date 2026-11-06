package com.applemusicktv.ui.screens

import org.junit.Assert.*
import org.junit.Test

class LyricScrollMotionTest {
    @Test fun curveStartsAndEndsAtRestAndMovesSmoothly() {
        val e = lyricScrollEasing(180f, 0f)
        assertEquals(550, LYRIC_SCROLL_DURATION_MS)
        assertEquals(0f, e.transform(0f), 0.0001f)
        assertEquals(1f, e.transform(1f), 0.0001f)
        assertTrue(e.transform(.1f) < .05f)
        assertTrue(e.transform(.9f) > .95f)
        var last = 0f
        for (i in 1..100) { val next = e.transform(i / 100f); assertTrue(next >= last); last = next }
    }
    @Test fun interruptedForwardScrollStartsWithForwardVelocityAndReverseDoesNotOvershoot() {
        val normal = lyricScrollEasing(180f, 0f)
        val continued = lyricScrollEasing(180f, 200f)
        assertTrue(continued.transform(.02f) > normal.transform(.02f))
        val reverse = lyricScrollEasing(-180f, 200f)
        assertEquals(normal.transform(.02f), reverse.transform(.02f), .0001f)
        assertEquals(1f, continued.transform(1f), .0001f)
    }
}
