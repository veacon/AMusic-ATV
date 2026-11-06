package com.applemusicktv.ui.background

/** Follow nominal <=60Hz displays; carry fractional frame credit when capping faster displays. */
internal class BackgroundFrameCadence(private val targetFps: Int = MacScene.FPS) {
    private val periodNs = 1_000_000_000L / targetFps
    private var previousNs: Long? = null
    private var refreshHz = 0f
    private var creditNs = 0L

    fun reset() {
        previousNs = null
        refreshHz = 0f
        creditNs = 0L
    }

    fun shouldRender(frameTimeNs: Long, displayRefreshHz: Float): Boolean {
        val hz = displayRefreshHz.takeIf { it.isFinite() && it > 0f } ?: targetFps.toFloat()
        val previous = previousNs
        previousNs = frameTimeNs
        val changed = kotlin.math.abs(hz - refreshHz) > 0.5f
        refreshHz = hz
        if (previous == null || changed || frameTimeNs <= previous) {
            creditNs = 0L
            return true
        }
        // The display mode is nominal. Its actual timestamps can be a few microseconds
        // shorter than 1/60s; filtering those normal callbacks would halve the frame rate.
        if (hz <= targetFps + 1f) {
            creditNs = 0L
            return true
        }
        val elapsed = frameTimeNs - previous
        // Resume at the current frame instead of catching up an obsolete backlog.
        if (elapsed >= periodNs * 2) {
            creditNs = 0L
            return true
        }
        creditNs += elapsed
        // A small early allowance absorbs callback jitter, but the debt is retained.
        // Do not reset credit after each accepted callback: 90Hz needs a 1/2 pattern.
        if (creditNs + 500_000L < periodNs) return false
        creditNs -= periodNs
        return true
    }
}
