package com.applemusicktv.data

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Crossfade length, settable from the phone web server (port 8080). */
@Singleton
class CrossfadePreferences @Inject constructor(@ApplicationContext private val context: Context) {
    private val prefs = context.getSharedPreferences("crossfade_prefs", Context.MODE_PRIVATE)

    private val _durationMs = MutableStateFlow(normalize(prefs.getLong("duration_ms", DEFAULT_MS)))
    /** Emits on every change so playback picks up edits without an app restart. */
    val durationMs: StateFlow<Long> = _durationMs

    fun getDuration(): Long = _durationMs.value

    fun setDuration(ms: Long) {
        val clamped = normalize(ms)
        prefs.edit { putLong("duration_ms", clamped) }
        _durationMs.value = clamped
    }

    companion object {
        const val DEFAULT_MS = 5_000L
        const val MIN_MS = 0L
        const val MIN_ENABLED_MS = 1_000L
        const val STEP_MS = 500L
        const val MAX_STEP = 29

        /** Zero is off; the existing enabled range still starts at one second. */
        fun normalize(ms: Long): Long = if (ms <= 0L) 0L else ms.coerceIn(MIN_ENABLED_MS, MAX_MS)
        fun step(ms: Long, direction: Int): Long = when {
            direction < 0 && ms <= MIN_ENABLED_MS -> 0L
            direction > 0 && ms == 0L -> MIN_ENABLED_MS
            else -> normalize(ms + direction.coerceIn(-1, 1) * STEP_MS)
        }
        fun sliderStep(ms: Long): Int = if (ms <= 0L) 0 else (1 + (normalize(ms) - MIN_ENABLED_MS) / STEP_MS).toInt()
        fun isEnabled(legacyEnabled: Boolean, ms: Long): Boolean = legacyEnabled && ms > 0L
        fun needsPreparedNext(legacyEnabled: Boolean, ms: Long, consecutiveAlbumTracks: Boolean): Boolean =
            !isEnabled(legacyEnabled, ms) || consecutiveAlbumTracks
        const val MAX_MS = 15_000L
    }
}
