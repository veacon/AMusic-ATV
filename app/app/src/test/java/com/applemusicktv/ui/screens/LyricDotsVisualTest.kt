package com.applemusicktv.ui.screens

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.applemusicktv.data.network.LyricLine
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w960dp-h540dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LyricDotsVisualTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun captureWaitingDotFrames() {
        compose.mainClock.autoAdvance = false
        var pos by mutableLongStateOf(0L)
        compose.setContent {
            Box(Modifier.size(360.dp, 420.dp).background(Color.Black).testTag("panel")) {
                LyricsPanel(listOf(LyricLine(8000L, 10000L, "等待歌词")), rememberUpdatedState(pos), 0L, {})
            }
        }
        for (ms in listOf(200L, 400L, 3200L, 6500L, 7500L, 7800L, 7900L)) {
            compose.runOnIdle { pos = ms; androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }
            compose.mainClock.advanceTimeBy(250); compose.waitForIdle()
            compose.runOnIdle {
                val view = compose.activity.window.decorView
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(android.graphics.Canvas(bitmap))
                for (cx in listOf(17, 37, 57)) {
                    val center = android.graphics.Color.red(bitmap.getPixel(cx, 37))
                    val corner = android.graphics.Color.red(bitmap.getPixel(cx - 5, 32))
                    assertTrue("dot center must stay visible at $ms", center > 0)
                    assertEquals("circle corner must stay clear at $ms", 0, corner)
                }
                File(com.applemusicktv.testing.TestArtifacts.root,"amtv-v65-dots-$ms.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }
    @Test fun thresholdAndTimelineJumpsShowOnlyTheExpectedDots() {
        compose.mainClock.autoAdvance = false
        var pos by mutableLongStateOf(2000L)
        var start by mutableLongStateOf(3999L)
        compose.setContent {
            Box(Modifier.size(360.dp, 420.dp).background(Color.Black)) {
                LyricsPanel(listOf(LyricLine(start, start + 2000L, " ")), rememberUpdatedState(pos), 0L, {})
            }
        }
        fun pixel(): Int {
            compose.runOnIdle { androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }
            compose.mainClock.advanceTimeBy(250); compose.waitForIdle()
            var red = -1
            compose.runOnIdle {
                val view = compose.activity.window.decorView
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(android.graphics.Canvas(bitmap))
                red = android.graphics.Color.red(bitmap.getPixel(17, 37))
            }
            return red
        }
        assertEquals("gap under four seconds has no dots", 0, pixel())
        compose.runOnIdle { start = 4000L }
        assertTrue("four-second gap has dots", pixel() > 0)
        val paused = pixel()
        assertEquals("unchanged playback position stays still", paused, pixel())
        compose.runOnIdle { pos = 4000L }
        assertEquals("dots disappear at next line", 0, pixel())
        compose.runOnIdle { pos = 2000L }
        assertEquals("seeking back restores the same frame", paused, pixel())
    }
}
