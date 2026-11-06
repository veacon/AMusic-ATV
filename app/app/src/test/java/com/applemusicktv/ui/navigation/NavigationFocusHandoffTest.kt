package com.applemusicktv.ui.navigation

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import androidx.tv.material3.Text
import com.applemusicktv.ui.components.*
import com.applemusicktv.ui.screens.PillButton
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Includes the real header and sibling focus groups omitted by the V14 detail-only tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,qualifiers="w960dp-h540dp-land-mdpi")
@OptIn(ExperimentalTestApi::class)
class NavigationFocusHandoffTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private lateinit var nav:NavHostController
    private lateinit var returns:NavigationReturnState
    private lateinit var back:androidx.activity.OnBackPressedDispatcher
    private val tabs=TopNavTab.entries.associateWith {FocusRequester()}
    private val headerEvents=mutableListOf<TopNavTab>()
    private var ready by mutableStateOf(false)
    private var failed by mutableStateOf(false)
    private class Mode:InputModeManager {
        override var inputMode by mutableStateOf(InputMode.Keyboard)
        override fun requestInputMode(inputMode:InputMode):Boolean {this.inputMode=inputMode;return true}
    }
    private fun mount() {
        val mode=Mode()
        compose.setContent {
            nav=rememberNavController();returns=remember(nav){NavigationReturnState(nav)}
            back=LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            CompositionLocalProvider(LocalNavigationReturn provides returns,LocalInputModeManager provides mode) {
                val handoff=rememberNavigationFocusHandoff(nav,returns)
                var barFocused by remember {mutableStateOf(false)}
                val content=remember {FocusRequester()}
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.focusProperties {
                        onEnter={if(!handoff) tabs.getValue(TopNavTab.ListenNow).requestFocus()}
                    }.focusGroup()) {
                        TopNavBar(TopNavTab.ListenNow,{tab->if(!returns.contentFocusHandoff) returns.selectTab(tab)},
                            contentFocusRequester=content,tabFocusRequesters=tabs,
                            focusEnabled=!handoff,navigationHasFocus=barFocused && !handoff,
                            onTabFocusChanged={tab,focused->barFocused=focused;if(focused) headerEvents+=tab})
                    }
                    NavHost(nav,Screen.Home.route,Modifier.weight(1f).fillMaxWidth().focusRequester(content).contentFocusExitPolicy(false,nav.currentDestination?.route==Screen.Home.route,tabs.getValue(TopNavTab.ListenNow)).focusGroup(),
                        enterTransition={BrowsingPageMotion.enter(initialState.destination.route,targetState.destination.route,UiMotion())},
                        exitTransition={BrowsingPageMotion.exit(initialState.destination.route,targetState.destination.route,UiMotion())},
                        popEnterTransition={BrowsingPageMotion.enter(initialState.destination.route,targetState.destination.route,UiMotion())},
                        popExitTransition={BrowsingPageMotion.exit(initialState.destination.route,targetState.destination.route,UiMotion())}) {
                        returningComposable(Screen.Home.route) {
                            Row(Modifier.padding(40.dp),horizontalArrangement=Arrangement.spacedBy(24.dp)) {
                                TouchSurface({nav.navigate(Screen.AlbumDetail.route("test"))},Modifier.size(100.dp).testTag("album-cover")){Text("album")}
                                TouchSurface({nav.navigate(Screen.PlaylistDetail.route("test","test","art"))},Modifier.size(100.dp).testTag("playlist-cover")){Text("playlist")}
                            }
                        }
                        returningComposable(Screen.AlbumDetail.route) {Detail()}
                        returningComposable(Screen.PlaylistDetail.route) {Detail()}
                    }
                }
            }
        }
    }
    @Composable private fun Detail() {
        val focus=rememberDetailPlayEntryFocus(ready,loading=!failed)
        FadeThrough(ready) {shown->
            if(shown) Row(Modifier.padding(40.dp)) {
                PillButton(Glyph.PLAY,"播放",Color(0xFFFA233B),focus) {}
                PillButton(Glyph.SHUFFLE,"随机播放",Color(0xFFE8E8ED)) {}
            } else Text(if(failed) "empty" else "loading")
        }
    }
    private fun open(tag:String) {
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.runOnIdle {headerEvents.clear()}
        compose.onNodeWithTag(tag).performKeyInput {pressKey(Key.DirectionCenter)}
    }
    @Test fun albumAndPlaylistEnterPlayDirectlyAndPopToTheirCoverWithoutHeaderFocus() {
        mount()
        for(tag in listOf("album-cover","playlist-cover")) {
            compose.runOnIdle {ready=false}
            open(tag)
            compose.runOnIdle {assertTrue(returns.contentFocusHandoff);assertTrue(headerEvents.isEmpty())}
            compose.runOnIdle {ready=true}
            compose.onNodeWithText("播放").assertIsFocused()
            compose.runOnIdle {assertFalse(returns.contentFocusHandoff);assertTrue(headerEvents.isEmpty())}
            compose.runOnIdle {
                compose.activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_BACK))
                compose.activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP,android.view.KeyEvent.KEYCODE_BACK))
            }
            compose.onNodeWithTag(tag).assertIsFocused()
            compose.runOnIdle {assertTrue("No transient focus on a header tab: $headerEvents",headerEvents.isEmpty())}
        }
    }
    @Test fun emptyDetailReleasesTheHeaderInsteadOfLeavingItDisabled() {
        mount();open("album-cover")
        compose.runOnIdle {failed=true}
        compose.runOnIdle {assertFalse(returns.contentFocusHandoff);tabs.getValue(TopNavTab.ListenNow).requestFocus()}
        compose.onNodeWithContentDescription("立即聆听").assertIsFocused()
    }
    @Test fun passiveSelectionOfCurrentTabCannotCancelTheDetailReservation() {
        mount();open("album-cover")
        compose.runOnIdle {
            val serial=returns.inputSerial
            returns.selectTab(TopNavTab.ListenNow)
            assertEquals(serial,returns.inputSerial)
            assertTrue(returns.contentFocusHandoff)
            ready=true
        }
        compose.onNodeWithText("播放").assertIsFocused()
    }
}
