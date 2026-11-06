package com.applemusicktv.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.applemusicktv.data.fonts.*
import com.applemusicktv.ui.components.FontGatedLyrics
import com.applemusicktv.ui.components.AlbumCard
import com.applemusicktv.ui.components.TouchSurface
import com.applemusicktv.ui.components.focusedArtworkBorder
import com.applemusicktv.data.model.Album
import com.applemusicktv.ui.screens.*
import com.applemusicktv.ui.viewmodel.OnboardingState
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], application=Application::class, qualifiers="w960dp-h540dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalTestApi::class)
class V21PolishTest {
    @org.junit.Before fun selectChineseForFixtures() { com.applemusicktv.i18n.AppLanguage.select(com.applemusicktv.i18n.LanguageChoice.Chinese) }

    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private fun bitmap(name:String): Bitmap {
        lateinit var image:Bitmap
        compose.runOnIdle {
            val view=compose.activity.window.decorView
            image=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            view.draw(Canvas(image))
            val root=com.applemusicktv.testing.TestArtifacts.root
            File(root,"artifacts/v3.0-v21-release/preview/$name.png").apply { parentFile.mkdirs() }.outputStream().use {
                image.compress(Bitmap.CompressFormat.PNG,100,it)
            }
        }
        return image
    }
    private fun pixels(image:Bitmap,color:Int,minY:Int=0)=
        (minY until image.height).flatMap {y->(0 until image.width).filter {x->image.getPixel(x,y)==color}.map {x->x to y}}

    @Test fun delayedLoginFocusDoesNotResizeButtonAndDarkLinkRemainsTouchOnly() {
        compose.mainClock.autoAdvance=false
        val url="http://192.0.2.15:8080/"
        compose.setContent {
            val mode = LocalInputModeManager.current
            SideEffect { mode.requestInputMode(InputMode.Keyboard) }
            OnboardingContent(OnboardingState(phoneUrl=url),{})
        }
        compose.runOnIdle { compose.activity.window.decorView.dispatchWindowFocusChanged(true) }
        compose.mainClock.advanceTimeBy(16)
        val before=bitmap("login-before-focus")
        compose.mainClock.advanceTimeBy(700)
        val after=bitmap("login-after-focus")
        fun bounds(image:Bitmap):List<Int> {
            val points=pixels(image,0xFFFA233B.toInt(),image.height/2)
            assertTrue(points.size>1000)
            return listOf(points.minOf {it.first},points.maxOf {it.first},points.minOf {it.second},points.maxOf {it.second})
        }
        assertEquals("Delayed autofocus must not resize the filled button",bounds(before),bounds(after))
        compose.onNodeWithText("跳过，仅试听").assertIsFocused()
        compose.onNodeWithText(url+" ↗").assertDoesNotExist()
        compose.onNodeWithText(url).assert(isFocusable().not())
        compose.onNodeWithText("跳过，仅试听").performKeyInput {pressKey(Key.DirectionUp)}
        compose.onNodeWithText("跳过，仅试听").assertIsFocused()
        compose.onNodeWithText(url).performTouchInput {click(center)}
        val intent=shadowOf(compose.activity).nextStartedActivity
        assertEquals(android.content.Intent.ACTION_VIEW,intent.action)
        assertEquals(url,intent.dataString)
    }

