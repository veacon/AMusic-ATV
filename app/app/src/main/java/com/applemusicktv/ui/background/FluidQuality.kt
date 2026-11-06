package com.applemusicktv.ui.background

import com.applemusicktv.i18n.tr
import com.applemusicktv.R

/** Quality changes resolution/cadence, never the reference artwork or artistic parameters. */
enum class FluidQuality(private val labelId: Int, val maxEdge: Int, val fps: Int) {
    OFF(R.string.msg_004, 0, 0),
    STATIC(R.string.msg_005, 0, 0),
    POWER_SAVE(R.string.msg_006, 640, 60),
    STANDARD(R.string.msg_007, 960, 60),
    HIGH_QUALITY(R.string.fluid_quality_high, 1280, 60);
    val label: String get() = tr(labelId)


    val animated: Boolean get() = fps > 0

    companion object {
        fun fromName(name: String?): FluidQuality = entries.firstOrNull { it.name == name } ?: STANDARD
    }
}

internal data class FluidRenderState(
    val quality: FluidQuality = FluidQuality.STANDARD,
    val playing: Boolean = false,
)

/** Consolidate only the legacy background constraint; user-selected OFF/STATIC remain stronger. */
internal fun unifiedFluidQuality(quality:FluidQuality,reduce:Boolean,lowPower:Boolean):FluidQuality=when {
    quality==FluidQuality.OFF || quality==FluidQuality.STATIC->quality
    reduce->FluidQuality.STATIC
    lowPower->FluidQuality.POWER_SAVE
    else->quality
}
