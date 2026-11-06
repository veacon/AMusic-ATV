package com.applemusicktv.ui.wall

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.applemusicktv.ui.navigation.rememberReturnTarget
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.platform.testTag
import kotlin.math.ceil
import kotlin.math.roundToInt

@Composable
internal fun AlbumWallGrid(cards: List<CachedWallCard>, selectionMode: Boolean, selectedIndex: Int,
    onSelectedIndex: (Int) -> Unit, onOpen: (CachedWallCard) -> Unit,
    gridFocus: FocusRequester, touchEnabled: Boolean, board: WallBoard? = null, autoFlipEnabled: Boolean = false) {
    val (returnModifier, markOrigin) = rememberReturnTarget(selectionMode)
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF181818)).clipToBounds()) {
        if (cards.isNotEmpty()) {
            val width = constraints.maxWidth.toFloat(); val height = constraints.maxHeight.toFloat()
            val cell = minOf(width, height) / 5f
            val columns = ceil(width / cell).toInt().coerceAtLeast(1)
            val rows = ceil(height / cell).toInt().coerceAtLeast(1)
            val density = LocalDensity.current
            val index = if (selectedIndex < 0) wallCenterIndex(columns, rows)
                else selectedIndex.coerceIn(0, rows * columns - 1)
            val deck = remember(cards, columns, rows) { WallFloatingDeck(if (selectionMode) index else null) }
            // Do not float a face that is currently turning; it can be selected once it settles.
            val floatIndex = if (selectionMode && board?.flip?.tile != index) index else null
            SideEffect { deck.select(floatIndex) }
            LaunchedEffect(selectionMode, columns, rows) {
                if (selectionMode && selectedIndex < 0) onSelectedIndex(wallCenterIndex(columns, rows))
            }
            val portrait = height > width
            val side = if (portrait) (maxWidth * .07f).coerceIn(20.dp, 48.dp) else 48.dp
            val footer = with(density) { (side + 64.dp + 24.dp).toPx() }
            if (board != null) WallAutoFlip(board, autoFlipEnabled && !selectionMode) {
                (0 until rows * columns).filterTo(hashSetOf()) { tile ->
                    val bottom = ((tile / columns + 1) * cell).roundToInt()
                    // Prefer complete, unobscured covers; leave the cropped outer edge alone.
                    bottom <= height - footer && (tile % columns + 1) * cell <= width + .5f &&
                        deck.entries.none { it.tile == tile }
                }
            }
            Box(Modifier.fillMaxSize().focusRequester(gridFocus).then(returnModifier).testTag("wallSelectionGrid")
                .focusProperties { canFocus = selectionMode }
                .onPreviewKeyEvent { event ->
                    if (!selectionMode) false else {
                        val handled = event.key in setOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft,
                            Key.DirectionRight, Key.DirectionCenter, Key.Enter, Key.NumPadEnter)
                        if (handled && event.type == KeyEventType.KeyDown) when (event.key) {
                            Key.DirectionUp -> onSelectedIndex(wallMove(index, columns, rows, 0, -1))
                            Key.DirectionDown -> onSelectedIndex(wallMove(index, columns, rows, 0, 1))
                            Key.DirectionLeft -> onSelectedIndex(wallMove(index, columns, rows, -1, 0))
                            Key.DirectionRight -> onSelectedIndex(wallMove(index, columns, rows, 1, 0))
                            else -> if (event.nativeKeyEvent.repeatCount == 0) {
                                if (board?.flip?.tile != index) { markOrigin(); onOpen(cards[index % cards.size]) }
                            }
                        }
                        handled
                    }
                }.focusable()) {
                // This grid never scales or changes drawing order.
                for (row in 0 until rows) for (col in 0 until columns) {
                    val tile = row * columns + col
                    val card = cards[tile % cards.size]
                    val image = remember(card.bitmap) { card.bitmap.asImageBitmap() }
                    val left = (col * cell).roundToInt(); val top = (row * cell).roundToInt()
                    val right = ((col + 1) * cell).roundToInt(); val bottom = ((row + 1) * cell).roundToInt()
                    val allowed = wallTouchAllowed(bottom.toFloat(), height, footer)
                    Box(Modifier.offset { IntOffset(left, top) }
                        .size(with(density) { (right - left).toDp() }, with(density) { (bottom - top).toDp() })
                        .testTag("wallBase-$tile")
                        .semantics(mergeDescendants = true) {
                            if (touchEnabled && allowed) onClick { if (board?.flip?.tile != tile) onOpen(card); true }
                        }) {
                        val active = board?.flip?.takeIf { it.tile == tile }
                        Image(image, contentDescription = card.info.title,
                            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer {
                                rotationY = active?.let { wallFlipRotation(it.angle.value) } ?: 0f
                                cameraDistance = cell * 8f
                            })
                    }
                }
            }
            // Every existing floating entry keeps its place in this list. No zIndex handoff occurs.
            Box(Modifier.fillMaxSize()) {
                for (entry in deck.entries.toList()) key(entry) {
                    FloatingWallCard(entry, cards[entry.tile % cards.size], cell, columns, width, height,
                        selected = entry.tile == floatIndex,
                        onRest = { deck.removeIfResting(entry) })
                }
            }
        }
        val shadeAlpha by animateFloatAsState(if (selectionMode) 0f else 1f, tween(300), label = "wallFooterShade")
        if (shadeAlpha > 0f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = shadeAlpha }.background(Brush.verticalGradient(listOf(Color.Transparent,
            Color.Transparent, Color.Black.copy(alpha = .65f)))))
    }
}

@Composable
private fun FloatingWallCard(entry: WallFloatingEntry, card: CachedWallCard, cell: Float, columns: Int,
    viewportWidth: Float, viewportHeight: Float, selected: Boolean, onRest: () -> Unit) {
    val latestRest by rememberUpdatedState(onRest)
    LaunchedEffect(entry, selected) {
        entry.scale.animateTo(if (selected) 1.15f else 1f, tween(180))
        if (!selected) latestRest()
    }
    val image = remember(card.bitmap) { card.bitmap.asImageBitmap() }
    val scale = entry.scale.value
    val progress = wallFloatingOpacity(scale)
    val row = entry.tile / columns; val col = entry.tile % columns
    val left = (col * cell).roundToInt(); val top = (row * cell).roundToInt()
    val right = ((col + 1) * cell).roundToInt(); val bottom = ((row + 1) * cell).roundToInt()
    val extraX = (right - left) * (scale - 1) / 2
    val extraY = (bottom - top) * (scale - 1) / 2
    // Apply edge adjustment to both outgoing and incoming cards, so shrinking never snaps position.
    val shiftX = when {
        left - extraX < 0 -> extraX - left
        right + extraX > viewportWidth -> viewportWidth - right - extraX
        else -> 0f
    }
    val shiftY = when {
        top - extraY < 0 -> extraY - top
        bottom + extraY > viewportHeight -> viewportHeight - bottom - extraY
        else -> 0f
    }
    val density = LocalDensity.current
    Box(Modifier.offset { IntOffset(left, top) }
        .size(with(density) { (right - left).toDp() }, with(density) { (bottom - top).toDp() })
        .testTag("wallFloating-${entry.tile}")
        .graphicsLayer {
            scaleX = scale; scaleY = scale; translationX = shiftX; translationY = shiftY
            alpha = progress
            shadowElevation = 14.dp.toPx() * progress; shape = RectangleShape; clip = false
        }) {
        Image(image, contentDescription = null,
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (progress > 0f) Box(Modifier.fillMaxSize()
            .border(2.dp, Color.White.copy(alpha = .8f), RectangleShape))
    }
}
