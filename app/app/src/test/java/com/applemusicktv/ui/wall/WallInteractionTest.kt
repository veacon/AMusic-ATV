package com.applemusicktv.ui.wall

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import androidx.tv.material3.Text
import com.applemusicktv.data.model.Song
import com.applemusicktv.ui.components.motionInput
import com.applemusicktv.ui.navigation.*
import com.applemusicktv.ui.screens.FullScreenLyrics
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w960dp-h540dp-land-mdpi")
@OptIn(ExperimentalTestApi::class)
class WallInteractionTest {
    @Before fun language() { com.applemusicktv.i18n.AppLanguage.select(com.applemusicktv.i18n.LanguageChoice.Chinese) }
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun cards(): List<CachedWallCard> {
        val bitmap = Bitmap.createBitmap(320, 320, Bitmap.Config.RGB_565)
        return (0..49).map { CachedWallCard(WallDescriptor("$it", "Album $it", "Artist", "local", WallKind.Album, "home"), bitmap) }
    }
    private class Keyboard : InputModeManager {
        override var inputMode by mutableStateOf(InputMode.Keyboard)
        override fun requestInputMode(inputMode: InputMode): Boolean { this.inputMode = inputMode; return true }
    }
    @Test fun touchPassesThroughPassiveForegroundAndFirstTapOnlyWakes() {
        val cards = cards()
        var visible by mutableStateOf(false)
        var opened = -1; var wakes = 0
        compose.setContent {
            Box(Modifier.requiredSize(960.dp, 540.dp).testTag("touchRoot")
                .wallTouchNavigation(true, false, cards, { visible }, { visible = true; wakes++ }, { index, _ -> opened = index })) {
                // The production foreground has this full-screen passive pointer-input node.
                Box(Modifier.fillMaxSize().motionInput(true))
            }
        }
        compose.onNodeWithTag("touchRoot").performTouchInput { click(Offset(100f, 100f)) }
        compose.runOnIdle { assertEquals(1, wakes); assertEquals(-1, opened) }
        compose.onNodeWithTag("touchRoot").performTouchInput { click(Offset(250f, 150f)) }
        compose.runOnIdle { assertEquals(11, opened); opened = -1 }
        compose.onNodeWithTag("touchRoot").performTouchInput { click(Offset(250f, 520f)) }
        compose.runOnIdle { assertEquals(-1, opened) }
        compose.onNodeWithTag("touchRoot").performTouchInput {
            down(Offset(100f, 100f)); moveTo(Offset(400f, 200f)); up()
        }
        compose.runOnIdle { assertEquals(-1, opened) }
    }
    @Test fun detailBackRestoresTheSelectedGridInsteadOfAFooterFallback() {
        val cards = cards()
        lateinit var nav: NavHostController
        lateinit var returns: NavigationReturnState
        var selectedSnapshot = -1
        lateinit var enterSelection: () -> Unit
        compose.setContent {
            nav = rememberNavController()
            returns = remember(nav) { NavigationReturnState(nav) }
            val mode = remember { Keyboard() }
            CompositionLocalProvider(LocalNavigationReturn provides returns, LocalInputModeManager provides mode) {
                NavHost(nav, startDestination = "wall", enterTransition = { androidx.compose.animation.EnterTransition.None },
                    exitTransition = { androidx.compose.animation.ExitTransition.None }) {
                    returningComposable("wall") {
                        var selected by rememberSaveable { mutableIntStateOf(-1) }
                        var selecting by rememberSaveable { mutableStateOf(false) }
                        val focus = remember { FocusRequester() }
                        SideEffect {
                            selectedSnapshot = selected
                            enterSelection = { selected = -1; selecting = true }
                        }
                        Box(Modifier.requiredSize(960.dp, 540.dp)) {
                            AlbumWallGrid(cards, selecting, selected, { selected = it }, { nav.navigate("detail") }, focus, true)
                            if (!selecting) com.applemusicktv.ui.components.TouchSurface({}, Modifier.testTag("footer")) { Text("play") }
                        }
                        LaunchedEffect(selecting) {
                            if (!selecting) return@LaunchedEffect
                            repeat(60) { withFrameNanos { }; if (runCatching { focus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect }
                        }
                    }
                    returningComposable("detail") { Text("detail", Modifier.testTag("detail")) }
                }
            }
        }
        compose.runOnIdle { enterSelection() }
        compose.onNodeWithTag("wallSelectionGrid").assertIsFocused()
        compose.onNodeWithTag("wallSelectionGrid").performKeyInput { pressKey(Key.DirectionRight) }
        compose.runOnIdle { assertEquals(23, selectedSnapshot) }
        compose.onNodeWithTag("wallSelectionGrid").performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithTag("detail").assertExists()
        compose.runOnIdle { returns.back() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1500)
        compose.onNodeWithTag("footer").assertDoesNotExist()
        compose.onNodeWithTag("wallSelectionGrid").assertIsFocused()
        compose.runOnIdle { assertEquals(23, selectedSnapshot) }
    }
    @Test fun selectionFooterIsRemovedIncludingSongInformationAndTransport() {
        var footer by mutableStateOf(false)
        val song = Song("1", "Footer title", "Footer artist", "Album", 1000, null, null, null)
        compose.setContent {
            Box(Modifier.requiredSize(960.dp, 540.dp)) {
                FullScreenLyrics(emptyList(), remember { mutableStateOf(0L) }, 0, song, false, {}, {}, {}, {},
                    lyricsHidden = true, suppressPlayFocus = true, footerVisible = footer)
            }
        }
        compose.onNodeWithText(song.title).assertDoesNotExist()
        compose.onNodeWithText(song.artistName).assertDoesNotExist()
        for (label in listOf("上一首", "播放", "下一首")) compose.onNodeWithContentDescription(label).assertDoesNotExist()
        compose.runOnIdle { footer = true }
        compose.onNodeWithText(song.title).assertExists()
        compose.onNodeWithText(song.artistName).assertExists()
        for (label in listOf("上一首", "播放", "下一首")) compose.onNodeWithContentDescription(label).assertExists()
    }
    @Test fun footerFadeDisablesOutgoingControlsImmediatelyAndCanReverse() {
        var footer by mutableStateOf(true)
        var clicks = 0
        val song = Song("1", "Fading title", "Artist", "Album", 1000, null, null, null)
        compose.setContent {
            Box(Modifier.requiredSize(960.dp, 540.dp).testTag("footerRoot")) {
                FullScreenLyrics(emptyList(), remember { mutableStateOf(0L) }, 0, song, false,
                    {}, {}, { clicks++ }, {}, lyricsHidden = true, suppressPlayFocus = true, footerVisible = footer)
            }
        }
        val playCenter = compose.onNodeWithContentDescription("播放").fetchSemanticsNode().boundsInRoot.center
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { footer = false }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(120)
        compose.onNodeWithContentDescription("播放").assertIsNotFocused()
        compose.onNodeWithTag("footerRoot").performTouchInput { click(playCenter) }
        compose.runOnIdle { assertEquals(0, clicks); footer = true }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(320)
        compose.onNodeWithContentDescription("播放").assertExists().performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
        compose.mainClock.autoAdvance = true
    }
    @Test fun fixedGridDoesNotMoveWhileOutgoingAndIncomingCopiesOverlap() {
        val cards = cards()
        var selected by mutableIntStateOf(10)
        compose.setContent {
            Box(Modifier.requiredSize(960.dp, 540.dp)) {
                AlbumWallGrid(cards, true, selected, { selected = it }, {}, remember { FocusRequester() }, true)
            }
        }
        val oldBounds = compose.onNodeWithTag("wallBase-10").fetchSemanticsNode().boundsInRoot
        val newBounds = compose.onNodeWithTag("wallBase-11").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("wallFloating-10").assertExists()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { selected = 11 }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(60)
        compose.onNodeWithTag("wallFloating-10").assertExists()
        compose.onNodeWithTag("wallFloating-11").assertExists()
        assertEquals(oldBounds, compose.onNodeWithTag("wallBase-10").fetchSemanticsNode().boundsInRoot)
        assertEquals(newBounds, compose.onNodeWithTag("wallBase-11").fetchSemanticsNode().boundsInRoot)
        compose.mainClock.advanceTimeBy(240)
        compose.onNodeWithTag("wallFloating-10").assertDoesNotExist()
        compose.onNodeWithTag("wallFloating-11").assertExists()
        assertEquals(oldBounds, compose.onNodeWithTag("wallBase-10").fetchSemanticsNode().boundsInRoot)
        compose.mainClock.autoAdvance = true
    }
}
