package com.applemusicktv.ui.wall

enum class WallKind { Album, Playlist }
data class WallDescriptor(val id: String, val title: String, val subtitle: String,
    val artworkUrl: String, val kind: WallKind, val source: String) {
    val key: String get() = "${kind.name}:$id"
}

internal fun wallDescriptor(id: String, title: String, subtitle: String, artwork: String?, type: String,
    source: String): WallDescriptor? {
    if (id.isBlank() || title.isBlank() || artwork.isNullOrBlank()) return null
    if (listOf("ra.", "ac-", "c-", "mr-").any(id::startsWith)) return null
    val kind = when {
        id.startsWith("pl.") || id.startsWith("p.") || type in setOf("playlists", "library-playlists") -> WallKind.Playlist
        type in setOf("albums", "library-albums") -> WallKind.Album
        else -> return null
    }
    return WallDescriptor(id, title, subtitle, artwork, kind, source)
}

internal fun <T> wallInterleave(groups: List<List<T>>): List<T> = buildList {
    val count = groups.maxOfOrNull { it.size } ?: 0
    for (i in 0 until count) for (group in groups) group.getOrNull(i)?.let(::add)
}

internal fun wallMove(index: Int, columns: Int, rows: Int, dx: Int, dy: Int): Int {
    val col = (index % columns + dx).coerceIn(0, columns - 1)
    val row = (index / columns + dy).coerceIn(0, rows - 1)
    return row * columns + col
}

internal fun wallTouchAllowed(cellBottom: Float, viewportHeight: Float, footerHeight: Float): Boolean =
    cellBottom <= viewportHeight - footerHeight

/** For an even number of cells either middle neighbour is suitable. */
internal fun wallCenterIndex(columns: Int, rows: Int): Int = (rows / 2) * columns + columns / 2

/** Physical touch coordinates; reserve a full-width strip for footer controls. */
internal fun wallTapIndex(x: Float, y: Float, width: Int, height: Int, footer: Float): Int? {
    if (width <= 0 || height <= 0 || x < 0 || y < 0 || x >= width || y >= height - footer) return null
    val cell = minOf(width, height) / 5f
    val columns = kotlin.math.ceil(width / cell).toInt()
    return (y / cell).toInt() * columns + (x / cell).toInt()
}

/** Keep page-source mixing within each kind, but exhaust albums before considering playlists. */
internal fun wallAlbumsFirst(groups: List<List<WallDescriptor>>): List<WallDescriptor> =
    wallInterleave(groups.map { group -> group.filter { it.kind == WallKind.Album } }) +
        wallInterleave(groups.map { group -> group.filter { it.kind == WallKind.Playlist } })
