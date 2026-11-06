package com.applemusicktv.ui.components

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.applemusicktv.ui.screens.ClearCacheDialog
import com.applemusicktv.ui.screens.ResetAppDialog
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,qualifiers="w960dp-h540dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class ConfirmationDialogTest {
    @Before fun selectChineseForLegacyLabels() { com.applemusicktv.i18n.AppLanguage.select(com.applemusicktv.i18n.LanguageChoice.Chinese) }
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private var kind by mutableIntStateOf(0)
    private var shown by mutableStateOf(true)
    private var confirms=0
    private var cancels=0
    private fun mount() {
        compose.setContent {
            val mode = androidx.compose.ui.platform.LocalInputModeManager.current
            SideEffect { mode.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard) }
            if(shown) key(kind) {
                val dismiss={cancels++;shown=false}
                val confirm={confirms++;shown=false}
                when(kind) {
                    0->ClearCacheDialog(dismiss,confirm)
                    1->ResetAppDialog(dismiss,confirm)
                    else->ExitAppDialog(dismiss,confirm)
                }
            }
        }
    }
    private fun show(which:Int) {
        compose.runOnIdle {kind=which;shown=true};compose.waitForIdle()
        compose.runOnIdle { ShadowDialog.getLatestDialog().window!!.decorView.dispatchWindowFocusChanged(true) }
        compose.waitForIdle()
    }
    @Test fun allConfirmationDialogsMatchCacheGeometryAndRemoteFocus() {
        mount()
        var expectedHeight=0f
        listOf("清理","重置","退出").forEachIndexed {index,label->
            show(index)
            compose.onNodeWithTag("confirmationDialog").assertWidthIsEqualTo(400.dp)
            compose.onNodeWithText(if (index == 2) label else "取消").assertIsFocused()
            compose.onNodeWithText(label).assertHeightIsEqualTo(52.dp).assertWidthIsEqualTo(166.dp)
            val height=compose.onNodeWithTag("confirmationDialog").fetchSemanticsNode().size.height.toFloat()
            if(index==0) expectedHeight=height else assertEquals(expectedHeight,height,0f)
            compose.runOnIdle {
                val view=ShadowDialog.getLatestDialog().window!!.decorView
                val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap))
                val root=com.applemusicktv.testing.TestArtifacts.root
                val out=File(root,"artifacts/v3.0-v45-release/preview").apply {mkdirs()}
                File(out,"confirmation-$index.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            }
            if (index != 2) compose.onNodeWithText("取消").performKeyInput {pressKey(Key.DirectionRight)}
            compose.onNodeWithText(label).assertIsFocused()
            compose.onNodeWithText(label).performKeyInput {pressKey(Key.DirectionCenter)}
            assertEquals(index+1,confirms)
            assertEquals(0,cancels)
        }
    }
    @Test fun touchAndBackCancelWithoutExecutingAnyOperation() {
        mount()
        repeat(3) {index->
            show(index);compose.onNodeWithText("取消").performTouchInput {click(center)}
            assertEquals(index*2+1,cancels);assertEquals(0,confirms)
            show(index);compose.runOnIdle {ShadowDialog.getLatestDialog().onBackPressed()};compose.waitForIdle()
            assertEquals(index*2+2,cancels);assertEquals(0,confirms)
        }
    }
    @Test fun touchConfirmRunsEachOperationExactlyOnce() {
        mount()
        listOf("清理","重置","退出").forEachIndexed {index,label->
            show(index);compose.onNodeWithText(label).performTouchInput {click(center)}
            assertEquals(index+1,confirms);assertEquals(0,cancels)
        }
    }
}
