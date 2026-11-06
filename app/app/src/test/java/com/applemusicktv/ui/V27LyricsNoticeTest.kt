package com.applemusicktv.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.tv.material3.Text
import com.applemusicktv.data.model.Song
import com.applemusicktv.data.network.LyricLine
import com.applemusicktv.ui.components.FadeThrough
import com.applemusicktv.ui.screens.EmptyLyrics
import com.applemusicktv.ui.screens.emptyLyricsNoticeVisible
import com.applemusicktv.ui.viewmodel.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,qualifiers="w960dp-h540dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class V27LyricsNoticeTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private fun song(id:String)=Song(id,"歌曲 $id","艺人","专辑",60_000,null,null,null)
    private fun visible(id:String="old",generation:Long=1)=PlayerState(currentSong=song(id),
        lyricsNotice=LyricsNoticePhase.Visible,lyricsNoticeSongId=id,lyricsNoticeGeneration=generation,
        exhaustedEmptyLyricsFor=id)
    private fun resolved(id:String="new",generation:Long=2)=PlayerState(currentSong=song(id),
        lyricsNoticeSongId=id,lyricsNoticeGeneration=generation,
        lyrics=List(55){LyricLine(it*1000L,(it+1)*1000L,"新歌歌词 $it")})
    private var state by mutableStateOf(PlayerState())
    private var centered by mutableStateOf(false)

    private fun mount(initial:PlayerState,initialCentered:Boolean=false) {
        state=initial;centered=initialCentered;compose.mainClock.autoAdvance=false
        compose.setContent {
            val current=state
            val panel=if(current.lyrics.isNotEmpty())"lyrics" else "empty"
            Box(Modifier.fillMaxSize().background(Color(0xFF242634)),Alignment.Center) {
                FadeThrough(panel to current,Modifier.fillMaxSize(),contentKey={it.first}) { (shown,snapshot)->
                    Box(Modifier.fillMaxSize(),Alignment.Center) {
                        if(shown=="lyrics") Text(snapshot.lyrics.first().text,color=Color.White)
                        else EmptyLyrics(snapshot.currentSong!!.id,1f,
                            panel=="empty" && emptyLyricsNoticeVisible(snapshot,current),centered)
                    }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame();compose.waitForIdle()
    }
    private fun assertNoEmptyDuringExit() {
        for(ms in listOf(0L,16L,32L,64L)) {
            if(ms>0)compose.mainClock.advanceTimeBy(ms)
            compose.onNodeWithText("暂无歌词").assertDoesNotExist()
        }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("新歌歌词 0").assertIsDisplayed()
    }
    private fun save(name:String)=compose.runOnIdle {
        val view=compose.activity.window.decorView
        val image=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
        view.draw(Canvas(image))
        val root=com.applemusicktv.testing.TestArtifacts.root
        File(root,"artifacts/v3.0-v27-release/preview/$name.png").apply { parentFile.mkdirs() }.outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG,100,it)
        }
    }

    @Test fun centeredOldEmptySnapshotCannotReappearWhenIncomingLyricsMoveLeft() {
        mount(visible(),true)
        compose.onNodeWithText("暂无歌词").assertDoesNotExist()
        compose.runOnIdle {state=resolved();centered=false}
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("暂无歌词").assertDoesNotExist();save("no-stale-empty-during-exit")
        assertNoEmptyDuringExit();save("cached-lyrics-ready")
    }
    @Test fun visibleOldNoticeClearsImmediatelyRatherThanFadingOntoNextSong() {
        mount(visible())
        compose.onNodeWithText("暂无歌词").assertIsDisplayed()
        compose.runOnIdle {state=resolved()}
        compose.mainClock.advanceTimeByFrame();assertNoEmptyDuringExit()
    }
    @Test fun sameSongNewSelectionDoesNotAcceptOldNoticeGeneration() {
        val old=visible("same",1);val next=visible("same",2)
        assertFalse(emptyLyricsNoticeVisible(old,next))
        assertTrue(emptyLyricsNoticeVisible(next,next))
        mount(old,true)
        compose.runOnIdle {state=resolved("same",2);centered=false}
        compose.mainClock.advanceTimeByFrame();assertNoEmptyDuringExit()
    }
    @Test fun idleAndFinalPendingStatesRemainQuietUntilNoticeActuallyStarts() {
        val initial=visible().copy(lyricsNotice=LyricsNoticePhase.Idle,exhaustedEmptyLyricsFor=null)
        mount(initial)
        compose.onNodeWithText("暂无歌词").assertDoesNotExist()
        compose.runOnIdle {state=initial.copy(lyricsNotice=LyricsNoticePhase.Pending,exhaustedEmptyLyricsFor="old")}
        compose.mainClock.advanceTimeByFrame();compose.onNodeWithText("暂无歌词").assertDoesNotExist()
        compose.runOnIdle {state=visible()}
        compose.mainClock.advanceTimeByFrame();compose.onNodeWithText("暂无歌词").assertIsDisplayed()
        compose.runOnIdle {state=visible().copy(lyricsNotice=LyricsNoticePhase.Done,emptyLyricsNoticeCompletedFor="old");centered=true}
        compose.mainClock.advanceTimeByFrame();compose.onNodeWithText("暂无歌词").assertDoesNotExist()
    }
    @Test fun retrySuccessNeverDisplaysAnEmptyNoticeBetweenIdleAndLyrics() {
        mount(visible().copy(lyricsNotice=LyricsNoticePhase.Idle,exhaustedEmptyLyricsFor=null))
        compose.onNodeWithText("暂无歌词").assertDoesNotExist()
        compose.runOnIdle {state=resolved("old",1)}
        compose.mainClock.advanceTimeByFrame();assertNoEmptyDuringExit()
    }
    @Test fun mismatchedOwnerOrExistingLyricsRejectsVisiblePhaseEvenWithoutTransition() {
        val current=visible()
        assertFalse(emptyLyricsNoticeVisible(current.copy(lyricsNoticeSongId="another"),current))
        assertFalse(emptyLyricsNoticeVisible(current,current.copy(lyricsNoticeSongId="another")))
        assertFalse(emptyLyricsNoticeVisible(current,current.copy(exhaustedEmptyLyricsFor=null)))
        assertFalse(emptyLyricsNoticeVisible(current,current.copy(lyrics=resolved().lyrics)))
    }
}
