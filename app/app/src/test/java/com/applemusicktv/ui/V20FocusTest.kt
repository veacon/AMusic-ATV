package com.applemusicktv.ui

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
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import com.applemusicktv.data.model.Album
import com.applemusicktv.ui.components.AlbumCard
import com.applemusicktv.ui.components.TouchSurface
import com.applemusicktv.ui.screens.OnboardingContent
import com.applemusicktv.ui.screens.LauncherIconSettings
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
class V20FocusTest {
    @org.junit.Before fun selectChineseForFixtures() { com.applemusicktv.i18n.AppLanguage.select(com.applemusicktv.i18n.LanguageChoice.Chinese) }

    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private fun bitmap(name:String): Bitmap {
        lateinit var result:Bitmap
        compose.runOnIdle {
            val view=compose.activity.window.decorView
            result=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            view.draw(Canvas(result))
            val root=com.applemusicktv.testing.TestArtifacts.root
            File(root,"artifacts/v3.0-v21-release/preview").apply {mkdirs()}
                .resolve("$name.png").outputStream().use {result.compress(Bitmap.CompressFormat.PNG,100,it)}
        }
        return result
    }
    private fun count(image:Bitmap,color:Int)= (0 until image.height).sumOf { y ->
        (0 until image.width).count { x -> image.getPixel(x,y)==color }
    }

    @Test fun focusedAlbumFramesArtworkButNeverItsTitle() {
        val focus=FocusRequester();lateinit var input:InputModeManager
        compose.setContent {
            input=LocalInputModeManager.current
            Box(Modifier.fillMaxSize().background(Color.White).padding(80.dp)) {
                AlbumCard(Album(id="album",title="封面外的标题",artistName="艺人",artworkUrl=null,color=0xFF336699),
                    size=180,onClick={},modifier=Modifier.focusRequester(focus))
            }
        }
        compose.runOnIdle {input.requestInputMode(InputMode.Keyboard);focus.requestFocus()}
        compose.mainClock.advanceTimeBy(600)
        val image=bitmap("focused-artwork-only")
        val points=(0 until image.height).flatMap {y->(0 until image.width).filter{x->image.getPixel(x,y)==0xFFC7C7CC.toInt()}.map{x->x to y}}
        // Compare rendered pixels: semantics layout bounds omit the TV card's graphics-layer scale.
        val titleRows=(0 until image.height).filter {y->(0 until image.width).any{x->image.getPixel(x,y)==0xFF1D1D1F.toInt()}}
        assertTrue("Artwork outline appears",points.size>100)
        assertTrue("Title is rendered",titleRows.isNotEmpty())
        assertTrue("Outline ends before the title",points.maxOf {it.second}<titleRows.min())
    }

    @Test fun loginLinkIsTouchOnlyAndUsesBrowserIntentWhileButtonKeepsOriginalFill() {
        val url="http://192.0.2.15:8080/"
        compose.setContent {
            val mode = LocalInputModeManager.current
            SideEffect { mode.requestInputMode(InputMode.Keyboard) }
            OnboardingContent(OnboardingState(phoneUrl=url),onStart={})
        }
        compose.runOnIdle { compose.activity.window.decorView.dispatchWindowFocusChanged(true) }
        compose.mainClock.advanceTimeBy(600)
        val image=bitmap("login-focused-button-and-link")
        assertTrue(count(image,0xFFFA233B.toInt())>1000)
        assertEquals(0,count(image,0xFFD91D34.toInt()))
        assertTrue(count(image,0xFFC7C7CC.toInt())>30)
        compose.onNodeWithText("跳过，仅试听").performKeyInput {pressKey(Key.DirectionUp)}
        compose.onNodeWithText(url).assert(isFocusable().not())
        compose.onNodeWithText("跳过，仅试听").assertIsFocused()
        compose.onNodeWithText(url).performTouchInput {click(center)}
        val intent=shadowOf(compose.activity).nextStartedActivity
        assertEquals(android.content.Intent.ACTION_VIEW,intent.action)
        assertEquals(url,intent.dataString)
    }

    @Test fun iconSelectionIsGrayAndRemoteFocusIsAccentWithoutChangingSelection() {
        lateinit var input:InputModeManager
        compose.setContent {input=LocalInputModeManager.current;Box(Modifier.fillMaxSize().background(Color.White)) {LauncherIconSettings()}}
        compose.mainClock.advanceTimeBy(300)
        val selected=bitmap("icon-selection-gray")
        assertTrue(count(selected,0xFFC7C7CC.toInt())>50)
        compose.runOnIdle {input.requestInputMode(InputMode.Keyboard)}
        compose.onAllNodes(isFocusable())[1].performKeyInput {pressKey(Key.DirectionRight)}
        compose.mainClock.advanceTimeBy(500)
        val focused=bitmap("icon-remote-focus-accent")
        assertTrue("Remote focus adds an accent outline",count(focused,0xFFFA233B.toInt())>count(selected,0xFFFA233B.toInt())+100)
        compose.onAllNodes(isSelected()).assertCountEquals(1)
    }
}
