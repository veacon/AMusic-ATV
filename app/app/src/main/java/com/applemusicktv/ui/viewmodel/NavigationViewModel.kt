package com.applemusicktv.ui.viewmodel

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class NavigationViewModel @Inject constructor() : ViewModel() {
    private val _goToNowPlaying = MutableStateFlow(false)
    val goToNowPlaying: StateFlow<Boolean> = _goToNowPlaying

    // Toggles queue/lyrics panel in NowPlaying screen
    private val _toggleQueue = MutableStateFlow(0)
    val toggleQueue: StateFlow<Int> = _toggleQueue

    private val _playerChromeVisible = MutableStateFlow(true)
    val playerChromeVisible: StateFlow<Boolean> = _playerChromeVisible
    private val _playerInteraction = MutableStateFlow(0L)
    val playerInteraction: StateFlow<Long> = _playerInteraction
    var fullScreenLyricsHasSplitOrigin: Boolean = false
    private val wakeKeys = com.applemusicktv.ui.navigation.PlayerWakeKeyGate()
    fun consumePlayerWakeKey(event: android.view.KeyEvent): Boolean =
        wakeKeys.consume(event, isOnNowPlaying, !_playerChromeVisible.value, fullScreenLyricsHasSplitOrigin, ::wakePlayerControls)
    fun wakePlayerControls() { _playerChromeVisible.value = true; notePlayerInteraction() }
    fun closeQueuePanel() { if (_toggleQueue.value % 2 != 0) _toggleQueue.value++ }
    fun setPlayerChromeVisible(visible: Boolean) { _playerChromeVisible.value = visible }
    fun notePlayerInteraction() { _playerInteraction.value = android.os.SystemClock.elapsedRealtimeNanos() }

    // MainActivity sets this so Menu key knows current screen
    var isOnNowPlaying: Boolean = false

    // When a fullscreen music video is on screen, MainActivity must NOT eat media/menu
    // keys for the audio player — the video screen owns transport, and Menu shouldn't
    // jump to Now Playing.
    var isOnMusicVideo: Boolean = false

    fun navigateToNowPlaying() { _goToNowPlaying.value = true }
    fun consumeNowPlayingNavigation() { _goToNowPlaying.value = false }
    fun toggleQueuePanel() { _toggleQueue.value++ }
}
