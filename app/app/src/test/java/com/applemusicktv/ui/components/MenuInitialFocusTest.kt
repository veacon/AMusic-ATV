package com.applemusicktv.ui.components

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], application=Application::class, qualifiers="w960dp-h540dp-land-mdpi")
class MenuInitialFocusTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun delayedTargetReceivesFocusWhenAvailable() {
        var ready by mutableStateOf(false)
        compose.setContent {
            val first = remember { FocusRequester() }
            val mode = LocalInputModeManager.current
            SideEffect { mode.requestInputMode(InputMode.Keyboard) }
            Column(Modifier.menuInitialFocus(first, targetAvailable=ready)) {
                if (ready) androidx.compose.foundation.layout.Box(Modifier.size(60.dp).focusRequester(first).focusable().testTag("first"))
            }
        }
        compose.runOnIdle { ready=true }
        compose.onNodeWithTag("first").assertIsFocused()
    }

    @Test fun existingUserFocusIsNotTakenAfterTargetAppears() {
        var ready by mutableStateOf(false)
        val second = FocusRequester()
        compose.setContent {
            val first = remember { FocusRequester() }
            val mode = LocalInputModeManager.current
            SideEffect { mode.requestInputMode(InputMode.Keyboard) }
            Column(Modifier.menuInitialFocus(first, targetAvailable=ready)) {
                androidx.compose.foundation.layout.Box(Modifier.size(60.dp).focusRequester(second).focusable().testTag("second"))
                if (ready) androidx.compose.foundation.layout.Box(Modifier.size(60.dp).focusRequester(first).focusable().testTag("first"))
            }
        }
        compose.runOnIdle { second.requestFocus(); ready=true }
        compose.onNodeWithTag("second").assertIsFocused()
    }

    @Test fun waitsForWindowFocusBeforeRequesting() {
        var ready by mutableStateOf(false)
        compose.setContent {
            val first = remember { FocusRequester() }
            val mode = LocalInputModeManager.current
            val window = remember { object : androidx.compose.ui.platform.WindowInfo {
                override val isWindowFocused: Boolean get() = ready
            } }
            SideEffect { mode.requestInputMode(InputMode.Keyboard) }
            CompositionLocalProvider(androidx.compose.ui.platform.LocalWindowInfo provides window) {
                Column(Modifier.menuInitialFocus(first)) {
                    androidx.compose.foundation.layout.Box(Modifier.size(60.dp).focusRequester(first).focusable().testTag("first"))
                }
            }
        }
        compose.onNodeWithTag("first").assertIsNotFocused()
        compose.runOnIdle { ready=true }
        compose.onNodeWithTag("first").assertIsFocused()
    }

    @Test fun touchModeDoesNotForceFocus() {
        compose.setContent {
            val first = remember { FocusRequester() }
            val mode = LocalInputModeManager.current
            SideEffect { mode.requestInputMode(InputMode.Keyboard) }
            CompositionLocalProvider(LocalMenuOpeningInputMode provides InputMode.Touch) {
                Column(Modifier.menuInitialFocus(first)) {
                    androidx.compose.foundation.layout.Box(Modifier.size(60.dp).focusRequester(first).focusable().testTag("first"))
                }
            }
        }
        compose.onNodeWithTag("first").assertIsNotFocused()
    }
}
