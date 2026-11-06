package com.applemusicktv.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class PageInsetTest {
    @Test fun gutterPreservesNarrowLayoutsAndCapsWideScreens() {
        for ((width, expected) in listOf(600f to 40f, 880f to 40f, 921.6f to 60.8f, 960f to 80f, 1040f to 120f, 1536f to 120f)) {
            assertEquals(expected, settingsPageInset(width.dp).value, 0.01f)
        }
    }
}
