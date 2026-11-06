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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.applemusicktv.data.fonts.*
import com.applemusicktv.data.model.Song
import com.applemusicktv.ui.components.*
import com.applemusicktv.ui.screens.*
import com.applemusicktv.ui.theme.LocalAppFontState
import com.applemusicktv.ui.viewmodel.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,qualifiers="w960dp-h540dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTvMaterial3Api::class,ExperimentalTestApi::class)
class V22PlayerUiTest {
    @Before fun selectChineseForLegacyLabels() { com.applemusicktv.i18n.AppLanguage.select(com.applemusicktv.i18n.LanguageChoice.Chinese) }
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private fun popupKey(keyCode:Int) = compose.runOnIdle {
        val type=Class.forName("android.view.WindowManagerGlobal")
        val instance=type.getDeclaredMethod("getInstance").invoke(null)
        val field=type.getDeclaredField("mViews").apply {isAccessible=true}
        @Suppress("UNCHECKED_CAST") val views=field.get(instance) as List<android.view.View>
        val popup=views.last {it.javaClass.name.contains("PopupLayout")}
        popup.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,keyCode))
        popup.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP,keyCode))
    }
    private fun save(name:String,popup:Boolean=false) = compose.runOnIdle {
        val decor=compose.activity.window.decorView
        val view=if(popup) runCatching {
            val type=Class.forName("android.view.WindowManagerGlobal")
            val instance=type.getDeclaredMethod("getInstance").invoke(null)
            val field=type.getDeclaredField("mViews").apply {isAccessible=true}
            @Suppress("UNCHECKED_CAST") val views=field.get(instance) as List<android.view.View>
            views.lastOrNull {it.javaClass.name.contains("PopupLayout")}?:decor
        }.getOrDefault(decor) else decor
        val image=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
        view.draw(Canvas(image))
        val root=com.applemusicktv.testing.TestArtifacts.root
        File(root,"artifacts/v3.0-v22-release/preview/$name.png").apply { parentFile.mkdirs() }.outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG,100,it)
        }
    }

    @Test fun explicitItemMenuConsumesRemoteMenuWithoutTriggeringClickOrOtherLongAction() {
        val focus=FocusRequester();var opened=0;var played=0;var global=0
        compose.setContent {
            Box(Modifier.fillMaxSize().onKeyEvent {event->
                if(event.key==Key.Menu && event.type==KeyEventType.KeyDown) {global++;true} else false
            }) {
                TouchSurface(onClick={played++},onLongClick={opened++},onContextMenu={opened++},
                    modifier=Modifier.size(200.dp,50.dp).focusRequester(focus).testTag("song")) {
                    androidx.tv.material3.Text("歌曲菜单")
                }
            }
        }
        compose.runOnIdle {focus.requestFocus()}
        compose.onNodeWithTag("song").performKeyInput {pressKey(Key.Menu)}
        assertEquals(1,opened);assertEquals(0,played);assertEquals(0,global)
    }

    @Test fun nonMenuLongPressKeepsOriginalMenuFallback() {
        val focus=FocusRequester();var pinned=0;var global=0
        compose.setContent {
            Box(Modifier.fillMaxSize().onKeyEvent {event->
                if(event.key==Key.Menu && event.type==KeyEventType.KeyDown) {global++;true} else false
            }) {
                TouchSurface(onClick={},onLongClick={pinned++},
                    modifier=Modifier.size(200.dp,50.dp).focusRequester(focus).testTag("playlist")) {}
            }
        }
        compose.runOnIdle {focus.requestFocus()}
        compose.onNodeWithTag("playlist").performKeyInput {pressKey(Key.Menu)}
        assertEquals(0,pinned);assertEquals(1,global)
    }

    @Test fun lyricsBubbleCanHideRestoreAndEnterFullScreenWithoutChangingButtonStyle() {
        compose.mainClock.autoAdvance=false
        var hidden by mutableStateOf(false);var expanded by mutableStateOf(false);var full=0
        compose.setContent {
            val mode = androidx.compose.ui.platform.LocalInputModeManager.current
            SideEffect { mode.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard) }
            Box(Modifier.fillMaxSize().background(Color(0xFF303040)),Alignment.Center) {
                LyricsOptionsButton(hidden,expanded,{expanded=it},{hidden=!hidden},{full++})
            }
        }
        compose.onNodeWithContentDescription("歌词选项").performSemanticsAction(SemanticsActions.RequestFocus) {it()}
        compose.onNodeWithContentDescription("歌词选项").performKeyInput {pressKey(Key.DirectionCenter)}
        compose.mainClock.advanceTimeBy(200)
        compose.waitForIdle()
        compose.runOnIdle {
            val type=Class.forName("android.view.WindowManagerGlobal")
            val instance=type.getDeclaredMethod("getInstance").invoke(null)
            val field=type.getDeclaredField("mViews").apply {isAccessible=true}
            @Suppress("UNCHECKED_CAST") val views=field.get(instance) as List<android.view.View>
            views.last {it.javaClass.name.contains("PopupLayout")}.dispatchWindowFocusChanged(true)
        }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("隐藏歌词").assertIsDisplayed()
        compose.onNodeWithText("隐藏歌词").assertIsFocused()
        compose.onNodeWithText("全屏歌词").assertIsDisplayed()
        popupKey(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("全屏歌词").assertIsFocused()
        popupKey(android.view.KeyEvent.KEYCODE_DPAD_UP)
        save("lyrics-bubble",popup=true)
        compose.onNodeWithText("隐藏歌词").performClick()
        assertTrue(hidden);assertFalse(expanded);assertEquals(0,full)
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithContentDescription("歌词选项").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("显示歌词").performClick()
        assertFalse(hidden)
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithContentDescription("歌词选项").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("全屏歌词").performClick()
        assertEquals(1,full);assertFalse(expanded)
    }

    @Test fun combinedOrderButtonCyclesFourStatesAndFavoriteAddsOnce() {
        var state by mutableStateOf(PlayerState());var favorites=0
        compose.setContent {
            Row(Modifier.fillMaxSize().background(Color.Black).padding(80.dp),horizontalArrangement=Arrangement.spacedBy(20.dp)) {
                PlayerFavoriteButton {favorites++}
                PlaybackOrderButton(state.playbackOrder()) {state=state.nextPlaybackOrder()}
            }
        }
        for(label in listOf("顺序播放","列表循环","单曲循环","列表随机")) {
            compose.onNodeWithContentDescription("播放模式：$label").performClick()
        }
        compose.onNodeWithContentDescription("播放模式：顺序播放").assertIsDisplayed()
        compose.onNodeWithContentDescription("收藏，添加到资料库").performClick()
        assertEquals(1,favorites)
        save("favorite-and-combined-order")
    }

    @Test fun hiddenTransportParksOnPlayAndFirstOkOrDirectionOnlyWakes() {
        val nav=NavigationViewModel().apply {isOnNowPlaying=true}
        val play=FocusRequester();val previous=FocusRequester();var plays=0
        compose.setContent {
            val visible by nav.playerChromeVisible.collectAsState()
            PlayerPlayFocusEffect(play,visible)
            Row(Modifier.fillMaxSize().background(Color.Black).padding(80.dp)
                .onPreviewKeyEvent {nav.consumePlayerWakeKey(it.nativeKeyEvent)}.graphicsLayer {alpha=if(visible) 1f else 0f}) {
                TransportButton(TransportIcon.Prev,{},modifier=Modifier.focusRequester(previous))
                TransportButton(TransportIcon.Play,{plays++},modifier=Modifier.focusRequester(play))
                TransportButton(TransportIcon.Next,{})
            }
        }
        compose.mainClock.advanceTimeBy(200)
        compose.runOnIdle {previous.requestFocus();nav.setPlayerChromeVisible(false)}
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithContentDescription("播放").assertIsFocused()
        compose.onNodeWithContentDescription("播放").performKeyInput {pressKey(Key.DirectionCenter)}
        assertTrue(nav.playerChromeVisible.value);assertEquals(0,plays)
        compose.onNodeWithContentDescription("播放").performKeyInput {pressKey(Key.DirectionCenter)}
        assertEquals(1,plays)
        compose.runOnIdle {nav.setPlayerChromeVisible(false)}
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithContentDescription("播放").performKeyInput {pressKey(Key.DirectionRight)}
        compose.onNodeWithContentDescription("播放").assertIsFocused()
        compose.onNodeWithContentDescription("播放").performKeyInput {pressKey(Key.DirectionRight)}
        compose.onNodeWithContentDescription("下一首").assertIsFocused()
    }

    @Test fun fullscreenLyricsAlsoParksHiddenFocusOnPlay() {
        var visible by mutableStateOf(true)
        val song=Song("song","标题","艺人","专辑",60_000,null,null,null)
        compose.setContent {
            CompositionLocalProvider(LocalAppFontState provides FontDownloadState(1,1,FontDownloadPhase.Ready)) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    FullScreenLyrics(emptyList(),remember {mutableStateOf(0L)},0,song,false,{},{},{},{},chromeVisible=visible)
                }
            }
        }
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithContentDescription("播放").performKeyInput {pressKey(Key.DirectionLeft)}
        compose.onNodeWithContentDescription("上一首").assertIsFocused()
        compose.runOnIdle {visible=false}
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithContentDescription("播放").assertIsFocused()
    }

    @Test fun idleTextIsCenteredAndMatchesEmptyLyricsFontMetrics() {
        compose.setContent {Box(Modifier.fillMaxSize().background(Color.Black),Alignment.Center) {
            PlayerStatusText("尚未播放",1f,Modifier)
        }}
        val layout=mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("尚未播放").performSemanticsAction(SemanticsActions.GetTextLayoutResult) {it(layout)}
        assertEquals(32f,layout.single().layoutInput.style.fontSize.value,.01f)
        assertEquals(FontWeight.Bold,layout.single().layoutInput.style.fontWeight)
        val bounds=compose.onNodeWithText("尚未播放").fetchSemanticsNode().boundsInRoot
        val root=compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertEquals(root.center.x,bounds.center.x,1f);assertEquals(root.center.y,bounds.center.y,1f)
        save("idle-player-text-metrics")
    }

    @Test fun hiddenLyricsDoNotReappearInAutomaticFullscreenLayout() {
        val song=Song("song","标题","艺人","专辑",60_000,null,null,null)
        compose.setContent {Box(Modifier.fillMaxSize().background(Color.Black)) {
            FullScreenLyrics(emptyList(),remember {mutableStateOf(0L)},0,song,false,{},{},{},{},lyricsHidden=true)
        }}
        compose.onNodeWithText("暂无歌词").assertDoesNotExist()
        compose.onNodeWithText("歌词字体加载中",substring=true).assertDoesNotExist()
        compose.onNodeWithText("标题").assertIsDisplayed()
        compose.onNodeWithContentDescription("播放").assertIsDisplayed()
    }

    @Test fun idleFluidPipelineUsesExistingLocalArtworkWhenNoCoverHasLoaded() {
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                IdlePlayerBackground(com.applemusicktv.ui.background.FluidQuality.STATIC,false,false,
                    artworkSources=remember {kotlinx.coroutines.flow.MutableStateFlow(emptyList<String>())})
                Box(Modifier.fillMaxSize(),Alignment.Center) {PlayerStatusText("尚未播放",1f,Modifier)}
            }
        }
        compose.waitUntil(timeoutMillis=15_000) {
            var colored=0
            compose.runOnIdle {
                val view=compose.activity.window.decorView
                val image=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
                view.draw(Canvas(image))
                for(y in 0 until image.height step 16) for(x in 0 until image.width step 16) {
                    val pixel=image.getPixel(x,y)
                    if(android.graphics.Color.red(pixel)+android.graphics.Color.green(pixel)+android.graphics.Color.blue(pixel)>60) colored++
                }
                image.recycle()
            }
            colored>200
        }
        compose.onNodeWithText("尚未播放").assertIsDisplayed()
        save("idle-fluid-local-artwork-static-preview")
    }
}
