package com.applemusicktv.ui.background
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
internal data class IntermediatePlan(val width: Int, val height: Int, val sigma: Double)
internal fun intermediatePlan(width: Int, height: Int, baseCap: Int, quality: FluidQuality): IntermediatePlan {
    fun dimensions(edge: Int): Pair<Int, Int> {
        val scale = min(1.0, edge.coerceAtLeast(1).toDouble() / max(width, height))
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }
    val (baseW,baseH) = dimensions(baseCap)
    val desired = if (quality == FluidQuality.HIGH_QUALITY) 192 else 128
    val (w,h) = dimensions(min(baseCap,desired))
    val ratio = max(w,h).toDouble() / max(baseW,baseH)
    return IntermediatePlan(w,h,MacScene.sigma(baseW,baseH)*ratio)
}
