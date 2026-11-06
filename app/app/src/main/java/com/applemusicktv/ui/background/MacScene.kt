package com.applemusicktv.ui.background

import kotlin.math.*

/** Recovered Music 1.4.6.32 math, identical to the approved desktop demo. */
internal object MacScene {
    const val MESH_COUNT = 5
    const val FPS = 60
    const val ARTWORK_SIZE = 256
    const val TRANSITION_SECONDS = 1.0
    const val MOTION_SPEED = 0.7
    const val BLUR_FACTOR = 0.04539470697716646
    const val CYCLE_SECONDS = 1260.0
    fun advanceTime(time: Double, seconds: Double): Double = (time + seconds * MOTION_SPEED) % CYCLE_SECONDS
    val periods = doubleArrayOf(60.0, 45.0, 35.0)
    val offsets = arrayOf(floatArrayOf(0f, 0f), floatArrayOf(-.5f, .7f), floatArrayOf(-.95f, -.7f))
    fun warpMix(time: Double): Float {
        val u = acos(sin(Math.PI * time / 1.75).coerceIn(-1.0, 1.0)) / Math.PI
        return (u * u * (3 - 2 * u)).toFloat()
    }
    fun angle(time: Double, model: Int): Float = ((2 * Math.PI * time / periods[model]) % (2 * Math.PI)).toFloat()
    fun sigma(w: Int, h: Int): Double = floor(hypot(w.toDouble(), h.toDouble()) * BLUR_FACTOR).coerceAtLeast(.5)
    fun maxEdge(quality: FluidQuality, fragmentHighp: Boolean = true): Int = min(if(fragmentHighp) 960 else 256, when(quality) {
        FluidQuality.POWER_SAVE -> 320
        else -> 640
    })
    /** Each pair combines adjacent Gaussian taps using linear texture filtering. */
    fun gaussian(sigma: Double): List<Pair<Double, Double>> {
        val radius = ceil(sigma * 3).toInt()
        val weights = DoubleArray(radius + 1) { exp(-.5 * it * it / (sigma * sigma)) }
        val sum = weights[0] + 2 * weights.drop(1).sum()
        val result = arrayListOf(0.0 to weights[0] / sum)
        for (i in 1..radius step 2) {
            val a = weights[i]; val b = weights.getOrElse(i + 1) { 0.0 }
            result += (i + b / (a + b)) to (a + b) / sum
        }
        return result
    }
}

/** One selection per scene; artwork changes and EGL recovery reuse the same clock. */
internal class MacClock(random: kotlin.random.Random = kotlin.random.Random.Default) {
    val meshIndex: Int = random.nextInt(MacScene.MESH_COUNT)
    var time = 0.0
}
