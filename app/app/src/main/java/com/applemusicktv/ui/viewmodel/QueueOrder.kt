package com.applemusicktv.ui.viewmodel

import com.applemusicktv.data.model.Song

/** Restore original entries, retaining items inserted while shuffled and the exact playing occurrence. */
internal fun restoreQueueOrder(queue: List<Song>, original: List<Song>, index: Int): Pair<List<Song>, Int> {
    if (queue.isEmpty()) return emptyList<Song>() to 0
    if (original.isEmpty()) return queue to index.coerceIn(queue.indices)
    val safeIndex = index.coerceIn(queue.indices)
    val current = queue[safeIndex]
    val occurrence = queue.take(safeIndex + 1).count { it === current }
    val remaining = queue.toMutableList()
    val restored = original.mapNotNull { song ->
        val found = remaining.indexOfFirst { it === song }.takeIf { it >= 0 }
            ?: remaining.indexOfFirst { it.id == song.id }.takeIf { it >= 0 }
        found?.let { remaining.removeAt(it) }
    }.toMutableList()
    // Play-next entries are not in originalQueue; keep them beside the song they followed.
    remaining.forEach { added ->
        val oldIndex = queue.indexOfFirst { it === added }
        val predecessor = queue.take(oldIndex.coerceAtLeast(0)).lastOrNull { candidate -> restored.any { it === candidate } }
        val after = restored.indexOfFirst { it === predecessor }
        restored.add(if (after >= 0) after + 1 else restored.size, added)
    }
    val newIndex = restored.indices.filter { restored[it] === current }.getOrNull(occurrence - 1)
        ?: restored.indexOfFirst { it.id == current.id }.coerceAtLeast(0)
    return restored to newIndex
}
