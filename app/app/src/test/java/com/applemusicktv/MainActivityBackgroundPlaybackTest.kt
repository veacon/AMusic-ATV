package com.applemusicktv

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelLazy
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.media3.exoplayer.ExoPlayer
import com.applemusicktv.ui.viewmodel.MusicVideoViewModel
import com.applemusicktv.ui.viewmodel.MvUiState
import com.applemusicktv.ui.viewmodel.PlayerState
import com.applemusicktv.ui.viewmodel.PlayerViewModel
import com.applemusicktv.ui.viewmodel.SourcePreparation
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Calls the actual Activity onStop with ViewModels already obtained by the UI, but with
 *  untouched Activity delegates: the precise ownership mismatch exposed by removing PiP. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28], application = Application::class, manifest = Config.NONE)
class MainActivityBackgroundPlaybackTest {
    private class Engine {
        var pauseCalls = 0
        var playing = true
        val player = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { _, method, _ ->
            when (method.name) {
                "pause" -> { pauseCalls++; playing = false; null }
                else -> throw AssertionError("Unexpected call ${method.name}")
            }
        } as ExoPlayer
    }

    private fun <T> allocate(type: Class<T>): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return type.cast(unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type))
    }

    private fun field(target: Any, name: String, value: Any) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    private inner class Fixture(background: Boolean = false, allowed: Boolean = true) {
        val main = Engine()
        val crossfade = Engine()
        val video = Engine()
        val preparation = SourcePreparation()
        val audioVm = allocate(PlayerViewModel::class.java).also {
            field(it, "player", main.player)
            field(it, "crossfadeExo", crossfade.player)
            field(it, "_state", MutableStateFlow(PlayerState(backgroundPlayEnabled = background)))
            field(it, "sourcePreparation", preparation)
        }
        val videoVm = allocate(MusicVideoViewModel::class.java).also {
            field(it, "player", video.player)
            field(it, "_state", MutableStateFlow(MvUiState(playing = true)))
        }
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T = modelClass.cast(
                when (modelClass) {
                    PlayerViewModel::class.java -> audioVm
                    MusicVideoViewModel::class.java -> videoVm
                    else -> throw AssertionError("Unexpected ViewModel $modelClass")
                }
            )
        }
        // Like hiltViewModel() in AppShell, populate the shared store without touching
        // either Activity delegate. Use the real ViewModelLazy / ViewModelProvider APIs.
        val audioLazy = ViewModelLazy(PlayerViewModel::class, { store }, { factory })
        val videoLazy = ViewModelLazy(MusicVideoViewModel::class, { store }, { factory })
        init {
            val uiProvider = ViewModelProvider(store, factory)
            assertSame(audioVm, uiProvider[PlayerViewModel::class.java])
            assertSame(videoVm, uiProvider[MusicVideoViewModel::class.java])
            field(activity, "playerVmLazy", audioLazy)
            field(activity, "mvVmLazy", videoLazy)
            field(activity, "appWindowAllowed", allowed)
            assertFalse(audioLazy.isInitialized())
            assertFalse(videoLazy.isInitialized())
        }
        fun stop() {
            MainActivity::class.java.getDeclaredMethod("onStop").apply { isAccessible = true }.invoke(activity)
        }
    }

    @Test fun backgroundDisabledPausesUiOwnedPlayersEvenWhenActivityDelegatesAreUntouched() {
        val f = Fixture()
        f.stop()
        assertEquals(1, f.main.pauseCalls)
        assertEquals(1, f.crossfade.pauseCalls)
        assertEquals(1, f.video.pauseCalls)
        assertSame(f.audioVm, f.audioLazy.value)
        assertSame(f.videoVm, f.videoLazy.value)
    }

    @Test fun backgroundEnabledKeepsTheExistingPlaybackPolicy() {
        val f = Fixture(background = true)
        f.stop()
        assertTrue(f.main.playing)
        assertTrue(f.crossfade.playing)
        assertTrue(f.video.playing)
    }

    @Test fun disallowedWindowDoesNotCreatePlaybackViewModels() {
        val f = Fixture(allowed = false)
        f.stop()
        assertFalse(f.audioLazy.isInitialized())
        assertFalse(f.videoLazy.isInitialized())
        assertEquals(0, f.main.pauseCalls)
    }

    @Test fun initializedActivityAndRepeatedStopRemainPaused() {
        val f = Fixture()
        f.audioLazy.value
        f.videoLazy.value
        f.stop()
        f.stop()
        assertFalse(f.main.playing)
        assertFalse(f.crossfade.playing)
        assertEquals(2, f.video.pauseCalls)
    }

    @Test fun goingToBackgroundAlsoCancelsPendingSourceAutoplayIntent() {
        val f = Fixture()
        f.preparation.begin(7, true)
        f.stop()
        assertEquals(false, f.preparation.finish(7))
        assertFalse(f.main.playing)
    }
}
