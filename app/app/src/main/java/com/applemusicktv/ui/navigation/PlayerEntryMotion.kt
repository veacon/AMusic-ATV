package com.applemusicktv.ui.navigation

import androidx.compose.animation.core.FastOutSlowInEasing
import com.applemusicktv.ui.components.UiMotion

/** Shared timing for a full-page player/content crossfade. */
object PlayerEntryMotion {
    const val crossfadeMs = 500
    val easing = FastOutSlowInEasing

    fun duration(motion: UiMotion): Int = if (motion.enabled) crossfadeMs else 0
}
