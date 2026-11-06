package com.applemusicktv.ui.navigation

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Drive the real activity dispatcher, not KEYCODE_BACK, through NavHost.
 * Touch deliberately never acknowledges a tab focus request, as on the failing path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class RootPageBackHandlerTest {
    @get:Rule val compose = createComposeRule()
    private class Mode(initial: InputMode) : InputModeManager {
        override var inputMode by mutableStateOf(initial)
        override fun requestInputMode(inputMode: InputMode): Boolean {
            this.inputMode = inputMode
            return true
        }
    }
    private lateinit var mode: Mode
    private lateinit var dispatcher: OnBackPressedDispatcher
    private lateinit var nav: NavHostController
    private var navFocused by mutableStateOf(false)
    private var childOpen by mutableStateOf(false)
    private var focusRequests = 0
    private var exitRequests = 0
    private fun mount(input: InputMode) {
        mode = Mode(input)
        compose.setContent {
            CompositionLocalProvider(LocalInputModeManager provides mode) {
                dispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
                nav = rememberNavController()
                NavHost(nav, startDestination = "home") {
                    listOf("home", "browse", "library", "search").forEach { route ->
                        composable(route) {
                            RootPageBackHandler(
                                enabled = true,
                                isHome = route == "home",
                                navBarHasFocus = navFocused,
                                onFocusTab = { focusRequests++ },
                                onReturnHome = { nav.popBackStack("home", inclusive = false) },
                                onRequestExit = { exitRequests++ },
                            )
                            // Same child-after-root ordering as a search editor / menu.
                            BackHandler(childOpen) { childOpen = false }
                            Text(route)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }
    private fun back() { compose.runOnIdle { dispatcher.onBackPressed() }; compose.waitForIdle() }

    @Test fun touchHomeCanRequestExitRepeatedlyWithoutAnyKeyboardFocus() {
        mount(InputMode.Touch)
        repeat(3) { back() }
        compose.runOnIdle {
            assertEquals(3, exitRequests)
            assertEquals(0, focusRequests)
            assertFalse(navFocused)
        }
    }

    @Test fun touchEachOtherRootReturnsHomeThenRequestsExit() {
        mount(InputMode.Touch)
        listOf("browse", "library", "search").forEachIndexed { index, route ->
            compose.runOnIdle { nav.navigate(route) }
            compose.waitForIdle()
            back()
            compose.runOnIdle { assertEquals("home", nav.currentDestination?.route) }
            back()
            compose.runOnIdle { assertEquals(index + 1, exitRequests); assertEquals(0, focusRequests) }
        }
    }

    @Test fun remoteStillFocusesTabBeforeHomeExitOrRootNavigation() {
        mount(InputMode.Keyboard)
        back()
        compose.runOnIdle { assertEquals(1, focusRequests); assertEquals(0, exitRequests); navFocused = true }
        back()
        compose.runOnIdle { assertEquals(1, exitRequests); nav.navigate("library"); navFocused = false }
        compose.waitForIdle()
        back()
        compose.runOnIdle { assertEquals("library", nav.currentDestination?.route); assertEquals(2, focusRequests); navFocused = true }
        back()
        compose.runOnIdle { assertEquals("home", nav.currentDestination?.route); assertEquals(1, exitRequests) }
    }

    @Test fun switchingFromRemoteToTouchDoesNotKeepTheKeyboardFocusGate() {
        mount(InputMode.Keyboard)
        back()
        compose.runOnIdle { assertEquals(1, focusRequests); mode.inputMode = InputMode.Touch }
        back()
        compose.runOnIdle { assertEquals(1, exitRequests); assertEquals(1, focusRequests) }
    }

    @Test fun childEditorOrMenuClosesBeforeRootExitAndCanBeReopened() {
        mount(InputMode.Touch)
        compose.runOnIdle { childOpen = true }
        back()
        compose.runOnIdle { assertFalse(childOpen); assertEquals(0, exitRequests) }
        back()
        compose.runOnIdle { assertEquals(1, exitRequests); childOpen = true }
        back()
        compose.runOnIdle { assertFalse(childOpen); assertEquals(1, exitRequests); assertEquals(0, focusRequests) }
    }
}
