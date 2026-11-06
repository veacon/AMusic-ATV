package com.applemusicktv.ui.wall

import org.junit.Assert.*
import org.junit.Test

class WallPolicyTest {
    @Test fun onlyCardsWithRealDetailTargetsAreIncluded() {
        assertEquals(WallKind.Album, wallDescriptor("123", "Album", "Artist", "url", "albums", "home")!!.kind)
        assertEquals(WallKind.Playlist, wallDescriptor("p.123", "Mix", "Curator", "url", "library-playlists", "library")!!.kind)
        for (type in listOf("songs", "music-videos", "artists", "stations"))
            assertNull(wallDescriptor("123", "Card", "Artist", "url", type, "browse"))
        for (id in listOf("ra.1", "ac-1", "c-1", "mr-1"))
            assertNull(wallDescriptor(id, "Card", "Artist", "url", "albums", "home"))
    }
    @Test fun incompleteMetadataCannotCreateClickableImages() {
        assertNull(wallDescriptor("", "Title", "", "url", "albums", "home"))
        assertNull(wallDescriptor("1", "", "", "url", "albums", "home"))
        assertNull(wallDescriptor("1", "Title", "", null, "albums", "home"))
    }
    @Test fun allSourcesAreMixedInsteadOfOnlyTheFirstNonemptyOne() {
        assertEquals(listOf("h1", "b1", "l1", "h2", "l2", "h3"),
            wallInterleave(listOf(listOf("h1", "h2", "h3"), listOf("b1"), listOf("l1", "l2"))))
    }
    @Test fun gridMovementClampsAtEdgesWithoutWrapping() {
        assertEquals(0, wallMove(0, 8, 5, -1, 0))
        assertEquals(0, wallMove(0, 8, 5, 0, -1))
        assertEquals(7, wallMove(7, 8, 5, 1, 0))
        assertEquals(39, wallMove(39, 8, 5, 0, 1))
        assertEquals(18, wallMove(10, 8, 5, 0, 1))
    }
    @Test fun touchExcludesAnyCellOverlappingTheWholeFooterStrip() {
        assertTrue(wallTouchAllowed(800f, 1000f, 136f))
        assertFalse(wallTouchAllowed(900f, 1000f, 136f))
        assertTrue(wallTouchAllowed(864f, 1000f, 136f))
    }
    @Test fun explicitEntryUsesTheCentralCellInBothOrientations() {
        assertEquals(20, wallCenterIndex(8, 5))
        assertEquals(22, wallCenterIndex(5, 8))
        assertEquals(12, wallCenterIndex(5, 5))
    }
    @Test fun touchCoordinatesMapToTheVisibleCardAndExcludeFooter() {
        assertEquals(0, wallTapIndex(50f, 50f, 960, 540, 136f))
        assertEquals(11, wallTapIndex(250f, 150f, 960, 540, 136f))
        assertNull(wallTapIndex(250f, 500f, 960, 540, 136f))
        assertNull(wallTapIndex(-1f, 100f, 960, 540, 136f))
        assertNull(wallTapIndex(100f, 100f, 0, 0, 136f))
    }
    @Test fun albumsFromEverySourcePrecedeAllPlaylistCandidates() {
        fun card(id: String, kind: WallKind, source: String) = WallDescriptor(id, id, "", "url", kind, source)
        val result = wallAlbumsFirst(listOf(
            listOf(card("ph", WallKind.Playlist, "home"), card("ah", WallKind.Album, "home")),
            listOf(card("ab", WallKind.Album, "browse"), card("pb", WallKind.Playlist, "browse")),
            listOf(card("al", WallKind.Album, "library"))))
        assertEquals(listOf("ah", "ab", "al", "ph", "pb"), result.map { it.id })
    }
}
