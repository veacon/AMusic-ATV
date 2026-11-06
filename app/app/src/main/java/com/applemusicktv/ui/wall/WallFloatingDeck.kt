package com.applemusicktv.ui.wall

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.mutableStateListOf

internal class WallFloatingEntry(val tile: Int, initialScale: Float = 1f) {
    val scale = Animatable(initialScale)
}

/** Bottom-to-top order is immutable for existing entries; new cards are inserted below outgoing cards. */
internal class WallFloatingDeck(initialSelection: Int? = null) {
    val entries = mutableStateListOf<WallFloatingEntry>()
    private var target: Int? = initialSelection
    init {
        if (initialSelection != null) entries.add(WallFloatingEntry(initialSelection, 1.15f))
    }

    fun select(tile: Int?) {
        target = tile
        if (tile != null && entries.none { it.tile == tile }) entries.add(0, WallFloatingEntry(tile))
    }

    fun removeIfResting(entry: WallFloatingEntry) {
        if (target != entry.tile && entry.scale.value == 1f) entries.remove(entry)
    }
}

/** Resting copies must be transparent: a full-size outgoing copy could still mask incoming overhang. */
internal fun wallFloatingOpacity(scale: Float): Float = ((scale - 1f) / .15f).coerceIn(0f, 1f)
