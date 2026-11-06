package com.applemusicktv.ui.viewmodel

import com.applemusicktv.data.model.Song

/** Transient display metadata, never a queue item or a persisted playback bookmark. */
data class StationLaunch(val request: Long, val id: String, val title: String, val artwork: String?, val failed: Boolean = false)

internal fun PlayerState.stationPresentation(): PlayerState {
    val pending = stationLaunch ?: return this
    val cover = Song(id = "station-launch:${pending.id}", title = pending.title, artistName = "Apple Music",
        albumName = "", durationMs = 0L, artworkUrl = pending.artwork, artworkBgColor = null,
        previewUrl = null, hasLyrics = false)
    return copy(currentSong = cover, song = cover, lyrics = emptyList(), progressMs = 0L,
        motionUrl = null, isPlaying = false, isLoading = !pending.failed, isLiveRadio = false, isFullStream = true)
}
