package com.applemusicktv.ui.screens

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.applemusicktv.data.model.*
import com.applemusicktv.data.network.*
import com.applemusicktv.data.network.HomeSection
import com.applemusicktv.data.repository.*
import com.applemusicktv.ui.components.*
import com.applemusicktv.ui.viewmodel.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,qualifiers="w1440dp-h1000dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PageLayoutUiTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @org.junit.Before fun useChinese() { com.applemusicktv.i18n.AppLanguage.select(com.applemusicktv.i18n.LanguageChoice.Chinese) }
    private val rail=SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)
    private val vertical=SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
    private fun <T> blank(c:Class<T>):T {
        val k=Class.forName("sun.misc.Unsafe");val u=k.getDeclaredField("theUnsafe").apply {isAccessible=true}.get(null)
        @Suppress("UNCHECKED_CAST") return k.getMethod("allocateInstance",Class::class.java).invoke(u,c) as T
    }
    private fun set(vm:Any,name:String,value:Any) {vm.javaClass.getDeclaredField(name).apply {isAccessible=true}.set(vm,value)}
    private fun player()=blank(PlayerViewModel::class.java)
    private fun search(state:SearchUiState,recents:List<String> = emptyList()):SearchViewModel {
        val vm=blank(SearchViewModel::class.java);val flow=MutableStateFlow(state)
        set(vm,"_state",flow);set(vm,"state",flow);set(vm,"recentSearches",MutableStateFlow(recents));return vm
    }
    private val output:File get()=File(System.getProperty("java.io.tmpdir"), "amtv-v73-ui-preview").apply {mkdirs()}
    private fun bitmap():Bitmap {
        var image:Bitmap?=null
        compose.runOnIdle {val v=compose.activity.window.decorView;image=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(image!!))}
        return image!!
    }
    private fun save(name:String,width:Int,height:Int) {
        val b=bitmap();val crop=Bitmap.createBitmap(b,0,0,width,height.coerceAtMost(b.height))
        File(output,"$name.png").outputStream().use {crop.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    private fun mount(width:Int,height:Int,content:@Composable ()->Unit) {
        compose.setContent {
            Box(Modifier.fillMaxSize(),contentAlignment=Alignment.TopStart) {
                Box(Modifier.requiredSize(width.dp,height.dp).background(Color.White)) {
                    PageLayout {CompositionLocalProvider(LocalUiMotion provides UiMotion(false)) {content()}}
                }
            }
        }
    }
    private fun assertRailsWidth(width:Int) {
        val rails=compose.onAllNodes(rail).fetchSemanticsNodes().filter { it.boundsInRoot.height > 0 };assertTrue(rails.isNotEmpty())
        for(n in rails) {assertEquals(0f,n.boundsInRoot.left,.1f);assertEquals(width.toFloat(),n.boundsInRoot.right,.1f)}
    }
    @Test fun sharedGutterCapsWideLayoutsAndRespondsToResize() {
        var width by mutableStateOf(600)
        compose.setContent {
            Box(Modifier.fillMaxSize(),contentAlignment=Alignment.TopStart) {
                Box(Modifier.requiredSize(width.dp,900.dp)) {
                    // Actual /68 Settings modifier chain, used as the reference geometry.
                    Column(Modifier.align(Alignment.TopCenter).widthIn(max=880.dp).fillMaxWidth().padding(horizontal=40.dp)) {
                        Box(Modifier.fillMaxWidth().height(20.dp).testTag("reference"))
                    }
                    PageLayout {
                        Box(Modifier.fillMaxWidth().padding(horizontal=pageHorizontalInset).height(20.dp).testTag("shared"))
                    }
                }
            }
        }
        for(w in listOf(600,960,1440,720)) {
            compose.runOnIdle {width=w}
            val a=compose.onNodeWithTag("reference").fetchSemanticsNode().boundsInRoot
            val b=compose.onNodeWithTag("shared").fetchSemanticsNode().boundsInRoot
            val expected = minOf(a.left, 120f)
            assertEquals(expected,b.left,.1f);assertEquals(w - expected,b.right,.1f)
            save("page-gutter-$w",w,900)
        }
    }
    private fun searchEdges(width:Int,height:Int) {
        val vm=search(SearchUiState(categories=listOf(CategoryGroup("Genres",(0..7).map {Curator("c$it","Category $it","multiroom",true,null)}))))
        mount(width,height) {SearchScreen(player(),vm=vm)}
        assertRailsWidth(width)
        val row=compose.onAllNodes(rail)[0]
        val bounds=row.fetchSemanticsNode().boundsInRoot
        val y=(bounds.top+30).toInt()
        val before=bitmap()
        assertEquals(android.graphics.Color.WHITE,before.getPixel(1,y))
        assertNotEquals(android.graphics.Color.WHITE,before.getPixel(width-1,y))
        val gutter=minOf(120, if(width<=880) 40 else (width-880)/2+40)
        assertEquals(android.graphics.Color.WHITE,before.getPixel(gutter-2,y))
        assertNotEquals(android.graphics.Color.WHITE,before.getPixel(gutter+2,y))
        save("search-$width-initial",width,height)
        row.performSemanticsAction(SemanticsActions.ScrollBy) {it(100f,0f)}
        assertNotEquals(android.graphics.Color.WHITE,bitmap().getPixel(1,y))
        save("search-$width-scrolled",width,height)
        row.performSemanticsAction(SemanticsActions.ScrollBy) {it(-100f,0f)}
        assertEquals(android.graphics.Color.WHITE,bitmap().getPixel(1,y))
    }
    @Test fun portraitSearchStartsAtSharedGutterAndScrollsToBothScreenEdges()=searchEdges(600,960)
    @Test fun landscapeSearchStartsAtSharedGutterAndScrollsToBothScreenEdges()=searchEdges(960,600)
    @Test fun searchResultsAndRecentsAllUseFullWidthRailViewports() {
        val albums=(0..7).map {Album("a$it","Album $it","Artist",artworkUrl=null)}
        val artists=(0..7).map {Artist("r$it","Artist $it",null)}
        val curators=(0..7).map {Curator("c$it","Curator $it","multiroom",true,null)}
        val state=MutableStateFlow(SearchUiState(categories=listOf(CategoryGroup("Genres",curators))))
        val vm=search(state.value,(0..7).map {"Recent $it"});set(vm,"state",state);set(vm,"_state",state)
        mount(600,960) {SearchScreen(player(),vm=vm)}
        assertTrue(compose.onAllNodes(rail).fetchSemanticsNodes().size>=2);assertRailsWidth(600)
        compose.runOnIdle {state.value=SearchUiState(query="test",results=SearchResults(albums=albums,artists=artists,playlists=albums,curators=curators))}
        for(index in 0..3) {
            compose.onAllNodes(vertical)[0].performScrollToIndex(index)
            assertRailsWidth(600)
        }
        save("search-results-portrait",600,960)
    }
    @Test fun categoryAlbumAndVideoRailsExtendToScreenEdgesWithoutParentClipping() {
        val state=CategoryUiState(isLoading=false,title="Editorial",sections=listOf(
            HomeSection(title="Albums",albums=(0..7).map {AlbumDto(id="a$it",title="Album $it",artistName="Artist",artworkUrl=null,artworkBgColor=null,releaseDate=null)}),
            HomeSection(title="Videos",albums=emptyList(),videos=(0..7).map {SongDto(id="v$it",title="Video $it",artistName="Artist",albumName="",durationMs=1000,artworkUrl=null,artworkBgColor=null,previewUrl=null,previewHlsUrl=null)})
        ))
        mount(600,960) {CategoryContent(state,playerVm=player())}
        assertRailsWidth(600)
        val row=compose.onAllNodes(rail)[0]
        val y=(row.fetchSemanticsNode().boundsInRoot.top+30).toInt()
        assertEquals(android.graphics.Color.WHITE,bitmap().getPixel(1,y))
        assertNotEquals(android.graphics.Color.WHITE,bitmap().getPixel(599,y))
        save("category-portrait-initial",600,960)
        row.performSemanticsAction(SemanticsActions.ScrollBy) {it(100f,0f)}
        assertNotEquals(android.graphics.Color.WHITE,bitmap().getPixel(1,y))
        save("category-portrait-scrolled",600,960)
    }
    @Test fun libraryOuterSidebarAndAllContentSectionsShareTheSettingsEnvelope() {
        val vm=blank(LibraryViewModel::class.java)
        val account=com.applemusicktv.data.MutPreferences(org.robolectric.RuntimeEnvironment.getApplication()).also {it.setMUT("layout-fixture")}
        set(vm,"mutPrefs",account);set(vm,"loadedAccount","layout-fixture")
        val freshness=LibraryRefreshPolicy().also {policy->LibrarySection.entries.forEach(policy::succeeded)}
        set(vm,"refreshPolicy",freshness)
        val songs=(0..7).map {Song("s$it","Song $it","Artist","",180000,null,null,null)}
        val flow=MutableStateFlow(LibraryState(hasMut=true,songs=songs,sort=SortState(SortField.DEFAULT,SortDir.ASC),albums=listOf(Album("a","Library album","Artist",artworkUrl=null)),artists=listOf(Artist("r","Library artist",null))))
        set(vm,"_state",flow);set(vm,"state",flow)
        val pins=MutableStateFlow(emptySet<String>());set(vm,"_pinnedIds",pins);set(vm,"pinnedIds",pins)
        mount(960,600) {LibraryScreen(vm,player())}
        val title=compose.onNodeWithText("资料库").fetchSemanticsNode().boundsInRoot
        assertEquals(80f,title.left,.1f) // Page title matches the Settings title edge.
        val list=compose.onAllNodes(vertical)[0].fetchSemanticsNode().boundsInRoot
        assertEquals(880f,list.right,.1f) // 960 - 80dp, matching Settings.
        save("library-landscape-songs",960,600)
        compose.onNodeWithText("专辑",substring=false).performClick()
        save("library-landscape-albums",960,600)
        compose.onNodeWithText("艺人",substring=false).performClick()
        assertEquals(880f,compose.onAllNodes(vertical)[0].fetchSemanticsNode().boundsInRoot.right,.1f)
        save("library-landscape-artists",960,600)
    }
    @Test fun albumAndPlaylistOuterArtworkAndTrackPaneAlignInBothOrientations() {
        val songs=(0..7).map {Song("s$it","Track $it","Artist","",180000,null,null,null)}
        val album=blank(AlbumDetailViewModel::class.java)
        val albumState=MutableStateFlow(AlbumDetailUiState(isLoading=false,album=Album("a","Album fixture","Artist",artworkUrl=null),tracks=songs))
        set(album,"_state",albumState);set(album,"state",albumState)
        val playlist=blank(PlaylistDetailViewModel::class.java)
        val playlistState=MutableStateFlow(PlaylistDetailState(tracks=songs))
        set(playlist,"_state",playlistState);set(playlist,"state",playlistState);set(playlist,"loadedId","fixture")
        val pvm=player();var width by mutableStateOf(600);var playlistPage by mutableStateOf(false)
        compose.setContent {
            Box(Modifier.fillMaxSize(),contentAlignment=Alignment.TopStart) {
                Box(Modifier.requiredSize(width.dp,if(width==600) 960.dp else 600.dp).background(Color.White)) {
                    PageLayout {CompositionLocalProvider(LocalUiMotion provides UiMotion(false)) {
                        if(playlistPage) PlaylistDetailScreen("fixture","Playlist fixture",playerVm=pvm,onBack={},vm=playlist)
                        else AlbumDetailScreen(pvm,vm=album)
                    }}
                }
            }
        }
        for(w in listOf(600,960)) for(pl in listOf(false,true)) {
            compose.runOnIdle {width=w;playlistPage=pl}
            val gutter=if(w==600) 40 else 80
            val list=compose.onAllNodes(vertical)[0].fetchSemanticsNode().boundsInRoot
            assertEquals((w-gutter).toFloat(),list.right,.1f)
            val b=bitmap()
            assertEquals(android.graphics.Color.WHITE,b.getPixel(gutter-2,130))
            assertEquals(if(pl) 0xFFF0EDF6.toInt() else 0xFFF0F0F4.toInt(),b.getPixel(gutter+2,130))
            save("${if(pl) "playlist" else "album"}-$w",w,if(w==600) 960 else 600)
        }
    }

}
