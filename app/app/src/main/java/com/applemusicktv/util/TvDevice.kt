package com.applemusicktv.util

import android.content.Context
import android.os.Build
import com.applemusicktv.data.OnboardingPreferences

/**
 * Which kind of TV box we're on. This matters for one thing: Fire TV remotes have a
 * Menu button and Google TV remotes don't, so the queue/lyrics toggle needs an
 * on-screen control everywhere except Fire TV.
 *
 * Detected rather than asked. `amazon.hardware.fire_tv` is declared by every Fire TV
 * device; MANUFACTURER is the belt-and-braces check for older sticks that predate it.
 */
object TvDevice {

    /** Keep touch navigation visible on tablets without changing TV idle behaviour. */
    fun usesTouchControls(context: Context): Boolean =
        context.packageManager.hasSystemFeature("android.hardware.touchscreen") &&
            !context.packageManager.hasSystemFeature("android.software.leanback") &&
            !context.packageManager.hasSystemFeature("android.hardware.type.television")

    fun isFireTv(context: Context): Boolean =
        context.packageManager.hasSystemFeature("amazon.hardware.fire_tv") ||
            Build.MANUFACTURER.equals("Amazon", ignoreCase = true)

    /**
     * True when the remote has no Menu key, so the UI must expose the toggle itself.
     * The onboarding override wins over detection — a repackaged box can lie about
     * the system feature, and the user can see their own remote.
     */
    fun needsOnScreenMenuToggle(context: Context, override: String = OnboardingPreferences.REMOTE_AUTO): Boolean =
        if (usesTouchControls(context)) true else when (override) {
            OnboardingPreferences.REMOTE_FIRE -> false
            OnboardingPreferences.REMOTE_GOOGLE -> true
            else -> !isFireTv(context)
        }
}
