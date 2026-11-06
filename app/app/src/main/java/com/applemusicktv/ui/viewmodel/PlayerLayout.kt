package com.applemusicktv.ui.viewmodel

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.runningFold

/** Unknown lyrics keep the previous layout, including across track changes and page remounts. */
internal fun nextPlayerLayoutCentered(previous: Boolean, state: PlayerState): Boolean {
    val song = state.currentSong ?: return false
    if (state.stationLaunch != null) return false
    if (state.lyrics.isNotEmpty()) return false
    if (state.isLiveRadio || (state.emptyLyricsNoticeCompletedFor == song.id && state.exhaustedEmptyLyricsFor == song.id)) return true
    return previous
}

/** One session-wide layout decision; audio preparation and buffering never reset it. */
internal fun Flow<PlayerState>.stablePlayerLayout(): Flow<Boolean> =
    runningFold(false, ::nextPlayerLayoutCentered).distinctUntilChanged()
