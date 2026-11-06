package com.applemusicktv.ui.navigation

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.*
import com.applemusicktv.ui.components.TopNavBar
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,qualifiers="w960dp-h540dp-land-mdpi")
@OptIn(ExperimentalTestApi::class)
class TabStackNavigationTest {
    @get:Rule val compose=createComposeRule()
    private lateinit var nav:NavHostController
    private lateinit var returns:NavigationReturnState
    private class Mode(initial:InputMode):InputModeManager {
        override var inputMode by mutableStateOf(initial)
        override fun requestInputMode(inputMode:InputMode):Boolean {this.inputMode=inputMode;return true}
    }
    private lateinit var mode:Mode
    private val focuses=TopNavTab.entries.associateWith {FocusRequester()}
    private fun mount(header:Boolean=false,input:InputMode=InputMode.Touch) {
        mode=Mode(input)
        compose.setContent {
            nav=rememberNavController();returns=remember(nav) {NavigationReturnState(nav)}
            val entry by nav.currentBackStackEntryAsState()
            CompositionLocalProvider(LocalNavigationReturn provides returns,LocalInputModeManager provides mode) {
                Box(Modifier.fillMaxSize()) {
                NavHost(nav,startDestination=Screen.Home.route,enterTransition={androidx.compose.animation.EnterTransition.None},exitTransition={androidx.compose.animation.ExitTransition.None}) {
                    listOf(Screen.Home,Screen.Browse,Screen.Library,Screen.Search,Screen.NowPlaying,Screen.DevMenu).forEach { screen ->
                        returningComposable(screen.route) {Text(screen.route)}
                    }
                    returningComposable("playlist") {Text("playlist")}
                }
                if(header) TopNavBar(selected=returns.owner(entry),onSelect=returns::selectTab,
                    tabFocusRequesters=focuses,modifier=Modifier.align(Alignment.TopCenter))
                }
            }
        }
        compose.waitForIdle()
    }
    private fun select(tab:TopNavTab) {
        compose.runOnIdle {returns.selectTab(tab)}
        compose.waitForIdle()
    }
    private fun assertRoute(route:String)=compose.runOnIdle {assertEquals(route,nav.currentDestination?.route)}
    @Test fun librarySettingsListenNowLibraryDoesNotRestoreSettings() {
        mount();select(TopNavTab.Library);select(TopNavTab.Dev);select(TopNavTab.ListenNow);select(TopNavTab.Library)
        assertRoute(Screen.Library.route)
    }
    @Test fun repeatedBrowseLibrarySearchAndAvatarSelectionsNeverLockOnSettings() {
        mount()
        repeat(4) {
            listOf(TopNavTab.Browse to Screen.Browse.route,TopNavTab.Library to Screen.Library.route,TopNavTab.Search to Screen.Search.route).forEach { (tab,route) ->
                select(tab);assertRoute(route);select(TopNavTab.Dev);assertRoute(Screen.DevMenu.route)
            }
        }
    }
    @Test fun switchingTabsPreservesOrdinaryPlaylistHistoryButNeverItsSettingsVisit() {
        mount();select(TopNavTab.Library)
        var id=""
        compose.runOnIdle {nav.navigate("playlist");id=nav.currentBackStackEntry!!.id}
        compose.waitForIdle();select(TopNavTab.Dev);select(TopNavTab.ListenNow);select(TopNavTab.Library)
        assertRoute("playlist");compose.runOnIdle {assertEquals(id,nav.currentBackStackEntry!!.id)}
    }
    @Test fun legacySavedSettingsStackIsRepairedWhenRestored() {
        mount();select(TopNavTab.Library)
        compose.runOnIdle {
            nav.navigate(Screen.DevMenu.route)
            nav.navigate(Screen.Home.route) {
                popUpTo(nav.graph.findStartDestination().id) {saveState=true}
                launchSingleTop=true;restoreState=true
            }
        }
        compose.waitForIdle();select(TopNavTab.Library);assertRoute(Screen.Library.route)
    }
    @Test fun settingsBackStillReturnsToTheActualSourceDetail() {
        mount();select(TopNavTab.Browse)
        compose.runOnIdle {nav.navigate("playlist")};compose.waitForIdle()
        select(TopNavTab.Dev);compose.runOnIdle {returns.back()};compose.waitForIdle()
        assertRoute("playlist")
    }
    private fun tap(description:String) {
        compose.onNodeWithContentDescription(description).performTouchInput {click(center)}
        compose.waitForIdle()
    }
    @Test fun actualTouchHeaderCyclesLibraryBrowseSearchAndAvatarWithoutGettingStuck() {
        mount(header=true)
        repeat(6) {
            listOf(TopNavTab.Browse to Screen.Browse.route,TopNavTab.Library to Screen.Library.route,TopNavTab.Search to Screen.Search.route).forEach { (tab,route) ->
                tap(tab.label);assertRoute(route);tap(com.applemusicktv.i18n.tr(com.applemusicktv.R.string.msg_292));assertRoute(Screen.DevMenu.route)
            }
        }
    }
    @Test fun actualRemoteCanFocusSearchFromBothLibraryAndAvatarWithoutConfirm() {
        mount(header=true,input=InputMode.Keyboard)
        compose.runOnIdle {focuses.getValue(TopNavTab.Library).requestFocus()};compose.waitForIdle()
        repeat(6) {
            compose.onRoot().performKeyInput {pressKey(Key.DirectionRight)}
            compose.waitForIdle();assertRoute(Screen.Search.route)
            compose.onNodeWithContentDescription(TopNavTab.Search.label).assertIsFocused()
            compose.onRoot().performKeyInput {pressKey(Key.DirectionRight)}
            compose.waitForIdle();assertRoute(Screen.DevMenu.route)
            compose.onNodeWithContentDescription(com.applemusicktv.i18n.tr(com.applemusicktv.R.string.msg_292)).assertIsFocused()
            compose.onRoot().performKeyInput {pressKey(Key.DirectionLeft)}
            compose.waitForIdle();assertRoute(Screen.Search.route)
            compose.onNodeWithContentDescription(TopNavTab.Search.label).assertIsFocused()
            compose.onRoot().performKeyInput {pressKey(Key.DirectionLeft)}
            compose.waitForIdle();assertRoute(Screen.Library.route)
        }
    }
    @Test fun touchModeIncidentalAvatarFocusDoesNotNavigateAwayFromRequestedTab() {
        mount(header=true);select(TopNavTab.Library)
        compose.runOnIdle {focuses.getValue(TopNavTab.Dev).requestFocus()};compose.waitForIdle()
        assertRoute(Screen.Library.route)
        tap(TopNavTab.Search.label);assertRoute(Screen.Search.route)
    }

}
