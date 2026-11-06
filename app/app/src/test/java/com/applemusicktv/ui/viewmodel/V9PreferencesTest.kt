package com.applemusicktv.ui.viewmodel

import android.app.Application
import com.applemusicktv.data.CrossfadePreferences
import com.applemusicktv.ui.background.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[23],application=Application::class)
class V9PreferencesTest {
    @Test fun freshCrossfadeDefaultsToFiveSecondsAndExplicitUserValueIsPreserved() {
        val context=RuntimeEnvironment.getApplication()
        assertEquals(5000L,CrossfadePreferences(context).getDuration())
        CrossfadePreferences(context).setDuration(9000)
        assertEquals(9000L,CrossfadePreferences(context).getDuration())
    }
    @Test fun oldBackgroundConstraintsConsolidateWithoutOverridingOffOrStatic() {
        assertEquals(FluidQuality.STATIC,unifiedFluidQuality(FluidQuality.STANDARD,true,true))
        assertEquals(FluidQuality.POWER_SAVE,unifiedFluidQuality(FluidQuality.STANDARD,false,true))
        assertEquals(FluidQuality.OFF,unifiedFluidQuality(FluidQuality.OFF,true,true))
        assertEquals(FluidQuality.STATIC,unifiedFluidQuality(FluidQuality.STATIC,false,true))
        assertEquals(FluidQuality.STANDARD,unifiedFluidQuality(FluidQuality.STANDARD,false,false))
    }
}
