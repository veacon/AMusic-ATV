package com.applemusicktv.ui

import android.app.Application
import com.applemusicktv.R
import android.graphics.Bitmap
import android.graphics.Canvas
import java.io.File
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.applemusicktv.i18n.AppLanguage
import com.applemusicktv.i18n.LanguageChoice
import com.applemusicktv.ui.screens.OnboardingContent
import com.applemusicktv.ui.viewmodel.OnboardingState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w960dp-h540dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneServerUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun save(name: String) = compose.runOnIdle {
        val view = compose.activity.window.decorView
        val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(image))
        val root = com.applemusicktv.testing.TestArtifacts.root
        val out = File(root, "artifacts/v3.0-v32-release/preview").apply { mkdirs() }
        File(out, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun portChangeUpdatesVisibleAddressAndStopRemovesIt() {
        AppLanguage.initialize(RuntimeEnvironment.getApplication())
        AppLanguage.select(LanguageChoice.Chinese)
        var state by mutableStateOf(OnboardingState(phoneUrl = "http://192.168.1.4:8080"))
        compose.setContent { OnboardingContent(state, {}) }
        compose.onNodeWithText("http://192.168.1.4:8080").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(phoneUrl = "http://192.168.1.4:8082") }
        compose.onNodeWithText("http://192.168.1.4:8080").assertDoesNotExist()
        compose.onNodeWithText("http://192.168.1.4:8082").assertIsDisplayed()
        save("login-fallback-port")
        compose.runOnIdle { state = state.copy(phoneUrl = "", phoneServerUnavailable = true) }
        compose.onNodeWithText("http://192.168.1.4:8082").assertDoesNotExist()
        compose.onAllNodesWithText("手机配置服务未启动，请检查设置或重启应用").assertCountEquals(2)
        compose.onNodeWithText("跳过，仅试听").assertIsDisplayed()
        compose.onNodeWithText("用手机扫码登录").assertDoesNotExist()
        save("login-unavailable-zh")
    }

    @Test fun wiredAddressWaitingAndDisconnectStayReadableWithoutChangingLoginActions() {
        AppLanguage.initialize(RuntimeEnvironment.getApplication())
        AppLanguage.select(LanguageChoice.Chinese)
        var state by mutableStateOf(OnboardingState(phoneSetupMessage = R.string.phone_network_waiting))
        compose.setContent { OnboardingContent(state, {}) }
        compose.onAllNodesWithText("正在获取局域网地址…").assertCountEquals(2)
        compose.onNodeWithText("用手机扫码登录").assertDoesNotExist()
        save("login-waiting-zh")
        compose.runOnIdle { state = state.copy(phoneUrl = "http://192.168.1.44:8082", phoneSetupMessage = null) }
        compose.onNodeWithText("http://192.168.1.44:8082").assertIsDisplayed()
        save("login-wired-zh")
        compose.runOnIdle { AppLanguage.select(LanguageChoice.English) }
        compose.onNodeWithText("http://192.168.1.44:8082").assertIsDisplayed()
        compose.onNodeWithText("Skip and Preview").assertIsDisplayed()
        save("login-wired-en")
        compose.runOnIdle { state = state.copy(phoneUrl = "", phoneSetupMessage = R.string.msg_172) }
        compose.onNodeWithText("http://192.168.1.44:8082").assertDoesNotExist()
        compose.onAllNodesWithText("Connect using Wi-Fi or Ethernet to see the sign-in address.").assertCountEquals(2)
        save("login-disconnected-en")
    }

    @Test fun unavailableMessageIsLocalizedAndExistingLoginStillWorks() {
        AppLanguage.initialize(RuntimeEnvironment.getApplication())
        AppLanguage.select(LanguageChoice.English)
        compose.setContent { OnboardingContent(OnboardingState(phoneServerUnavailable = true, hasMut = true), {}) }
        compose.onAllNodesWithText("Phone setup is unavailable. Check settings or restart the app.").assertCountEquals(2)
        compose.onNodeWithText("Start Listening").assertIsDisplayed()
        compose.onNodeWithText("Scan with your phone to sign in").assertDoesNotExist()
        save("login-unavailable-en")
    }
}
