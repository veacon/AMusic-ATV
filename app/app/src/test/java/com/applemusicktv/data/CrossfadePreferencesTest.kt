package com.applemusicktv.data

import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[23,28],application=Application::class)
class CrossfadePreferencesTest {
    private val context get()=RuntimeEnvironment.getApplication()
    @Before fun clear() { context.getSharedPreferences("crossfade_prefs",Context.MODE_PRIVATE).edit().clear().commit() }

    @Test fun zeroPersistsAndReopensAsOffWhileDefaultStaysFiveSeconds() {
        val prefs=CrossfadePreferences(context)
        assertEquals(5000L,prefs.getDuration())
        prefs.setDuration(0)
        assertEquals(0L,prefs.durationMs.value)
        assertEquals(0L,CrossfadePreferences(context).getDuration())
        assertFalse(CrossfadePreferences.isEnabled(true,prefs.getDuration()))
        prefs.setDuration(1000)
        assertTrue(CrossfadePreferences.isEnabled(true,prefs.getDuration()))
    }

    @Test fun bothSettingsDirectionsVisitOnlyOffAndTheOriginalEnabledSteps() {
        val expected=listOf(0L)+(1000L..15000L step 500L)
        val actual=mutableListOf(0L)
        repeat(expected.size-1) { actual+=CrossfadePreferences.step(actual.last(),1) }
        assertEquals(expected,actual)
        assertEquals(expected.indices.toList(),actual.map(CrossfadePreferences::sliderStep))
        assertFalse(500L in actual)
        assertEquals(15000L,CrossfadePreferences.step(15000,1))
        assertEquals(0L,CrossfadePreferences.step(0,-1))
        val descending=mutableListOf(15000L)
        repeat(expected.size-1) { descending+=CrossfadePreferences.step(descending.last(),-1) }
        assertEquals(expected.reversed(),descending)
    }

    @Test fun invalidAndOutOfRangeValuesCannotEnableAHalfSecondFade() {
        val prefs=CrossfadePreferences(context)
        for ((input,expected) in listOf(-1L to 0L,500L to 1000L,1000L to 1000L,1500L to 1500L,20000L to 15000L)) {
            prefs.setDuration(input);assertEquals(expected,prefs.getDuration())
        }
    }

    @Test fun offPreparesNextAcrossAlbumsAndNonzeroKeepsTheOriginalAlbumException() {
        for(legacy in listOf(false,true)) for(sameAlbum in listOf(false,true)) {
            assertTrue(CrossfadePreferences.needsPreparedNext(legacy,0,sameAlbum))
        }
        assertTrue(CrossfadePreferences.needsPreparedNext(false,5000,false))
        assertTrue(CrossfadePreferences.needsPreparedNext(true,5000,true))
        assertFalse(CrossfadePreferences.needsPreparedNext(true,1000,false))
        assertFalse(CrossfadePreferences.needsPreparedNext(true,15000,false))
    }
}
