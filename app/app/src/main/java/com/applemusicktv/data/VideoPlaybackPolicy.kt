package com.applemusicktv.data

import com.applemusicktv.data.model.Song
import com.applemusicktv.data.network.*

/** User V75 temporarily disables on-demand video. Motion artwork and audio radio stay enabled. */
object VideoPlaybackPolicy {
    const val ENABLED = false
    fun isVideo(type: String) = type.contains("video", ignoreCase = true)
    fun allows(id: String, type: String): Boolean = !isVideo(type) && id != "grouping-34" && !(id == "34" && type in setOf("grouping", "genres", "genre"))
    fun allows(song: Song) = allows(song.id, song.type)
    fun audio(songs: List<Song>) = songs.filter(::allows)
    fun sections(input: List<HomeSection>): List<HomeSection> = input.mapNotNull { section ->
        val albums = section.albums.filter { allows(it.id, it.type) }
        if (albums.isEmpty()) null else section.copy(albums = albums, videos = emptyList())
    }
    fun home(response: HomeResponse) = response.copy(sections = sections(response.sections))
    fun room(response: MultiRoomDto) = response.copy(sections = sections(response.sections))
    fun artist(response: ArtistFullDto) = response.copy(
        musicVideos = emptyList(), topSongs = response.topSongs.filter { allows(it.id, it.type) },
        latestRelease = response.latestRelease?.takeIf { allows(it.id, it.type) },
        albums = response.albums.filter { allows(it.id, it.type) },
        featuredAlbums = response.featuredAlbums.filter { allows(it.id, it.type) })

    data class Selection(val songs: List<Song>, val index: Int) {
        val song: Song? get() = songs.getOrNull(index)
    }
    /** Preserve the selected audio item's occurrence, even when duplicate ids surround videos. */
    fun selection(input: List<Song>, index: Int): Selection {
        val positions = input.indices.filter { allows(input[it]) }
        if (positions.isEmpty()) return Selection(emptyList(), 0)
        val requested = index.coerceIn(0, input.lastIndex)
        val chosen = positions.firstOrNull { it >= requested } ?: positions.last()
        return Selection(positions.map(input::get), positions.indexOf(chosen))
    }
    fun restored(saved: Song, queue: List<Song>, index: Int): Selection {
        val input = queue.ifEmpty { listOf(saved) }
        val selected = if (allows(saved)) input.indices.firstOrNull { it == index && input[it] == saved }
            ?: input.indexOfFirst { it.id == saved.id && allows(it) }.takeIf { it >= 0 }
            else null
        return selection(input, selected ?: index)
    }
}
