package com.applemusicktv.ui.navigation

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import com.applemusicktv.ui.components.TouchSurface
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import androidx.tv.material3.Text
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real NavHost entries and SavedStateHandles: playback must pop, never recreate, the wall owner. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w960dp-h540dp-land-mdpi")
class WallPlaybackNavigationTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var nav: NavHostController
    private lateinit var returns: NavigationReturnState
    private class Mode(initial: InputMode) : InputModeManager {
        override var inputMode by mutableStateOf(initial)
        override fun requestInputMode(inputMode: InputMode): Boolean { this.inputMode = inputMode; return true }
    }
    private lateinit var mode: Mode
    private fun mount(input: InputMode = InputMode.Touch) {
        mode = Mode(input)
        compose.setContent {
            nav = rememberNavController()
            returns = remember(nav) { NavigationReturnState(nav) }
            CompositionLocalProvider(LocalNavigationReturn provides returns, LocalInputModeManager provides mode) {
                NavHost(nav, startDestination = Screen.Home.route,
                    enterTransition = { androidx.compose.animation.EnterTransition.None },
                    exitTransition = { androidx.compose.animation.ExitTransition.None }) {
                    returningComposable(Screen.Home.route) { Text("home") }
                    returningComposable(Screen.NowPlaying.route) { TouchSurface({}, Modifier.testTag("playerInfo")) { Text("player") } }
                    returningComposable(Screen.AlbumDetail.route,
                        listOf(navArgument("albumId") { type = NavType.StringType })) { Text("album") }
                    returningComposable(Screen.ArtistDetail.route,
                        listOf(navArgument("artistId") { type = NavType.StringType })) { Text("artist") }
                    returningComposable(Screen.PlaylistDetail.route, listOf(
                        navArgument("playlistId") { type = NavType.StringType },
                        navArgument("playlistName") { type = NavType.StringType },
                        navArgument("artworkUrl") { type = NavType.StringType })) { Text("playlist") }
                }
            }
        }
        compose.waitForIdle()
    }
    private fun player(): String {
        var id = ""
        compose.runOnIdle { nav.navigate(Screen.NowPlaying.route); id = nav.currentBackStackEntry!!.id }
        compose.waitForIdle()
        return id
    }
    private fun detail(route: String, wall: Boolean = true, fromPlayer: Boolean = false) {
        compose.runOnIdle { returns.openDetail(route, fromWall = wall, fromPlayer = fromPlayer) }
        compose.waitForIdle()
    }
    private fun assertSameWall(id: String) = compose.runOnIdle {
        assertEquals(id, nav.currentBackStackEntry!!.id)
        assertEquals(Screen.NowPlaying.route, nav.currentDestination!!.route)
        assertEquals(Screen.Home.route, nav.previousBackStackEntry!!.destination.route)
        assertEquals(true, nav.currentBackStackEntry!!.savedStateHandle.get<Boolean>(WALL_PLAYBACK_RETURN))
        assertNull(returns.pending)
        assertNull(returns.pendingDetailFocus)
        assertNull(returns.returnToTab)
    }
    @Test fun remoteAlbumPlaybackReusesItsWallAndClearsTheOldGridFocusAddress() {
        mount(InputMode.Keyboard)
        val id = player()
        val origin = nav.currentBackStackEntry!!
        detail(Screen.AlbumDetail.route("a"))
        compose.runOnIdle {
            origin.savedStateHandle["return.address"] = ReturnAddress(123, emptyList()).encode()
            origin.savedStateHandle["return.tabFocus"] = TopNavTab.NowPlaying.name
            returns.request(nav.currentBackStackEntry!!.id)
            returns.pendingDetailFocus = nav.currentBackStackEntry!!.id
            assertTrue(returns.returnToPlayerAfterPlayback())
            assertNull(origin.savedStateHandle.get<String>("return.address"))
            assertNull(origin.savedStateHandle.get<String>("return.tabFocus"))
        }
        compose.waitForIdle(); assertSameWall(id)
        compose.runOnIdle {
            assertEquals(InputMode.Keyboard, mode.inputMode)
            assertTrue(runCatching { nav.getBackStackEntry(Screen.AlbumDetail.route) }.isFailure)
        }
    }
    @Test fun touchPlaylistPlaybackReturnsToTheWallWithoutForcingRemoteFocus() {
        mount(); val id = player()
        detail(Screen.PlaylistDetail.route("pl.test", "Playlist", "local-art"))
        compose.runOnIdle { assertTrue(returns.returnToPlayerAfterPlayback()) }
        compose.waitForIdle(); assertSameWall(id)
        compose.runOnIdle { assertEquals(InputMode.Touch, mode.inputMode) }
    }
    @Test fun relatedArtistAndAlbumKeepTheWallOriginAndTheWholeBranchIsDiscarded() {
        mount(); val id = player()
        detail(Screen.AlbumDetail.route("a"))
        detail(Screen.ArtistDetail.route("artist"), wall = false)
        detail(Screen.AlbumDetail.route("b"), wall = false)
        compose.runOnIdle { assertTrue(returns.returnToPlayerAfterPlayback()) }
        compose.waitForIdle(); assertSameWall(id)
        compose.runOnIdle {
            assertTrue(runCatching { nav.getBackStackEntry(Screen.ArtistDetail.route) }.isFailure)
            assertTrue(runCatching { nav.getBackStackEntry(Screen.AlbumDetail.route) }.isFailure)
        }
    }
    @Test fun ordinaryBrowsePlaybackStillPushesAPlayerAndBackReturnsToItsDetail() {
        mount()
        detail(Screen.AlbumDetail.route("browse"), wall = false)
        val detailId = nav.currentBackStackEntry!!.id
        compose.runOnIdle {
            assertFalse(returns.returnToPlayerAfterPlayback())
            nav.navigate(Screen.NowPlaying.route) { launchSingleTop = true }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(detailId, nav.previousBackStackEntry!!.id)
            returns.back()
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(detailId, nav.currentBackStackEntry!!.id) }
    }
    @Test fun anUnmarkedPlayerDetailKeepsItsTemporaryVisitBehavior() {
        mount(); val first = player()
        detail(Screen.AlbumDetail.route("current"), wall = false)
        compose.runOnIdle {
            assertFalse(returns.returnToPlayerAfterPlayback())
            nav.navigate(Screen.NowPlaying.route) { launchSingleTop = true }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertNotEquals(first, nav.currentBackStackEntry!!.id)
            assertEquals(Screen.AlbumDetail.route, nav.previousBackStackEntry!!.destination.route)
        }
    }
    @Test fun detailBackWithoutPlaybackKeepsTheExistingWallReturnBehavior() {
        mount(); val id = player()
        detail(Screen.AlbumDetail.route("a"))
        compose.runOnIdle { returns.back() }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(id, nav.currentBackStackEntry!!.id)
            assertNotEquals(true, nav.currentBackStackEntry!!.savedStateHandle.get<Boolean>(WALL_PLAYBACK_RETURN))
        }
    }
    @Test fun repeatedWallPlaysNeverAccumulateAPlayerOrDetailBehindTheWall() {
        mount(); val id = player()
        repeat(6) { i ->
            detail(Screen.AlbumDetail.route("album$i"))
            compose.runOnIdle { assertTrue(returns.returnToPlayerAfterPlayback()) }
            compose.waitForIdle(); assertSameWall(id)
            compose.runOnIdle { nav.currentBackStackEntry!!.savedStateHandle[WALL_PLAYBACK_RETURN] = false }
        }
        compose.runOnIdle { returns.back() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(Screen.Home.route, nav.currentDestination!!.route) }
    }
    @Test fun currentSongMenuFromFullscreenReusesThePlayerAndRestoresItsInfoFocus() {
        mount(InputMode.Keyboard); val id = player()
        compose.onNodeWithTag("playerInfo").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("playerInfo").assertIsFocused()
        val origin = nav.currentBackStackEntry!!
        detail(Screen.AlbumDetail.route("current"), wall = false, fromPlayer = true)
        compose.runOnIdle {
            assertTrue(returns.returnToPlayerAfterPlayback())
            assertNotNull(origin.savedStateHandle.get<String>("return.address"))
        }
        compose.waitForIdle()
        compose.onNodeWithTag("playerInfo").assertIsFocused()
        compose.runOnIdle {
            assertEquals(id, nav.currentBackStackEntry!!.id)
            assertEquals(Screen.Home.route, nav.previousBackStackEntry!!.destination.route)
            assertEquals(false, origin.savedStateHandle.get<Boolean>(WALL_PLAYBACK_RETURN))
            assertNull(returns.pending)
        }
    }
    @Test fun currentSongMenuFromTheWallAlsoReturnsToTheNormalWall() {
        mount(); val id = player()
        compose.runOnIdle { nav.currentBackStackEntry!!.savedStateHandle[PLAYER_IS_WALL] = true }
        detail(Screen.ArtistDetail.route("wall-artist"), wall = false, fromPlayer = true)
        compose.runOnIdle { assertTrue(returns.returnToPlayerAfterPlayback()) }
        compose.waitForIdle(); assertSameWall(id)
    }
    @Test fun fullscreenLyricsAndSplitPresentationRemainOwnedByTheOriginalPlayer() {
        mount(); val id = player()
        for (layout in listOf("lyrics", "split")) {
            compose.runOnIdle { nav.currentBackStackEntry!!.savedStateHandle["test.layout"] = layout }
            detail(Screen.ArtistDetail.route("artist-$layout"), wall = false, fromPlayer = true)
            detail(Screen.AlbumDetail.route("album-$layout"), wall = false)
            compose.runOnIdle { assertTrue(returns.returnToPlayerAfterPlayback()) }
            compose.waitForIdle()
            compose.runOnIdle {
                assertEquals(id, nav.currentBackStackEntry!!.id)
                assertEquals(layout, nav.currentBackStackEntry!!.savedStateHandle.get<String>("test.layout"))
                assertEquals(false, nav.currentBackStackEntry!!.savedStateHandle.get<Boolean>(WALL_PLAYBACK_RETURN))
                assertEquals(Screen.Home.route, nav.previousBackStackEntry!!.destination.route)
            }
        }
    }
    @Test fun aLaterOrdinaryDetailCannotInheritThePreviousWallVisit() {
        mount(); player()
        detail(Screen.AlbumDetail.route("wall"))
        compose.runOnIdle { assertTrue(returns.returnToPlayerAfterPlayback()) }
        compose.waitForIdle()
        detail(Screen.AlbumDetail.route("ordinary"), wall = false)
        compose.runOnIdle { assertFalse(returns.returnToPlayerAfterPlayback()) }
    }
}
