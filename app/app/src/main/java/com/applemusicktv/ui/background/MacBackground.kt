package com.applemusicktv.ui.background

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.Coil
import coil.request.ImageRequest
import com.applemusicktv.data.NetworkLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private data class MacArtworkFrame(val source: Bitmap, val fallback: Bitmap)

/** The only integration surface: sits behind the unchanged cover/lyrics/controls. */
@Composable
fun MacBackground(
    artworkUrl: String?,
    quality: FluidQuality,
    playing: Boolean,
    reduceMotion: Boolean,
    lowPower: Boolean,
    modifier: Modifier = Modifier,
) {
    if (quality == FluidQuality.OFF || artworkUrl == null) {
        Box(modifier.fillMaxSize().background(Color.Black))
        return
    }
    val sceneClock = remember { MacClock() }
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
    // Keep the old frame during download. A cancelled old request can never publish a new frame.
    val artwork by produceState<MacArtworkFrame?>(initialValue = null, artworkUrl, sceneClock.meshIndex) {
        try {
            val result = Coil.imageLoader(context).execute(ImageRequest.Builder(context)
                .data(artworkUrl).size(256).allowHardware(false)
                .bitmapConfig(Bitmap.Config.ARGB_8888).build())
            currentCoroutineContext().ensureActive()
            val input = (result.drawable as? BitmapDrawable)?.bitmap
            value = if (input == null) null else withContext(Dispatchers.Default) {
                // Own a small bitmap rather than borrowing/recycling Coil's cached bitmap.
                val copy = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
                Canvas(copy).drawBitmap(input, null, Rect(0, 0, 256, 256), Paint(Paint.FILTER_BITMAP_FLAG))
                val frame = MacArtworkFrame(copy, macStaticFrame(context, copy, context.resources.displayMetrics.widthPixels, context.resources.displayMetrics.heightPixels, sceneClock.meshIndex))
                currentCoroutineContext().ensureActive()
                frame
            }
            if (value == null) NetworkLog.line("MacFluid", "artwork unavailable; black fallback")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            value = null
            NetworkLog.line("MacFluid", "artwork load failed (${e.javaClass.simpleName}); black fallback")
        }
    }
    val effectiveQuality = when {
        reduceMotion -> FluidQuality.STATIC
        lowPower && quality.animated -> FluidQuality.POWER_SAVE
        else -> quality
    }
    var failed by remember(effectiveQuality) { mutableStateOf(false) }
    Box(modifier.fillMaxSize().background(Color.Black)) {
        val frame = artwork
        if (frame != null) {
            Image(frame.fallback.asImageBitmap(), contentDescription = null,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            if (effectiveQuality.animated && !failed) {
                AndroidView(
                    factory = { ctx -> MacTextureView(ctx, sceneClock) { failed = true } },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = { it.close() },
                    update = { view ->
                        view.update(frame.source, FluidRenderState(effectiveQuality, playing), foreground)
                    },
                )
            }
        }
    }
}

