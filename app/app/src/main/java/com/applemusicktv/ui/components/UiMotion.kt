package com.applemusicktv.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics

/** One finite opacity animation per region; no layout animation or duplicate list/decoder. */
data class UiMotion(val enabled: Boolean = true, val enterMs: Int = 270, val exitMs: Int = 120)
val LocalUiMotion = staticCompositionLocalOf { UiMotion() }
internal val LocalMotionInteractive = compositionLocalOf { true }
internal val LocalRegionOpacity = compositionLocalOf<State<Float>?> { null }

/**
 * Retain one state while it recedes, then replace at a shallow opacity trough
 * and ease the latest state in. Only a disappearing overlay goes fully transparent. Same-key updates (progress, paging, image loads)
 * update in place. Cancelling the effect on a new key implements latest-wins,
 * including reversal mid-exit; only one content subtree is ever composed.
 * Keep inputs, navigation and the GL background OUTSIDE this region.
 */
@Composable
fun <S> FadeThrough(
    targetState: S,
    modifier: Modifier = Modifier,
    contentKey: (S) -> Any? = { it },
    content: @Composable BoxScope.(S) -> Unit,
) {
    val motion = LocalUiMotion.current
    val targetKey = contentKey(targetState)
    var shownKey by remember { mutableStateOf(targetKey) }
    var snapshot by remember { mutableStateOf(targetState) }
    val latestState by rememberUpdatedState(targetState)
    val latestKey by rememberUpdatedState(targetKey)
    val opacity = remember { Animatable(1f) }
    val current = !motion.enabled || shownKey == targetKey
    val displayed = if (current) targetState else snapshot
    SideEffect { if (current) snapshot = targetState }
    LaunchedEffect(targetKey, motion) {
        if (!motion.enabled) {
            shownKey = latestKey
            opacity.snapTo(1f)
        } else {
            if (shownKey != latestKey) {
                // Opening an empty overlay has no outgoing pixels to wait for.
                if (shownKey == null) opacity.snapTo(0f)
                else {
                    // A state-to-state change never exposes a fully blank region.
                    // Null means a dismissed overlay: it must still fade all the way out.
                    val trough = if (latestKey == null) 0f else 0.35f
                    opacity.animateTo(trough, tween(motion.exitMs, easing = FastOutSlowInEasing))
                }
                snapshot = latestState
                shownKey = latestKey
            }
            opacity.animateTo(1f, tween(motion.enterMs, easing = FastOutSlowInEasing))
        }
    }
    val interactive = LocalMotionInteractive.current && current
    CompositionLocalProvider(LocalMotionInteractive provides interactive,
        LocalRegionOpacity provides if (motion.enabled) opacity.asState() else null) {
        Box(modifier
            .graphicsLayer { alpha = if (motion.enabled) opacity.value else 1f }
            .motionInput(interactive)
        ) { content(displayed) }
    }
}

/** Dialog content lives in another window, so it must read the region's opacity itself. */
@Composable
fun MotionDialog(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    val opacity = LocalRegionOpacity.current
    val interactive = LocalMotionInteractive.current
    val openingMode = androidx.compose.ui.platform.LocalInputModeManager.current.inputMode
    com.applemusicktv.ui.navigation.ReturnFocusLayer {
    androidx.compose.ui.window.Dialog(onDismissRequest = { if (interactive) onDismissRequest() }) {
        CompositionLocalProvider(LocalMenuOpeningInputMode provides openingMode) {
            Box(Modifier.graphicsLayer { alpha = opacity?.value ?: 1f }.motionInput(interactive)) { content() }
        }
    }
    }
}

/** Opacity never creates a focus target/group or consumes navigation/Back keys. */
fun Modifier.motionInput(interactive: Boolean): Modifier = this
    .then(if (interactive) Modifier else Modifier.focusProperties { canFocus = false })
    .onPreviewKeyEvent {
        !interactive && it.key in setOf(Key.Enter, Key.NumPadEnter, Key.DirectionCenter)
    }
    .pointerInput(interactive) {
        if (!interactive) awaitPointerEventScope {
            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
    .then(if (interactive) Modifier else Modifier.clearAndSetSemantics { })

/** No size/slide defaults; the exiting subtree is immediately non-interactive. */
@Composable
fun FadeVisibility(visible: Boolean, modifier: Modifier = Modifier, durationMs: Int? = null, content: @Composable () -> Unit) {
    val motion = LocalUiMotion.current
    val interactive = LocalMotionInteractive.current && visible
    AnimatedVisibility(visible, modifier.motionInput(interactive),
        enter = fadeIn(tween(if (motion.enabled) (durationMs ?: motion.enterMs) else 0)),
        exit = fadeOut(tween(if (motion.enabled) (durationMs ?: motion.exitMs) else 0))) {
        CompositionLocalProvider(LocalMotionInteractive provides interactive) { content() }
    }
}
