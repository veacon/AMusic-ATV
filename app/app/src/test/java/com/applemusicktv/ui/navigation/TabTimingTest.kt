package com.applemusicktv.ui.navigation

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.compose.*
import com.applemusicktv.ui.components.TopNavBar
import com.applemusicktv.ui.components.UiMotion
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
class TabTimingTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private fun image():Bitmap {
        lateinit var bitmap:Bitmap
        compose.runOnIdle {
            val view=compose.activity.findViewById<android.view.View>(android.R.id.content)
            bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap))
        }
        return bitmap
    }
    private fun pixels(bitmap:Bitmap,rect:Rect,color:Int):Int {
        var count=0
        for(y in rect.top.toInt().coerceAtLeast(0) until rect.bottom.toInt().coerceAtMost(bitmap.height))
            for(x in rect.left.toInt().coerceAtLeast(0) until rect.right.toInt().coerceAtMost(bitmap.width))
                if(bitmap.getPixel(x,y)==color) count++
        return count
    }
    @Test fun capsuleAndLabelColorsFollowTheStationaryContentFadeAndFinishAt200ms() {
        var selected by mutableStateOf(TopNavTab.ListenNow)
        lateinit var nav:androidx.navigation.NavHostController
        var incomingX=0f
        compose.setContent {
            nav=rememberNavController()
            Column(Modifier.fillMaxSize().background(Color.White)) {
                TopNavBar(selected,{selected=it})
                NavHost(nav,startDestination="home",modifier=Modifier.weight(1f),
                    enterTransition={BrowsingPageMotion.enter(initialState.destination.route,targetState.destination.route,UiMotion())},
                    exitTransition={BrowsingPageMotion.exit(initialState.destination.route,targetState.destination.route,UiMotion())}) {
                    composable("home") {Box(Modifier.fillMaxSize().background(Color.Red))}
                    composable("library") {Box(Modifier.fillMaxSize().background(Color.Blue).onGloballyPositioned {incomingX=it.positionInRoot().x})}
                }
            }
        }
        compose.waitForIdle()
        val home=compose.onNodeWithContentDescription("立即聆听").fetchSemanticsNode().boundsInRoot
        val library=compose.onNodeWithContentDescription("资料库").fetchSemanticsNode().boundsInRoot
        val capsule=compose.onNodeWithTag("topNavCapsule").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance=false
        compose.runOnIdle {selected=TopNavTab.Library;nav.navigate("library")}
        compose.mainClock.advanceTimeBy(32);compose.mainClock.advanceTimeBy(80);compose.waitForIdle()
        val mid=image()
        val y=(home.center.y+14).toInt()
        val white=(capsule.left.toInt()+4 until capsule.right.toInt()-4).filter {mid.getPixel(it,y)==android.graphics.Color.WHITE}
        assertTrue(white.isNotEmpty())
        val center=(white.first()+white.last())/2f
        val pillProgress=(center-home.center.x)/(library.center.x-home.center.x)
        val contentColor=mid.getPixel(mid.width/2,mid.height-30)
        assertEquals("Content stays at its layout position",0f,incomingX,1f)
        assertTrue("Old page remains visible mid-fade",android.graphics.Color.red(contentColor)>0)
        assertTrue("Incoming page fades in",android.graphics.Color.blue(contentColor)>0)
        assertTrue("White pill still moving mid-fade",pillProgress in .1f.. .9f)
        assertEquals("Target label has not finished early",0,pixels(mid,library,android.graphics.Color.rgb(250,35,59)))
        compose.mainClock.advanceTimeBy(160);compose.waitForIdle()
        val end=image()
        assertEquals(android.graphics.Color.BLUE,end.getPixel(end.width/2,end.height-30))
        assertEquals(0f,incomingX,1f)
        assertTrue("Target label finishes with content",pixels(end,library,android.graphics.Color.rgb(250,35,59))>0)
        val whites=(capsule.left.toInt()+4 until capsule.right.toInt()-4).filter {end.getPixel(it,y)==android.graphics.Color.WHITE}
        assertEquals("White pill finishes at selected Tab",library.center.x,(whites.first()+whites.last())/2f,3f)

    }
}
