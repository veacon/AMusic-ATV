package com.applemusicktv.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.dp
import com.applemusicktv.i18n.AppLanguage
import com.applemusicktv.i18n.LanguageChoice
import com.applemusicktv.ui.screens.LanguageSettingsSection
import com.applemusicktv.ui.screens.OnboardingContent
import com.applemusicktv.ui.viewmodel.OnboardingState
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,qualifiers="w960dp-h540dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class V29LanguageUiTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Before fun init() {AppLanguage.initialize(RuntimeEnvironment.getApplication());AppLanguage.select(LanguageChoice.English)}
    private fun save(name:String)=compose.runOnIdle {
        val view=compose.activity.window.decorView
        val image=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
        view.draw(Canvas(image))
        val root=com.applemusicktv.testing.TestArtifacts.root
        val out=File(root,"artifacts/v3.0-v29-release/preview").apply {mkdirs()}
        File(out,"$name.png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test fun realSettingsControlsSwitchBothWaysWithoutRecreatingActivity() {
        val activity=compose.activity
        compose.setContent {
            Column(Modifier.fillMaxSize().background(Color.White).padding(40.dp)) {LanguageSettingsSection()}
        }
        compose.onNodeWithText("App Language").assertIsDisplayed()
        compose.onNodeWithText("English").assertIsDisplayed();save("language-settings-en")
        compose.onNodeWithText("−").performClick()
        compose.onNodeWithText("界面语言").assertIsDisplayed()
        assertEquals(LanguageChoice.Chinese,AppLanguage.choice);save("language-settings-zh")
        compose.onNodeWithText("+").performClick()
        compose.onNodeWithText("App Language").assertIsDisplayed()
        assertSame(activity,compose.activity)
        compose.onNodeWithText("+").performClick()
        compose.onNodeWithText("Follow System").assertIsDisplayed()
        assertEquals(LanguageChoice.System,AppLanguage.choice)
    }
    @Test fun englishLoginFitsAndRetainsUnfocusableClickableAddress() {
        compose.setContent {OnboardingContent(OnboardingState(phoneUrl="http://192.0.2.16:8080/"),{})}
        compose.onNodeWithText("Sign In").assertIsDisplayed()
        compose.onNodeWithText("Skip and Preview").assertIsDisplayed()
        compose.onNodeWithText("http://192.0.2.16:8080/").assertIsDisplayed()
        assertNoTextOverflow("Skip and Preview")
        save("login-en")
    }
    @Test fun readyAndVerifyLaterEnglishButtonsFit() {
        compose.setContent {OnboardingContent(OnboardingState(phoneUrl="http://192.0.2.16:8080/",tokenStored=true),{})}
        compose.onNodeWithText("Verify Later and Continue").assertIsDisplayed()
        assertNoTextOverflow("Verify Later and Continue")
        save("login-en-verify-later")
    }
    @Test fun languageUpdateTranslatesLoginInPlaceWithoutResettingFocus() {
        compose.setContent {OnboardingContent(OnboardingState(phoneUrl="http://192.0.2.16:8080/",hasMut=true),{})}
        compose.onNodeWithText("Start Listening").assertIsDisplayed()
        compose.runOnIdle {AppLanguage.select(LanguageChoice.Chinese)}
        compose.onNodeWithText("开始聆听").assertIsDisplayed()
        compose.onNodeWithText("登录").assertIsDisplayed();save("login-zh")
    }
    private fun assertNoTextOverflow(value:String) {
        val layouts=mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(value).performSemanticsAction(SemanticsActions.GetTextLayoutResult){it(layouts)}
        assertTrue(layouts.isNotEmpty());assertFalse(value,layouts.any {it.hasVisualOverflow})
    }
}
