package com.applemusicktv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** Touch scrubbing previews locally and commits once on release, not on every move. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun TouchSeekBar(
    progressMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    trackHeight: Dp = 3.dp,
    loading: Boolean = false,
    onInteraction: () -> Unit = {},
) {
    var pending by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(loading) { if (loading) pending = null }
    val duration = durationMs.coerceAtLeast(1)
    val current = (progressMs.toFloat() / duration).coerceIn(0f, 1f)
    Slider(
        value = pending ?: current,
        onValueChange = { onInteraction(); pending = it },
        onValueChangeFinished = {
            onInteraction()
            if (!loading) pending?.let { onSeek((it * duration).toLong().coerceIn(0, duration)) }
            pending = null
        },
        enabled = durationMs > 0 && !loading,
        colors = SliderDefaults.colors(
            thumbColor = Color.White,
            activeTrackColor = Color.White,
            inactiveTrackColor = Color(0x44FFFFFF),
        ),
        thumb = { Spacer(Modifier.width(0.dp).height(24.dp)) },
        track = { slider ->
            // Keep Slider's normal touch target, but lower the visible track so the time labels
            // below sit closer to it without shrinking the interactive area.
            val trackModifier = Modifier.fillMaxWidth().offset(y = 10.dp).height(trackHeight)
            if (loading) PlayerLoadingTrack(trackModifier)
            else Box(trackModifier.background(Color(0x44FFFFFF), RoundedCornerShape(2.dp))) {
                Box(Modifier.fillMaxWidth(slider.value.coerceIn(0f, 1f)).fillMaxHeight()
                    .background(Color.White, RoundedCornerShape(2.dp)))
            }
        },
        modifier = modifier,
    )
}
