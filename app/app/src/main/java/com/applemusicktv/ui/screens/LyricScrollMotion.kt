package com.applemusicktv.ui.screens

import android.os.SystemClock
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import kotlin.math.abs

internal const val LYRIC_SCROLL_DURATION_MS = 550
private const val RETARGET_WINDOW_MS = 150L

/** A soft S-curve; retain forward velocity when a new line interrupts the previous move. */
internal fun lyricScrollEasing(distance: Float, velocity: Float): CubicBezierEasing {
    val forwardVelocity = if (distance * velocity > 0f) abs(velocity) else 0f
    val firstY = if (abs(distance) > 0.001f)
        (forwardVelocity * (LYRIC_SCROLL_DURATION_MS / 1000f) / abs(distance) / 3f).coerceIn(0f, 1f)
    else 0f
    return CubicBezierEasing(1f / 3f, firstY, 2f / 3f, 1f)
}

/** Remember only the most recent applied velocity; never carry it across user or long jumps. */
internal class LyricScrollMotion {
    private var lastVelocity = 0f
    private var lastFrameMs = Long.MIN_VALUE

    fun reset() { lastVelocity = 0f; lastFrameMs = Long.MIN_VALUE }

    suspend fun scrollBy(state: LazyListState, distance: Float) {
        val elapsed = SystemClock.uptimeMillis() - lastFrameMs
        val inherited = if (elapsed in 0..RETARGET_WINDOW_MS) lastVelocity else 0f
        var previous = 0f
        var completed = false
        try {
            state.scroll {
                animate(0f, distance, animationSpec = tween(
                    LYRIC_SCROLL_DURATION_MS, easing = lyricScrollEasing(distance, inherited)
                )) { value, velocity ->
                    val desired = value - previous
                    val consumed = scrollBy(desired)
                    previous = value
                    lastVelocity = if (abs(consumed - desired) < 0.5f) velocity else 0f
                    lastFrameMs = SystemClock.uptimeMillis()
                }
            }
            completed = true
        } finally {
            if (completed) reset()
        }
    }
}
