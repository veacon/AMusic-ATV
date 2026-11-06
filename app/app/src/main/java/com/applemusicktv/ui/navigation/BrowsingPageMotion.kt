package com.applemusicktv.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import com.applemusicktv.ui.components.UiMotion

/** Browsing/settings roots crossfade; player and detail transitions retain their existing behavior. */
object BrowsingPageMotion {
    private val roots = listOf(Screen.NowPlaying.route, Screen.Home.route, Screen.Browse.route, Screen.Library.route, Screen.Search.route, Screen.DevMenu.route)

    /** Positive = move towards the right-hand tab; incoming page starts on the right. */
    fun direction(from: String?, to: String?): Int {
        val before = roots.indexOf(from)
        val after = roots.indexOf(to)
        if (before < 0 || after < 0) return 0
        return (after - before).compareTo(0)
    }

    private val browsingRoots = roots.filterNot { it == Screen.NowPlaying.route }.toSet()
    internal fun browsingFade(from: String?, to: String?): Boolean =
        from != to && from in browsingRoots && to in browsingRoots

    // Only player/root transitions still translate; retain V11's 500ms duration.
    private const val durationMs = PlayerEntryMotion.crossfadeMs
    // Symmetric, gradual acceleration/deceleration for the full-width content pane.
    // Both pages use the same curve so their shared edge remains contiguous.
    internal val pageEasing = TabMotion.easing

    // Settings has no horizontal tab position. Finish fading its outgoing content
    // before revealing incoming content to avoid overlapping text in the same place.
    internal fun settingsFade(from: String?, to: String?): Boolean =
        from != to && (from == Screen.DevMenu.route || to == Screen.DevMenu.route) &&
            from != Screen.NowPlaying.route && to != Screen.NowPlaying.route

    fun enter(from: String?, to: String?, motion: UiMotion): EnterTransition {
        if (browsingFade(from, to)) return fadeIn(tween(
            if (motion.enabled) TabMotion.durationMillis else 0, easing = pageEasing))
        val rootDirection=direction(from,to)
        if(rootDirection!=0 && motion.enabled) return slideInHorizontally(tween(durationMs,easing=pageEasing)) { rootDirection * it }

        if (settingsFade(from, to)) return fadeIn(tween(
            durationMillis = if (motion.enabled) motion.enterMs else 0,
            delayMillis = if (motion.enabled) motion.exitMs else 0,
        ))
        if (from == Screen.NowPlaying.route && to != Screen.NowPlaying.route) {
            return fadeIn(tween(
                PlayerEntryMotion.duration(motion),
                easing = PlayerEntryMotion.easing,
            ))
        }
        if (to == Screen.NowPlaying.route && from != Screen.NowPlaying.route) {
            return fadeIn(tween(
                PlayerEntryMotion.duration(motion),
                easing = PlayerEntryMotion.easing,
            ))
        }
        val direction = direction(from, to)
        return if (motion.enabled && direction != 0) {
            slideInHorizontally(tween(durationMs, easing = pageEasing)) { direction * it }
        } else fadeIn(tween(if (motion.enabled) motion.enterMs else 0))
    }

    fun exit(from: String?, to: String?, motion: UiMotion): ExitTransition {
        if (browsingFade(from, to)) return fadeOut(tween(
            if (motion.enabled) TabMotion.durationMillis else 0, easing = pageEasing))
        val rootDirection=direction(from,to)
        if(rootDirection!=0 && motion.enabled) return slideOutHorizontally(tween(durationMs,easing=pageEasing)) { -rootDirection * it }

        if (settingsFade(from, to)) return fadeOut(tween(if (motion.enabled) motion.exitMs else 0))
        // Keep both full pages composed and crossfade them together in either direction.
        if ((to == Screen.NowPlaying.route && from != Screen.NowPlaying.route) ||
            (from == Screen.NowPlaying.route && to != Screen.NowPlaying.route)) {
            return fadeOut(tween(
                PlayerEntryMotion.duration(motion),
                easing = PlayerEntryMotion.easing,
            ))
        }
        val direction = direction(from, to)
        return if (motion.enabled && direction != 0) {
            slideOutHorizontally(tween(durationMs, easing = pageEasing)) { -direction * it }
        } else fadeOut(tween(
            durationMillis = if (motion.enabled) motion.exitMs else 0,
            // Keep the outgoing page under the incoming page until it is visible.
            delayMillis = if (motion.enabled) (motion.enterMs - motion.exitMs).coerceAtLeast(0) else 0,
        ))
    }
}
