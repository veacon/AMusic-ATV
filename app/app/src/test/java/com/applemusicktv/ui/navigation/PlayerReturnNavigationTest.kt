package com.applemusicktv.ui.navigation

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import com.applemusicktv.data.model.Album
import com.applemusicktv.ui.components.AlbumCard
import com.applemusicktv.ui.components.TouchSurface
import com.applemusicktv.ui.components.MotionDialog
import androidx.tv.material3.Text
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real navigation, lazy rails, production cards/dialog and activity Back dispatcher. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,qualifiers="w960dp-h540dp-land-mdpi")
class PlayerReturnNavigationTest {
    @get:Rule val compose=createComposeRule()
    private lateinit var nav: NavHostController
    private lateinit var returns: NavigationReturnState
    private lateinit var dispatcher: OnBackPressedDispatcher
    private class Mode(initial: InputMode):InputModeManager {
        override var inputMode by mutableStateOf(initial)
        override fun requestInputMode(inputMode:InputMode):Boolean {this.inputMode=inputMode;return true}
    }
    private lateinit var mode:Mode
    private var ids by mutableStateOf((0..23).map { "item$it" })
    private var rows by mutableStateOf((0..5).toList())
    private var menu by mutableStateOf(false)
    private var loading by mutableStateOf(false)
    private fun mount(input:InputMode=InputMode.Keyboard) {
        mode=Mode(input)
        compose.setContent {
            nav=rememberNavController();returns=remember(nav) {NavigationReturnState(nav)}
            dispatcher=LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            CompositionLocalProvider(LocalNavigationReturn provides returns,LocalInputModeManager provides mode) {
                NavHost(nav,startDestination=Screen.Home.route,enterTransition={androidx.compose.animation.EnterTransition.None},exitTransition={androidx.compose.animation.ExitTransition.None}) {
                    returningComposable(Screen.Home.route) { Source() }
                    returningComposable("detail") { TouchSurface(onClick={nav.navigate("artist")},modifier=Modifier.testTag("artist-link")) {Text("artist")} }
                    returningComposable("artist") { TouchSurface(onClick={nav.navigate(Screen.NowPlaying.route)},modifier=Modifier.testTag("play")) {Text("play")} }
                    returningComposable(Screen.NowPlaying.route) {Text("player")}
                    returningComposable(Screen.DevMenu.route) {Text("settings")}
                }
            }
        }
        compose.waitForIdle()
    }
    @Composable private fun Source() {
        if(loading) {Text("loading");return}
        ReturnLazyColumn(Modifier.fillMaxSize().testTag("source")) {
            items(rows,key={"row$it"}) { row ->
                ReturnLazyRow(Modifier.fillMaxWidth().height(176.dp).testTag("rail$row")) {
                    items(ids,key={it}) { id ->
                        AlbumCard(Album(id=id,title=id,artistName="artist",artworkUrl=null),size=130,
                            modifier=Modifier.testTag("$row-$id"),
                            onClick={nav.navigate("detail")},onLongClick={menu=true})
                    }
                }
            }
        }
        if(menu) MotionDialog(onDismissRequest={menu=false}) {
            TouchSurface(onClick={menu=false},modifier=Modifier.testTag("menu-close")) {Text("Close")}
            BackHandler {menu=false}
        }
    }
    private fun focusAndClick(tag:String) {
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus) {it()}
        compose.onNodeWithTag(tag).assertIsFocused().performClick()
        compose.waitForIdle()
    }
    private fun back() {compose.runOnIdle {dispatcher.onBackPressed()};compose.waitForIdle()}
    private fun scrollSource() {
        compose.onNodeWithTag("source").performScrollToIndex(4)
        compose.onNodeWithTag("rail4").performScrollToIndex(8)
    }
    @Test fun detailBackRestoresTheExactCardInTheSameVerticalAndHorizontalRail() {
        mount();scrollSource();focusAndClick("4-item8");back()
        compose.onNodeWithTag("4-item8").assertIsFocused().assertIsDisplayed()
        compose.runOnIdle {assertNull(returns.returnToTab);assertNull(returns.pending)}
        repeat(2) {focusAndClick("4-item8");back();compose.onNodeWithTag("4-item8").assertIsFocused()}
    }
    @Test fun nestedDetailsAndFullscreenPopOneEntryAndRestoreEachOwnControl() {
        mount();scrollSource();focusAndClick("4-item8")
        focusAndClick("artist-link");focusAndClick("play")
        back();compose.onNodeWithTag("play").assertIsFocused()
        back();compose.onNodeWithTag("artist-link").assertIsFocused()
        back();compose.onNodeWithTag("4-item8").assertIsFocused()
    }
    @Test fun movedIdentityIsFoundEvenWhenItMovesOutsideTheSavedViewport() {
        mount();scrollSource();focusAndClick("4-item8")
        compose.runOnIdle {ids=ids.filter {it!="item8"}+"item8"}
        back();compose.onNodeWithTag("4-item8").assertIsFocused().assertIsDisplayed()
    }
    @Test fun deletedIdentityFallsBackNearItsOriginalPositionWithoutTab() {
        mount();scrollSource();focusAndClick("4-item8")
        compose.runOnIdle {ids=ids.filter {it!="item8"}}
        back();compose.onNodeWithTag("4-item9").assertIsFocused()
        compose.runOnIdle {assertNull(returns.returnToTab)}
    }
    @Test fun settingsBackReturnsToTheActualSourceEntry() {
        mount();scrollSource()
        compose.onNodeWithTag("4-item8").performSemanticsAction(SemanticsActions.RequestFocus) {it()}
        compose.runOnIdle {nav.navigate(Screen.DevMenu.route)};compose.waitForIdle();back()
        compose.onNodeWithTag("4-item8").assertIsFocused()
    }
    @Test fun touchBackKeepsViewportWithoutForcingKeyboardInput() {
        mount(InputMode.Touch);scrollSource();compose.onNodeWithTag("4-item8").performClick();back()
        compose.onNodeWithTag("4-item8").assertIsDisplayed()
        compose.runOnIdle {assertEquals(InputMode.Touch,mode.inputMode);assertNull(returns.returnToTab)}
    }
    @Test fun dialogCloseRestoresOpenerAndDoesNotPopThePage() {
        mount();scrollSource()
        compose.onNodeWithTag("4-item8").performSemanticsAction(SemanticsActions.RequestFocus) {it()}
        compose.runOnIdle {menu=true};compose.waitForIdle()
        compose.onNodeWithTag("menu-close").performClick();compose.waitForIdle()
        compose.onNodeWithTag("4-item8").assertIsFocused()
        compose.runOnIdle {assertEquals(Screen.Home.route,nav.currentDestination?.route)}
    }
    @Test fun duplicateSongIdsReceiveDistinctStableOccurrenceKeys() {
        assertEquals(listOf("1:a:0","1:b:0","1:a:1"),occurrenceKeys(listOf("a","b","a")))
        assertEquals("1:a:1",occurrenceKeys(listOf("b","a","a")).last())
    }
    @Test fun gridBackRestoresTheExactCover() {
        compose.setContent {
            nav=rememberNavController();returns=remember(nav) {NavigationReturnState(nav)}
            dispatcher=LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            mode=remember {Mode(InputMode.Keyboard)}
            CompositionLocalProvider(LocalNavigationReturn provides returns,LocalInputModeManager provides mode) {
                NavHost(nav,startDestination=Screen.Library.route,enterTransition={androidx.compose.animation.EnterTransition.None},exitTransition={androidx.compose.animation.ExitTransition.None}) {
                    returningComposable(Screen.Library.route) {
                        ReturnLazyVerticalGrid(androidx.compose.foundation.lazy.grid.GridCells.Fixed(4),modifier=Modifier.fillMaxSize().testTag("grid")) {
                            items(40,key={"album$it"}) { index ->
                                AlbumCard(Album(id="album$index",title="Album $index",artistName="artist",artworkUrl=null),size=130,
                                    modifier=Modifier.testTag("cover$index"),onClick={nav.navigate("detail")})
                            }
                        }
                    }
                    returningComposable("detail") {Text("details")}
                }
            }
        }
        compose.onNodeWithTag("grid").performScrollToIndex(28)
        focusAndClick("cover30");back()
        compose.onNodeWithTag("cover30").assertIsFocused().assertIsDisplayed()
    }
    @Test fun sourceAddressesRemainIndependentForTwoInstancesOfTheSameDetailRoute() {
        mount();scrollSource();focusAndClick("4-item8");focusAndClick("artist-link")
        compose.runOnIdle {nav.navigate("detail")};compose.waitForIdle()
        focusAndClick("artist-link");back();compose.onNodeWithTag("artist-link").assertIsFocused()
        back();compose.onNodeWithTag("play").assertIsDisplayed()
        back();compose.onNodeWithTag("artist-link").assertIsFocused()
        back();compose.onNodeWithTag("4-item8").assertIsFocused()
    }
    @Test fun cancellationInvalidatesThePendingRestoreSoItCannotStealLaterFocus() {
        mount()
        compose.runOnIdle {
            val source=nav.currentBackStackEntry!!.id
            returns.request(source)
            val token=returns.generation
            returns.cancel()
            assertNull(returns.pending)
            assertTrue(returns.generation>token)
        }
    }

    @Test fun pageFocusMemoryRemainsSafeWhenAnEntryIsDestroyedByBack() {
        mount();focusAndClick("0-item0")
        lateinit var page:ReturnPageState
        compose.runOnIdle {page=returns.pages.getValue(nav.currentBackStackEntry!!.id)}
        focusAndClick("artist-link");back();back()
        compose.runOnIdle {
            assertEquals(androidx.lifecycle.Lifecycle.State.DESTROYED,page.entry.lifecycle.currentState)
            val origin=page.last
            page.last=origin // Dialog/inline-layer disposal is allowed to retain its opener after pop.
            assertEquals(origin,page.last)
        }
    }

    @Test fun removingTheWholeShelfStillFallsBackToContentRatherThanTab() {
        mount();scrollSource();focusAndClick("4-item8")
        compose.runOnIdle {rows=rows.filter {it!=4}}
        back()
        compose.onAllNodes(isFocused()).assertCountEquals(1)
        compose.runOnIdle {assertNull(returns.returnToTab);assertNull(returns.pending)}
    }

    @Test fun touchReturnToUnavailableContentNeverForcesTabOrKeyboardMode() {
        mount(InputMode.Touch);compose.onNodeWithTag("0-item0").performClick()
        compose.runOnIdle {loading=true}
        back()
        compose.runOnIdle {assertEquals(InputMode.Touch,mode.inputMode);assertNull(returns.returnToTab);assertNull(returns.pending)}
    }

}
