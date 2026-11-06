package com.applemusicktv.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class TouchControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun tapAndDpadEachInvokeOnce() {
        var clicks = 0
        val focus = FocusRequester()
        compose.setContent {
            TouchSurface({ clicks++ }, Modifier.size(120.dp).focusRequester(focus).testTag("button")) {
                Text("Play")
            }
        }
        compose.onNodeWithTag("button").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, clicks); focus.requestFocus() }
        compose.onNodeWithTag("button").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(2, clicks) }
    }

    @Test fun disabledSurfaceDoesNotRespondToTouch() {
        var clicks = 0
        compose.setContent {
            TouchSurface({ clicks++ }, Modifier.size(120.dp).testTag("disabled"), enabled = false) {
                Text("Disabled")
            }
        }
        compose.onNodeWithTag("disabled").performTouchInput { click() }
        compose.runOnIdle { assertEquals(0, clicks) }
    }

    @Test fun cardLongPressDoesNotAlsoPlay() {
        var plays = 0
        var menus = 0
        compose.setContent {
            TouchCard({ plays++ }, Modifier.size(120.dp).testTag("card"), onLongClick = { menus++ }) {
                Text("Album")
            }
        }
        compose.onNodeWithTag("card").performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(1, menus); assertEquals(0, plays) }
        compose.onNodeWithTag("card").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, menus); assertEquals(1, plays) }
    }

    @Test fun scrollingCancelsCardActivation() {
        var plays = 0
        compose.setContent {
            LazyColumn(Modifier.size(240.dp).testTag("list")) {
                items(20) {
                    TouchCard({ plays++ }, Modifier.fillMaxWidth().height(90.dp)) { Text("Song $it") }
                }
            }
        }
        compose.onNodeWithTag("list").performTouchInput { swipeUp() }
        compose.runOnIdle { assertEquals(0, plays) }
    }

    @Test fun nestedControlDoesNotAlsoActivateParent() {
        var parent = 0
        var child = 0
        compose.setContent {
            TouchSurface({ parent++ }, Modifier.size(200.dp)) {
                TouchSurface({ child++ }, Modifier.size(80.dp).testTag("child")) { Text("More") }
            }
        }
        compose.onNodeWithTag("child", useUnmergedTree = true).performTouchInput { click() }
        compose.runOnIdle { assertEquals(0, parent); assertEquals(1, child) }
    }

    @Test fun recompositionUsesLatestAction() {
        var result = 0
        var value by mutableIntStateOf(1)
        compose.setContent {
            val captured = value
            TouchSurface({ result = captured }, Modifier.size(120.dp).testTag("button")) { Text("$captured") }
        }
        compose.runOnIdle { value = 2 }
        compose.onNodeWithTag("button").performTouchInput { click() }
        compose.runOnIdle { assertEquals(2, result) }
    }

    @Test fun seekDragCommitsOnlyOnRelease() {
        val seeks = mutableListOf<Long>()
        compose.setContent {
            TouchSeekBar(0, 100_000, { seeks.add(it) }, Modifier.width(300.dp).testTag("seek"))
        }
        compose.onNodeWithTag("seek").performTouchInput {
            down(centerLeft + androidx.compose.ui.geometry.Offset(16f, 0f))
            moveTo(center)
            moveTo(centerRight - androidx.compose.ui.geometry.Offset(20f, 0f))
        }
        compose.runOnIdle { assertTrue(seeks.isEmpty()) }
        compose.onNodeWithTag("seek").performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(1, seeks.size)
            assertTrue(seeks.single() in 80_000L..100_000L)
        }
    }

    @Test fun zeroDurationCannotSeek() {
        var seeks = 0
        compose.setContent {
            TouchSeekBar(0, 0, { seeks++ }, Modifier.width(300.dp).testTag("seek"))
        }
        compose.onNodeWithTag("seek").performTouchInput { click() }
        compose.runOnIdle { assertEquals(0, seeks) }
    }
}
