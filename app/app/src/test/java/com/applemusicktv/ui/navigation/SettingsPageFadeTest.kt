package com.applemusicktv.ui.navigation

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
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
class SettingsPageFadeTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private lateinit var nav:NavHostController
    private fun mount(motion:UiMotion=UiMotion()) {
        compose.mainClock.autoAdvance=false
        compose.setContent {
            nav=rememberNavController()
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                NavHost(nav,startDestination="library",
                    enterTransition={ BrowsingPageMotion.enter(initialState.destination.route,targetState.destination.route,motion) },
                    exitTransition={ BrowsingPageMotion.exit(initialState.destination.route,targetState.destination.route,motion) },
                    popEnterTransition={ BrowsingPageMotion.enter(initialState.destination.route,targetState.destination.route,motion) },
                    popExitTransition={ BrowsingPageMotion.exit(initialState.destination.route,targetState.destination.route,motion) }) {
                    composable("library") { Box(Modifier.fillMaxSize().background(Color.Red)) }
                    composable("dev_menu") { Box(Modifier.fillMaxSize().background(Color.Blue)) }
                }
            }
        }
        tick(500)
    }
    private fun tick(ms:Long) {
        compose.runOnIdle { androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeBy(ms);compose.waitForIdle()
    }
    private fun color():Int {
        var color=0
        compose.runOnIdle {
            val view=compose.activity.findViewById<android.view.View>(android.R.id.content)
            val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap));color=bitmap.getPixel(view.width/2,view.height/2)
        }
        return color
    }
    @Test fun settingsAndLibraryCrossfadeInPlaceAndCompleteTogetherAt200ms() {
        mount()
        compose.runOnIdle {nav.navigate("dev_menu")};tick(32);tick(80)
        val forward=color()
        assertTrue(android.graphics.Color.red(forward)>0)
        assertTrue(android.graphics.Color.blue(forward)>0)
        tick(128);assertEquals(android.graphics.Color.BLUE,color())
        compose.runOnIdle {nav.popBackStack()};tick(32);tick(80)
        val backward=color()
        assertTrue(android.graphics.Color.red(backward)>0)
        assertTrue(android.graphics.Color.blue(backward)>0)
        tick(128);assertEquals(android.graphics.Color.RED,color())
    }
    @Test fun disabledMotionStillSwitchesWithoutDelay() {
        mount(UiMotion(enabled=false))
        compose.runOnIdle { nav.navigate("dev_menu") };tick(96)
        assertEquals(android.graphics.Color.BLUE,color())
        compose.runOnIdle { nav.popBackStack() };tick(96)
        assertEquals(android.graphics.Color.RED,color())
    }
}
