package com.applemusicktv.ui.components

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.applemusicktv.ui.screens.PillButton
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
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalTestApi::class)
class ContentActionFocusTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private fun bitmap(name:String):Bitmap {
        lateinit var bmp:Bitmap
        compose.runOnIdle {
            val view=compose.activity.window.decorView
            bmp=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bmp))
            val root=com.applemusicktv.testing.TestArtifacts.root
            val out=File(root,"artifacts/v3.0-v16-release/preview").apply {mkdirs()}
            File(out,"$name.png").outputStream().use {bmp.compress(Bitmap.CompressFormat.PNG,100,it)}
        }
        return bmp
    }
    private fun count(b:Bitmap,c:Int):Int=(0 until b.height).sumOf {y->(0 until b.width).count {x->b.getPixel(x,y)==c}}
    @Test fun playKeepsRedFillAndGainsOuterGrayRingAndScaleWithoutActivation() {
        val play=FocusRequester();val other=FocusRequester();var clicks=0
        compose.setContent {
            Row(Modifier.fillMaxSize().background(Color.White).padding(60.dp),horizontalArrangement=Arrangement.spacedBy(32.dp)) {
                PillButton(Glyph.PLAY,"播放",Color(0xFFFA233B),Modifier.focusRequester(play)) {clicks++}
                PillButton(Glyph.SHUFFLE,"随机播放",Color(0xFFE8E8ED),Modifier.focusRequester(other)) {}
            }
        }
        compose.runOnIdle {other.requestFocus()};compose.mainClock.advanceTimeBy(600)
        val unfocused=bitmap("actions-shuffle-focus")
        compose.runOnIdle {play.requestFocus()};compose.mainClock.advanceTimeBy(600)
        val b=bitmap("actions-play-focus")
        assertTrue("Rendered red fill grows on focus",count(b,0xFFFA233B.toInt())>count(unfocused,0xFFFA233B.toInt())*1.04f)
        assertTrue("Original red fill remains",count(b,0xFFFA233B.toInt())>1000)
        assertEquals("Old brighter focused fill is gone",0,count(b,0xFFFF3B54.toInt()))
        assertTrue("Light gray border is visible",count(b,0xFFC7C7CC.toInt())>30)
        assertEquals(0,clicks)
        compose.onNodeWithText("播放").performKeyInput {pressKey(Key.DirectionCenter)}
        assertEquals(1,clicks)
    }
    @Test fun circularActionKeepsGrayFillAndRemainsTouchClickable() {
        val sort=FocusRequester();var clicks=0
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Color.White).padding(60.dp)) {
                TouchSurface({clicks++},Modifier.size(44.dp).focusRequester(sort).testTag("sort"),
                    shape=ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                    colors=contentActionColors(Color(0xFFE8E8ED)),
                    border=contentActionBorder(RoundedCornerShape(50)),
                    scale=ClickableSurfaceDefaults.scale(focusedScale=1.08f)) {Text("⇅")}
            }
        }
        compose.runOnIdle {sort.requestFocus()};compose.mainClock.advanceTimeBy(600)
        val b=bitmap("actions-sort-focus")
        assertTrue(count(b,0xFFE8E8ED.toInt())>300)
        assertTrue(count(b,0xFFC7C7CC.toInt())>30)
        compose.onNodeWithTag("sort").performTouchInput {click(center)}
        assertEquals(1,clicks)
    }
}
