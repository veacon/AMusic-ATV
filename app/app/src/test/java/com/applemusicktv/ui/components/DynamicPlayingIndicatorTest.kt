package com.applemusicktv.ui.components

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.applemusicktv.media.BeatAnalyzer
import com.applemusicktv.ui.navigation.TopNavTab
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,qualifiers="w960dp-h540dp-land-mdpi")
class DynamicPlayingIndicatorTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun browsingRestoresBeatSubscriptionAndPauseDisposesIt() {
        val beat=BeatAnalyzer();var playing by mutableStateOf(true)
        compose.setContent {TopNavBar(TopNavTab.ListenNow,{},isPlaying=playing,beatAnalyzer=beat)}
        compose.onNodeWithTag("dynamicPlayingIndicator",useUnmergedTree=true).assertExists()
        assertEquals(1,(beat.energy as MutableStateFlow<Float>).subscriptionCount.value)
        compose.runOnIdle {playing=false}
        compose.onNodeWithTag("dynamicPlayingIndicator",useUnmergedTree=true).assertDoesNotExist()
        assertEquals(0,(beat.energy as MutableStateFlow<Float>).subscriptionCount.value)
        compose.runOnIdle {playing=true}
        compose.onNodeWithTag("dynamicPlayingIndicator",useUnmergedTree=true).assertExists()
        assertEquals(1,(beat.energy as MutableStateFlow<Float>).subscriptionCount.value)
    }
}
