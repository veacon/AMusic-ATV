package com.applemusicktv.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.applemusicktv.data.local.LocalLibraryStore
import com.applemusicktv.data.local.PlaylistImporter
import com.applemusicktv.data.model.Album
import com.applemusicktv.data.model.Artist
import com.applemusicktv.data.model.Song
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SortField { DEFAULT, NAME, ARTIST, DATE }
enum class SortDir   { ASC, DESC }

data class SortState(val field: SortField = SortField.DATE, val dir: SortDir = SortDir.DESC)

data class LibraryState(
    val isLoading: Boolean = false,
    /** 收藏的曲目（资料库「歌曲」页）。 */
    val songs: List<Song> = emptyList(),
    val playlists: List<LocalLibraryStore.LocalPlaylist> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val error: String? = null,
    val sort: SortState = SortState(),
)

/**
 * 本地资料库：收藏 + 歌单，全部来自 [LocalLibraryStore]，不再依赖 Apple 账号。
 *
 * 专辑 / 艺人两页由收藏曲目聚合而成（只聚合带 Apple id 的曲目，保证点开详情可用）。
 */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: LocalLibraryStore,
    private val importer: PlaylistImporter,
) : ViewModel() {

    // 必须在 _state 之前初始化：loadSavedSort() 会读它。
    private val cachePrefs = context.getSharedPreferences("library_cache", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(LibraryState(sort = loadSavedSort()))
    val state: StateFlow<LibraryState> = _state

    var activeSection: LibrarySection = LibrarySection.Songs
        private set
    private var visible = false
    private var importJob: Job? = null

    init {
        // 收藏/歌单任一变化都重算派生数据；数据量小，直接重建列表。
        viewModelScope.launch {
            combine(store.favorites, store.playlists) { favorites, playlists ->
                favorites to playlists
            }.collect { (favorites, playlists) ->
                rebuild(favorites, playlists)
            }
        }
    }

    private fun rebuild(favorites: List<Song>, playlists: List<LocalLibraryStore.LocalPlaylist>) {
        _state.update {
            it.copy(
                songs = favorites,
                playlists = playlists,
                albums = aggregateAlbums(favorites),
                artists = aggregateArtists(favorites),
                isLoading = false,
            )
        }
    }

    private fun aggregateAlbums(songs: List<Song>): List<Album> = songs
        .filter { !it.albumId.isNullOrBlank() && it.albumName.isNotBlank() }
        .distinctBy { it.albumId }
        .map {
            Album(
                id = it.albumId!!,
                title = it.albumName,
                artistName = it.artistName,
                artistId = it.artistId,
                artworkUrl = it.artworkUrl,
                type = "albums",
            )
        }

    private fun aggregateArtists(songs: List<Song>): List<Artist> = songs
        .filter { !it.artistId.isNullOrBlank() }
        .groupBy { it.artistId!! }
        .map { (id, list) ->
            Artist(
                id = id,
                name = list.first().artistName,
                artworkUrl = list.first().artworkUrl,
            )
        }
        .sortedBy { it.name.lowercase() }

    // ── 页面生命周期（保留原调用点） ────────────────────────────────────────

    fun onHidden() { visible = false }

    fun selectSection(section: LibrarySection, markVisible: Boolean = true) {
        if (markVisible) visible = true
        activeSection = section
    }

    /** 本地数据无需预热，保留方法避免调用方改动。 */
    fun preloadAfterLogin() = Unit

    fun refresh() {
        rebuild(store.favorites.value, store.playlists.value)
    }

    // ── 收藏 ────────────────────────────────────────────────────────────────

    fun isFavorite(songId: String): Boolean = store.isFavorite(songId)

    fun toggleFavorite(song: Song): Boolean = store.toggleFavorite(song)

    fun removeFavorite(songId: String) = store.removeFavorite(songId)

    // ── 歌单 ────────────────────────────────────────────────────────────────

    fun importPlaylist(raw: String, name: String? = null, onDone: (String) -> Unit) {
        if (importJob?.isActive == true) return
        _state.update { it.copy(isLoading = true, error = null) }
        importJob = viewModelScope.launch {
            val result = importer.import(raw, name)
            _state.update { it.copy(isLoading = false) }
            when (result) {
                is PlaylistImporter.Result.Ok ->
                    onDone("已导入「${result.playlist.name}」${result.imported} 首")
                is PlaylistImporter.Result.Err -> onDone(result.message)
            }
        }
    }

    fun createPlaylist(name: String) = store.createPlaylist(name)

    fun deletePlaylist(id: String) = store.deletePlaylist(id)

    fun renamePlaylist(id: String, name: String) = store.renamePlaylist(id, name)

    fun addSongToPlaylist(playlistId: String, song: Song) = store.addSongToPlaylist(playlistId, song)

    fun removeSongFromPlaylist(playlistId: String, songId: String) =
        store.removeSongFromPlaylist(playlistId, songId)

    fun playlist(id: String) = store.playlist(id)

    fun playPlaylist(id: String, playerVm: PlayerViewModel) {
        val songs = store.playlist(id)?.songs.orEmpty()
        if (songs.isNotEmpty()) playerVm.playAlbum(songs, 0)
    }

    // ── 排序 ────────────────────────────────────────────────────────────────

    private fun loadSavedSort(): SortState {
        val fieldIdx = cachePrefs.getInt("sort_field", -1)
        if (fieldIdx < 0) return SortState()
        val field = SortField.entries.getOrNull(fieldIdx) ?: return SortState()
        val dir = SortDir.entries.getOrNull(cachePrefs.getInt("sort_dir", 0)) ?: SortDir.ASC
        return SortState(field, dir)
    }

    fun setSort(field: SortField) {
        val cur = _state.value.sort
        setSort(field, if (cur.field == field && cur.dir == SortDir.ASC) SortDir.DESC else SortDir.ASC)
    }

    fun setSort(field: SortField, dir: SortDir) {
        val newSort = SortState(field, dir)
        _state.value = _state.value.copy(sort = newSort)
        cachePrefs.edit().putInt("sort_field", newSort.field.ordinal)
            .putInt("sort_dir", newSort.dir.ordinal).apply()
    }

    // ── 置顶（歌单） ────────────────────────────────────────────────────────

    private val _pinnedIds = MutableStateFlow(loadPinnedIds())
    val pinnedIds: StateFlow<Set<String>> = _pinnedIds

    private fun loadPinnedIds(): Set<String> {
        val raw = cachePrefs.getString("pinned_playlists", "") ?: ""
        return if (raw.isEmpty()) emptySet() else raw.split(",").toSet()
    }

    fun togglePin(id: String) {
        val cur = _pinnedIds.value.toMutableSet()
        if (id in cur) cur.remove(id) else cur.add(id)
        _pinnedIds.value = cur
        cachePrefs.edit().putString("pinned_playlists", cur.joinToString(",")).apply()
    }

    fun isPinned(id: String) = id in _pinnedIds.value

    // ── 排序后的视图 ────────────────────────────────────────────────────────

    fun sortedPlaylists(): List<LocalLibraryStore.LocalPlaylist> {
        val s = _state.value
        val pinned = _pinnedIds.value
        val list = s.playlists
        val sorted = when (s.sort.field) {
            SortField.NAME -> list.sortedBy { it.name.lowercase() }
            else -> list // 存储本身已按「最近更新在前」
        }
        val ordered = if (s.sort.dir == SortDir.DESC && s.sort.field == SortField.NAME) sorted.reversed() else sorted
        val (pins, rest) = ordered.partition { it.id in pinned }
        return pins + rest
    }

    fun sortedAlbums(): List<Album> {
        val s = _state.value
        val list = s.albums
        val sorted = when (s.sort.field) {
            SortField.NAME -> list.sortedBy { it.title.lowercase() }
            SortField.ARTIST -> list.sortedBy { it.artistName.lowercase() }
            else -> list
        }
        return if (s.sort.dir == SortDir.DESC && s.sort.field != SortField.DATE &&
            s.sort.field != SortField.DEFAULT) sorted.reversed() else sorted
    }

    fun sortedSongs(): List<Song> {
        val s = _state.value
        if (s.sort.field == SortField.DATE || s.sort.field == SortField.DEFAULT) {
            // 收藏顺序：最新收藏在前
            return if (s.sort.dir == SortDir.ASC) s.songs.reversed() else s.songs
        }
        val sorted = when (s.sort.field) {
            SortField.ARTIST -> s.songs.sortedBy { it.artistName.lowercase() }
            else -> s.songs.sortedBy { it.title.lowercase() }
        }
        return if (s.sort.dir == SortDir.DESC) sorted.reversed() else sorted
    }

    fun sortedArtists(): List<Artist> {
        val s = _state.value
        val sorted = if (s.sort.field == SortField.NAME) s.artists.sortedBy { it.name.lowercase() } else s.artists
        return if (s.sort.dir == SortDir.DESC) sorted.reversed() else sorted
    }
}
