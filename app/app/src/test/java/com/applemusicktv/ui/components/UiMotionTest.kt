package com.applemusicktv.ui.components

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Host-side Compose clock tests; no account, player, network or device UI. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class UiMotionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun rapidSwitchRendersOnlyLatestAndNeverMountsTwoLists() {
        var page by mutableStateOf("A")
        var active = 0
        var peak = 0
        var rendered = ""
        compose.setContent {
            FadeThrough(page) { value ->
                DisposableEffect(value) {
                    active++; peak = maxOf(peak, active)
                    onDispose { active-- }
                }
                SideEffect { rendered = value }
                Text(value)
            }
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { page = "B"; Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeBy(32)
        compose.runOnIdle { page = "C"; Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeBy(600)
        compose.runOnIdle { assertEquals("C", rendered); assertEquals(1, peak) }
        compose.onNodeWithText("C").assertExists()
        compose.onNodeWithText("B").assertDoesNotExist()
    }

    @Test fun reversalDuringExitRestoresOriginalWithoutBlankOrStaleState() {
        var page by mutableStateOf("A")
        compose.setContent { FadeThrough(page) { Text(it) } }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { page = "B"; Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeBy(32)
        compose.runOnIdle { page = "A"; Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("A").assertExists()
        compose.onNodeWithText("B").assertDoesNotExist()
    }

    @Test fun sameKeyUpdatesKeepRememberedStateAndDoNotBlockInput() {
        var data by mutableStateOf("list" to "old")
        var mounts = 0
        compose.setContent {
            FadeThrough(data, contentKey = { it.first }) {
                DisposableEffect(Unit) { mounts++; onDispose { } }
                TouchSurface({}, Modifier.testTag("action")) { Text(it.second) }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { data = "list" to "new"; Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeBy(48)
        compose.waitForIdle()
        compose.onNodeWithText("new").assertExists()
        compose.onNodeWithTag("action").assertIsEnabled()
        compose.runOnIdle { assertEquals(1, mounts) }
    }

    @Test fun outgoingRegionCannotBeActivatedByTouchOrAccessibility() {
        var page by mutableStateOf("A")
        var clicks = 0
        compose.setContent {
            Box(Modifier.size(200.dp).background(Color.Black).testTag("host")) {
                FadeThrough(page, Modifier.fillMaxSize()) {
                    TouchSurface({ clicks++ }, Modifier.fillMaxSize().testTag("action")) { Text(it) }
                }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { page = "B"; Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeBy(48)
        compose.waitForIdle()
        compose.onNodeWithTag("action").assertDoesNotExist()
        compose.onNodeWithTag("host").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(0, clicks) }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("action").performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test fun reduceMotionSwitchesImmediatelyWithoutWaitingForClock() {
        var page by mutableStateOf("A")
        compose.setContent {
            CompositionLocalProvider(LocalUiMotion provides UiMotion(enabled = false)) {
                FadeThrough(page) { Text(it) }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { page = "B"; Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeBy(48)
        compose.waitForIdle()
        compose.onNodeWithText("B").assertExists()
    }

    @Test fun visibilityRetainsPixelsForExitButRemovesActionsImmediately() {
        var visible by mutableStateOf(true)
        var mounted = false
        compose.setContent {
            FadeVisibility(visible) {
                DisposableEffect(Unit) { mounted = true; onDispose { mounted = false } }
                TouchSurface({}, Modifier.testTag("action")) { Text("Menu") }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { visible = false; Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeBy(48)
        compose.waitForIdle()
        compose.onNodeWithTag("action").assertDoesNotExist()
        compose.runOnIdle { assertTrue(mounted) }
        compose.mainClock.advanceTimeBy(600)
        compose.runOnIdle { assertFalse(mounted) }
    }

    @Test fun outgoingContentCannotRegainRemoteFocus() {
        var page by mutableStateOf("A")
        val focus = FocusRequester()
        compose.setContent {
            FadeThrough(page) {
                TouchSurface({}, Modifier.size(120.dp).focusRequester(focus).testTag("action")) { Text(it) }
            }
        }
        compose.runOnIdle { assertTrue(focus.requestFocus()) }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { page = "B"; Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeBy(48)
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(focus.requestFocus()) }
        compose.mainClock.advanceTimeBy(600)
        compose.runOnIdle { assertTrue(focus.requestFocus()) }
        compose.onNodeWithTag("action").assertIsFocused()
    }

    @Test fun nullableDialogSurvivesExitWithoutKeepingStaleActions() {
        var payload by mutableStateOf<String?>(null)
        var mounted = false
        compose.setContent {
            FadeThrough(payload) { value ->
                if (value != null) MotionDialog(onDismissRequest = { payload = null }) {
                    DisposableEffect(Unit) { mounted = true; onDispose { mounted = false } }
                    TouchSurface({}, Modifier.testTag("menu")) { Text(value) }
                }
            }
        }
        compose.runOnIdle { payload = "Menu" }
        compose.onNodeWithTag("menu").assertExists()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { payload = null; Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeBy(48)
        compose.waitForIdle()
        compose.onNodeWithTag("menu").assertDoesNotExist()
        compose.runOnIdle { assertTrue(mounted) }
        compose.mainClock.advanceTimeBy(600)
        compose.runOnIdle { assertFalse(mounted) }
    }

    @Test fun booleanFalseLayoutStillGetsAnExitBeforeReplacement() {
        var fullscreen by mutableStateOf(false)
        var shown = false
        compose.setContent {
            FadeThrough(fullscreen) { value ->
                SideEffect { shown = value }
                Text(if (value) "Fullscreen" else "Split")
            }
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { fullscreen = true; Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeBy(48)
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(shown) }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("Fullscreen").assertExists()
    }

    @Test fun stateHandoffNeverBecomesFullyTransparentAndStillMountsOnlyOneSubtree() {
        var page by mutableStateOf("A")
        var opacity = 1f
        var shown = ""
        var active = 0
        var peak = 0
        compose.setContent {
            FadeThrough(page) { value ->
                val frameAlpha = LocalRegionOpacity.current?.value ?: 1f
                SideEffect { opacity = frameAlpha; shown = value }
                DisposableEffect(value) {
                    active++; peak = maxOf(peak, active)
                    onDispose { active-- }
                }
                Text(value)
            }
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { page = "B"; Snapshot.sendApplyNotifications() }
        var lowest = 1f
        repeat(32) {
            compose.mainClock.advanceTimeBy(16)
            compose.waitForIdle()
            compose.runOnIdle {
                lowest = minOf(lowest, opacity)
                assertTrue("State handoff must keep visible pixels", opacity >= 0.349f)
            }
        }
        compose.runOnIdle {
            assertTrue("Transition must actually recede", lowest < 0.5f)
            assertEquals("B", shown)
            assertEquals(1f, opacity, 0.001f)
            assertEquals(1, peak)
        }
    }

    @Test fun dismissedNullableOverlayStillFadesToZeroBeforeRemoval() {
        var overlay by mutableStateOf<String?>("Menu")
        var opacity = 1f
        var shown: String? = "Menu"
        compose.setContent {
            FadeThrough(overlay) { value ->
                val frameAlpha = LocalRegionOpacity.current?.value ?: 1f
                SideEffect { opacity = frameAlpha; shown = value }
                if (value != null) Text(value)
            }
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { overlay = null; Snapshot.sendApplyNotifications() }
        var lastOldAlpha = 1f
        repeat(12) {
            compose.mainClock.advanceTimeBy(16)
            compose.waitForIdle()
            compose.runOnIdle { if (shown != null) lastOldAlpha = opacity }
        }
        compose.runOnIdle {
            assertNull(shown)
            assertTrue("Closing must not jump away at the state-handoff floor", lastOldAlpha < 0.05f)
        }
        compose.onNodeWithText("Menu").assertDoesNotExist()
    }

    @Test fun enablingReduceMotionMidHandoffRestoresFullyVisibleLatestContent() {
        var page by mutableStateOf("A")
        var reduced by mutableStateOf(false)
        var opacity = 1f
        compose.setContent {
            CompositionLocalProvider(LocalUiMotion provides UiMotion(enabled = !reduced)) {
                FadeThrough(page) {
                    val frameAlpha = LocalRegionOpacity.current?.value ?: 1f
                    SideEffect { opacity = frameAlpha }
                    Text(it)
                }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { page = "B"; Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeBy(64)
        compose.runOnIdle { reduced = true; Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeBy(48)
        compose.waitForIdle()
        compose.onNodeWithText("B").assertExists()
        compose.runOnIdle { assertEquals(1f, opacity, 0.001f) }
    }
}
