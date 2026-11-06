package com.applemusicktv.ui.navigation

import android.app.Application
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import androidx.tv.material3.Text
import com.applemusicktv.ui.components.Glyph
import com.applemusicktv.ui.components.TouchSurface
import com.applemusicktv.ui.components.FadeThrough
import com.applemusicktv.ui.screens.PillButton
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,qualifiers="w960dp-h540dp-land-mdpi")
@OptIn(ExperimentalTestApi::class)
class DetailEntryFocusTest {
    @get:Rule val compose=createComposeRule()
    private lateinit var nav:NavHostController
    private lateinit var returns:NavigationReturnState
    private var ready by mutableStateOf(false)
    private var clicks=0
    private class Mode(initial:InputMode):InputModeManager {
        override var inputMode by mutableStateOf(initial)
        override fun requestInputMode(inputMode:InputMode):Boolean {this.inputMode=inputMode;return true}
    }
    private fun mount(input:InputMode=InputMode.Keyboard) {
        val mode=Mode(input)
        compose.setContent {
            nav=rememberNavController();returns=remember(nav) {NavigationReturnState(nav)}
            CompositionLocalProvider(LocalNavigationReturn provides returns,LocalInputModeManager provides mode) {
                NavHost(nav,Screen.Home.route,enterTransition={EnterTransition.None},exitTransition={ExitTransition.None}) {
                    returningComposable(Screen.Home.route) {
                        TouchSurface({nav.navigate("album")},Modifier.testTag("open")) {Text("open")}
                    }
                    listOf("album","playlist","artist").forEach {kind->
                        returningComposable(kind) { Detail(kind) }
                    }
                    returningComposable("child") { TouchSurface({returns.back()},Modifier.testTag("back")) {Text("back")} }
                }
            }
        }
    }
    @Composable private fun Detail(kind:String) {
        val list=rememberLazyListState()
        val focus=rememberDetailPlayEntryFocus(ready) {list.scrollToItem(if(kind=="artist") 3 else 0)}
        FadeThrough(ready) {visible->
            if(visible) ReturnLazyColumn(state=list,modifier=Modifier.fillMaxSize()) {
                if(kind=="artist") items(3) {Box(Modifier.height(300.dp)) {Text("hero $it")}}
                item {Row(horizontalArrangement=Arrangement.spacedBy(24.dp)) {
                    PillButton(Glyph.PLAY,"播放",Color(0xFFFA233B),focus) {clicks++}
                    PillButton(Glyph.SHUFFLE,"随机播放",Color(0xFFE8E8ED)) {nav.navigate("child")}
                }}
                items(20) {i->TouchSurface({},Modifier.height(50.dp).testTag("track-$i")) {Text("track $i")}}
            } else Text("loading")
        }
    }
    @Test fun remoteAlbumWaitsForContentAndFocusesPlayWithoutClicking() {
        mount();compose.runOnIdle {nav.navigate("album")}
        compose.onNodeWithText("播放").assertDoesNotExist()
        compose.runOnIdle {ready=true}
        compose.onNodeWithText("播放").assertIsFocused();assertEquals(0,clicks)
        compose.onNodeWithText("播放").performKeyInput {pressKey(Key.DirectionCenter)}
        assertEquals(1,clicks)
    }
    @Test fun playlistAndArtistCanFocusPlayEvenBeyondTheInitialViewport() {
        mount();compose.runOnIdle {ready=true;nav.navigate("playlist")}
        compose.onNodeWithText("播放").assertIsFocused()
        compose.runOnIdle {nav.navigate("artist")}
        compose.onNodeWithText("播放").assertIsFocused().assertIsDisplayed();assertEquals(0,clicks)
    }
    @Test fun touchEntryDoesNotForcePlayFocus() {
        mount(InputMode.Touch);compose.onNodeWithTag("open").performTouchInput {click(center)}
        compose.runOnIdle {ready=true}
        compose.onNodeWithText("播放").assertIsNotFocused();assertEquals(0,clicks)
    }
    @Test fun explicitInputDuringLoadingCancelsDelayedPlayFocus() {
        mount();compose.runOnIdle {nav.navigate("album")}
        compose.runOnIdle {returns.cancel();ready=true}
        compose.onNodeWithText("播放").assertIsNotFocused()
    }
    @Test fun returningFromChildRestoresShuffleInsteadOfRefocusingPlay() {
        mount();compose.runOnIdle {ready=true;nav.navigate("album")}
        compose.onNodeWithText("播放").assertIsFocused()
        compose.onNodeWithText("随机播放").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithText("随机播放").performKeyInput {pressKey(Key.DirectionCenter)}
        compose.onNodeWithTag("back").performClick()
        compose.onNodeWithText("随机播放").assertIsFocused()
        compose.onNodeWithText("播放").assertIsNotFocused();assertEquals(0,clicks)
    }
}
