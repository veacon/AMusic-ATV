package com.applemusicktv.ui.screens

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
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
@OptIn(ExperimentalTestApi::class)
class CacheSettingsUiTest {
    @org.junit.Before fun selectChineseForFixtures() { com.applemusicktv.i18n.AppLanguage.select(com.applemusicktv.i18n.LanguageChoice.Chinese) }

    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private fun save(name:String, dialog:Boolean=false)=compose.runOnIdle {
        val view=if(dialog) org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView else compose.activity.window.decorView
        val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val output=File(com.applemusicktv.testing.TestArtifacts.root,"artifacts/v3.0-v24-release/preview")
        output.mkdirs()
        File(output,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
    }
    @Test fun wholeGigabyteButtonsClampAndProgressUpdatesOnlyAfterConfirmedClear() {
        var gb by mutableStateOf(1);var used by mutableStateOf(750_000_000L);var message by mutableStateOf("")
        compose.setContent {
            val mode = androidx.compose.ui.platform.LocalInputModeManager.current
            SideEffect { mode.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard) }
            Box(Modifier.fillMaxSize().background(Color.White).padding(40.dp)) {
                Column(Modifier.width(800.dp)) {
                    CacheSettingsSection(gb,used,false,message,{gb=it},{used=0;message="清理完成"})
                }
            }
        }
        compose.onNodeWithText("−").assertIsNotEnabled()
        compose.onNodeWithText("已使用 750M / 1G").assertIsDisplayed()
        compose.onNodeWithContentDescription("缓存实际占用").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,ProgressBarRangeInfo(.75f,0f..1f)))
        save("cache-default-1g")
        repeat(9) { compose.onNodeWithText("+").performClick() }
        assertEquals(10,gb);compose.onNodeWithText("+").assertIsNotEnabled()
        compose.onNodeWithText("已使用 750M / 10G").assertIsDisplayed()
        save("cache-capacity-10g")
        repeat(9) { compose.onNodeWithText("−").performClick() }
        assertEquals(1,gb)
        compose.onNodeWithText("一键清理").performClick()
        compose.runOnIdle { org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView.dispatchWindowFocusChanged(true) }
        compose.waitForIdle()
        assertEquals(750_000_000L,used)
        compose.onNodeWithText("清理缓存？").assertIsDisplayed()
        compose.onNodeWithText("取消").assertIsFocused()
        save("cache-confirm-dialog", dialog=true)
        compose.onNodeWithText("清理").performClick()
        compose.onNodeWithText("清理缓存？").assertDoesNotExist()
        assertEquals(0,used)
        compose.onNodeWithText("已使用 0M / 1G").assertIsDisplayed()
        compose.onNodeWithContentDescription("缓存实际占用").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,ProgressBarRangeInfo(0f,0f..1f)))
        save("cache-after-clear")
    }
    @Test fun busyStateBlocksDuplicateActionsAndDpadActivatesFocusedPlus() {
        var busy by mutableStateOf(false);var gb by mutableStateOf(1);var clears=0
        compose.setContent {
            Column(Modifier.width(800.dp)) {
                CacheSettingsSection(gb,0,busy,"",{gb=it},{clears++})
            }
        }
        // The first enabled focusable element is + at the 1G minimum.
        compose.onNodeWithText("+").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(2,gb)
        compose.runOnIdle { busy=true }
        compose.onNodeWithText("+").assertIsNotEnabled()
        compose.onNodeWithText("−").assertIsNotEnabled()
        compose.onNodeWithText("正在处理缓存…").assertIsNotEnabled()
        assertEquals(0,clears)
    }

    @Test fun cancelAndBackDismissWithoutClearingWhileRemoteConfirmClearsOnce() {
        var clears = 0
        compose.setContent {
            val mode = androidx.compose.ui.platform.LocalInputModeManager.current
            SideEffect { mode.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard) }
            Column(Modifier.width(800.dp)) {
                CacheSettingsSection(1,750_000_000L,false,"",{},{clears++})
            }
        }
        compose.onNodeWithText("一键清理").performClick()
        compose.runOnIdle { org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView.dispatchWindowFocusChanged(true) }
        compose.waitForIdle()
        compose.onNodeWithText("取消").assertIsFocused()
        compose.onNodeWithText("取消").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithText("清理缓存？").assertDoesNotExist()
        assertEquals(0, clears)
        compose.onNodeWithText("一键清理").performClick()
        compose.runOnIdle { org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView.dispatchWindowFocusChanged(true) }
        compose.waitForIdle()
        compose.runOnIdle { org.robolectric.shadows.ShadowDialog.getLatestDialog().onBackPressed() }
        compose.onNodeWithText("清理缓存？").assertDoesNotExist()
        assertEquals(0, clears)
        compose.onNodeWithText("一键清理").performClick()
        compose.runOnIdle { org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView.dispatchWindowFocusChanged(true) }
        compose.waitForIdle()
        compose.onNodeWithText("取消").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithText("清理").assertIsFocused()
        compose.onNodeWithText("清理").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithText("清理缓存？").assertDoesNotExist()
        assertEquals(1, clears)
    }
}
