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
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.applemusicktv.data.fonts.FontDownloadPhase
import com.applemusicktv.data.fonts.FontDownloadState
import com.applemusicktv.data.model.Song
import com.applemusicktv.ui.components.Glyph
import com.applemusicktv.ui.components.PlayerLoadingTrack
import com.applemusicktv.ui.components.TouchSeekBar
import com.applemusicktv.ui.theme.LocalAppFontState
import java.io.File
import java.lang.reflect.Proxy
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
@OptIn(ExperimentalTestApi::class)
class PlayerPolishUiTest {
    @org.junit.Before fun selectChineseForFixtures() { com.applemusicktv.i18n.AppLanguage.select(com.applemusicktv.i18n.LanguageChoice.Chinese) }

    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val ready=FontDownloadState(1,1,FontDownloadPhase.Ready)
    private val indeterminate=SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,ProgressBarRangeInfo.Indeterminate)
    private val output: File get() = File(com.applemusicktv.testing.TestArtifacts.root,"artifacts/v3.0-v21-release/preview").apply { mkdirs() }
    private fun save(name:String) = compose.runOnIdle {
        val view=compose.activity.window.decorView
        val image=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
        view.draw(Canvas(image))
        File(output,"$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
    }
    private fun artwork(): String {
        val file=File(output,"cover-fixture.png")
        Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(140,70,90)) }
            .let { bitmap -> file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) } }
        return file.toURI().toString()
    }
    private fun song(cover:String?=null)=Song("fixture","封面与控件应保留","测试歌手","测试专辑",60_000,cover,null,null)
    private fun capture(node:SemanticsNodeInteraction): Bitmap {
        val bounds=node.fetchSemanticsNode().boundsInRoot
        lateinit var image:Bitmap
        compose.runOnIdle {
            val view=compose.activity.findViewById<android.view.View>(android.R.id.content)
            image=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            view.draw(Canvas(image))
        }
        val left=bounds.left.toInt().coerceAtLeast(0);val top=bounds.top.toInt().coerceAtLeast(0)
        return Bitmap.createBitmap(image,left,top,bounds.width.toInt().coerceAtLeast(1),bounds.height.toInt().coerceAtLeast(1))
    }
    private fun peak(description:String): Float {
        val pixels=capture(compose.onNodeWithContentDescription(description))
        var peak=0f
        for (x in 0 until pixels.width) for(y in 0 until pixels.height) peak=maxOf(peak,android.graphics.Color.red(pixels.getPixel(x,y))/255f)
        return peak
    }

    @Test fun focusedInactiveToggleStaysDimUntilActuallyActivated() {
        var selected by mutableStateOf(false)
        val focus=FocusRequester()
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                PlayerAccessoryButton(Glyph.SHUFFLE,"随机播放",selected,modifier=Modifier.focusRequester(focus)) { selected=!selected }
            }
        }
        compose.runOnIdle { focus.requestFocus() }
        compose.waitForIdle()
        val dim=peak("随机播放")
        assertTrue("Focus must not turn an inactive icon white: $dim",dim<0.85f)
        save("toggle-focused-inactive")
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        assertTrue(selected)
        assertTrue(peak("随机播放")>dim+0.15f)
        save("toggle-focused-active")
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        assertFalse(selected)
        assertTrue(peak("随机播放")<0.85f)
    }

    @Test fun focusedMomentaryTransportOnlyBrightensWhilePressed() {
        val focus=FocusRequester();var clicks=0
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                TransportButton(TransportIcon.Next,{clicks++},modifier=Modifier.focusRequester(focus),inactiveColor=Color.White.copy(alpha=.5f))
            }
        }
        compose.runOnIdle { focus.requestFocus() }
        val dim=peak("下一首");assertTrue(dim<.85f)
        compose.onRoot().performKeyInput { keyDown(Key.DirectionCenter) }
        compose.waitForIdle()
        assertTrue(peak("下一首")>dim+.15f)
        compose.onRoot().performKeyInput { keyUp(Key.DirectionCenter) }
        compose.waitForIdle()
        assertEquals(1,clicks)
        assertTrue(peak("下一首")<.85f)
    }

    @Test fun loadingHighlightMovesAndStopsMountingWhenNormalTrackReturns() {
        compose.mainClock.autoAdvance=false
        var loading by mutableStateOf(true)
        val player=Proxy.newProxyInstance(Player::class.java.classLoader,arrayOf(Player::class.java)) { _,_,_->null } as Player
        compose.setContent {
            Column(Modifier.width(300.dp).background(Color.Black)) {
                PlayerProgressBar(remember { mutableStateOf(0L) },song(),FocusRequester(),player,loading=loading)
            }
        }
        compose.mainClock.advanceTimeBy(32)
        compose.onNode(indeterminate,useUnmergedTree=true).assertExists()
        fun brightCenter(): Float {
            val map=capture(compose.onNode(indeterminate,useUnmergedTree=true))
            val xs=(0 until map.width).filter { android.graphics.Color.red(map.getPixel(it,map.height/2))>178 }
            assertTrue(xs.isNotEmpty())
            return xs.average().toFloat()
        }
        val first=brightCenter()
        save("loading-track-start")
        compose.mainClock.advanceTimeBy(450)
        assertTrue(brightCenter()>first+60f)
        save("loading-track-moving")
        compose.runOnIdle { loading=false }
        compose.mainClock.advanceTimeBy(32)
        compose.onNode(indeterminate,useUnmergedTree=true).assertDoesNotExist()
    }

    @Test fun touchSeekBarKeepsSameHeightAndCannotSeekWhileLoading() {
        var loading by mutableStateOf(true);val seeks=mutableListOf<Long>()
        compose.setContent {
            Box(Modifier.width(300.dp)) {
                TouchSeekBar(0,60_000,seeks::add,Modifier.testTag("seek"),loading=loading)
            }
        }
        val height=compose.onNodeWithTag("seek").fetchSemanticsNode().boundsInRoot.height
        compose.onNodeWithTag("seek").assertIsNotEnabled()
        compose.onNodeWithTag("seek").performTouchInput { click(center) }
        assertTrue(seeks.isEmpty())
        compose.runOnIdle { loading=false }
        compose.onNodeWithTag("seek").assertIsEnabled()
        assertEquals(height,compose.onNodeWithTag("seek").fetchSemanticsNode().boundsInRoot.height,.1f)
        compose.onNodeWithTag("seek").performTouchInput { click(center) }
        assertTrue(seeks.isNotEmpty())
    }

    @Test fun emptyFullscreenRetainsCoverMetadataControlsAndTheirIdlePolicy() {
        var visible by mutableStateOf(true);var playing by mutableStateOf(false);var clicks=0
        val song=song(artwork())
        compose.setContent {
            CompositionLocalProvider(LocalAppFontState provides ready) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    FullScreenLyrics(emptyList(),remember { mutableStateOf(0L) },0,song,playing,{}, {}, {clicks++;playing=!playing}, {},chromeVisible=visible)
                }
            }
        }
        compose.onNodeWithText(song.title).assertIsDisplayed()
        compose.onNodeWithText("暂无歌词").assertIsDisplayed()
        for(label in listOf("上一首","播放","下一首")) compose.onNodeWithContentDescription(label).assertIsDisplayed()
        compose.waitUntil(5_000) {
            // Wait for Coil's local-file decode before exporting the actual cover preview.
            var found=false
            compose.runOnIdle {
                val view=compose.activity.window.decorView
                val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                found=(40 until 128).any { x -> (400 until minOf(bitmap.height,515)).any { y ->
                    val color=bitmap.getPixel(x,y)
                    android.graphics.Color.red(color)>100 && android.graphics.Color.green(color) in 30..110
                } }
            }
            found
        }
        save("fullscreen-no-lyrics-controls")
        compose.onNodeWithContentDescription("播放").performClick()
        assertEquals(1,clicks)
        compose.onNodeWithContentDescription("暂停").assertIsDisplayed()
        compose.runOnIdle { visible=false }
        compose.mainClock.advanceTimeBy(500)
        for(label in listOf("上一首","暂停","下一首")) {
            val bounds = compose.onNodeWithContentDescription(label).fetchSemanticsNode().boundsInRoot
            compose.runOnIdle { com.applemusicktv.testing.assertBlackRegion(compose.activity.window.decorView, bounds) }
        }
        compose.onNodeWithText(song.title).assertIsDisplayed()
        save("fullscreen-no-lyrics-idle")
        compose.runOnIdle { visible=true }
        compose.onNodeWithContentDescription("暂停").assertIsDisplayed()
    }

    @Test fun fontDownloadMessageDoesNotHideFullscreenTransport() {
        compose.setContent {
            CompositionLocalProvider(LocalAppFontState provides FontDownloadState(50,100)) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    FullScreenLyrics(emptyList(),remember { mutableStateOf(0L) },0,song(),false,{}, {}, {}, {})
                }
            }
        }
        compose.onNodeWithText("歌词字体加载中，50%").assertIsDisplayed()
        compose.onNodeWithContentDescription("播放").assertIsDisplayed()
        compose.onNodeWithText("暂无歌词").assertDoesNotExist()
    }
}
