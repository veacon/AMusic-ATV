package com.applemusicktv.ui.navigation

import androidx.compose.animation.core.CubicBezierEasing

/** Shared clock for browsing/settings crossfade, navigation capsule and label colors. */
internal object TabMotion {
    const val durationMillis=200
    fun selectionDuration(from: TopNavTab, to: TopNavTab): Int =
        if (from == TopNavTab.NowPlaying || to == TopNavTab.NowPlaying)
            PlayerEntryMotion.crossfadeMs else durationMillis

    val easing=CubicBezierEasing(.37f,0f,.63f,1f)
}
