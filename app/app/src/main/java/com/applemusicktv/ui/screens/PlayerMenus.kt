package com.applemusicktv.ui.screens

import com.applemusicktv.i18n.tr
import com.applemusicktv.R

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.PopupPositionProvider
import com.applemusicktv.ui.components.Glyph
import com.applemusicktv.ui.components.FadingAnchoredPopup
import com.applemusicktv.ui.components.menuInitialFocus
import com.applemusicktv.ui.viewmodel.PlaybackOrder

/** Shared by the existing artist/album menu and the new lyrics menu. */
@Composable
internal fun PlayerBubbleMenu(visibility: MutableTransitionState<Boolean>, onDismiss: () -> Unit,
    initialFocus: FocusRequester? = null, focusAvailable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit) {
    val position = remember {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize) = IntOffset(
                (anchorBounds.left + (anchorBounds.width-popupContentSize.width)/2)
                    .coerceIn(0,(windowSize.width-popupContentSize.width).coerceAtLeast(0)),
                (anchorBounds.top-popupContentSize.height).coerceAtLeast(0))
        }
    }
    FadingAnchoredPopup(visibleState=visibility,popupPositionProvider=position,onDismissRequest=onDismiss) {
        Column(Modifier.menuInitialFocus(initialFocus, visibility.targetState, focusAvailable),
            horizontalAlignment=Alignment.CenterHorizontally) {
            Column(Modifier.width(230.dp).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(6.dp),content=content)
            Canvas(Modifier.size(16.dp,8.dp)) {
                drawPath(Path().apply {moveTo(0f,0f);lineTo(size.width,0f);lineTo(size.width/2f,size.height);close()},Color.White)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
internal fun LyricsOptionsButton(hidden: Boolean, expanded: Boolean, onExpandedChange: (Boolean)->Unit,
    onToggleLyrics: ()->Unit, onFullScreen: ()->Unit, visualScale: Float=1f) {
    val visibility=remember {MutableTransitionState(false)}
    visibility.targetState=expanded
    val firstFocus=remember {FocusRequester()}
    val fullFocus=remember {FocusRequester()}
    Box(Modifier.size(36.dp)) {
        PlayerAccessoryButton(Glyph.LYRICS,tr(R.string.msg_140),selected=false,visualScale=visualScale,
            onContextMenu={onExpandedChange(true)}) {onExpandedChange(!expanded)}
        PlayerBubbleMenu(visibility,onDismiss={onExpandedChange(false)},initialFocus=firstFocus) {
            NpMenuItem(if(hidden) tr(R.string.msg_141) else tr(R.string.msg_142),Modifier.focusRequester(firstFocus).focusProperties {down=fullFocus},
                icon=Glyph.LYRICS,iconAlpha=if(hidden) 1f else 0.5f) {onExpandedChange(false);onToggleLyrics()}
            NpMenuItem(tr(R.string.msg_143),Modifier.focusRequester(fullFocus).focusProperties {up=firstFocus},
                icon=Glyph.FULLSCREEN) {onExpandedChange(false);onFullScreen()}
        }
    }
}

@Composable
internal fun PlaybackOrderButton(order: PlaybackOrder, visualScale: Float=1f, onClick: ()->Unit) {
    val glyph=when(order) {
        PlaybackOrder.Shuffle -> Glyph.SHUFFLE
        PlaybackOrder.RepeatOne -> Glyph.REPEAT_ONE
        else -> Glyph.REPEAT
    }
    PlayerAccessoryButton(glyph,tr(R.string.msg_144, order.label),order!=PlaybackOrder.Sequential,
        visualScale=visualScale,onClick=onClick)
}

@Composable
internal fun PlayerFavoriteButton(enabled: Boolean=true, visualScale: Float=1f, onAdd: ()->Unit) {
    PlayerAccessoryButton(Glyph.STAR,tr(R.string.msg_145),visualScale=visualScale) {if(enabled) onAdd()}
}
