package com.applemusicktv.ui

import android.app.Application
import android.view.KeyEvent
import com.applemusicktv.data.model.Song
import com.applemusicktv.ui.navigation.PlayerWakeKeyGate
import com.applemusicktv.ui.navigation.dispatchRemoteMenu
import com.applemusicktv.ui.background.IdleArtworkPool
import com.applemusicktv.ui.viewmodel.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class V22RemoteTest {
    private fun event(key:Int,action:Int=KeyEvent.ACTION_DOWN,repeat:Int=0)=KeyEvent(10,20,action,key,repeat)

    @Test fun everyWakeKeyConsumesDownRepeatsAndReleaseButSecondPressWorksNormally() {
        for(key in listOf(KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,KeyEvent.KEYCODE_BACK,KeyEvent.KEYCODE_MENU,KeyEvent.KEYCODE_ESCAPE)) {
            val gate=PlayerWakeKeyGate();var hidden=true;var wakes=0
            fun consume(e:KeyEvent)=gate.consume(e,true,hidden) {hidden=false;wakes++}
            assertTrue(consume(event(key)))
            assertTrue(consume(event(key,repeat=1)))
            assertTrue(consume(event(key,repeat=3)))
            assertTrue(consume(event(key,KeyEvent.ACTION_UP)))
            assertEquals(1,wakes)
            assertFalse(consume(event(key)))
            assertFalse(consume(event(key,KeyEvent.ACTION_UP)))
        }
    }

    @Test fun mediaKeysAndOtherPagesRetainTheirBehavior() {
        val gate=PlayerWakeKeyGate()
        for(key in listOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,KeyEvent.KEYCODE_MEDIA_NEXT,KeyEvent.KEYCODE_VOLUME_UP))
            assertFalse(gate.consume(event(key),true,true) {fail("Dedicated media key must pass through")})
        assertFalse(gate.consume(event(KeyEvent.KEYCODE_MENU),false,true) {fail("Other page must not wake player")})
    }

    @Test fun focusedContextMenuHasPriorityAndHoldingMenuNeverRepeatsAction() {
        var menu=0;var fallback=0
        fun dispatch(e:KeyEvent)=dispatchRemoteMenu(e,{menu++;true},{fallback++})
        assertTrue(dispatch(event(KeyEvent.KEYCODE_MENU)))
        assertTrue(dispatch(event(KeyEvent.KEYCODE_MENU,repeat=2)))
        assertTrue(dispatch(event(KeyEvent.KEYCODE_MENU,KeyEvent.ACTION_UP)))
        assertEquals(1,menu);assertEquals(0,fallback)
        assertFalse(dispatch(event(KeyEvent.KEYCODE_DPAD_RIGHT)))
    }

    @Test fun unsupportedItemUsesOriginalMenuActionAndHiddenMenuOnlyWakes() {
        var item=0;var global=0
        val nav=NavigationViewModel().apply {isOnNowPlaying=true;setPlayerChromeVisible(false)}
        fun dispatch(e:KeyEvent):Boolean = nav.consumePlayerWakeKey(e) ||
            dispatchRemoteMenu(e,{item++;false},{global++})
        assertTrue(dispatch(event(KeyEvent.KEYCODE_MENU)))
        assertTrue(nav.playerChromeVisible.value)
        assertTrue(dispatch(event(KeyEvent.KEYCODE_MENU,KeyEvent.ACTION_UP)))
        assertEquals(0,item);assertEquals(0,global)
        assertTrue(dispatch(event(KeyEvent.KEYCODE_MENU)))
        assertTrue(dispatch(event(KeyEvent.KEYCODE_MENU,KeyEvent.ACTION_UP)))
        assertEquals(1,item);assertEquals(1,global)
    }

    private fun song(id:String)=Song(id,id,"artist","album",60_000,null,null,null)

    @Test fun fourPlaybackModesKeepPlayingSongProgressAndRestoreQueueOrder() {
        val songs=listOf(song("a"),song("b"),song("c"),song("d"))
        val initial=PlayerState(queue=songs,queueIndex=1,currentSong=songs[1],song=songs[1],progressMs=17_000,isPlaying=true)
        val all=initial.nextPlaybackOrder();assertEquals(PlaybackOrder.RepeatAll,all.playbackOrder())
        val one=all.nextPlaybackOrder();assertEquals(PlaybackOrder.RepeatOne,one.playbackOrder())
        val shuffled=one.nextPlaybackOrder {it.reversed()}
        assertEquals(PlaybackOrder.Shuffle,shuffled.playbackOrder())
        assertEquals(RepeatMode.All,shuffled.repeatMode)
        assertEquals(listOf("a","b","d","c"),shuffled.queue.map {it.id})
        assertSame(initial.currentSong,shuffled.currentSong)
        assertEquals(17_000L,shuffled.progressMs);assertTrue(shuffled.isPlaying)
        val restored=shuffled.nextPlaybackOrder()
        assertEquals(PlaybackOrder.Sequential,restored.playbackOrder())
        assertEquals(songs,restored.queue);assertEquals(1,restored.queueIndex)
        assertTrue(restored.originalQueue.isEmpty());assertFalse(restored.isShuffled)
        assertSame(initial.currentSong,restored.currentSong)
    }

    @Test fun leavingShuffleRetainsInsertedPlayingOccurrenceAndEmptyQueueIsSafe() {
        val a=song("same");val b=song("same");val added=song("added")
        val state=PlayerState(queue=listOf(a,added,b),originalQueue=listOf(a,b),queueIndex=1,currentSong=added,isShuffled=true)
        val restored=state.nextPlaybackOrder()
        assertSame(added,restored.queue[restored.queueIndex]);assertSame(added,restored.currentSong)
        var empty=PlayerState()
        repeat(4) {empty=empty.nextPlaybackOrder()}
        assertTrue(empty.queue.isEmpty());assertEquals(PlaybackOrder.Sequential,empty.playbackOrder())
    }

    @Test fun idleArtworkPoolIsBoundedDeduplicatedAndOnlyAcceptsLoadedPublicCovers() {
        IdleArtworkPool.record("https://example.invalid/private")
        IdleArtworkPool.record("http://is1-ssl.mzstatic.com/insecure")
        IdleArtworkPool.record("file:///private/file")
        val rejected=IdleArtworkPool.urls.value
        assertFalse(rejected.any {it.contains("example.invalid") || it.startsWith("http:") || it.startsWith("file:")})
        repeat(70) {IdleArtworkPool.record("https://is1-ssl.mzstatic.com/image/fixture-$it.jpg")}
        assertEquals(64,IdleArtworkPool.urls.value.size)
        val last=IdleArtworkPool.urls.value.last()
        IdleArtworkPool.record(last)
        assertEquals(64,IdleArtworkPool.urls.value.size);assertEquals(last,IdleArtworkPool.urls.value.last())
    }
}
