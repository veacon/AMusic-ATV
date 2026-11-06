package com.applemusicktv.ui.components

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalWindowInfo

internal val LocalMenuOpeningInputMode = compositionLocalOf<InputMode?> { null }

/** One initial focus handoff per open menu; user navigation always wins. */
@Composable
internal fun Modifier.menuInitialFocus(target: FocusRequester?, enabled: Boolean = true,
    targetAvailable: Boolean = true): Modifier {
    val interactive = LocalMotionInteractive.current
    val mode = LocalMenuOpeningInputMode.current ?: LocalInputModeManager.current.inputMode
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    var placed by remember { mutableStateOf(false) }
    var stopped by remember { mutableStateOf(false) }
    LaunchedEffect(target, enabled, interactive, targetAvailable, mode, windowFocused, placed, stopped) {
        if (target != null && enabled && interactive && targetAvailable && !stopped && placed && windowFocused && mode == InputMode.Keyboard) {
            repeat(120) {
                withFrameNanos { }
                if (stopped) return@LaunchedEffect
                if (runCatching { target.requestFocus() }.getOrDefault(false)) {
                    stopped = true
                    return@LaunchedEffect
                }
            }
            if (!stopped) android.util.Log.w("MenuInitialFocus", "Initial menu focus was not established")
        }
    }
    return this.onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key in setOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight, Key.Tab)) stopped = true
        false
    }.pointerInput(Unit) {
        awaitEachGesture { awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial); stopped = true }
    }.onGloballyPositioned { placed = true }
        .onFocusChanged { if (it.hasFocus && !targetAvailable) stopped = true }
        .focusGroup()
}
