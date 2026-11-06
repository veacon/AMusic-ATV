package com.applemusicktv.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager

/** A system gesture must not wait for keyboard focus on a selectable Tab.
 * Keep this handler inside the NavHost destination, before its child UI, so
 * child editor/dialog handlers retain priority over root-page navigation.
 */
@Composable
internal fun RootPageBackHandler(
    enabled: Boolean,
    isHome: Boolean,
    navBarHasFocus: Boolean,
    onFocusTab: () -> Unit,
    onReturnHome: () -> Unit,
    onRequestExit: () -> Unit,
) {
    val inputModeManager = LocalInputModeManager.current
    BackHandler(enabled) {
        // Read live at dispatch: a Pad can alternate touch and remote input.
        val directionalInput = inputModeManager.inputMode != InputMode.Touch
        when {
            directionalInput && !navBarHasFocus -> onFocusTab()
            isHome -> onRequestExit()
            else -> onReturnHome()
        }
    }
}
