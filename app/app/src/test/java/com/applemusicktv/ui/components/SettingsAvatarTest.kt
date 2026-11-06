package com.applemusicktv.ui.components

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.applemusicktv.ui.navigation.TopNavTab
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
class SettingsAvatarTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun selectedSettingsAvatarMatchesActualRemoteFocusAfterFocusMovesToContent() {
        val avatar=FocusRequester();val content=FocusRequester()
        var selected by mutableStateOf(TopNavTab.Dev)
        compose.setContent {
            Column {
                TopNavBar(selected,{selected=it},tabFocusRequesters=mapOf(TopNavTab.Dev to avatar))
                Box(Modifier.weight(1f).fillMaxWidth().focusRequester(content).focusable())
            }
        }
        compose.runOnIdle {avatar.requestFocus()};compose.waitForIdle()
        val rect=compose.onNodeWithContentDescription("个人头像，设置").fetchSemanticsNode().boundsInRoot
        fun crop():Bitmap {
            lateinit var result:Bitmap
            compose.runOnIdle {
                val view=compose.activity.findViewById<android.view.View>(android.R.id.content)
                val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap))
                result=Bitmap.createBitmap(bitmap,(rect.center.x-32).toInt(),(rect.center.y-32).toInt(),64,64)
            }
            return result
        }
        val focused=crop()
        compose.runOnIdle {content.requestFocus()};compose.waitForIdle()
        compose.onNodeWithContentDescription("个人头像，设置").assertIsNotFocused()
        assertTrue("Settings selection must retain the same scale and border",focused.sameAs(crop()))
        compose.runOnIdle {selected=TopNavTab.Library};compose.waitForIdle()
        assertFalse("Leaving settings must remove the selected appearance",focused.sameAs(crop()))
    }
}
