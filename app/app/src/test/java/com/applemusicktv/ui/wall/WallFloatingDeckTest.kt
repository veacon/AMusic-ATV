package com.applemusicktv.ui.wall

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WallFloatingDeckTest {
    @Test fun incomingCardsAreAddedBelowOutgoingWithoutChangingExistingOrder() {
        val deck = WallFloatingDeck()
        deck.select(12); val old = deck.entries.single()
        deck.select(13)
        assertEquals(listOf(13, 12), deck.entries.map { it.tile })
        assertSame(old, deck.entries.last())
        deck.select(14)
        assertEquals(listOf(14, 13, 12), deck.entries.map { it.tile })
        assertSame(old, deck.entries.last())
    }
    @Test fun anOutgoingCopyIsRemovedOnlyAfterItExactlyMatchesTheFixedGrid() = runBlocking {
        val deck = WallFloatingDeck()
        deck.select(12); val old = deck.entries.single()
        old.scale.snapTo(1.15f)
        deck.select(13); val incoming = deck.entries.first()
        old.scale.snapTo(1.03f); deck.removeIfResting(old)
        assertEquals(listOf(13, 12), deck.entries.map { it.tile })
        old.scale.snapTo(1f); deck.removeIfResting(old)
        assertEquals(listOf(13), deck.entries.map { it.tile })
        deck.removeIfResting(incoming) // Still selected, even before its first animation frame.
        assertEquals(listOf(13), deck.entries.map { it.tile })
    }
    @Test fun reversingRetainsBothIdentityAndCurrentScaleWithoutReordering() = runBlocking {
        val deck = WallFloatingDeck()
        deck.select(12); val original = deck.entries.single()
        original.scale.snapTo(1.08f)
        deck.select(13)
        deck.select(12)
        assertEquals(listOf(13, 12), deck.entries.map { it.tile })
        assertSame(original, deck.entries.last())
        assertEquals(1.08f, original.scale.value, 0f)
        deck.removeIfResting(original)
        assertEquals(2, deck.entries.size)
    }
    @Test fun exitingAndRapidRetargetingCannotRemoveTheLatestSelectedCopy() = runBlocking {
        val deck = WallFloatingDeck()
        deck.select(1); val a = deck.entries.single()
        deck.select(2); val b = deck.entries.first()
        deck.select(3); val c = deck.entries.first()
        a.scale.snapTo(1f); deck.removeIfResting(a)
        b.scale.snapTo(1f); deck.removeIfResting(b)
        assertEquals(listOf(3), deck.entries.map { it.tile })
        deck.select(null)
        c.scale.snapTo(1.05f); deck.removeIfResting(c)
        assertEquals(1, deck.entries.size)
        deck.select(3); c.scale.snapTo(1f); deck.removeIfResting(c)
        assertEquals(1, deck.entries.size)
        deck.select(null); deck.removeIfResting(c)
        assertTrue(deck.entries.isEmpty())
    }
    @Test fun restoringAnExistingSelectionStartsAtItsSettledSize() {
        val deck = WallFloatingDeck(12)
        assertEquals(listOf(12), deck.entries.map { it.tile })
        assertEquals(1.15f, deck.entries.single().scale.value, 0f)
    }
    @Test fun aRestingCopyCannotMaskTheIncomingCardsOverhangWhenRemoved() {
        assertEquals(0f, wallFloatingOpacity(1f), 0f)
        assertEquals(1f, wallFloatingOpacity(1.15f), .0001f)
        assertEquals(.5f, wallFloatingOpacity(1.075f), .0001f)
        assertTrue(wallFloatingOpacity(1.04f) < wallFloatingOpacity(1.08f))
    }
}
