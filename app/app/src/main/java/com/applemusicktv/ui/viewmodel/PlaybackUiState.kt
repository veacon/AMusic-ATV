package com.applemusicktv.ui.viewmodel

import com.applemusicktv.data.model.Song
import com.applemusicktv.ui.background.FluidQuality

/** Only fields read by navigation; progress/lyrics changes do not invalidate the shell. */
internal data class ShellPlaybackUiState(
    val isPlaying: Boolean, val isInPip: Boolean, val currentSong: Song?,
    val queue: List<Song>, val queueIndex: Int, val motionArtworkEnabled: Boolean,
    val lowPowerMode: Boolean, val mutExpired: Boolean, val isFullStream: Boolean,
)
internal fun PlayerState.shellUiState(): ShellPlaybackUiState {
    val shown = stationPresentation()
    return ShellPlaybackUiState(shown.isPlaying,isInPip,shown.currentSong,
        queue,queueIndex,motionArtworkEnabled,lowPowerMode,mutExpired,shown.isFullStream)
}

/** Settings observes preferences/output changes, never the playback clock or queue. */
internal data class SettingsPlaybackUiState(
    val volumeLeveling: Boolean, val backgroundPlayEnabled: Boolean, val fluidQuality: FluidQuality,
    val lyricsScale: Float, val motionArtworkEnabled: Boolean, val artworkRounded: Boolean,
    val avSyncAuto: Boolean, val avOutputLabel: String, val avOnBluetooth: Boolean,
)
internal fun PlayerState.settingsUiState() = SettingsPlaybackUiState(volumeLeveling,
    backgroundPlayEnabled,fluidQuality,lyricsScale,motionArtworkEnabled,artworkRounded,
    avSyncAuto,avOutputLabel,avOnBluetooth)
