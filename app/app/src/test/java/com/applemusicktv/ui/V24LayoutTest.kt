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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.applemusicktv.data.model.Song
import com.applemusicktv.data.network.LyricLine
import com.applemusicktv.ui.components.*
import com.applemusicktv.ui.screens.*
import com.applemusicktv.ui.viewmodel.*
import kotlinx.coroutines.flow.*
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
class V24LayoutTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private fun song(id:String)=Song(id,"歌曲 $id","艺人","专辑",60_000,null,null,null)
    private fun state(id:String="a")=PlayerState(currentSong=song(id))
    private val lines=listOf(LyricLine(0,1000,"歌词已加载"))
    private val focus=FocusRequester()
    private var mounts=0
    private var hidden by mutableStateOf(false)
    private var queue by mutableStateOf(false)
    private lateinit var input:MutableStateFlow<PlayerState>
    private val cover=224.dp
    private val spacing=playerColumnSpacing(960.dp,72.dp,67.2.dp,cover)

    private fun mount(initial:PlayerState=state()) {
        input=MutableStateFlow(initial)
        compose.setContent {
            val scope=rememberCoroutineScope()
            val layout=remember {input.stablePlayerLayout().stateIn(scope,SharingStarted.Eagerly,false)}
            val settled by layout.collectAsState()
            val st by input.collectAsState()
            val centered=centerPlayerControls(st,hidden,queue,true,settled)
            Box(Modifier.fillMaxSize().background(Color(0xFF242634))) {
                PlayerSplitLayout(960.dp,cover,spacing,centered,controls={
                    DisposableEffect(Unit) {mounts++;onDispose {}}
                    Column(Modifier.width(cover).testTag("controls"),horizontalAlignment=Alignment.CenterHorizontally) {
                        Box(Modifier.size(cover).background(Color(0xFFBF3A4F)))
                        Text(st.currentSong?.title.orEmpty(),color=Color.White)
                        TransportButton(TransportIcon.Play,{},modifier=Modifier.focusRequester(focus))
                    }
                },panel={
                    Box(Modifier.fillMaxSize(),Alignment.Center) {
                        if(queue) Text("播放列表",color=Color.White)
                        else if(!hidden && st.lyrics.isNotEmpty()) Text(st.lyrics.first().text,color=Color.White)
                        else if(!hidden && !st.isLoading) EmptyLyrics(st.currentSong!!.id,1f,
                            st.lyricsNotice==LyricsNoticePhase.Visible,centered)
                    }
                })
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {focus.requestFocus()}
    }
    private fun publish(st:PlayerState) {
        compose.runOnIdle {input.value=st}
        compose.waitForIdle();compose.mainClock.advanceTimeBy(600);compose.waitForIdle()
    }
    private fun assertLayout(centered:Boolean) {
        compose.waitForIdle();compose.mainClock.advanceTimeBy(600)
        val bounds=compose.onNodeWithTag("controls").fetchSemanticsNode().boundsInRoot
        assertEquals(if(centered)480f-cover.value/2 else spacing.side.value,bounds.left,1f)
        compose.onNodeWithContentDescription("播放").assertIsFocused()
        assertEquals(1,mounts)
        if(centered) compose.onNodeWithText("暂无歌词").assertDoesNotExist()
    }
    private fun save(name:String)=compose.runOnIdle {
        val view=compose.activity.window.decorView
        val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val root=com.applemusicktv.testing.TestArtifacts.root
        File(root,"artifacts/v3.0-v25-release/preview/$name.png").apply { parentFile.mkdirs() }.outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG,100,it)
        }
    }

    @Test fun coldStartFirstFailurePlaybackBufferReadyAndRetryKeepsSplit() {
        mount();assertLayout(false)
        publish(state().copy(lyricsNotice=LyricsNoticePhase.Pending));assertLayout(false)
        publish(state().copy(lyricsNotice=LyricsNoticePhase.Visible));assertLayout(false)
        compose.onNodeWithText("暂无歌词").assertIsDisplayed();save("first-play-empty-notice")
        publish(state().copy(lyricsNotice=LyricsNoticePhase.Visible,isLoading=true));assertLayout(false)
        publish(state().copy(lyricsNotice=LyricsNoticePhase.Visible,isPlaying=true));assertLayout(false)
        publish(state().copy(lyricsNotice=LyricsNoticePhase.Done));assertLayout(false)
        publish(state().copy(lyricsNotice=LyricsNoticePhase.Done,lyrics=lines));assertLayout(false)
        compose.onNodeWithText("歌词已加载").assertIsDisplayed()
    }

    @Test fun firstUnknownShowsNoticeBeforeCenteringThenEmptyToEmptyNeverMovesLeft() {
        mount();assertLayout(false)
        publish(state().copy(lyricsNotice=LyricsNoticePhase.Visible));assertLayout(false)
        compose.onNodeWithText("暂无歌词").assertIsDisplayed()
        publish(state().copy(lyricsNotice=LyricsNoticePhase.Done,emptyLyricsNoticeCompletedFor="a",exhaustedEmptyLyricsFor="a"));assertLayout(true)
        save("empty-notice-expired-centered")
        publish(state("b").copy(isLoading=true,emptyLyricsNoticeCompletedFor="a",exhaustedEmptyLyricsFor="a"));assertLayout(true)
        for(phase in LyricsNoticePhase.entries) {
            publish(state("b").copy(lyricsNotice=phase));assertLayout(true)
        }
        publish(state("b").copy(lyricsNotice=LyricsNoticePhase.Done,emptyLyricsNoticeCompletedFor="b",exhaustedEmptyLyricsFor="b"));assertLayout(true)
        publish(state("b").copy(isLoading=true,emptyLyricsNoticeCompletedFor="b",exhaustedEmptyLyricsFor="b"));assertLayout(true)
        save("empty-to-empty-keeps-center")
    }

    @Test fun emptyToLyricsKeepsCenterUntilLinesThenRestoresSplitOnce() {
        mount(state().copy(emptyLyricsNoticeCompletedFor="a",exhaustedEmptyLyricsFor="a"));assertLayout(true)
        publish(state("b").copy(isLoading=true,emptyLyricsNoticeCompletedFor="a",exhaustedEmptyLyricsFor="a"));assertLayout(true)
        publish(state("b").copy(lyricsNotice=LyricsNoticePhase.Visible));assertLayout(true)
        publish(state("b").copy(lyricsNotice=LyricsNoticePhase.Done));assertLayout(true)
        save("next-lyrics-unknown-keeps-center")
        publish(state("b").copy(lyrics=lines,isLoading=true));assertLayout(false)
        compose.onNodeWithText("歌词已加载").assertIsDisplayed()
        publish(state("b").copy(lyrics=lines));assertLayout(false)
        save("next-lyrics-ready-restores-split")
    }

    @Test fun queueAndHiddenOverridesDoNotOverwriteTheSettledLayout() {
        mount(state().copy(emptyLyricsNoticeCompletedFor="a",exhaustedEmptyLyricsFor="a"));assertLayout(true)
        compose.runOnIdle {queue=true};assertLayout(false)
        compose.onNodeWithText("播放列表").assertIsDisplayed()
        compose.runOnIdle {queue=false};assertLayout(true)
        publish(state().copy(lyrics=lines));assertLayout(false)
        compose.runOnIdle {hidden=true};assertLayout(true)
        compose.runOnIdle {hidden=false};assertLayout(false)
    }
}
