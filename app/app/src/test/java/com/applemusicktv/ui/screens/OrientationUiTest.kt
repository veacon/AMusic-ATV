package com.applemusicktv.ui.screens

import android.app.Application
import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.ExoPlayer
import com.applemusicktv.MainActivity
import com.applemusicktv.data.fonts.FontDownloadPhase
import com.applemusicktv.data.fonts.FontDownloadState
import com.applemusicktv.data.model.Song
import com.applemusicktv.data.network.LyricLine
import com.applemusicktv.ui.background.FluidQuality
import com.applemusicktv.ui.components.LocalUiMotion
import com.applemusicktv.ui.components.UiMotion
import com.applemusicktv.ui.theme.LocalAppFontState
import com.applemusicktv.ui.viewmodel.*
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,qualifiers="w960dp-h960dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OrientationUiTest {
    @org.junit.Before fun selectChineseForFixtures() { com.applemusicktv.i18n.AppLanguage.select(com.applemusicktv.i18n.LanguageChoice.Chinese) }

    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val ready=FontDownloadState(1,1,FontDownloadPhase.Ready)
    private fun song()=Song("orientation-fixture","测试歌曲标题","测试艺人","专辑",180000,null,null,null)
    private val playerCalls=mutableListOf<String>()
    private fun vm():Pair<PlayerViewModel,MutableStateFlow<PlayerState>> {
        val c=Class.forName("sun.misc.Unsafe");val u=c.getDeclaredField("theUnsafe").apply {isAccessible=true}.get(null)
        val vm=c.getMethod("allocateInstance",Class::class.java).invoke(u,PlayerViewModel::class.java) as PlayerViewModel
        fun field(name:String,value:Any) {PlayerViewModel::class.java.getDeclaredField(name).apply {isAccessible=true}.set(vm,value)}
        val song=song()
        val state=MutableStateFlow(PlayerState(currentSong=song,song=song,queue=listOf(song),queueIndex=0,
            progressMs=5000,isPlaying=false,isLoading=false,fluidQuality=FluidQuality.OFF,
            lyrics=listOf(LyricLine(0, 10000, "第一行歌词"),LyricLine(10000,20000,"第二行歌词")),lyricsScale=.85f))
        field("_state",state);field("state",state)
        field("artworkCentered", MutableStateFlow(false))
        field("albumWallPrefetch", kotlinx.coroutines.CompletableDeferred(emptyList<com.applemusicktv.ui.wall.CachedWallCard>()))
        val player=Proxy.newProxyInstance(ExoPlayer::class.java.classLoader,arrayOf(ExoPlayer::class.java)) { _,m,_ ->
            if (m.name !in listOf("equals", "hashCode", "toString")) playerCalls+=m.name
            when(m.returnType) {
                java.lang.Boolean.TYPE->false
                java.lang.Long.TYPE->0L
                java.lang.Integer.TYPE->0
                java.lang.Float.TYPE->1f
                else->null
            }
        } as ExoPlayer
        field("player",player)
        return vm to state
    }
    private fun save(name:String,width:Int,height:Int) = compose.runOnIdle {
        val view=compose.activity.window.decorView
        val image=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(image))
        val cropped=Bitmap.createBitmap(image,0,0,minOf(width,image.width),minOf(height,image.height))
        val root=com.applemusicktv.testing.TestArtifacts.root
        val out=File(root,"artifacts/v3.0-v16-release/preview").apply {mkdirs()}
        File(out,"$name.png").outputStream().use {cropped.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    private fun coverFixture():String {
        val image=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(image);canvas.drawColor(android.graphics.Color.rgb(220,140,50))
        val paint=android.graphics.Paint().apply {color=android.graphics.Color.rgb(100,180,220)}
        canvas.drawCircle(22f,22f,17f,paint)
        val file=File(compose.activity.cacheDir,"orientation-cover.png")
        file.outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)}
        return file.toURI().toString()
    }
    @Test fun manifestFollowsUserRotationAndHandlesSizeChangesWithoutRecreatingActivity() {
        val context=compose.activity
        val info=context.packageManager.getActivityInfo(ComponentName(context,MainActivity::class.java),0)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER,info.screenOrientation)
        for(flag in listOf(ActivityInfo.CONFIG_ORIENTATION,ActivityInfo.CONFIG_SCREEN_SIZE,ActivityInfo.CONFIG_SCREEN_LAYOUT,ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE))
            assertEquals(flag,info.configChanges and flag)
    }
    @Test fun narrowPortraitForcesLyricsAndRotatingBackRestoresSplitWithoutTouchingPlayerOrQueue() {
        val (vm,state)=vm();val nav=NavigationViewModel();var portrait by mutableStateOf(false)
        compose.setContent {
            val base=LocalConfiguration.current
            val config=Configuration(base).apply {orientation=if(portrait) Configuration.ORIENTATION_PORTRAIT else Configuration.ORIENTATION_LANDSCAPE}
            CompositionLocalProvider(LocalConfiguration provides config,LocalAppFontState provides ready,LocalUiMotion provides UiMotion(false)) {
                Box(Modifier.requiredSize(if(portrait) 540.dp else 960.dp,if(portrait) 960.dp else 540.dp)) {NowPlayingScreen(vm,nav)}
            }
        }
        compose.onNodeWithContentDescription("歌词选项").assertIsDisplayed()
        save("landscape-original-split",960,540)
        compose.runOnIdle {portrait=true}
        compose.onNodeWithContentDescription("歌词选项").assertDoesNotExist()
        compose.onNodeWithText("测试歌曲标题").assertIsDisplayed()
        compose.onNodeWithText("第一行歌词").assertIsDisplayed()
        save("portrait-forced-fullscreen-lyrics",540,960)
        nav.toggleQueuePanel() // Portrait remains lyrics-only even if the queue shortcut is invoked.
        compose.onNodeWithText("第一行歌词").assertIsDisplayed()
        compose.runOnIdle {portrait=false}
        compose.onNodeWithContentDescription("歌词选项").assertIsDisplayed()
        assertEquals(5000L,state.value.progressMs);assertEquals(listOf(song()),state.value.queue)
        assertTrue("Layout rotation must not call player transport/source APIs: $playerCalls",playerCalls.isEmpty())
        assertTrue(vm.nowPlayingVisible)
    }
    @Test fun manuallyExpandedLandscapeChoiceSurvivesPortraitRoundTrip() {
        val (vm,_)=vm();val nav=NavigationViewModel();var portrait by mutableStateOf(false)
        compose.setContent {
            val config=Configuration(LocalConfiguration.current).apply {orientation=if(portrait) Configuration.ORIENTATION_PORTRAIT else Configuration.ORIENTATION_LANDSCAPE}
            CompositionLocalProvider(LocalConfiguration provides config,LocalAppFontState provides ready,LocalUiMotion provides UiMotion(false)) {
                Box(Modifier.requiredSize(if(portrait) 540.dp else 960.dp,if(portrait) 960.dp else 540.dp)) {NowPlayingScreen(vm,nav)}
            }
        }
        compose.onNodeWithContentDescription("歌词选项").performClick()
        compose.onNodeWithText("全屏歌词").performClick()
        compose.onNodeWithContentDescription("歌词选项").assertDoesNotExist()
        compose.runOnIdle {portrait=true}
        compose.onNodeWithText("第一行歌词").assertIsDisplayed()
        compose.runOnIdle {portrait=false}
        compose.onNodeWithContentDescription("歌词选项").assertDoesNotExist()
        compose.onNodeWithText("测试歌曲标题").assertIsDisplayed()
        assertTrue(playerCalls.isEmpty())
    }
    @Test fun everyPortraitRatioForcesLyricsAndSquareKeepsNormalChoice() {
        val (vm,state)=vm();val nav=NavigationViewModel();var height by mutableStateOf(600)
        compose.setContent {
            CompositionLocalProvider(LocalAppFontState provides ready,LocalUiMotion provides UiMotion(false)) {
                Box(Modifier.requiredSize(600.dp,height.dp)) {NowPlayingScreen(vm,nav)}
            }
        }
        compose.onNodeWithContentDescription("歌词选项").assertIsDisplayed()
        for (h in listOf(601,660,720,721,900)) {
            compose.runOnIdle {height=h}
            compose.onNodeWithContentDescription("歌词选项").assertDoesNotExist()
            compose.onNodeWithText("第一行歌词").assertIsDisplayed()
            if(h==660) save("portrait-near-square-full-lyrics",600,h)
        }
        compose.runOnIdle {height=540}
        compose.onNodeWithContentDescription("歌词选项").assertIsDisplayed()
        assertEquals(5000L,state.value.progressMs)
        assertTrue(playerCalls.isEmpty())
    }
    @Test fun narrowPortraitKeepsMetadataLeftOfTransportOnSameRowAndRetainsTapAndIdleBehavior() {
        var playing by mutableStateOf(false);var chrome by mutableStateOf(true);var taps=0
        val longSong=song().copy(title="这是一首很长很长的歌曲标题，用于验证竖屏不会挤压播放控件",artistName="名字很长的演唱者与合作艺人",artworkUrl=coverFixture())
        compose.setContent {
            CompositionLocalProvider(LocalAppFontState provides ready,LocalUiMotion provides UiMotion(false)) {
                Box(Modifier.requiredSize(360.dp,780.dp).background(Color.Black)) {
                    FullScreenLyrics(listOf(LyricLine(0,10000,"在竖屏里阅读歌词")),remember {mutableStateOf(0L)},0,longSong,playing,{}, {}, {taps++;playing=!playing}, {},lyricsScale=.85f,chromeVisible=chrome)
                }
            }
        }
        val title=compose.onNodeWithText(longSong.title).fetchSemanticsNode().boundsInRoot
        val artist=compose.onNodeWithText(longSong.artistName).fetchSemanticsNode().boundsInRoot
        val play=compose.onNodeWithContentDescription("播放").fetchSemanticsNode().boundsInRoot
        assertTrue(title.right <= play.left); assertTrue(artist.right <= play.left)
        assertTrue(title.bottom > play.top && artist.top < play.bottom)
        assertTrue(title.left >= 20 && title.right <= 340)
        for(label in listOf("上一首","播放","下一首")) compose.onNodeWithContentDescription(label).assertIsDisplayed()
        save("portrait-360-controls-and-long-title",360,780)
        compose.onNodeWithContentDescription("播放").performClick();assertEquals(1,taps)
        compose.onNodeWithContentDescription("暂停").assertIsDisplayed()
        compose.runOnIdle {chrome=false}
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle { com.applemusicktv.testing.assertBlackRegion(compose.activity.window.decorView, play) }
        compose.onNodeWithText(longSong.title).assertIsDisplayed()
        save("portrait-360-idle",360,780)
    }
}
