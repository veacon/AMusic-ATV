package com.applemusicktv.ui.wall

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.*
import kotlin.random.Random

internal class WallFlip(val tile: Int, val next: CachedWallCard) {
    val angle = Animatable(0f)
    var committed = false
        internal set
}

/** A stable board shared by drawing and navigation, retained across detail-page visits. */
class WallBoard internal constructor(val pool: List<CachedWallCard>, val capacity: Int,
    previous: List<CachedWallCard> = emptyList(), private val random: Random = Random.Default) {
    val cards = mutableStateListOf<CachedWallCard>().apply {
        if (pool.isNotEmpty()) {
            val count = capacity.coerceAtLeast(0)
            addAll(previous.distinctBy { it.info.key }.take(count))
            val retained = mapTo(hashSetOf()) { it.info.key }
            addAll(pool.filter { it.info.key !in retained }.take(count - size))
            // Preserve the existing fill behavior only when the entire valid pool is too small.
            while (size < count) add(pool[size % pool.size])
        }
    }
    internal var flip by mutableStateOf<WallFlip?>(null)
        private set
    private val positions = mutableListOf<Int>()
    private val replacements = pool.filter { it.info.kind == WallKind.Album }.shuffled(random).toMutableList()
    private var lastTile = -1

    /** Each eligible position gets a turn before reshuffling; new faces are never already visible. */
    internal fun beginFlip(eligible: Set<Int>): WallFlip? {
        if (flip != null || cards.isEmpty()) return null
        val visible = cards.mapTo(hashSetOf()) { it.info.key }
        val next = replacements.firstOrNull { it.info.key !in visible } ?: return null
        val allowed = eligible.filter { it in cards.indices }
        if (allowed.isEmpty()) return null
        positions.removeAll { it !in allowed }
        if (positions.isEmpty()) positions.addAll(allowed.shuffled(random))
        val tile = positions.firstOrNull { it != lastTile } ?: positions.first()
        positions.remove(tile)
        lastTile = tile
        return WallFlip(tile, next).also { flip = it }
    }

    /** Swap the pixels and their route together, only while the card is edge-on. */
    internal fun commit(active: WallFlip) {
        if (flip !== active || active.committed) return
        val outgoing = cards[active.tile]
        cards[active.tile] = active.next
        active.committed = true
        if (outgoing.info.kind == WallKind.Album) {
            replacements.remove(outgoing)
            replacements.add(outgoing)
        }
        replacements.remove(active.next)
        replacements.add(active.next) // Rotate the reserve, rather than choosing the same two albums.
    }

    /** Cancellation occurs only on leaving the screen/lifecycle; keep whichever face was visible. */
    internal fun finish(active: WallFlip) { if (flip === active) flip = null }
}

internal fun wallFlipInterval(): Long = 2_000L
internal fun wallFlipRotation(angle: Float): Float = if (angle < 90f) angle else angle - 180f
