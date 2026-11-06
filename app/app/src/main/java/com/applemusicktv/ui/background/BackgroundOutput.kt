package com.applemusicktv.ui.background
import kotlin.math.max
import kotlin.math.roundToInt
internal data class BackgroundOutputSize(val width: Int, val height: Int)
internal fun backgroundOutputSize(width: Int, height: Int, quality: FluidQuality = FluidQuality.STANDARD): BackgroundOutputSize {
    val w = width.coerceAtLeast(1); val h = height.coerceAtLeast(1)
    val cap = quality.maxEdge.coerceAtLeast(1)
    val scale = minOf(1.0, cap.toDouble() / max(w, h))
    return BackgroundOutputSize((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1))
}
