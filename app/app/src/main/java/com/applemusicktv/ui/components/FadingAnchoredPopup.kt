package com.applemusicktv.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/**
 * No Layout node before Popup: its anchor remains the caller's metadata Box.
 * Only the separate popup window contains an animation layout. Keep the window
 * until the exit finishes, without changing the anchor rectangle or provider.
 */
@Composable
fun FadingAnchoredPopup(
    visibleState: MutableTransitionState<Boolean>,
    popupPositionProvider: PopupPositionProvider,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (visibleState.isIdle && !visibleState.currentState) return
    val openingMode = androidx.compose.ui.platform.LocalInputModeManager.current.inputMode
    val motion = LocalUiMotion.current
    val interactive = LocalMotionInteractive.current && visibleState.targetState
    com.applemusicktv.ui.navigation.ReturnFocusLayer {
    Popup(popupPositionProvider = popupPositionProvider, onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true)) {
        AnimatedVisibility(visibleState = visibleState, modifier = Modifier.motionInput(interactive),
            enter = fadeIn(tween(if (motion.enabled) motion.enterMs else 0)),
            exit = fadeOut(tween(if (motion.enabled) motion.exitMs else 0))) {
            CompositionLocalProvider(LocalMotionInteractive provides interactive, LocalMenuOpeningInputMode provides openingMode) { content() }
        }
    }
    }
}
