package com.applemusicktv.ui.screens

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import android.graphics.Canvas
import org.robolectric.shadows.ShadowDialog
import com.applemusicktv.ui.viewmodel.OnboardingState
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w960dp-h540dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LoginLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun save(name: String) {
        val root = com.applemusicktv.testing.TestArtifacts.root
        val file = File(root, "artifacts/v3.0-v16-release/preview/$name.png")
        file.parentFile!!.mkdirs()
        compose.runOnIdle {
            val view = if (name == "reset-dialog") ShadowDialog.getLatestDialog().window!!.decorView
                else compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun tokenArrivalStaysOnLoginUntilExplicitStart() {
        var state by mutableStateOf(OnboardingState("http://192.0.2.16:8080", false))
        var finished = 0
        compose.setContent { OnboardingContent(state, { finished++ }) }
        compose.onNodeWithText("跳过，仅试听").assertIsDisplayed()
        save("login-waiting")
        compose.runOnIdle { state = state.copy(hasMut = true) }
        compose.onNodeWithText("开始聆听").assertIsDisplayed()
        listOf("你的遥控器", "交叉渐变", "使用方法", "继续").forEach { compose.onNodeWithText(it).assertDoesNotExist() }
        assertEquals(0, finished)
        save("login-ready")
        compose.onNodeWithText("开始聆听").performClick()
        assertEquals(1, finished)
    }

    @Test fun previewStillFinishesInOneAction() {
        var finished = false
        compose.setContent { OnboardingContent(OnboardingState(), { finished = true }) }
        compose.onNodeWithText("跳过，仅试听").performClick()
        assertTrue(finished)
    }

    @Test fun resetButtonsAreEqualWideCenteredAndCancelIsSafe() {
        var canceled = false
        var reset = false
        compose.setContent { ResetAppDialog({ canceled = true }, { reset = true }) }
        compose.waitForIdle()
        // Unmerged text bounds versus merged clickable button bounds catch the old top-aligned labels.
        val cancel = compose.onNodeWithText("取消").fetchSemanticsNode().boundsInRoot
        val confirm = compose.onNodeWithText("重置").fetchSemanticsNode().boundsInRoot
        assertTrue(cancel.width >= 140f)
        assertEquals(cancel.width, confirm.width, 1f)
        for (label in listOf("取消", "重置")) {
            val button = compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
            val text = compose.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals(button.center.x, text.center.x, 1f)
            assertEquals(button.center.y, text.center.y, 1f)
        }
        save("reset-dialog")
        compose.onNodeWithText("取消").performClick()
        assertTrue(canceled)
        assertFalse(reset)
    }
}
