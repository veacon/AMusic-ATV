package com.applemusicktv.ui.screens

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.navigation.compose.*
import com.applemusicktv.ui.navigation.returningComposable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.applemusicktv.ui.components.TopNavBar
import com.applemusicktv.ui.navigation.TopNavTab
import com.applemusicktv.ui.viewmodel.MusicVideoViewModel
import com.applemusicktv.ui.viewmodel.MvUiState
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,qualifiers="w960dp-h600dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class ScopedFixUiTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private fun snapshot(name:String):Bitmap {
        lateinit var bitmap:Bitmap
        compose.runOnIdle {
            val view=compose.activity.window.decorView
            bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap))
            val root=com.applemusicktv.testing.TestArtifacts.root
            val out=File(root,"artifacts/v3.0-v16-release/preview").apply { mkdirs() }
            File(out,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        }
        return bitmap
    }
    private fun video(state:MutableStateFlow<MvUiState>):MusicVideoViewModel {
        val c=Class.forName("sun.misc.Unsafe");val u=c.getDeclaredField("theUnsafe").apply { isAccessible=true }.get(null)
        val vm=c.getMethod("allocateInstance",Class::class.java).invoke(u,MusicVideoViewModel::class.java) as MusicVideoViewModel
        fun set(name:String,value:Any) {MusicVideoViewModel::class.java.getDeclaredField(name).apply { isAccessible=true }.set(vm,value)}
        set("_state",state);set("state",state)
        val cues=MutableStateFlow(emptyList<androidx.media3.common.text.Cue>());set("_cues",cues);set("cues",cues)
        val queue=MutableStateFlow(false);set("_showQueue",queue);set("showQueue",queue)
        return vm
    }
    @Test fun videoChromeHidesAtFiveSecondsOnTouchAndRemoteWakesItWithoutSeeking() {
        val state=MutableStateFlow(MvUiState(loading=false,playing=true,title="视频验收样片",artist="测试艺人",durationMs=100000))
        val vm=video(state);val focus=FocusRequester()
        lateinit var inputMode:InputModeManager
        compose.setContent { inputMode=LocalInputModeManager.current;Box(Modifier.fillMaxSize().background(Color.Black).testTag("video")) {
            MusicVideoScreen(vm,{}, {},showOnScreenControls=true,focusRequester=focus)
        } }
        compose.waitForIdle();compose.mainClock.autoAdvance=false
        compose.onNodeWithText("视频验收样片").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(4600);compose.onNodeWithText("视频验收样片").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(800);compose.onNodeWithText("视频验收样片").assertDoesNotExist()
        snapshot("video-controls-hidden")
        // Automatic next video loading/playing must not wake hidden chrome.
        compose.runOnIdle {state.value=state.value.copy(loading=true,playing=false,title="Next video");Snapshot.sendApplyNotifications()}
        compose.mainClock.advanceTimeBy(6000);compose.onNodeWithText("Next video").assertDoesNotExist()
        compose.runOnIdle {state.value=state.value.copy(loading=false,playing=true,title="视频验收样片");Snapshot.sendApplyNotifications()}
        compose.mainClock.advanceTimeBy(6000);compose.onNodeWithText("视频验收样片").assertDoesNotExist()

        compose.runOnIdle {inputMode.requestInputMode(InputMode.Keyboard);focus.requestFocus()}
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        compose.runOnIdle {Snapshot.sendApplyNotifications()}
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(320)
        compose.onNodeWithText("视频验收样片").assertIsDisplayed();assertEquals(0L,state.value.positionMs)
        snapshot("video-controls-awake")
        // Buffering and pause keep the controls usable; successful playback restarts a full timer.
        compose.runOnIdle {state.value=state.value.copy(playing=false,buffering=true);Snapshot.sendApplyNotifications()}
        compose.mainClock.advanceTimeBy(6000);compose.onNodeWithText("视频验收样片").assertIsDisplayed()
        compose.runOnIdle {state.value=state.value.copy(buffering=false,paused=true);Snapshot.sendApplyNotifications()}
        compose.mainClock.advanceTimeBy(6000);compose.onNodeWithText("视频验收样片").assertIsDisplayed()
        compose.runOnIdle {state.value=state.value.copy(playing=true,paused=false);Snapshot.sendApplyNotifications()}
        compose.mainClock.advanceTimeBy(4500);compose.onNodeWithText("视频验收样片").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(1000);compose.onNodeWithText("视频验收样片").assertDoesNotExist()
        compose.onNodeWithTag("video").performTouchInput { click(center) }
        compose.runOnIdle {Snapshot.sendApplyNotifications()}
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(320);compose.onNodeWithText("视频验收样片").assertIsDisplayed()
    }
    @Test fun visibleVideoControlsDoNotConsumeAnExtraBackAndSystemBackMatchesRemote() {
        val state=MutableStateFlow(MvUiState(loading=false,playing=true,title="Video",durationMs=100000))
        val vm=video(state);val focus=FocusRequester();var exits=0
        lateinit var dispatcher:androidx.activity.OnBackPressedDispatcher
        lateinit var inputMode:InputModeManager
        compose.setContent {
            dispatcher=androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            inputMode=LocalInputModeManager.current
            MusicVideoScreen(vm,{exits++},{},focusRequester=focus)
        }
        compose.waitForIdle()
        compose.runOnIdle {inputMode.requestInputMode(InputMode.Keyboard);focus.requestFocus()}
        compose.onRoot().performKeyInput {pressKey(Key.Back)}
        compose.runOnIdle {assertEquals(1,exits);dispatcher.onBackPressed();assertEquals(2,exits)}
    }

    @Test fun videoArtistDetailBackRestoresTheManualArtistControlFocus() {
        val state=MutableStateFlow(MvUiState(loading=false,playing=true,title="Video",artist="Artist",durationMs=100000))
        val vm=video(state);val focus=FocusRequester()
        lateinit var nav:androidx.navigation.NavHostController
        lateinit var returns:com.applemusicktv.ui.navigation.NavigationReturnState
        lateinit var dispatcher:androidx.activity.OnBackPressedDispatcher
        lateinit var inputMode:InputModeManager
        compose.setContent {
            nav=rememberNavController();returns=remember(nav) {com.applemusicktv.ui.navigation.NavigationReturnState(nav)}
            dispatcher=androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            inputMode=LocalInputModeManager.current
            val entry by nav.currentBackStackEntryAsState()
            CompositionLocalProvider(com.applemusicktv.ui.navigation.LocalNavigationReturn provides returns) {
                Box {
                    NavHost(nav,startDestination=com.applemusicktv.ui.navigation.Screen.NowPlaying.route,
                        enterTransition={androidx.compose.animation.EnterTransition.None},exitTransition={androidx.compose.animation.ExitTransition.None}) {
                        returningComposable(com.applemusicktv.ui.navigation.Screen.NowPlaying.route) {}
                        returningComposable("artist") {androidx.tv.material3.Text("Artist details")}
                    }
                    if(entry?.destination?.route==com.applemusicktv.ui.navigation.Screen.NowPlaying.route) {
                        CompositionLocalProvider(com.applemusicktv.ui.navigation.LocalReturnPage provides returns.pages[entry?.id]) {
                            MusicVideoScreen(vm,{}, {},focusRequester=focus)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {inputMode.requestInputMode(InputMode.Keyboard);focus.requestFocus()}
        compose.onRoot().performKeyInput {pressKey(Key.DirectionUp)}
        compose.runOnIdle {
            assertEquals("ARTIST",returns.pages.getValue(nav.currentBackStackEntry!!.id).handle.get<String>("return.videoFocus"))
            nav.navigate("artist")
        }
        compose.waitForIdle();compose.runOnIdle {dispatcher.onBackPressed()};compose.waitForIdle()
        compose.runOnIdle {assertNull(returns.pending);assertNull(returns.returnToTab)}
        compose.onRoot().performKeyInput {pressKey(Key.DirectionRight)}
        compose.runOnIdle {assertEquals("QUALITY",returns.pages.getValue(nav.currentBackStackEntry!!.id).handle.get<String>("return.videoFocus"))}
    }

}
