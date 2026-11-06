package com.applemusicktv.ui.components

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.applemusicktv.ui.navigation.TopNavTab
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,qualifiers="w960dp-h600dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class TabFocusBorderTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val tab=FocusRequester();private val content=FocusRequester()
    private lateinit var input:InputModeManager
    private var entry by mutableIntStateOf(0)
    private fun setup(reduceMotion:Boolean=false) {
        compose.setContent {
            input=LocalInputModeManager.current
            val manager=LocalFocusManager.current
            var request by remember { mutableIntStateOf(0) }
            var navFocused by remember { mutableStateOf(false) }
            // Same asynchronous clear -> frame -> focus handshake as AppShell.
            LaunchedEffect(request) {
                if(request>0) {manager.clearFocus(force=true);withFrameNanos {};tab.requestFocus()}
            }
            CompositionLocalProvider(LocalUiMotion provides UiMotion(enabled=!reduceMotion)) {
                Column(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF999999))) {
                    Box(Modifier.onFocusChanged {
                        if(it.hasFocus && !navFocused && input.inputMode==InputMode.Keyboard) entry++
                        navFocused=it.hasFocus
                    }.focusGroup()) {
                        TopNavBar(TopNavTab.ListenNow,{},tabFocusRequesters=mapOf(TopNavTab.ListenNow to tab),
                            contentFocusRequester=content,focusEntryId=entry,navigationHasFocus=navFocused)
                    }
                    Box(Modifier.returnToTabOnUp {request++}.size(150.dp).focusRequester(content)
                        .testTag("content").focusable())
                }
            }
        }
        compose.runOnIdle { input.requestInputMode(InputMode.Keyboard);content.requestFocus() }
        compose.waitForIdle();compose.mainClock.autoAdvance=false
    }
    private fun tick(ms:Long) {
        compose.runOnIdle {Snapshot.sendApplyNotifications()}
        compose.mainClock.advanceTimeBy(ms)
    }
    private fun returnUp() {
        compose.onNodeWithTag("content").performKeyInput {pressKey(Key.DirectionUp)}
        tick(48)
        compose.onNodeWithContentDescription(TopNavTab.ListenNow.label).assertIsFocused()
        tick(32)
    }
    private fun snapshot(name:String):Bitmap {
        lateinit var bitmap:Bitmap
        compose.runOnIdle {
            val view=compose.activity.window.decorView
            bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap))
            val root=com.applemusicktv.testing.TestArtifacts.root
            val out=File(root,"artifacts/v3.0-v16-release/preview").apply {mkdirs()}
            File(out,"$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        }
        return bitmap
    }
    private fun borderPixels(bitmap:Bitmap):Int {
        val bounds=compose.onNodeWithContentDescription(TopNavTab.ListenNow.label).fetchSemanticsNode().boundsInRoot
        var count=0
        for(y in bounds.top.toInt() until bounds.bottom.toInt()) for(x in bounds.left.toInt() until bounds.right.toInt()) {
            val pixel=bitmap.getPixel(x,y);val r=android.graphics.Color.red(pixel);val g=android.graphics.Color.green(pixel);val b=android.graphics.Color.blue(pixel)
            if(r in 190..220 && r==g && kotlin.math.abs(b-r)<=6) count++
        }
        return count
    }
    private fun borderDarkness(before:Bitmap, image:Bitmap):Long {
        val bounds=compose.onNodeWithContentDescription(TopNavTab.ListenNow.label).fetchSemanticsNode().boundsInRoot
        var total=0L
        for(y in bounds.top.toInt() until bounds.bottom.toInt()) for(x in bounds.left.toInt() until bounds.right.toInt())
            total+=(android.graphics.Color.red(before.getPixel(x,y))-android.graphics.Color.red(image.getPixel(x,y))).coerceAtLeast(0)
        return total
    }
    @Test fun actualFocusEntryFadesIn200Holds300AndFadesOut200WithoutMovingContent() {
        setup();val before=snapshot("border-before-focus")
        val bounds=compose.onNodeWithContentDescription(TopNavTab.ListenNow.label).fetchSemanticsNode().boundsInRoot
        returnUp();assertEquals(1,entry)
        val entering=snapshot("border-during-entry")
        tick(224)
        val active=snapshot("border-active")
        assertTrue(borderDarkness(before,entering)>0)
        assertTrue(borderDarkness(before,entering)<borderDarkness(before,active))
        assertTrue(borderPixels(active)>borderPixels(before)+150)
        assertEquals(bounds,compose.onNodeWithContentDescription(TopNavTab.ListenNow.label).fetchSemanticsNode().boundsInRoot)
        // All changed pixels must remain inside the existing pill; no scale or layout movement.
        for(y in 0 until before.height) for(x in 0 until before.width) {
            if(x<bounds.left.toInt()-1 || x>bounds.right.toInt()+1 || y<bounds.top.toInt()-1 || y>bounds.bottom.toInt()+1)
                assertEquals("Outside pill at $x,$y",before.getPixel(x,y),active.getPixel(x,y))
        }
        val full = borderDarkness(before, active)
        tick(180)
        assertEquals(full,borderDarkness(before,snapshot("border-at-hold")))
        tick(160)
        val halfway = borderDarkness(before,snapshot("border-during-fade"))
        assertTrue("Border must fade continuously: full=$full, intermediate=$halfway", halfway > full * 0.1 && halfway < full * 0.9)
        tick(220);val settled=snapshot("border-expired")
        assertEquals(borderPixels(before),borderPixels(settled))
    }
    @Test fun leavingDismissesBorderAndReentryRestartsHoldWithoutHeldUpRestartingIt() {
        setup();returnUp();assertEquals(1,entry)
        compose.onNodeWithContentDescription(TopNavTab.ListenNow.label).performKeyInput {pressKey(Key.DirectionDown)}
        tick(32);compose.onNodeWithTag("content").assertIsFocused()
        assertEquals(0,borderPixels(snapshot("border-dismissed-on-down")))
        returnUp();assertEquals(2,entry)
        compose.onNodeWithContentDescription(TopNavTab.ListenNow.label).performKeyInput {pressKey(Key.DirectionUp)}
        tick(32);assertEquals(2,entry)
        tick(800);assertEquals(0,borderPixels(snapshot("border-not-restarted-by-up")))
    }
    @Test fun reduceMotionStillShowsTheFocusHint() {
        setup(reduceMotion=true);returnUp()
        tick(160)
        assertTrue(borderPixels(snapshot("border-with-reduce-motion"))>150)
        tick(800);assertEquals(0,borderPixels(snapshot("border-reduce-motion-expired")))
    }
}
