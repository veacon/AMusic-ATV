package com.applemusicktv.ui.background

/** Selected against supplied Mac/tablet recordings using Kawarp demo parameter ranges.
 * Original warp stays intact; artwork colours also evolve in a separate slow pass. */
internal object FluidTuning {
    const val BLUR_PASSES = 6
    const val WARP_INTENSITY = 1f
    const val ANIMATION_SPEED = 1f
    const val SATURATION = 1.9f
    const val TINT_INTENSITY = 0.08f
    const val SCALE = 2.2f
    const val DITHERING = 0.008f
    const val TRANSITION_SECONDS = 1.0
    const val PALETTE_CYCLE_SECONDS = 20.0
}
