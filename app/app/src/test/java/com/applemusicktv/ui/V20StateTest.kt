package com.applemusicktv.ui

import android.app.Application
import com.applemusicktv.data.MutPreferences
import com.applemusicktv.data.model.Song
import com.applemusicktv.ui.viewmodel.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class V20StateTest {
    @Test fun onlyVerifiedCurrentAccountTriggersOneWarmupPerAuthorization() {
        val prefs = MutPreferences(RuntimeEnvironment.getApplication())
        prefs.setMUT("test-account-a")
        assertEquals(0L, prefs.authorizationRevision.value)
        prefs.markVerified("wrong-account")
        assertEquals(0L, prefs.authorizationRevision.value)
        prefs.markVerified("test-account-a")
        val revision = prefs.authorizationRevision.value
        assertTrue(revision > 0)
        repeat(3) { prefs.markVerified("test-account-a") }
        assertEquals(revision, prefs.authorizationRevision.value)
        prefs.setMUT("test-account-b")
        assertEquals(0L, prefs.authorizationRevision.value)
        prefs.markVerified("test-account-a")
        assertEquals(0L, prefs.authorizationRevision.value)
        prefs.markVerified("test-account-b")
        assertTrue(prefs.authorizationRevision.value > revision)
    }

    private fun track() = Song(id="real-track", title="真实歌曲", artistName="艺人", albumName="专辑",
        durationMs=100_000L, artworkUrl=null, artworkBgColor=null, previewUrl=null)

    @Test fun stationPresentationIsImmediateAndDoesNotReplaceRealQueueOrBookmarkIdentity() {
        val song = track()
        val original = PlayerState(currentSong=song, song=song, queue=listOf(song), progressMs=50_000,
            isPlaying=true, stationLaunch=StationLaunch(7,"ra.test","测试电台","https://example.invalid/cover.jpg"))
        val shown = original.stationPresentation()
        assertEquals("测试电台", shown.currentSong?.title)
        assertEquals("https://example.invalid/cover.jpg", shown.currentSong?.artworkUrl)
        assertTrue(shown.isLoading)
        assertFalse(shown.isPlaying)
        assertEquals(0L, shown.progressMs)
        assertEquals(listOf(song), shown.queue)
        assertEquals(song, original.currentSong)
        assertEquals(50_000L, original.progressMs)
        assertEquals("测试电台", original.shellUiState().currentSong?.title)
    }

    @Test fun stationFailureStopsSpinnerAndRealSongTakesOverWithoutPlaceholderInQueue() {
        val song = track()
        val failed = PlayerState(currentSong=song, queue=listOf(song), stationLaunch=StationLaunch(9,"ra.test","电台",null,true))
        assertFalse(failed.stationPresentation().isLoading)
        val ready = failed.copy(stationLaunch=null, currentSong=song, isLoading=false, isPlaying=true)
        assertSame(ready, ready.stationPresentation())
        assertEquals("real-track", ready.stationPresentation().currentSong?.id)
        assertTrue(ready.stationPresentation().isPlaying)
    }
}
