package com.applemusicktv.ui.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class LyricsNoticePhase { Idle, Pending, Visible, Done }

internal data class LyricsNoticeState(val songId: String? = null, val generation: Long = 0L,
    val phase: LyricsNoticePhase = LyricsNoticePhase.Idle, val completedEmptySong: String? = null)

/** A notice belongs to one selection and starts only after all bounded fetch attempts finish empty. */
internal class LyricsFailureNotice(
    private val scope: CoroutineScope,
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    private val mutable = MutableStateFlow(LyricsNoticePhase.Idle)
    val phase = mutable.asStateFlow()
    private val expired = MutableStateFlow<String?>(null)
    val completedEmptySong = expired.asStateFlow()
    private val mutableStatus = MutableStateFlow(LyricsNoticeState())
    val status = mutableStatus.asStateFlow()
    private var songId: String? = null
    var generation: Long = 0L
        private set
    private var timer: Job? = null
    private fun publish(phase: LyricsNoticePhase, completed: String? = null) {
        mutable.value = phase
        expired.value = completed
        mutableStatus.value = LyricsNoticeState(songId,generation,phase,completed)
    }
    fun beginSong(id: String? = null) {
        timer?.cancel(); timer = null; songId = id; generation++
        publish(LyricsNoticePhase.Idle)
    }
    fun report(attempt: Int, lines: Int) {
        if (lines > 0) { timer?.cancel(); timer = null; publish(LyricsNoticePhase.Done) }
    }
    fun finishEmpty() {
        if (mutable.value == LyricsNoticePhase.Idle) publish(LyricsNoticePhase.Pending)
    }
    fun showWhenReady(expectedSong: String? = songId, expectedGeneration: Long = generation) {
        if (expectedSong != songId || expectedGeneration != generation || mutable.value != LyricsNoticePhase.Pending) return
        publish(LyricsNoticePhase.Visible)
        val owner = songId; val revision = generation
        timer = scope.launch {
            wait(3_000L)
            if (owner == songId && revision == generation && mutable.value == LyricsNoticePhase.Visible)
                publish(LyricsNoticePhase.Done,owner)
        }
    }
}
