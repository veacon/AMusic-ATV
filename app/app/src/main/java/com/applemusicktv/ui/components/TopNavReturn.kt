package com.applemusicktv.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent

/** Only attach to the first content row (or fixed header), never the whole list.
 * Bypass spatial search between the scrolling content group and overlay tabs.
 * Descendant controls and lazy rows cannot consume Up before this boundary.
 */
fun Modifier.returnToTabOnUp(onReturn: (() -> Unit)?): Modifier =
    if (onReturn == null) this else onPreviewKeyEvent { event ->
        if (event.key != Key.DirectionUp) false
        else {
            if (event.type == KeyEventType.KeyDown) onReturn()
            true
        }
    }

/** Restore the content padding after focus has actually reached the tab bar.
 * Clearing content focus first avoids fighting the list's bring-into-view scroll.
 */
@Composable
fun rememberTabAwareListState(tabBarFocused: Boolean): LazyListState {
    val state = rememberLazyListState()
    val returning = com.applemusicktv.ui.navigation.LocalNavigationReturn.current?.restoring == true
    LaunchedEffect(tabBarFocused, returning) {
        if (tabBarFocused && !returning) state.animateScrollToItem(0)
    }
    return state
}

@Composable
fun rememberTabAwareGridState(tabBarFocused: Boolean): LazyGridState {
    val state = rememberLazyGridState()
    val returning = com.applemusicktv.ui.navigation.LocalNavigationReturn.current?.restoring == true
    LaunchedEffect(tabBarFocused, returning) {
        if (tabBarFocused && !returning) state.animateScrollToItem(0)
    }
    return state
}


/** First interactive shelf, independent of preceding decorative headers or empty sections. */
internal class FirstTabReturnItem(private val onReturn: (() -> Unit)?) {
    private var claimed = false
    fun claim(): (() -> Unit)? = if (claimed) null else onReturn.also { claimed = true }
}

internal fun androidx.compose.foundation.lazy.LazyListScope.tabReturnItem(
    onReturn: (() -> Unit)?, content: @Composable () -> Unit,
) {
    item { androidx.compose.foundation.layout.Box(Modifier.returnToTabOnUp(onReturn)) { content() } }
}
