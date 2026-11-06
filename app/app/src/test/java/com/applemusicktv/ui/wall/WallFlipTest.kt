package com.applemusicktv.ui.wall

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w960dp-h540dp-land-mdpi")
class WallFlipTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun pool(count: Int, playlistFrom: Int = count): List<CachedWallCard> {
        val image = Bitmap.createBitmap(320, 320, Bitmap.Config.RGB_565)
        return (0 until count).map { CachedWallCard(WallDescriptor("$it", "Album $it", "Artist", "art/$it",
            if (it < playlistFrom) WallKind.Album else WallKind.Playlist, "home"), image) }
    }
    @Test fun noReserveDoesNotCreateDuplicatesOrUseAPlaylistAsNewFace() {
        val board = WallBoard(pool(6, 5), 5, random = Random(7))
        assertNull(board.beginFlip(setOf(0, 1)))
        assertEquals(listOf("0", "1", "2", "3", "4"), board.cards.map { it.info.id })
        val repeated = WallBoard(pool(2), 5)
        assertEquals(5, repeated.cards.size)
        assertNull(repeated.beginFlip(setOf(0)))
    }
    @Test fun commitIsAtomicWithRouteAndPoolNeverDuplicatesVisibleAlbums() {
        val board = WallBoard(pool(12), 5, random = Random(12))
        val visited = hashSetOf<Int>()
        repeat(5) {
            val active = board.beginFlip((0..4).toSet())!!
            assertTrue(visited.add(active.tile))
            val before = board.cards[active.tile]
            assertNotEquals(before.info.key, active.next.info.key)
            assertFalse(board.cards.any { it.info.key == active.next.info.key })
            assertNull(board.beginFlip(setOf(0))) // At most one in flight.
            assertSame(before, board.cards[active.tile])
            board.commit(active)
            assertSame(active.next, board.cards[active.tile])
            board.commit(active)
            assertEquals(5, board.cards.map { it.info.key }.distinct().size)
            board.finish(active)
        }
    }
    @Test fun eligiblePositionsExcludeProtectedCardsAndCancellationKeepsVisibleFace() {
        val board = WallBoard(pool(10), 5, random = Random(3))
        val before = board.cards.toList()
        val active = board.beginFlip(setOf(3))!!
        assertEquals(3, active.tile)
        board.finish(active)
        assertEquals(before, board.cards)
        board.commit(active) // Stale animation cannot mutate the board.
        assertEquals(before, board.cards)
        assertNull(board.beginFlip(emptySet()))
        val second = board.beginFlip(setOf(2))!!
        board.commit(second); board.finish(second)
        assertSame(second.next, board.cards[2])
    }
    @Test fun growingTheViewportRetainsFlippedSlotsWithoutDuplicatingTheirNewFaces() {
        val source = pool(12)
        val original = WallBoard(source, 5, random = Random(8))
        val active = original.beginFlip(setOf(2))!!
        original.commit(active); original.finish(active)
        val grown = WallBoard(source, 9, original.cards)
        assertSame(active.next, grown.cards[2])
        assertEquals(9, grown.cards.map { it.info.key }.distinct().size)
    }
    @Test fun cadenceAndBackFaceStayWithinTheChosenLimits() {
        assertEquals(2_000L, wallFlipInterval())
        assertEquals(0f, wallFlipRotation(0f), 0f)
        assertEquals(89f, wallFlipRotation(89f), 0f)
        assertEquals(-90f, wallFlipRotation(90f), 0f)
        assertEquals(0f, wallFlipRotation(180f), 0f)
    }
    @Test fun realAnimationSwapsAtEdgeAndTheNextTapUsesTheNewRoute() {
        val board = WallBoard(pool(50), 45, random = Random(4))
        var opened: CachedWallCard? = null
        compose.setContent {
            Box(Modifier.requiredSize(960.dp, 540.dp).testTag("root")
                .wallTouchNavigation(true, false, board.cards, { true }, {}, { _, card -> opened = card },
                    blocked = { board.flip?.tile == it })) {
                AlbumWallGrid(board.cards, false, -1, {}, { opened = it }, remember { FocusRequester() }, true, board)
            }
            val active = board.flip
            LaunchedEffect(active) {
                if (active != null) try { animateWallFlip(board, active) } finally { board.finish(active) }
            }
        }
        compose.mainClock.autoAdvance = false
        lateinit var active: WallFlip
        lateinit var old: CachedWallCard
        compose.runOnIdle { old = board.cards[11]; active = board.beginFlip(setOf(11))!! }
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        compose.mainClock.advanceTimeBy(160)
        compose.runOnIdle { assertTrue(active.angle.value < 90f); assertSame(old, board.cards[11]) }
        compose.onNodeWithTag("root").performTouchInput { click(Offset(250f, 150f)) }
        compose.runOnIdle { assertNull(opened) }
        compose.mainClock.advanceTimeBy(320)
        compose.runOnIdle { assertTrue(active.angle.value >= 90f); assertSame(active.next, board.cards[11]) }
        compose.mainClock.advanceTimeBy(400); compose.waitForIdle()
        compose.runOnIdle { assertNull(board.flip) }
        compose.onNodeWithTag("root").performTouchInput { click(Offset(250f, 150f)) }
        compose.runOnIdle { assertSame(active.next, opened) }
    }
}
