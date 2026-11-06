package com.applemusicktv.ui.screens

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w960dp-h540dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class LyricScrollUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun tick(ms: Long) { compose.runOnIdle { androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }; compose.mainClock.advanceTimeBy(ms); compose.waitForIdle() }
    @Test fun longLinesInterludeRetargetAndSameSizeSongChangeKeepCurrentLyricsVisible() {
        compose.mainClock.autoAdvance = false
        var pos by mutableLongStateOf(0L)
        fun lines(prefix: String) = (0..11).map { i ->
            val start = if (i >= 6) i * 2000L + 5000L else i * 2000L
            LyricLine(start, start + 1500L, "$prefix-$i " + if (i == 5) "这是一句需要自然换行的很长很长的歌词这是一句需要自然换行" else "歌词")
        }
        var lyrics by mutableStateOf(lines("甲"))
        compose.setContent { Box(Modifier.width(360.dp).height(420.dp)) { LyricsPanel(lyrics, rememberUpdatedState(pos), 0L, {}) } }
        tick(100)
        compose.runOnIdle { pos = 6000L }; tick(150)
        val first = compose.onNodeWithText("甲-3 歌词").fetchSemanticsNode().boundsInRoot.top
        compose.runOnIdle { pos = 8000L }; tick(48)
        val after = compose.onNodeWithText("甲-3 歌词").fetchSemanticsNode().boundsInRoot.top
        assertTrue("retarget should continue from current position", kotlin.math.abs(after - first) < 100f)
        tick(700)
        compose.runOnIdle { pos = 10000L }; tick(700)
        compose.onNodeWithText("甲-5 这是一句需要自然换行的很长很长的歌词这是一句需要自然换行").assertExists()
        compose.runOnIdle { pos = 14500L }; tick(200)
        compose.runOnIdle { pos = 17000L }; tick(700)
        compose.onNodeWithText("甲-6 歌词").assertExists()
        compose.runOnIdle { lyrics = lines("乙"); pos = 0L }; tick(700)
        compose.onNodeWithText("乙-0 歌词").assertExists()
    }
}
