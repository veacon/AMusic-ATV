package com.applemusicktv.ui.navigation

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import com.applemusicktv.ui.components.TopNavBar
import com.applemusicktv.ui.components.UiMotion
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w960dp-h540dp")
class BrowsingPageNavigationTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var nav: NavHostController
    private val tabs = TopNavTab.entries.associateWith { FocusRequester() }
    private val positions = mutableMapOf<String, Offset>()
    private var barPosition = Offset.Zero
    private var focused: TopNavTab? = null
    private val routes = linkedMapOf(TopNavTab.ListenNow to "home", TopNavTab.Browse to "browse",
        TopNavTab.Library to "library", TopNavTab.Search to "search", TopNavTab.NowPlaying to "now_playing")

    private fun mount(motion: UiMotion = UiMotion()) {
        compose.setContent {
            nav = rememberNavController()
            val entry by nav.currentBackStackEntryAsState()
            val selected = routes.entries.firstOrNull { it.value == entry?.destination?.route }?.key ?: TopNavTab.ListenNow
            Column(Modifier.fillMaxSize()) {
                TopNavBar(selected, onSelect = { tab ->
                    routes[tab]?.let { route ->
                        if (nav.currentDestination?.route != route) nav.navigate(route) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true; restoreState = true
                        }
                    }
                }, modifier = Modifier.onGloballyPositioned { barPosition = it.positionInRoot() },
                    tabFocusRequesters = tabs,
                    onTabFocusChanged = { tab, active -> if (active) focused = tab else if (focused == tab) focused = null })
                NavHost(nav, "home", Modifier.weight(1f).fillMaxWidth().clipToBounds(),
                    enterTransition = { BrowsingPageMotion.enter(initialState.destination.route, targetState.destination.route, motion) },
                    exitTransition = { BrowsingPageMotion.exit(initialState.destination.route, targetState.destination.route, motion) },
                    popEnterTransition = { BrowsingPageMotion.enter(initialState.destination.route, targetState.destination.route, motion) },
                    popExitTransition = { BrowsingPageMotion.exit(initialState.destination.route, targetState.destination.route, motion) }) {
                    routes.values.forEach { route -> composable(route) {
                        Box(Modifier.fillMaxSize().onGloballyPositioned { positions[route] = it.positionInRoot() }) { Text(route) }
                    } }
                }
            }
        }
    }

    private fun advance(ms: Long) { compose.mainClock.advanceTimeBy(ms); compose.waitForIdle() }

    @Test fun forwardAndPopKeepBothPagesAndBarStationaryDuringFade() {
        mount()
        val originalBar = barPosition
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { nav.navigate("browse"); Snapshot.sendApplyNotifications() }
        advance(96)
        compose.runOnIdle {
            assertEquals(0f,positions.getValue("home").x,1f)
            assertEquals(0f,positions.getValue("browse").x,1f)
            assertEquals(originalBar, barPosition)
        }
        advance(560)
        compose.runOnIdle { assertEquals(0f, positions.getValue("browse").x, 1f); nav.popBackStack(); Snapshot.sendApplyNotifications() }
        advance(96)
        compose.runOnIdle {
            assertEquals(0f,positions.getValue("browse").x,1f)
            assertEquals(0f,positions.getValue("home").x,1f)
            assertEquals(originalBar, barPosition)
        }
        advance(560)
        compose.runOnIdle { assertEquals(0f, positions.getValue("home").x, 1f) }
    }

    @Test fun keyboardSelectsEachTabWithoutConfirmDuringTheFade() {
        mount()
        compose.runOnIdle { tabs.getValue(TopNavTab.ListenNow).requestFocus() }
        compose.mainClock.autoAdvance = false
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        advance(64)
        compose.runOnIdle { assertEquals("browse", nav.currentDestination?.route); assertEquals(TopNavTab.Browse, focused) }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        compose.runOnIdle { assertEquals("library", nav.currentDestination?.route); assertEquals(TopNavTab.Library, focused) }
        advance(640)
        compose.runOnIdle {
            assertEquals("library", nav.currentDestination?.route)
            assertEquals(TopNavTab.Library, focused)
            assertEquals(0f, positions.getValue("library").x, 1f)
        }
    }

    @Test fun leftFromListenNowEntersPlayerWithoutConfirm() {
        mount()
        compose.runOnIdle { tabs.getValue(TopNavTab.ListenNow).requestFocus() }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
        compose.runOnIdle {
            assertEquals("now_playing", nav.currentDestination?.route)
            assertEquals(TopNavTab.NowPlaying, focused)
        }
    }

    @Test fun rapidArrowsReverseDuringUnfinishedTransitionsWithoutLosingSelection() {
        mount()
        compose.runOnIdle { tabs.getValue(TopNavTab.ListenNow).requestFocus() }
        compose.mainClock.autoAdvance = false
        compose.onRoot().performKeyInput {
            pressKey(Key.DirectionRight); pressKey(Key.DirectionRight)
            pressKey(Key.DirectionLeft); pressKey(Key.DirectionLeft)
        }
        advance(640)
        compose.runOnIdle {
            assertEquals("home", nav.currentDestination?.route)
            assertEquals(TopNavTab.ListenNow, focused)
        }
    }

    @Test fun touchStillSelectsTabs() {
        mount()
        compose.onNodeWithContentDescription("浏览").performClick()
        compose.runOnIdle { assertEquals("browse", nav.currentDestination?.route) }
        compose.onNodeWithContentDescription("搜索").performClick()
        compose.runOnIdle { assertEquals("search", nav.currentDestination?.route) }
    }

    @Test fun reducedMotionReplacesImmediately() {
        mount(UiMotion(enabled = false))
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { nav.navigate("search"); Snapshot.sendApplyNotifications() }
        advance(64)
        compose.onNodeWithText("home").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0f, positions.getValue("search").x, 1f) }
    }

    @Test fun playerTransitionRetainsItsFiveHundredMsHorizontalMotion() {
        mount()
        compose.mainClock.autoAdvance=false
        compose.runOnIdle {nav.navigate("now_playing");Snapshot.sendApplyNotifications()}
        advance(112)
        compose.runOnIdle {
            assertTrue(positions.getValue("home").x>0f)
            assertTrue(positions.getValue("now_playing").x<0f)
        }
        advance(160)
        compose.onNodeWithText("home").assertExists()
        compose.runOnIdle {assertTrue(positions.getValue("now_playing").x<0f)}
        advance(320)
        compose.onNodeWithText("home").assertDoesNotExist()
        compose.runOnIdle {assertEquals(0f,positions.getValue("now_playing").x,1f)}
    }

    @Test fun browsingCrossfadeCompletesAtTwoHundredMs() {
        mount(UiMotion())
        compose.mainClock.autoAdvance=false
        compose.runOnIdle {nav.navigate("search");Snapshot.sendApplyNotifications()}
        advance(112)
        compose.onNodeWithText("home").assertExists()
        compose.runOnIdle {assertEquals(0f,positions.getValue("search").x,1f)}
        advance(144)
        compose.onNodeWithText("home").assertDoesNotExist()
        compose.runOnIdle {assertEquals(0f,positions.getValue("search").x,1f)}
    }
}
