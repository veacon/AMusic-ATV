package com.applemusicktv.ui.screens

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.applemusicktv.data.model.Song
import com.applemusicktv.ui.components.LocalUiMotion
import com.applemusicktv.ui.components.UiMotion
import com.applemusicktv.ui.components.LargeScreenGate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], application=Application::class, qualifiers="w960dp-h960dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class V4PlayerVisualTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private fun image(name:String):Bitmap {
        lateinit var result:Bitmap
        compose.runOnIdle {
            val view=compose.activity.findViewById<android.view.View>(android.R.id.content)
            result=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(result))
            val root=com.applemusicktv.testing.TestArtifacts.root
            val out=File(root,"artifacts/v3.0-v16-release/preview").apply {mkdirs()}
            File(out,"$name.png").outputStream().use {result.compress(Bitmap.CompressFormat.PNG,100,it)}
        }
        return result
    }
    @Test fun tvLoadingHasNoBackgroundOutsideTheFourDpTrack() {
        // Force the actual television branch, independently of Android version.
        shadowOf(compose.activity.packageManager).setSystemFeature("android.hardware.type.television",true)
        val player=Proxy.newProxyInstance(Player::class.java.classLoader,arrayOf(Player::class.java)) {_,_,_->null} as Player
        var loading by mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalUiMotion provides UiMotion(false)) {
                Column(Modifier.width(300.dp).background(Color.Black).testTag("progressArea")) {
                    PlayerProgressBar(remember { mutableStateOf(0L) },Song("s","歌名","艺人","",60000,null,null,null),FocusRequester(),player,loading=loading)
                }
            }
        }
        val during=image("tv-loading-no-backplate")
        // The TV wrapper has 6dp top padding, 4dp track, 2dp bottom padding.
        for(y in (0..4).toList()+listOf(11)) for(x in 4..295)
            assertEquals("Loading background at $x,$y",android.graphics.Color.BLACK,during.getPixel(x,y))
        assertTrue(android.graphics.Color.red(during.getPixel(150,8))>0)
        compose.runOnIdle {loading=false}
        val ready=image("tv-ready-track")
        for(y in 0..4) for(x in 4..295) assertEquals(ready.getPixel(x,y),during.getPixel(x,y))
    }
    @Test fun songFocusBackingFadesWithChromeWhileTitleAndFocusRemain() {
        var alpha by mutableFloatStateOf(1f);val focus=FocusRequester()
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                SongInfoFocusSurface(alpha,{},Modifier.width(300.dp).focusRequester(focus).testTag("songInfo")) {
                    Text("歌曲名称",color=Color.White,modifier=Modifier.padding(20.dp))
                }
            }
        }
        compose.onNodeWithTag("songInfo").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag("songInfo").assertIsFocused()
        val shown=image("title-focus-visible");val bright=android.graphics.Color.red(shown.getPixel(10,10));assertTrue(bright>0)
        compose.runOnIdle {alpha=.5f}
        val half=image("title-focus-half");assertTrue(android.graphics.Color.red(half.getPixel(10,10)) in 1 until bright)
        compose.runOnIdle {alpha=0f}
        val hidden=image("title-focus-hidden");assertEquals(android.graphics.Color.BLACK,hidden.getPixel(10,10))
        compose.onNodeWithText("歌曲名称").assertIsDisplayed();compose.onNodeWithTag("songInfo").assertIsFocused()
    }
    @Test fun iconChoicesHaveDescriptionAboveIconsAndSwitchSelectionWithoutLabels() {
        compose.setContent {Box(Modifier.width(500.dp).background(Color(0xFFF5F5F7))) {LauncherIconSettings()}}
        val title=compose.onNodeWithText("更换应用图标").fetchSemanticsNode().boundsInRoot
        val description=compose.onNodeWithText("更换后无需重启应用，桌面图标稍后自动刷新").fetchSemanticsNode().boundsInRoot
        val red=compose.onNodeWithContentDescription("红色图标");val white=compose.onNodeWithContentDescription("白色图标")
        assertTrue(title.bottom<=description.top);assertTrue(description.bottom<red.fetchSemanticsNode().boundsInRoot.top)
        red.assertIsSelected();white.assertIsNotSelected()
        compose.onNodeWithText("红色 · 已选").assertDoesNotExist();compose.onNodeWithText("白色").assertDoesNotExist()
        image("icon-selection-red")
        white.performClick();white.assertIsSelected();red.assertIsNotSelected();image("icon-selection-white")
    }
    @Test fun gateHasOnlyIconBrandAndShortInstruction() {
        compose.setContent {Box(Modifier.requiredSize(360.dp,800.dp)) {LargeScreenGate {Text("Main")}}}
        compose.onNodeWithText("Main").assertDoesNotExist()
        val icon=compose.onNodeWithContentDescription("Apple Music TV").fetchSemanticsNode().boundsInRoot
        val brand=compose.onNodeWithText("Apple Music").fetchSemanticsNode().boundsInRoot
        val instruction=compose.onNodeWithText("请在大屏设备上使用").fetchSemanticsNode().boundsInRoot
        assertTrue(icon.bottom<brand.top);assertTrue(brand.bottom<instruction.top)
        compose.onNodeWithText("Apple Music 专为大屏体验设计。",substring=true).assertDoesNotExist()
        image("gate-simplified")
    }
}
