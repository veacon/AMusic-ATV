package com.applemusicktv.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*

/**
 * TV Material 1.0.0 handles D-pad keys but has no pointer click handler. Add pointer
 * gestures to the same controls, retaining their existing focus, semantics and styling.
 * Scroll gestures cancel the press, and long presses never also invoke the short click.
 */
@Composable
private fun Modifier.touchClicks(
    enabled: Boolean,
    source: MutableInteractionSource,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
): Modifier {
    val click by rememberUpdatedState(onClick)
    val longClick by rememberUpdatedState(onLongClick)
    return pointerInput(enabled, source, onLongClick != null) {
        if (!enabled) return@pointerInput
        detectTapGestures(
            onTap = { click() },
            onLongPress = if (onLongClick != null) ({ longClick?.invoke() }) else null,
            onPress = { position ->
                val press = PressInteraction.Press(position)
                source.emit(press)
                var released = false
                try {
                    released = tryAwaitRelease()
                } finally {
                    source.tryEmit(
                        if (released) PressInteraction.Release(press)
                        else PressInteraction.Cancel(press)
                    )
                }
            },
        )
    }
}

/** Pointer-only activation for controls whose D-pad focus is managed by their parent. */
@Composable
internal fun Modifier.touchClick(onClick: () -> Unit): Modifier {
    val interactive = LocalMotionInteractive.current
    return touchClicks(interactive, remember { MutableInteractionSource() }, onClick, null)
        .semantics(mergeDescendants = true) {
            role = Role.Button
            onClick { if (interactive) onClick(); interactive }
        }
}

@Composable
fun TouchSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    tonalElevation: Dp = 0.dp,
    shape: ClickableSurfaceShape = ClickableSurfaceDefaults.shape(),
    colors: ClickableSurfaceColors = ClickableSurfaceDefaults.colors(),
    scale: ClickableSurfaceScale = ClickableSurfaceDefaults.scale(),
    border: ClickableSurfaceBorder = ClickableSurfaceDefaults.border(),
    glow: ClickableSurfaceGlow = ClickableSurfaceDefaults.glow(),
    interactionSource: MutableInteractionSource? = null,
    onContextMenu: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val interactive = enabled && LocalMotionInteractive.current
    val focused by source.collectIsFocusedAsState()
    val showArtworkFocus = focused && androidx.compose.ui.platform.LocalInputModeManager.current.inputMode == androidx.compose.ui.input.InputMode.Keyboard
    val artworkHost = remember { ArtworkBorderHost() }
    val artworkBorder = artworkHost.border(showArtworkFocus)
    val (returnModifier, rememberOrigin) = com.applemusicktv.ui.navigation.rememberReturnTarget(interactive)
    androidx.tv.material3.Surface(
        onClick = { if (interactive) { rememberOrigin(); onClick() } },
        modifier = modifier.remoteContextMenu(interactive && focused, onContextMenu?.let { action -> { rememberOrigin(); action() } }).onGloballyPositioned(artworkHost::surfacePlaced).then(returnModifier).then(if (interactive) Modifier else Modifier.focusProperties { canFocus = false }).touchClicks(interactive, source, { rememberOrigin(); onClick() }, onLongClick?.let { action -> { rememberOrigin(); action() } }),
        onLongClick = onLongClick?.let { action -> { if (interactive) { rememberOrigin(); action() } } },
        enabled = enabled,
        tonalElevation = tonalElevation,
        shape = shape,
        colors = colors,
        scale = scale,
        border = artworkBorder?.let { ClickableSurfaceDefaults.border(border = Border.None, focusedBorder = it, pressedBorder = it) } ?: border,
        glow = glow,
        interactionSource = source,
        content = { CompositionLocalProvider(LocalArtworkFocused provides showArtworkFocus, LocalArtworkBorderHost provides artworkHost) { content() } },
    )
}

/** Non-interactive surfaces remain non-interactive, including dialog containers. */
@Composable
fun TouchSurface(
    modifier: Modifier = Modifier,
    tonalElevation: Dp = 0.dp,
    shape: Shape = SurfaceDefaults.shape,
    colors: SurfaceColors = SurfaceDefaults.colors(),
    border: Border = Border.None,
    glow: Glow = Glow.None,
    content: @Composable BoxScope.() -> Unit,
) = androidx.tv.material3.Surface(
    modifier = modifier,
    tonalElevation = tonalElevation,
    shape = shape,
    colors = colors,
    border = border,
    glow = glow,
    content = content,
)

@Composable
fun TouchCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    shape: CardShape = CardDefaults.shape(),
    colors: CardColors = CardDefaults.colors(),
    scale: CardScale = CardDefaults.scale(),
    border: CardBorder = CardDefaults.border(),
    glow: CardGlow = CardDefaults.glow(),
    interactionSource: MutableInteractionSource? = null,
    onContextMenu: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val interactive = LocalMotionInteractive.current
    val focused by source.collectIsFocusedAsState()
    val showArtworkFocus = focused && androidx.compose.ui.platform.LocalInputModeManager.current.inputMode == androidx.compose.ui.input.InputMode.Keyboard
    val artworkHost = remember { ArtworkBorderHost() }
    val artworkBorder = artworkHost.border(showArtworkFocus)
    val (returnModifier, rememberOrigin) = com.applemusicktv.ui.navigation.rememberReturnTarget(interactive)
    androidx.tv.material3.Card(
        onClick = { if (interactive) { rememberOrigin(); onClick() } },
        modifier = modifier.remoteContextMenu(interactive && focused, onContextMenu?.let { action -> { rememberOrigin(); action() } }).onGloballyPositioned(artworkHost::surfacePlaced).then(returnModifier).then(if (interactive) Modifier else Modifier.focusProperties { canFocus = false }).touchClicks(interactive, source, { rememberOrigin(); onClick() }, onLongClick?.let { action -> { rememberOrigin(); action() } }),
        onLongClick = onLongClick?.let { action -> { if(interactive) { rememberOrigin(); action() } } },
        shape = shape,
        colors = colors,
        scale = scale,
        border = artworkBorder?.let { CardDefaults.border(border = Border.None, focusedBorder = it, pressedBorder = it) } ?: border,
        glow = glow,
        interactionSource = source,
        content = { CompositionLocalProvider(LocalArtworkFocused provides showArtworkFocus, LocalArtworkBorderHost provides artworkHost) { content() } },
    )
}
