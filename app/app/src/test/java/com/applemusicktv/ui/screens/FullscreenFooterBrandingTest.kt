package com.applemusicktv.ui.screens

import android.app.Application
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.applemusicktv.BuildConfig
import com.applemusicktv.MainActivity
import com.applemusicktv.R
import com.applemusicktv.data.fonts.*
import com.applemusicktv.data.model.Song
import com.applemusicktv.data.network.LyricLine
import com.applemusicktv.ui.components.*
import com.applemusicktv.ui.theme.LocalAppFontState
import java.io.File
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
class FullscreenFooterBrandingTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val ready=FontDownloadState(1,1,FontDownloadPhase.Ready)
    private val output:File get()=File(com.applemusicktv.testing.TestArtifacts.root,"artifacts/v3.0-v16-release/preview").apply {mkdirs()}
    private fun cover():String {
        val b=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888);val c=Canvas(b);c.drawColor(android.graphics.Color.rgb(220,140,50))
        val p=android.graphics.Paint().apply {color=android.graphics.Color.rgb(100,180,220)};c.drawCircle(22f,22f,17f,p)
        val file=File(output,"footer-cover.png");file.outputStream().use {b.compress(Bitmap.CompressFormat.PNG,100,it)};return file.toURI().toString()
    }
    private fun song()=Song("footer","这是一首用于检查对齐的歌曲","歌手","",180000,cover(),null,null)
    private fun save(name:String,w:Int,h:Int)=compose.runOnIdle {
        val v=compose.activity.window.decorView;val b=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(b))
        val crop=Bitmap.createBitmap(b,0,0,w,h);File(output,"$name.png").outputStream().use {crop.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test fun launcherIdentityUsesNewNamePackageVersionAndExactSizedTransparentIcon() {
        assertEquals("com.applemusic.lavande",BuildConfig.APPLICATION_ID)
        val packageInfo = compose.activity.packageManager.getPackageInfo(BuildConfig.APPLICATION_ID, 0)
        assertEquals(BuildConfig.VERSION_NAME, packageInfo.versionName)
        assertEquals(BuildConfig.VERSION_CODE, packageInfo.versionCode)
        val ctx=compose.activity;val app=ctx.packageManager.getApplicationInfo(BuildConfig.APPLICATION_ID,0)
        assertEquals("Apple Music TV",ctx.packageManager.getApplicationLabel(app).toString())
        val activity=ctx.packageManager.getActivityInfo(ComponentName(ctx,MainActivity::class.java),0)
        assertTrue(activity.exported)
        val icon=BitmapFactory.decodeResource(ctx.resources,R.mipmap.ic_launcher,BitmapFactory.Options().apply {inScaled=false})
        assertEquals(1280,icon.width);assertEquals(1280,icon.height)
        assertEquals(0,android.graphics.Color.alpha(icon.getPixel(0,0)))
        assertTrue(android.graphics.Color.alpha(icon.getPixel(512,512))>0)
    }
    @Test fun landscapeControlsMatchCoverCentreWithoutMovingTheLeftCover() {
        var scale by mutableStateOf(1f);val song=song()
        compose.setContent {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,scale),LocalAppFontState provides ready,LocalUiMotion provides UiMotion(false)) {
                Box(Modifier.requiredSize(960.dp,540.dp).background(Color.Black)) {
                    FullScreenLyrics(listOf(LyricLine(0,10000,"横屏歌词对齐")),remember {mutableStateOf(0L)},0,song,false,{}, {}, {}, {})
                }
            }
        }
        val cover=compose.onNodeWithTag("fullscreenArtwork").fetchSemanticsNode().boundsInRoot
        assertEquals(64f,cover.left,.5f);assertEquals(48f,540f-cover.bottom,.5f)
        for(label in listOf("上一首","播放","下一首")) {
            val control=compose.onNodeWithContentDescription(label).fetchSemanticsNode().boundsInRoot
            assertEquals(cover.center.y,control.center.y,.5f)
        }
        save("landscape-footer-aligned",960,540)
        compose.runOnIdle {scale=1.8f}
        val largeCover=compose.onNodeWithTag("fullscreenArtwork").fetchSemanticsNode().boundsInRoot
        val play=compose.onNodeWithContentDescription("播放").fetchSemanticsNode().boundsInRoot
        assertEquals(largeCover.center.y,play.center.y,.5f)
        assertEquals(64f,largeCover.left,.5f)
    }
    @Test fun portraitFooterHasEqualSideAndBottomInsetsAndCentredControls() {
        var width by mutableStateOf(360);var height by mutableStateOf(780);val song=song()
        compose.setContent {
            CompositionLocalProvider(LocalAppFontState provides ready,LocalUiMotion provides UiMotion(false)) {
                Box(Modifier.requiredSize(width.dp,height.dp).background(Color.Black)) {
                    FullScreenLyrics(listOf(LyricLine(0,10000,"竖屏歌词对齐")),remember {mutableStateOf(0L)},0,song,false,{}, {}, {}, {})
                }
            }
        }
        for((w,h) in listOf(360 to 780,600 to 900)) {
            compose.runOnIdle {width=w;height=h}
            val cover=compose.onNodeWithTag("fullscreenArtwork").fetchSemanticsNode().boundsInRoot
            val target=(w*.07f).coerceIn(20f,48f)
            assertEquals(target,cover.left,1f);assertEquals(target,h-cover.bottom,1f)
            val next=compose.onNodeWithContentDescription("下一首").fetchSemanticsNode().boundsInRoot
            assertEquals(target,w-next.right,1f)
            for(label in listOf("上一首","播放","下一首")) {
                val control=compose.onNodeWithContentDescription(label).fetchSemanticsNode().boundsInRoot
                assertEquals(cover.center.y,control.center.y,.5f)
            }
            save("portrait-$w-footer-aligned",w,h)
        }
    }
}
