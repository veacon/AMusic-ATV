package com.applemusicktv.ui.screens

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.applemusicktv.data.model.Song
import com.applemusicktv.data.network.AlbumDto
import com.applemusicktv.data.network.HomeSection
import com.applemusicktv.ui.components.*
import com.applemusicktv.ui.viewmodel.CategoryUiState
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,qualifiers="w960dp-h540dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class NavigationPolishUiTest {
    @Before fun selectChineseForLegacyLabels() { com.applemusicktv.i18n.AppLanguage.select(com.applemusicktv.i18n.LanguageChoice.Chinese) }
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val output: File get() = File(com.applemusicktv.testing.TestArtifacts.root,"artifacts/v3.0-v45-release/preview").apply { mkdirs() }
    private fun save(name:String,dialog:Boolean=false)=compose.runOnIdle {
        val view=if(dialog) ShadowDialog.getLatestDialog().window!!.decorView else compose.activity.window.decorView
        val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(output,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
    }
    private fun art():String {
        val bitmap=Bitmap.createBitmap(1200,800,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);canvas.drawColor(android.graphics.Color.rgb(75,115,80))
        val paint=android.graphics.Paint().apply { color=android.graphics.Color.rgb(160,185,75) }
        canvas.drawCircle(800f,200f,330f,paint)
        val file=File(output,"editorial-fixture.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        return file.toURI().toString()
    }
    private fun album(id:String,title:String)=AlbumDto(id=id,title=title,artistName="测试艺人",artworkUrl=null,artworkBgColor=null,releaseDate=null)
    @Test fun editorialHasOneTitleAndFirstShelfReturnsToOriginTabAfterDownUp() {
        val tab=FocusRequester();var tabFocused by mutableStateOf(false);var returns=0
        val state=CategoryUiState(isLoading=false,title="Apple Music 非洲音乐",artworkUrl=art(),sections=listOf(
            HomeSection(title="空内容",albums=emptyList()),
            HomeSection(title="apple\u00a0music\u200b非洲音乐",albums=listOf(album("a","首排专辑"))),
            HomeSection(title="后续内容",albums=listOf(album("b","下排专辑")))))
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Color.White)) {
                CategoryContent(state,tabBarFocused=tabFocused,onReturnToTab={returns++;tab.requestFocus();tabFocused=true})
                TouchSurface({},Modifier.align(Alignment.TopCenter).focusRequester(tab).testTag("search-tab")) { Text("搜索") }
            }
        }
        compose.waitUntil(5_000) {
            var decoded = false
            compose.runOnIdle {
                val view = compose.activity.window.decorView
                val image = Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
                view.draw(Canvas(image))
                val pixel = image.getPixel(10,100)
                decoded = android.graphics.Color.green(pixel) > android.graphics.Color.red(pixel) + 10
            }
            decoded
        }
        compose.onAllNodesWithText("Apple Music 非洲音乐").assertCountEquals(1)
        compose.onNodeWithText("apple\u00a0music\u200b非洲音乐").assertDoesNotExist()
        save("editorial-immersive-header")
        compose.onNodeWithText("首排专辑").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithText("首排专辑").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("下排专辑").assertIsFocused()
        assertEquals(0,returns)
        compose.onNodeWithText("下排专辑").performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithText("首排专辑").assertIsFocused()
        compose.onNodeWithText("首排专辑").performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithTag("search-tab").assertIsFocused();assertEquals(1,returns)
        compose.waitForIdle();save("editorial-return-search-tab")
    }
    @Test fun variableDetailShelvesOnlyFirstInteractiveItemReturnsUp() {
        val tab=FocusRequester();val first=FocusRequester();var returns=0
        compose.setContent {
            Column {
                TouchSurface({},Modifier.focusRequester(tab).testTag("tab")) { Text("浏览") }
                LazyColumn {
                    item { Text("无焦点的题图和简介") }
                    val boundary=FirstTabReturnItem { returns++;tab.requestFocus() }
                    tabReturnItem(boundary.claim()) { TouchSurface({},Modifier.focusRequester(first).testTag("first")) { Text("首个可用内容") } }
                    tabReturnItem(boundary.claim()) { TouchSurface({},Modifier.testTag("later")) { Text("后续内容") } }
                }
            }
        }
        compose.runOnIdle { first.requestFocus() }
        compose.onNodeWithTag("first").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithTag("later").assertIsFocused()
        compose.onNodeWithTag("later").performKeyInput { pressKey(Key.DirectionUp) }
        assertEquals(0,returns)
        compose.onNodeWithTag("first").performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithTag("tab").assertIsFocused();assertEquals(1,returns)
    }
    @Test fun libraryDialogIgnoresInheritedHeldKeyReleaseButAcceptsNextFreshClick() {
        var open by mutableStateOf(true);var actions=0
        val song=Song("s","长按歌曲","艺人","专辑",60_000,null,null,null)
        compose.setContent {
            val mode = androidx.compose.ui.platform.LocalInputModeManager.current
            SideEffect { mode.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard) }
            if(open) SongContextMenu(song,{open=false},{actions++;open=false},{actions++;open=false})
        }
        compose.runOnIdle { ShadowDialog.getLatestDialog().window!!.decorView.dispatchWindowFocusChanged(true) }
        compose.waitForIdle()
        compose.onNodeWithText("下一首播放").assertIsFocused()
        compose.runOnIdle {
            val dialog=ShadowDialog.getLatestDialog()
            dialog.dispatchKeyEvent(KeyEvent(0,1000,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_CENTER,5))
            dialog.dispatchKeyEvent(KeyEvent(0,1500,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_CENTER,0))
        }
        compose.onNodeWithText("下一首播放").assertIsDisplayed();assertEquals(0,actions);assertTrue(open)
        save("library-menu-after-release",true)
        compose.onNodeWithText("下一首播放").performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(1,actions);assertFalse(open)
    }
}
