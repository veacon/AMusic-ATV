package com.applemusicktv.ui.screens

import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.input.key.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties

/** Explicit sort requests must bypass the page's usual automatic list entry. */
internal class LibrarySortFocusRoute(val control: FocusRequester = FocusRequester()) {
    var requestingSort = false
        private set
    fun request(): Boolean {
        requestingSort = true
        return try { runCatching {control.requestFocus()}.getOrDefault(false) } finally { requestingSort = false }
    }
}

internal fun Modifier.libraryEntryFocus(list: FocusRequester, ready: Boolean, restoring: Boolean,
    sort: LibrarySortFocusRoute): Modifier = focusProperties {
    onEnter = { if (ready && !restoring && !sort.requestingSort) list.requestFocus() }
}

internal val LocalLibrarySortFocus = compositionLocalOf<LibrarySortFocusRoute?> {null}

/** Linear lists use Right; grid items opt in only at their right edge. */
@Composable
internal fun Modifier.librarySortOnRight(enabled: Boolean=true): Modifier {
    val route=LocalLibrarySortFocus.current
    return onPreviewKeyEvent {event ->
        enabled && route!=null && event.type==KeyEventType.KeyDown && event.key==Key.DirectionRight && route.request()
    }
}
