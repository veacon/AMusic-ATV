package com.applemusicktv.ui.screens

import android.app.Application
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.applemusicktv.ui.components.LocalUiMotion
import com.applemusicktv.ui.components.UiMotion
import com.applemusicktv.ui.viewmodel.NavigationViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,qualifiers="w960dp-h540dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class V41PlayerPresentationTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private fun tick(ms:Long) { compose.mainClock.advanceTimeBy(ms);compose.waitForIdle() }

    @Test fun coverAndControlsMoveTogetherOver500msWithoutRemounting() {
        compose.mainClock.autoAdvance=false
        var centered by mutableStateOf(false)
        var mounts=0;var disposals=0
        compose.setContent {
            PlayerSplitLayout(960.dp,240.dp,PlayerColumnSpacing(90.dp,70.dp),centered,
                controls={
                    DisposableEffect(Unit) {mounts++;onDispose {disposals++}}
                    Column(Modifier.width(240.dp)) {
                        Box(Modifier.size(240.dp).background(Color.Red).testTag("cover"))
                        Box(Modifier.size(240.dp,40.dp).background(Color.White).testTag("controls"))
                    }
                },panel={Text("lyrics")})
        }
        fun x(tag:String)=compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.left
        val start=x("cover");assertEquals(90f,start,1f)
        compose.runOnIdle {centered=true};tick(32)
        tick(100)
        val early=x("cover")
        assertTrue("Gentle acceleration: $early",early>start && early<start+270f*.25f)
        assertEquals(early,x("controls"),.1f)
        tick(150)
        val middle=x("cover");assertTrue(middle>early && middle<360f)
        tick(300)
        assertEquals(360f,x("cover"),1f)
        assertEquals(360f,x("controls"),1f)
        assertEquals(1,mounts);assertEquals(0,disposals)
        compose.runOnIdle {centered=false};tick(32);tick(550)
        assertEquals(start,x("cover"),1f)
        assertEquals(1,mounts);assertEquals(0,disposals)
    }

    @Test fun disabledMotionMovesImmediatelyAndKeepsOtherPlayerTiming() {
        var centered by mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalUiMotion provides UiMotion(enabled=false)) {
                PlayerSplitLayout(960.dp,240.dp,PlayerColumnSpacing(90.dp,70.dp),centered,
                    controls={Box(Modifier.size(240.dp).testTag("cover"))},panel={})
            }
        }
        compose.runOnIdle {centered=true}
        compose.onNodeWithTag("cover").assertLeftPositionInRootIsEqualTo(360.dp)
        assertEquals(360,playerMotionDuration(true))
        assertEquals(0,playerMotionDuration(false))
    }

    private fun backScenario(initialHidden:Boolean) {
        var preferred by mutableStateOf(true)
        var parentBacks=0
        val nav=NavigationViewModel().apply {isOnNowPlaying=true;setPlayerChromeVisible(!initialHidden)}
        compose.setContent {
            SideEffect {nav.fullScreenLyricsHasSplitOrigin=preferred}
            BackHandler {parentBacks++}
            FullScreenLyricsBackHandler(preferred,false) {preferred=false;nav.wakePlayerControls()}
            Text(if(preferred) "full lyrics" else "split player")
        }
        // The real NavigationViewModel gate must pass remote Back through even while hidden.
        compose.runOnIdle {
            assertFalse(nav.consumePlayerWakeKey(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BACK)))
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.onNodeWithText("split player").assertIsDisplayed()
        assertEquals(0,parentBacks);assertTrue(nav.playerChromeVisible.value)
        compose.runOnIdle {compose.activity.onBackPressedDispatcher.onBackPressed()}
        assertEquals(1,parentBacks)
    }
    @Test fun fullLyricsBackRestoresSplitBeforeReturningToSource() = backScenario(false)
    @Test fun hiddenFullLyricsBackRestoresSplitInOnePress() = backScenario(true)

    @Test fun portraitDoesNotForceASplitReturnAndOtherWakeKeysStillWake() {
        var forced by mutableStateOf(true)
        var splitReturns=0;var parentBacks=0
        val nav=NavigationViewModel().apply {isOnNowPlaying=true;setPlayerChromeVisible(false)}
        compose.setContent {
            SideEffect {nav.fullScreenLyricsHasSplitOrigin=!forced}
            BackHandler {parentBacks++}
            FullScreenLyricsBackHandler(true,forced) {splitReturns++}
        }
        compose.runOnIdle {
            assertTrue(nav.consumePlayerWakeKey(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BACK)))
            assertTrue(nav.consumePlayerWakeKey(KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_BACK)))
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        assertEquals(0,splitReturns);assertEquals(1,parentBacks)
        compose.runOnIdle {forced=false;nav.setPlayerChromeVisible(false)}
        compose.runOnIdle {
            assertTrue(nav.consumePlayerWakeKey(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_CENTER)))
            assertTrue(nav.playerChromeVisible.value)
        }
        compose.runOnIdle {compose.activity.onBackPressedDispatcher.onBackPressed()}
        assertEquals(1,splitReturns);assertEquals(1,parentBacks)
    }
}
