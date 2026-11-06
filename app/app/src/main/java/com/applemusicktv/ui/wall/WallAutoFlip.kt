package com.applemusicktv.ui.wall

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/** A flip already in motion finishes smoothly; selection only pauses the next scheduled flip. */
@Composable
internal fun WallAutoFlip(board: WallBoard, enabled: Boolean, eligible: () -> Set<Int>) {
    val latestEnabled by rememberUpdatedState(enabled)
    val latestEligible by rememberUpdatedState(eligible)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(board, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(wallFlipInterval())
                if (!latestEnabled) continue
                val active = board.beginFlip(latestEligible()) ?: continue
                try { animateWallFlip(board, active) } finally { board.finish(active) }
            }
        }
    }
}

internal suspend fun animateWallFlip(board: WallBoard, active: WallFlip) {
    active.angle.animateTo(180f, tween(800, easing = FastOutSlowInEasing)) {
        if (value >= 90f) board.commit(active)
    }
}
