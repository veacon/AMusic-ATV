package com.applemusicktv.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import com.applemusicktv.ui.components.UiMotion
import org.junit.Assert.*
import org.junit.Test

class BrowsingPageMotionTest {
    private val tabs = listOf("now_playing", "home", "browse", "library", "search", "dev_menu")

    @Test fun allTwentyBrowsingPairsUseOnlyPairedTwoHundredMsFades() {
        val browsing=tabs.filterNot { it=="now_playing" }
        for(from in browsing) for(to in browsing) if(from!=to) {
            assertTrue(BrowsingPageMotion.browsingFade(from,to))
            assertEquals(androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween<Float>(200,easing=TabMotion.easing)), BrowsingPageMotion.enter(from,to,UiMotion()))
            assertEquals(androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween<Float>(200,easing=TabMotion.easing)), BrowsingPageMotion.exit(from,to,UiMotion()))
        }
    }

    @Test fun playerPairsKeepTheirExistingFiveHundredMsSlideAndHeaderTiming() {
        for(root in tabs.filterNot {it=="now_playing"}) {
            assertFalse(BrowsingPageMotion.browsingFade(root,"now_playing"))
            assertEquals(-1,BrowsingPageMotion.direction(root,"now_playing"))
            assertNotSame(EnterTransition.None,BrowsingPageMotion.enter(root,"now_playing",UiMotion()))
            assertNotSame(ExitTransition.None,BrowsingPageMotion.exit(root,"now_playing",UiMotion()))
        }
        for(from in TopNavTab.entries) for(to in TopNavTab.entries) {
            assertEquals(if(from==TopNavTab.NowPlaying || to==TopNavTab.NowPlaying)500 else 200,TabMotion.selectionDuration(from,to))
        }
    }

    @Test fun sameRouteAndInitialMountNeverSlide() {
        for (tab in tabs) {
            assertEquals(0, BrowsingPageMotion.direction(tab, tab))
            assertEquals(0, BrowsingPageMotion.direction(null, tab))
            assertEquals(0, BrowsingPageMotion.direction(tab, null))
        }
    }

    @Test fun detailRoutesKeepTheirExistingTransition() {
        for (route in listOf("album/{albumId}", "category/{categoryId}", "radio")) {
            for (tab in tabs) {
                assertEquals(0, BrowsingPageMotion.direction(route, tab))
                assertEquals(0, BrowsingPageMotion.direction(tab, route))
            }
        }
    }

    @Test fun playerAndContentPagesUsePairedCrossfadesInBothDirections() {
        val motion = UiMotion()
        assertEquals(500, PlayerEntryMotion.crossfadeMs)
        assertEquals(500, PlayerEntryMotion.duration(motion))

        assertNotSame(EnterTransition.None, BrowsingPageMotion.enter("home", "now_playing", motion))
        assertNotSame(ExitTransition.None, BrowsingPageMotion.exit("home", "now_playing", motion))
        assertNotSame(EnterTransition.None, BrowsingPageMotion.enter("now_playing", "home", motion))
        assertNotSame(ExitTransition.None, BrowsingPageMotion.exit("now_playing", "home", motion))
    }

    @Test fun playerAndContentPagesStillSwitchImmediatelyWhenMotionIsDisabled() {
        val motion = UiMotion(enabled = false)
        assertEquals(0, PlayerEntryMotion.duration(motion))
    }
}
