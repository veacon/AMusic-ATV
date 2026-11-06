package com.applemusicktv.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

val LocalMotionArtworkEnabled = staticCompositionLocalOf { false }

/** A bounded, muted texture loop over a static cover; never creates an audio output. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun MotionArtwork(
    url: String,
    play: Boolean,
    modifier: Modifier = Modifier,
    startDelayMs: Long = 1000L,
    maxVideoSize: Int = 480,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var foreground by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            foreground = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val requested = play && foreground && LocalMotionArtworkEnabled.current
    var settled by remember(url) { mutableStateOf(false) }
    LaunchedEffect(url, requested, startDelayMs) {
        if (requested) { kotlinx.coroutines.delay(startDelayMs); settled = true }
        else settled = false
    }
    val exo = remember(url, settled, requested, maxVideoSize) {
        if (!settled || !requested) null else {
            val selector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(context).apply {
                parameters = buildUponParameters().setMaxVideoSize(maxVideoSize, maxVideoSize)
                    .setMaxVideoBitrate(1_500_000).setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
            }
            ExoPlayer.Builder(context).setTrackSelector(selector).build().apply {
                setMediaItem(MediaItem.fromUri(url))
                repeatMode = Player.REPEAT_MODE_ALL
                volume = 0f
                playWhenReady = true
            }
        }
    }
    var ready by remember(exo) { mutableStateOf(false) }
    DisposableEffect(exo) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { ready = true }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) { ready = false }
        }
        exo?.addListener(listener)
        exo?.prepare()
        onDispose { exo?.removeListener(listener); exo?.release() }
    }
    val alpha by animateFloatAsState(if (ready && requested) 1f else 0f, tween(480), label = "motionArt")
    if (exo == null) return
    key(exo) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { ctx -> android.view.TextureView(ctx).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(-1, -1)
                isOpaque = false
                exo.setVideoTextureView(this)
            } },
            update = { view -> exo.setVideoTextureView(view) },
            modifier = modifier.graphicsLayer { this.alpha = alpha },
        )
    }
}
