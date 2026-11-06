package com.applemusicktv.ui.components

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
@Config(sdk=[28],application=Application::class,qualifiers="w1000dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LargeScreenGateTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun bothMinimumDimensionsAreInclusiveAndCannotBeInterchanged() {
        assertTrue(supportsAppWindow(540f,480f)); assertTrue(supportsAppWindow(540f,900f))
        assertFalse(supportsAppWindow(539.9f,900f)); assertFalse(supportsAppWindow(960f,479.9f))
        assertFalse(supportsAppWindow(480f,600f))
    }
    @Test fun blockedWindowNeverComposesMainAppAndResizingReevaluatesAccess() {
        var width by mutableStateOf(360); var height by mutableStateOf(846)
        var access = false; var mainEntries = 0
        compose.setContent {
            Box(Modifier.requiredSize(width.dp,height.dp)) {
                LargeScreenGate(onAccessChanged={access=it}) {
                    DisposableEffect(Unit) { mainEntries++; onDispose {} }
                    Text("Main app")
                }
            }
        }
        compose.onNodeWithText("请在大屏设备上使用").assertIsDisplayed()
        compose.onNodeWithText("Main app").assertDoesNotExist(); assertFalse(access); assertEquals(0,mainEntries)
        compose.runOnIdle {
            val v=compose.activity.window.decorView;val b=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(b))
            val root=com.applemusicktv.testing.TestArtifacts.root
            val out=File(root,"artifacts/v3.0-v16-release/preview").apply {mkdirs()}
            File(out,"unsupported-window.png").outputStream().use {b.compress(Bitmap.CompressFormat.PNG,100,it)}
        }
        compose.runOnIdle { width=540;height=480 }
        compose.onNodeWithText("Main app").assertIsDisplayed(); assertTrue(access); assertEquals(1,mainEntries)
        compose.runOnIdle {height=479}
        compose.onNodeWithText("Main app").assertDoesNotExist(); assertFalse(access)
    }
}
