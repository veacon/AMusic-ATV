package com.applemusicktv.ui.screens

internal data class LyricDotsFrame(val groupAlpha: Float, val dotAlphas: List<Float>)

private fun smoothDot(value: Float): Float {
    val t = value.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** Pure playback-timeline animation; pause and seek follow the supplied playback position. */
internal fun lyricDotsFrame(positionMs: Long, gapStartMs: Long, gapEndMs: Long): LyricDotsFrame {
    val duration = gapEndMs - gapStartMs
    if (duration <= 0L || positionMs <= gapStartMs || positionMs >= gapEndMs) {
        return LyricDotsFrame(0f, listOf(.30f, .30f, .30f))
    }
    val elapsed = positionMs - gapStartMs
    val fraction = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
    val fadeIn = smoothDot(elapsed / 400f)
    val fadeOut = smoothDot((gapEndMs - positionMs) / 500f)
    val endFade = 1f - smoothDot((fraction - .82f) / .18f)
    val starts = floatArrayOf(0f, .24f, .48f)
    val alphas = starts.map { start ->
        val lit = if (fraction >= .82f) endFade else smoothDot((fraction - start) / .28f)
        .30f + .70f * lit
    }
    return LyricDotsFrame(fadeIn * fadeOut, alphas)
}
