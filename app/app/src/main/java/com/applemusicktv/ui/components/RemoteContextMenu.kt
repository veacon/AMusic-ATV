package com.applemusicktv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*

/** Opt in only for actual context menus, not shuffle/pin/reorder long-press actions. */
@Composable
internal fun Modifier.remoteContextMenu(enabled: Boolean, action: (() -> Unit)?): Modifier {
    val latest by rememberUpdatedState(action)
    return onPreviewKeyEvent { event ->
        if (!enabled || latest == null || event.key != Key.Menu) false
        else {
            if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) latest?.invoke()
            true
        }
    }
}
