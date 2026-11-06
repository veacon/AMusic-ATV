package com.applemusicktv.ui.wall

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** Handle wall taps at the common ancestor, above the passive full-screen foreground input layer. */
@Composable
internal fun Modifier.wallTouchNavigation(wallVisible: Boolean, selectionMode: Boolean,
    cards: List<CachedWallCard>, controlsVisible: () -> Boolean, onWake: () -> Unit,
    onOpen: (Int, CachedWallCard) -> Unit, blocked: (Int) -> Boolean = { false }): Modifier {
    val active by rememberUpdatedState(wallVisible)
    val selecting by rememberUpdatedState(selectionMode)
    val currentCards by rememberUpdatedState(cards)
    val visible by rememberUpdatedState(controlsVisible)
    val wake by rememberUpdatedState(onWake)
    val open by rememberUpdatedState(onOpen)
    val isBlocked by rememberUpdatedState(blocked)
    val density by rememberUpdatedState(LocalDensity.current.density)
    return pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val hidden = !visible()
            val snapshot = currentCards.toList()
            val side = if (size.height > size.width) (size.width * .07f).coerceIn(20 * density, 48 * density) else 48 * density
            val footer = if (selecting) 0f else side + 88 * density
            val hit = if (!hidden && active && snapshot.isNotEmpty())
                wallTapIndex(down.position.x, down.position.y, size.width, size.height, footer) else null
            if (hidden || hit != null) {
                if (hidden) wake()
                down.consume()
                var cancelled = hit != null && isBlocked(hit)
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (event.changes.size > 1 || change == null ||
                        (change.position - down.position).getDistance() > viewConfiguration.touchSlop) cancelled = true
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
                if (!hidden && !cancelled && hit != null && !isBlocked(hit) && currentCards.isNotEmpty() &&
                    currentCards[hit % currentCards.size].info.key == snapshot[hit % snapshot.size].info.key)
                    open(hit, snapshot[hit % snapshot.size])
            }
        }
    }
}
