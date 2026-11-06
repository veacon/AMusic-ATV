package com.applemusicktv.ui.viewmodel

import com.applemusicktv.data.model.Song
import com.applemusicktv.media.RecoveredCatalogSong

/** Fill missing display artwork while preserving the library identity and all playback state. */
internal fun PlayerState.withRecoveredArtwork(songId: String, replacement: RecoveredCatalogSong): PlayerState {
    if (currentSong?.id != songId || replacement.artworkUrl.isNullOrBlank()) return this
    fun fill(row: Song): Song = if (row.id == songId && row.artworkUrl.isNullOrBlank()) row.copy(
        artworkUrl = replacement.artworkUrl,
        artworkBgColor = row.artworkBgColor?.takeIf { it.isNotBlank() } ?: replacement.artworkBgColor,
    ) else row
    return copy(currentSong = currentSong?.let(::fill), song = song?.let(::fill),
        queue = queue.map(::fill), originalQueue = originalQueue.map(::fill), userQueue = userQueue.map(::fill))
}
