package com.veacon.veilmusic.ui.viewmodel

import com.veacon.veilmusic.data.model.Song
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PlaybackUiStateTest {
    @Test fun clockLyricsAndBeatChangesDoNotEmitShellOrSettingsUpdates()=runBlocking {
        val first=PlayerState()
        val changes=listOf(first,first.copy(progressMs=1000),first.copy(progressMs=2000,beatIntensity=2f))
        assertEquals(1,changes.asFlow().map {it.shellUiState()}.distinctUntilChanged().toList().size)
        assertEquals(1,changes.asFlow().map {it.settingsUiState()}.distinctUntilChanged().toList().size)
    }
    @Test fun transportQueueAuthorizationAndDisplayChangesStillReachTheShell() {
        val first=PlayerState();val song=Song("a","A","B","C",100,null,null,null)
        for(next in listOf(first.copy(isPlaying=true),first.copy(isInPip=true),first.copy(currentSong=song),
            first.copy(queue=listOf(song)),first.copy(queueIndex=1),first.copy(mutExpired=true),
            first.copy(motionArtworkEnabled=true),first.copy(lowPowerMode=true))) {
            assertNotEquals(first.shellUiState(),next.shellUiState())
        }
        // 免登录模式下 isFullStream 恒为 true（不再有试听降级），因此它不再产生 shell 差异。
        assertEquals(first.shellUiState(),first.copy(isFullStream=true).shellUiState())
    }
    @Test fun settingsSwitchAndBluetoothOutputUpdatesAreNeverFilteredOut() {
        val first=PlayerState()
        assertNotEquals(first.settingsUiState(),first.copy(lyricsScale=1.2f).settingsUiState())
        assertNotEquals(first.settingsUiState(),first.copy(backgroundPlayEnabled=!first.backgroundPlayEnabled).settingsUiState())
        assertNotEquals(first.settingsUiState(),first.copy(avOnBluetooth=true,avOutputLabel="Bluetooth").settingsUiState())
    }
}
