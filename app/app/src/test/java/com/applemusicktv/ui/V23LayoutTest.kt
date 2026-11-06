package com.applemusicktv.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.applemusicktv.data.model.Album
import com.applemusicktv.data.model.Song
import com.applemusicktv.data.network.LyricLine
import com.applemusicktv.ui.components.*
import com.applemusicktv.ui.screens.*
import com.applemusicktv.ui.viewmodel.*
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
@OptIn(ExperimentalTvMaterial3Api::class,ExperimentalTestApi::class)
class V23LayoutTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private fun save(name:String)=compose.runOnIdle {
        val view=compose.activity.window.decorView
        val image=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
        view.draw(Canvas(image))
        val root=com.applemusicktv.testing.TestArtifacts.root
        File(root,"artifacts/v3.0-v23-release/preview/$name.png").apply { parentFile.mkdirs() }.outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG,100,it)
        }
    }
    private fun song()=Song("song","歌曲","艺人","专辑",60_000,null,null,null)

    @Test fun centeringUsesStableLayoutAndRetainsPanelOverrides() {
        val unavailable=LibrarySortFocusRoute()
        assertFalse(unavailable.request());assertFalse(unavailable.requestingSort)
        val initial=PlayerState(currentSong=song())
        assertFalse(centerPlayerControls(initial,false,false,true))
        val empty=initial.copy(lyricsNotice=LyricsNoticePhase.Pending)
        assertFalse(centerPlayerControls(empty,false,false,true))
        assertFalse(centerPlayerControls(empty.copy(lyricsNotice=LyricsNoticePhase.Done),false,false,true))
        assertTrue(centerPlayerControls(empty.copy(isLoading=true),false,false,true,true))
        assertFalse(centerPlayerControls(empty,false,false,false,true))
        assertFalse(centerPlayerControls(empty,false,true,true,true))
        assertTrue(centerPlayerControls(initial,true,false,false))
        assertFalse(centerPlayerControls(initial.copy(stationLaunch=StationLaunch(1,"station","电台",null)),true,false,true))
        assertTrue(centerPlayerControls(initial.copy(isLiveRadio=true),false,false,true,true))
        assertFalse(centerPlayerControls(empty.copy(lyrics=listOf(LyricLine(0,1000,"歌词"))),false,false,true,true))
    }

    private fun checkCentering(width:Int,cover:Int,animate:Boolean) {
        var centered by mutableStateOf(false)
        var mounts=0;var disposals=0
        val focus=FocusRequester()
        val spacing=playerColumnSpacing(width.dp,(width.dp*.075f).coerceIn(32.dp,96.dp),
            (width.dp*.07f).coerceIn(36.dp,112.dp),cover.dp)
        compose.setContent {
            CompositionLocalProvider(LocalUiMotion provides UiMotion(enabled=animate)) {
                Box(Modifier.fillMaxSize().background(Color(0xFF242634))) {
                    PlayerSplitLayout(width.dp,cover.dp,spacing,centered,controls={
                        DisposableEffect(Unit) {mounts++;onDispose {disposals++}}
                        Column(Modifier.width(cover.dp).testTag("controls"),horizontalAlignment=Alignment.CenterHorizontally) {
                            Box(Modifier.size(cover.dp).background(Color(0xFFBF3A4F)))
                            androidx.tv.material3.Text("歌曲 · 艺人",color=Color.White)
                            TransportButton(TransportIcon.Play,{},modifier=Modifier.focusRequester(focus))
                        }
                    },panel={
                        Box(Modifier.fillMaxSize(),Alignment.Center) {
                            EmptyLyrics("song",1f,centered=centered)
                        }
                    })
                }
            }
        }
        compose.runOnIdle {focus.requestFocus()}
        val before=compose.onNodeWithTag("controls").fetchSemanticsNode().boundsInRoot
        assertEquals(spacing.side.value,before.left,1f)
        save("split-layout-$width")
        compose.runOnIdle {centered=true}
        compose.mainClock.advanceTimeBy(if(animate)600 else 32)
        val middle=compose.onNodeWithTag("controls").fetchSemanticsNode().boundsInRoot
        assertEquals(width/2f,middle.center.x,1f)
        assertEquals(before.top,middle.top,1f)
        assertEquals(before.width,middle.width,1f)
        compose.onNodeWithContentDescription("播放").assertIsFocused()
        compose.onNodeWithText("暂无歌词").assertDoesNotExist()
        save("centered-layout-$width")
        compose.runOnIdle {centered=false}
        compose.mainClock.advanceTimeBy(if(animate)600 else 32)
        val restored=compose.onNodeWithTag("controls").fetchSemanticsNode().boundsInRoot
        assertEquals(before.left,restored.left,1f)
        assertEquals(1,mounts);assertEquals(0,disposals)
        compose.onNodeWithContentDescription("播放").assertIsFocused()
    }
    @Test fun artworkControlsCenterAndRestoreWithoutRemountingOrLosingFocus()=checkCentering(960,224,true)
    @Test fun reducedMotionCentersWithoutAnimation()=checkCentering(960,224,false)
    @Test @Config(qualifiers="w1280dp-h720dp-land-mdpi")
    fun largerViewportAlsoCentersExactly()=checkCentering(1280,360,true)

    private fun libraryFixture(grid:Boolean) {
        val entry=FocusRequester();val list=FocusRequester();val sort=LibrarySortFocusRoute()
        val external=FocusRequester()
        var picks=0
        compose.setContent {
            Column(Modifier.fillMaxSize().background(Color.White)) {
                TouchSurface({},Modifier.size(100.dp,30.dp).focusRequester(external).testTag("outside")) {}
                Box(Modifier.fillMaxSize().focusRequester(entry).libraryEntryFocus(list,true,false,sort).focusGroup()) {
                    Column(Modifier.fillMaxSize()) {
                        SortBar(if(grid) LibrarySection.Albums else LibrarySection.Songs,SortField.NAME,SortDir.ASC,null,sort.control,list) {_,_->picks++}
                        Box(Modifier.weight(1f).focusRequester(list).focusGroup()) {
                            CompositionLocalProvider(LocalLibrarySortFocus provides sort) {
                                if(grid) Row(Modifier.padding(top=20.dp),horizontalArrangement=Arrangement.spacedBy(20.dp)) {
                                    repeat(4) {index->
                                        AlbumCard(Album("$index","专辑 $index","艺人",artworkUrl=null),size=140,onClick={},
                                            modifier=Modifier.testTag("item$index").librarySortOnRight(index==3))
                                    }
                                } else Column(Modifier.fillMaxSize().librarySortOnRight()) {
                                    repeat(3) {index->
                                        TouchSurface({},Modifier.fillMaxWidth().height(48.dp).testTag("item$index")) {
                                            androidx.tv.material3.Text("歌曲 $index")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.runOnIdle {entry.requestFocus()}
        compose.onNodeWithTag("item0").assertIsFocused()
        if(grid) repeat(3) {index->
            compose.onNodeWithTag("item$index").performKeyInput {pressKey(Key.DirectionRight)}
            compose.onNodeWithTag("item${index+1}").assertIsFocused()
        }
        compose.onNodeWithTag(if(grid) "item3" else "item0").performKeyInput {pressKey(Key.DirectionRight)}
        compose.onNodeWithContentDescription("资料库排序").assertIsFocused()
        save(if(grid) "library-grid-sort-focus" else "library-list-sort-focus")
        compose.onNodeWithContentDescription("资料库排序").performKeyInput {pressKey(Key.DirectionLeft)}
        assertTrue(compose.onAllNodes(isFocused()).fetchSemanticsNodes().any {
            it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.startsWith("item")==true
        })
        compose.runOnIdle {external.requestFocus();sort.request()}
        compose.onNodeWithContentDescription("资料库排序").assertIsFocused()
        compose.onNodeWithContentDescription("资料库排序").performKeyInput {pressKey(Key.DirectionCenter)}
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("排序方式").assertIsDisplayed()
        compose.onNodeWithText("降序",substring=false).performClick()
        assertEquals(1,picks)
    }
    @Test fun listRightReachesWorkingSortAndBypassesDefaultListEntry()=libraryFixture(false)
    @Test fun gridKeepsHorizontalBrowsingAndRightEdgeReachesSort()=libraryFixture(true)

    @Test fun developerAccountSectionRetainsAccountActionsWithoutPcConfiguration() {
        var refresh=0;var reset=0
        compose.setContent {DeveloperAccountSettings(true,false,{refresh++},{reset++})}
        compose.onNodeWithText("开发者令牌").assertIsDisplayed()
        compose.onNodeWithText("用户令牌").assertIsDisplayed()
        for(text in listOf("电脑服务器","服务器地址","重新检查服务器","点击填写 IP 地址"))
            compose.onNodeWithText(text).assertDoesNotExist()
        compose.onNodeWithText("刷新账号状态").performClick()
        compose.onNodeWithText("重新配置应用").performClick()
        assertEquals(1,refresh);assertEquals(1,reset)
        save("developer-account-section")
    }

    @Test fun fullScreenGlyphRendersAndRemainsDistinctFromLyrics() {
        compose.setContent {Row(Modifier.fillMaxSize().background(Color.White).padding(60.dp),horizontalArrangement=Arrangement.spacedBy(48.dp)) {
            Column(horizontalAlignment=Alignment.CenterHorizontally) {
                Icon(Glyph.LYRICS,size=48.dp,color=Color(0x801D1D1F))
                androidx.tv.material3.Text("隐藏歌词")
            }
            Column(horizontalAlignment=Alignment.CenterHorizontally) {
                Icon(Glyph.FULLSCREEN,size=48.dp,color=Color(0xFF1D1D1F))
                androidx.tv.material3.Text("全屏歌词")
            }
        }}
        compose.onNodeWithText("全屏歌词").assertIsDisplayed()
        save("lyrics-and-fullscreen-icons")
    }
}
