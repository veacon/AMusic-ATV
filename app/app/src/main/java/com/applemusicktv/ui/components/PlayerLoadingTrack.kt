package com.applemusicktv.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics

/** A single short highlight slides within the existing track; no changing layout or seek target. */
@Composable
internal fun PlayerLoadingTrack(modifier: Modifier = Modifier) {
    val phase = if (LocalUiMotion.current.enabled) {
        val transition = rememberInfiniteTransition(label = "playerLoadingTrack")
        val position by transition.animateFloat(0f, 1f,
            infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
            label = "loadingTrackPosition")
        position
    } else 0.5f
    Canvas(modifier.semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }) {
        val radius = CornerRadius(size.height / 2f)
        drawRoundRect(Color(0x44FFFFFF), cornerRadius = radius)
        val width = size.width * 0.25f
        drawRoundRect(Color.White.copy(alpha = 0.85f),
            topLeft = Offset((size.width - width) * phase, 0f), size = Size(width, size.height), cornerRadius = radius)
    }
}