    @Test fun focusedStepperUsesSolidAccentAndWhiteSymbolWithoutChangingValue() {
        val focus=FocusRequester(); var changed=0
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Color.White).padding(80.dp)) {
                // Stepper's actual buttons, with the second reached by the D-pad.
                Stepper("交叉渐变","3.0 秒","测试",{changed--},{changed++},Modifier.focusRequester(focus))
            }
        }
        compose.runOnIdle {focus.requestFocus()}
        compose.mainClock.advanceTimeBy(500)
        val image=bitmap("stepper-focused-solid-accent")
        val red=pixels(image,0xFFFA233B.toInt())
        assertTrue("Solid theme fill",red.size>500)
        val left=red.minOf {it.first};val right=red.maxOf {it.first}
        val top=red.minOf {it.second};val bottom=red.maxOf {it.second}
        val symbolStyle=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText("−").performSemanticsAction(SemanticsActions.GetTextLayoutResult) {it(symbolStyle)}
        assertEquals("Focused symbol resolves to white before antialiasing",Color.White,symbolStyle.single().layoutInput.style.color)
        val centerX=(left+right)/2;val centerY=(top+bottom)/2
        assertTrue("White symbol pixels are inside the circle",(centerY-8..centerY+8).any {y->(centerX-8..centerX+8).any {x->
            val pixel=image.getPixel(x,y)
            android.graphics.Color.red(pixel)>245 && android.graphics.Color.green(pixel)>220 && android.graphics.Color.blue(pixel)>220
        }})
        compose.onNodeWithText("−").performKeyInput {pressKey(Key.DirectionRight)}
        compose.mainClock.advanceTimeBy(500)
        val plusStyle=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText("+").performSemanticsAction(SemanticsActions.GetTextLayoutResult) {it(plusStyle)}
        assertEquals(Color.White,plusStyle.single().layoutInput.style.color)
        assertEquals(0,changed)
    }

    @Test fun stationStatusHasSameCenterAndInsetsAsEmptyLyricsAtBothScales() {
        var station by mutableStateOf(true);var scale by mutableFloatStateOf(1f)
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Color.Black).padding(60.dp)) {
                if(station) StationLaunchMessage(false,scale)
                else FontGatedLyrics(FontDownloadState(100,100,FontDownloadPhase.Ready)) {EmptyLyrics("song",scale)}
            }
        }
        for (value in listOf(1f,1.3f)) {
            compose.runOnIdle {scale=value;station=true}
            val pending=compose.onNodeWithText("电台启动中…").fetchSemanticsNode().boundsInRoot
            bitmap(if(value==1f) "station-starting" else "station-starting-fullscreen-scale")
            compose.runOnIdle {station=false}
            val empty=compose.onNodeWithText("暂无歌词").fetchSemanticsNode().boundsInRoot
            assertEquals(empty.center.x,pending.center.x,1f)
            assertEquals(empty.top,pending.top,1f)
        }
    }

    @Test fun fontLoadingShowsMatchingWidthProgressThenFailureAndReadyStates() {
        var state by mutableStateOf(FontDownloadState(50,100))
        compose.setContent {Box(Modifier.fillMaxSize().background(Color.Black)) {
            FontGatedLyrics(state) {androidx.tv.material3.Text("歌词内容")}
        }}
        val text=compose.onNodeWithText("歌词字体加载中，50%").fetchSemanticsNode().boundsInRoot
        val bar=compose.onNodeWithContentDescription("歌词字体加载进度").fetchSemanticsNode()
        assertEquals(text.width,bar.boundsInRoot.width,1f)
        assertTrue(bar.boundsInRoot.top>text.bottom)
        assertEquals(0.5f,bar.config[SemanticsProperties.ProgressBarRangeInfo].current,0.001f)
        assertTrue(text.height>=40f)
        val image=bitmap("font-loading-progress-50")
        assertTrue(pixels(image,0xFFFA233B.toInt()).size>300)
        compose.runOnIdle {state=FontDownloadState(99,100)}
        compose.onNodeWithText("歌词字体加载中，99%").assertIsDisplayed()
        compose.runOnIdle {state=state.copy(phase=FontDownloadPhase.Failed)}
        compose.onNodeWithText("字体下载失败，稍后将自动重试").assertIsDisplayed()
        compose.onNodeWithContentDescription("歌词字体加载进度").assertDoesNotExist()
        compose.runOnIdle {state=FontDownloadState(100,100,FontDownloadPhase.Ready)}
        compose.onNodeWithText("歌词内容").assertIsDisplayed()
        compose.onNodeWithText("字体下载失败，稍后将自动重试").assertDoesNotExist()
    }

    @Test fun albumFrameLeavesSmallGapWithoutFramingTitle() = checkArtworkGap(0)
    @Test fun circularFrameLeavesSmallConcentricGap() = checkArtworkGap(1)
    @Test fun wideFrameLeavesSmallGapWithMatchingCornerCenters() = checkArtworkGap(2)

    private fun checkArtworkGap(scene:Int) {
        val focus=FocusRequester();lateinit var input:InputModeManager
        compose.setContent {
            input=LocalInputModeManager.current
            Box(Modifier.fillMaxSize().background(Color.White).padding(80.dp)) {
                if(scene==0) AlbumCard(Album(id="album",title="标题不加框",artistName="艺人",artworkUrl=null,color=0xFF336699),
                    size=180,onClick={},modifier=Modifier.focusRequester(focus))
                else {
                    val shape=if(scene==1) CircleShape else RoundedCornerShape(topStart=10.dp,topEnd=10.dp)
                    TouchSurface(onClick={},modifier=Modifier.width(if(scene==1)180.dp else 260.dp).height(180.dp).focusRequester(focus),
                        shape=androidx.tv.material3.ClickableSurfaceDefaults.shape(shape),
                        scale=androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale=1.06f)) {
                        Box(Modifier.fillMaxSize().focusedArtworkBorder(shape).clip(shape).background(Color(0xFF336699)))
                    }
                }
            }
        }
        compose.runOnIdle {input.requestInputMode(InputMode.Keyboard);focus.requestFocus()}
        compose.mainClock.advanceTimeBy(700)
        val image=bitmap("artwork-separated-frame-$scene")
        val blue=pixels(image,0xFF336699.toInt())
        val row=(blue.minOf {it.second}+blue.maxOf {it.second})/2
        val left=blue.filter {it.second==row}.minOf {it.first}
        val right=blue.filter {it.second==row}.maxOf {it.first}
        fun isGray(pixel:Int):Boolean {
            val r=android.graphics.Color.red(pixel);val g=android.graphics.Color.green(pixel);val b=android.graphics.Color.blue(pixel)
            return r in 195..225 && g in 195..225 && b>=r && b<=235
        }
        val gray=(0 until image.width).filter {isGray(image.getPixel(it,row))}
        assertTrue("Both outside frame edges survive parent clipping",gray.any {it<left}&&gray.any {it>right})
        val before=gray.filter {it<left}.max();val after=gray.filter {it>right}.min()
        assertTrue("Fine left gap",left-before in 2..4)
        assertTrue("Fine right gap",after-right in 2..4)
        assertTrue("Image still enlarges instead of shrinking to reserve the gap",right-left>=185)
        if(scene==0) {
            val title=pixels(image,0xFF1D1D1F.toInt())
            val frame=pixels(image,0xFFC7C7CC.toInt())
            assertTrue(frame.maxOf {it.second}<title.minOf {it.second})
        }
    }
}
