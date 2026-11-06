package com.applemusicktv.ui.screens

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.tv.material3.Text
import com.applemusicktv.data.model.Song
import com.applemusicktv.data.network.LyricLine
import com.applemusicktv.ui.components.TouchSurface
import com.applemusicktv.ui.components.returnToTabOnUp
import java.io.File
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,qualifiers="w960dp-h540dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class PlayerPresentationUiTest {
    @org.junit.Before fun selectChineseForFixtures() { com.applemusicktv.i18n.AppLanguage.select(com.applemusicktv.i18n.LanguageChoice.Chinese) }

    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private fun image(): Bitmap {
        lateinit var result:Bitmap
        compose.runOnIdle {
            val view=compose.activity.findViewById<android.view.View>(android.R.id.content)
            result=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            view.draw(Canvas(result))
        }
        return result
    }
    private fun save(name:String):Bitmap {
        val result=image()
        val root=com.applemusicktv.testing.TestArtifacts.root
        val out=File(root,"artifacts/v3.0-v16-release/preview/$name.png");out.parentFile.mkdirs()
        out.outputStream().use { result.compress(Bitmap.CompressFormat.PNG,100,it) }
        return result
    }
    private fun tick(ms:Long) {
        compose.runOnIdle { androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeBy(ms);compose.waitForIdle()
    }

    @Test fun loadingNeverSpendsTheHideTimerAndPlaybackStartsANewFullCountdown() {
        compose.mainClock.autoAdvance=false
        var loading by mutableStateOf(true);var playing by mutableStateOf(false);var visible=true
        compose.setContent { PlayerChromeAutoHide("song",loading,playing,0,false) { visible=it } }
        tick(20_000);assertTrue(visible)
        compose.runOnIdle { loading=false };tick(10_000);assertTrue(visible)
        compose.runOnIdle { playing=true };tick(32);tick(7_800);assertTrue(visible)
        tick(300);assertFalse(visible)
        compose.runOnIdle { loading=true;playing=false };tick(32);assertFalse(visible)
        tick(15_000);assertFalse(visible)
        compose.runOnIdle { loading=false;playing=true };tick(32);tick(8_500);assertFalse(visible)
    }

    @Test fun trackChangesInteractionAndPopupRespectThePlaybackGate() {
        compose.mainClock.autoAdvance=false
        var id by mutableStateOf("first");var playing by mutableStateOf(true)
        var interaction by mutableStateOf(0L);var popup by mutableStateOf(false);var visible=true
        compose.setContent { PlayerChromeAutoHide(id,false,playing,interaction,popup) { visible=it } }
        tick(8_500);assertFalse(visible)
        compose.runOnIdle { id="second";playing=false };tick(12_000);assertFalse(visible)
        compose.runOnIdle { playing=true };tick(32);tick(6_000)
        compose.runOnIdle { interaction++ };tick(32);tick(7_000);assertTrue(visible)
        compose.runOnIdle { popup=true };tick(12_000);assertTrue(visible)
        compose.runOnIdle { popup=false };tick(32);tick(7_800);assertTrue(visible)
        tick(300);assertFalse(visible)
    }

    @Test fun lyricsWidthIs85PercentAndThreeSpacesReceiveEqualShares() {
        for ((width,cover) in listOf(921.6f to 260f,960f to 224f,1280f to 360f,720f to 180f)) {
            val w=width.dp;val side=(w*.075f).coerceIn(32.dp,96.dp);val gap=(w*.07f).coerceIn(36.dp,112.dp)
            val old=w-side*2-gap-cover.dp;val spacing=playerColumnSpacing(w,side,gap,cover.dp)
            assertEquals(old.value*.85f,(w-spacing.side*2-spacing.gap-cover.dp).value,.001f)
            assertEquals((spacing.side-side).value,(spacing.gap-gap).value,.001f)
        }
    }

    @Test fun emptyLyricsUsesLyricMetricsAndExpiresOnceAfterThreeSeconds() {
        compose.mainClock.autoAdvance=false
        var song by mutableStateOf("first")
        compose.setContent {
            val scope = rememberCoroutineScope()
            val notice = remember { com.applemusicktv.ui.viewmodel.LyricsFailureNotice(scope) }
            val phase by notice.phase.collectAsState()
            LaunchedEffect(song) { notice.beginSong(song); notice.report(1,0); notice.finishEmpty(); notice.showWhenReady() }
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                EmptyLyrics(song,.85f,phase == com.applemusicktv.ui.viewmodel.LyricsNoticePhase.Visible)
            }
        }
        tick(32)
        val layouts=mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("暂无歌词").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(27.2f,layouts.single().layoutInput.style.fontSize.value,.01f)
        assertEquals(androidx.compose.ui.text.font.FontWeight.Bold,layouts.single().layoutInput.style.fontWeight)
        save("empty-lyrics-visible")
        tick(2_800);compose.onNodeWithText("暂无歌词").assertIsDisplayed()
        tick(250);compose.onNodeWithText("暂无歌词").assertDoesNotExist()
        save("empty-lyrics-hidden")
        tick(10_000);compose.onNodeWithText("暂无歌词").assertDoesNotExist()
        compose.runOnIdle { song="second" };tick(32);compose.onNodeWithText("暂无歌词").assertIsDisplayed()
    }

    private fun seekNavigation(touch:Boolean) {
        shadowOf(compose.activity.packageManager).setSystemFeature("android.hardware.touchscreen",touch)
        shadowOf(compose.activity.packageManager).setSystemFeature("android.software.leanback",!touch)
        val upper=FocusRequester();val lower=FocusRequester()
        val player=Proxy.newProxyInstance(Player::class.java.classLoader,arrayOf(Player::class.java)) { _,_,_->null } as Player
        compose.setContent {
            Column(Modifier.width(300.dp)) {
                TouchSurface({},Modifier.size(50.dp).focusRequester(upper).testTag("metadata")) { Text("歌手") }
                PlayerProgressBar(remember { mutableStateOf(10_000L) },Song("s","t","a","b",60_000,null,null,null),lower,player)
                TouchSurface({},Modifier.size(50.dp).focusRequester(lower).testTag("transport")) { Text("播放") }
            }
        }
        compose.runOnIdle { upper.requestFocus() }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithTag("transport").assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithTag("metadata").assertIsFocused()
    }
    @Test fun remoteSkipsSeekBarInBothVerticalDirections() = seekNavigation(false)
    @Test fun tabletDpadAlsoSkipsTheTouchSlider() = seekNavigation(true)

    @Test fun bothCrossfadeButtonsReturnUpToAvatarAfterLowerRow() {
        val avatar=FocusRequester()
        compose.setContent {
            Column(Modifier.fillMaxSize()) {
                TouchSurface({},Modifier.size(50.dp).focusRequester(avatar).testTag("avatar")) { Text("头像") }
                Stepper("交叉渐变","2 秒","衔接",{},{},Modifier.returnToTabOnUp { avatar.requestFocus() })
                TouchSurface({},Modifier.fillMaxWidth().height(60.dp).testTag("lower")) { Text("下方选项") }
            }
        }
        for (symbol in listOf("−","+")) {
            compose.onNodeWithText(symbol).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
            compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            compose.onNodeWithTag("lower").assertIsFocused()
            compose.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
            compose.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
            compose.onNodeWithTag("avatar").assertIsFocused()
        }
    }

    @Test fun focusedStepperUsesSolidAccentInsteadOfGray() {
        compose.setContent { Column(Modifier.width(500.dp).background(Color.White)) { Stepper("交叉渐变","2 秒","衔接",{},{}) } }
        compose.onNodeWithText("+").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        compose.mainClock.advanceTimeBy(400)
        val bmp=save("settings-stepper-red-focus")
        val bounds=compose.onNodeWithText("+").fetchSemanticsNode().boundsInRoot
        // Sample inside the round button, away from its clipped corner and glyph.
        val color=bmp.getPixel(bounds.center.x.toInt(),bounds.top.toInt()+4)
        assertTrue("Focus background must use the accent: $color",android.graphics.Color.red(color)>android.graphics.Color.green(color)+15)
        assertTrue(android.graphics.Color.green(color)<100)
    }


    @Test fun lyricViewportKeepsItsStartAndParagraphWidthWhileAllowingFocusOverflow() {
        compose.mainClock.autoAdvance=false
        val text="i".repeat(300)
        val outside=FocusRequester()
        compose.setContent {
            Column(Modifier.fillMaxSize().background(Color.Black)) {
                TouchSurface({},Modifier.size(40.dp).focusRequester(outside)) { Text("播放") }
                Box(Modifier.padding(start=40.dp).width(700.dp).height(420.dp)) {
                    LyricsPanel(listOf(LyricLine(0,60_000,text)),remember { mutableStateOf(10_000L) },0,{},fontScale=.85f)
                }
            }
        }
        compose.runOnIdle { outside.requestFocus() };tick(400)
        fun layout():TextLayoutResult {
            val result=mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(text,useUnmergedTree=true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(result) }
            return result.single()
        }
        val before=layout()
        val bounds=compose.onNodeWithText(text,useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
        assertEquals(48f,bounds.left,.5f)
        assertEquals(668,before.layoutInput.constraints.maxWidth)
        compose.onNodeWithText(text).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        tick(240)
        val after=layout();assertEquals(before.lineCount,after.lineCount);assertEquals(before.size,after.size)
        val bitmap=save("lyrics-panel-focus-overflow")
        assertTrue("Scaled glyphs must be allowed beyond the 700dp panel edge",(740 until 770).any { x ->
            (60 until 400).any { y -> android.graphics.Color.red(bitmap.getPixel(x,y))>80 }
        })
    }

    @Test fun focusedLyricKeepsParagraphLayoutWhileScalingWithoutABackplate() {
        compose.mainClock.autoAdvance=false
        val lyricFocus=FocusRequester();val outside=FocusRequester()
        val text="文字保持原有换行 This paragraph keeps its original wrapping"
        compose.setContent {
            Column(Modifier.fillMaxSize().background(Color.Black)) {
                TouchSurface({},Modifier.size(50.dp).focusRequester(outside).testTag("outside")) { Text("播放") }
                Box(Modifier.width(360.dp)) { LyricLineRow(LyricLine(0,60_000,text),true,false,{0L},{},focusRequester=lyricFocus) }
            }
        }
        compose.runOnIdle { outside.requestFocus() };tick(400)
        fun layout():TextLayoutResult {
            val result=mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(text,useUnmergedTree=true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(result) }
            return result.single()
        }
        val before=layout();save("lyric-unfocused")
        compose.runOnIdle { lyricFocus.requestFocus() };tick(240)
        val after=layout();assertEquals(before.lineCount,after.lineCount);assertEquals(before.size,after.size)
        val bounds=compose.onNodeWithText(text,useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.width>before.size.width*1.04f)
        val bmp=save("lyric-focused-no-backplate")
        assertEquals(android.graphics.Color.BLACK,bmp.getPixel(2,bounds.top.toInt()+2))
    }
}
