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
@Config(sdk = [23, 28], application = Application::class)
class AudioQualityPreferencesTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun clear() {
        context.getSharedPreferences("audio_quality_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun defaultIsThreeTwentyAndSurvivesRestart() {
        val prefs = AudioQualityPreferences(context)
        assertEquals(AudioQuality.HIGH, prefs.get())
        prefs.set(AudioQuality.LOSSLESS)
        assertEquals(AudioQuality.LOSSLESS, prefs.quality.value)
        assertEquals(AudioQuality.LOSSLESS, AudioQualityPreferences(context).get())
    }

    @Test fun unknownOrLegacyKeyFallsBackToDefault() {
        assertEquals(AudioQuality.HIGH, AudioQuality.fromKey(null))
        assertEquals(AudioQuality.HIGH, AudioQuality.fromKey("master"))
        assertEquals(AudioQuality.STANDARD, AudioQuality.fromKey("128k"))
    }

    @Test fun stepperClampsInsteadOfWrapping() {
        assertEquals(AudioQuality.STANDARD, AudioQuality.STANDARD.next(-1))
        assertEquals(AudioQuality.HIGH, AudioQuality.STANDARD.next(1))
        assertEquals(AudioQuality.LOSSLESS, AudioQuality.HIGH.next(1))
        assertEquals(AudioQuality.LOSSLESS, AudioQuality.LOSSLESS.next(1))
        // 大步长（长按）仍然只走一档
        assertEquals(AudioQuality.HIGH, AudioQuality.STANDARD.next(9))
    }

    @Test fun everyLadderStartsAtItsOwnCeilingAndOnlyDegradesDown() {
        assertEquals("128k", AudioQuality.STANDARD.ladder.first())
        assertEquals("320k", AudioQuality.HIGH.ladder.first())
        assertEquals("flac24bit", AudioQuality.LOSSLESS.ladder.first())
        // 兜底里出现 128k 的档位，门槛必须低于 128，否则兜底流会被当成试听片段误杀
        AudioQuality.entries.forEach { q ->
            if ("128k" in q.ladder) assertTrue("${q.key} 门槛过高", q.minKbps <= 128)
        }
    }
}
