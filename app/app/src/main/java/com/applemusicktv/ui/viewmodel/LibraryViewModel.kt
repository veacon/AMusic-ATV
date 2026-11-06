package com.applemusicktv.ui.viewmodel

import com.applemusicktv.i18n.tr
import com.applemusicktv.R

import com.applemusicktv.data.cache.AppDiskCache
import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.applemusicktv.data.MutPreferences
import com.applemusicktv.data.model.Album
import com.applemusicktv.data.model.Artist
import com.applemusicktv.data.model.Song
import com.applemusicktv.data.network.PlaylistDto
import com.applemusicktv.data.repository.MusicRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject

enum class SortField { DEFAULT, NAME, ARTIST, DATE }
enum class SortDir   { ASC, DESC }

data class SortState(val field: SortField = SortField.DATE, val dir: SortDir = SortDir.DESC)

data class LibraryState(
    val isLoading: Boolean = false,
    val hasMut: Boolean = false,
    val playlists: List<PlaylistDto> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val songs: List<Song> = emptyList(),
    val error: String? = null,
    val songsError: String? = null,
    val songsNewestFirst: Boolean = false,
    val sort: SortState = SortState(),
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: MusicRepository,
    private val mutPrefs: MutPreferences,
    private val moshi: Moshi,
) : ViewModel() {

    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state

    private val contentCache = AppDiskCache.get(context).content("library_cache")
    private val cachePrefs = context.getSharedPreferences("library_cache", Context.MODE_PRIVATE)
    private val playlistsAdapter = moshi.adapter<List<PlaylistDto>>(Types.newParameterizedType(List::class.java, PlaylistDto::class.java))
    private val albumsAdapter    = moshi.adapter<List<Album>>(Types.newParameterizedType(List::class.java, Album::class.java))
    private val artistsAdapter   = moshi.adapter<List<Artist>>(Types.newParameterizedType(List::class.java, Artist::class.java))
    private val songsAdapter     = moshi.adapter<List<Song>>(Types.newParameterizedType(List::class.java, Song::class.java))

    private val refreshPolicy = LibraryRefreshPolicy()
    private var activeSection = LibrarySection.Songs
    private var visible = false
    private var cacheRestored = false
    private var loadedAccount = mutPrefs.getMUT()
    private var loadJob: Job? = null
    private var loadingSection: LibrarySection? = null
    private var loadGeneration = 0L

    init {
        loadSavedSort()
        _state.update { it.copy(hasMut = loadedAccount.isNotEmpty()) }
        // Lightweight account listener only. No cache parsing or network during app startup.
        viewModelScope.launch {
            mutPrefs.accountChanges.map { mutPrefs.getMUT() }.distinctUntilChanged().collect { account ->
                if (account != loadedAccount) {
                    resetAccount(account)
                    if (visible) selectSection(activeSection)
                }
            }
        }
    }

    private fun resetAccount(account: String) {
        ++loadGeneration
        loadJob?.cancel()
        loadingSection = null
        loadedAccount = account
        // Existing disk caches belong to the previous account until replaced by fresh results.
        cacheRestored = true
        refreshPolicy.invalidate()
        _state.update { LibraryState(hasMut = account.isNotEmpty(), sort = it.sort) }
    }

    fun onHidden() { visible = false }

    /** Called on Library entry/category selection. Only this category may request data. */
    fun selectSection(section: LibrarySection, markVisible: Boolean = true) {
        if (markVisible) visible = true
        activeSection = section
        val account = mutPrefs.getMUT()
        if (account != loadedAccount) resetAccount(account)
        if (account.isEmpty()) return
        if (loadJob?.isActive == true && loadingSection == section) return
        val generation = ++loadGeneration
        loadJob?.cancel()
        loadingSection = null
        if (refreshPolicy.isFresh(section)) {
            _state.update { it.copy(isLoading = false, error = null) }
            return
        }
        loadingSection = section
        loadJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = true, hasMut = true, error = null,
                songsError = if (section == LibrarySection.Songs) null else it.songsError) }
            try {
                if (!cacheRestored) {
                    val cached = withContext(Dispatchers.IO) { readCache() }
                    currentCoroutineContext().ensureActive()
                    if (generation != loadGeneration || mutPrefs.getMUT() != account) return@launch
                    if (cached != null) _state.update { cached.copy(sort = it.sort, isLoading = true) }
                    cacheRestored = true
                }
                var songsNewestFirst: Boolean? = null
                val save: Pair<String, String> = when (section) {
                    LibrarySection.Playlists -> {
                        val value = repo.getLibraryPlaylists().getOrThrow()
                        currentCoroutineContext().ensureActive()
                        if (mutPrefs.getMUT() != account) return@launch
                        _state.update { it.copy(playlists = value) }
                        withContext(Dispatchers.IO) { "playlists" to playlistsAdapter.toJson(value) }
                    }
                    LibrarySection.Albums -> {
                        val value = repo.getLibraryAlbums().getOrThrow()
                        currentCoroutineContext().ensureActive()
                        if (mutPrefs.getMUT() != account) return@launch
                        _state.update { it.copy(albums = value) }
                        withContext(Dispatchers.IO) { "albums" to albumsAdapter.toJson(value) }
                    }
                    LibrarySection.Artists -> {
                        val value = repo.getLibraryArtists().getOrThrow()
                        currentCoroutineContext().ensureActive()
                        if (mutPrefs.getMUT() != account) return@launch
                        _state.update { it.copy(artists = value) }
                        withContext(Dispatchers.IO) { "artists" to artistsAdapter.toJson(value) }
                    }
                    LibrarySection.Songs -> {
                        val value = repo.getLibrarySongs().getOrThrow()
                        currentCoroutineContext().ensureActive()
                        if (mutPrefs.getMUT() != account) return@launch
                        _state.update { it.copy(songs = value.songs, songsError = null, songsNewestFirst = value.newestFirst) }
                        songsNewestFirst = value.newestFirst
                        withContext(Dispatchers.IO) { "songs" to songsAdapter.toJson(value.songs) }
                    }
                }
                currentCoroutineContext().ensureActive()
                if (generation != loadGeneration || mutPrefs.getMUT() != account) return@launch
                withContext(Dispatchers.IO) { contentCache.putString(save.first, save.second) }
                currentCoroutineContext().ensureActive()
                if (generation == loadGeneration && mutPrefs.getMUT() == account) {
                    // Preserve V12's order: addition-order metadata follows its matching cache write.
                    songsNewestFirst?.let { newest -> cachePrefs.edit { putBoolean("songs_newest_first", newest) } }
                    refreshPolicy.succeeded(section)
                }
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                if (generation != loadGeneration || mutPrefs.getMUT() != account) return@launch
                val message = when (failure) {
                    is HttpException -> if (failure.code() in listOf(401,403))
                        tr(R.string.msg_008) else tr(R.string.msg_009, failure.code())
                    is IOException -> tr(R.string.msg_010)
                    else -> tr(R.string.msg_011)
                }
                if (section == LibrarySection.Songs) _state.update { it.copy(songsError = message) }
                else _state.update { state ->
                    val cached = when (section) {
                        LibrarySection.Playlists -> state.playlists.isNotEmpty()
                        LibrarySection.Albums -> state.albums.isNotEmpty()
                        LibrarySection.Artists -> state.artists.isNotEmpty()
                        else -> false
                    }
                    state.copy(error = if (cached) null else message)
                }
            } finally {
                if (generation == loadGeneration) {
                    loadingSection = null
                    _state.update { it.copy(isLoading = false) }
                }
            }
        }
    }

    private fun loadSavedSort() {
        if (!cachePrefs.getBoolean("web_date_desc_v1", false)) {
            cachePrefs.edit {
                putInt("sort_field", SortField.DATE.ordinal)
                putInt("sort_dir", SortDir.DESC.ordinal)
                putBoolean("date_sort_v2", true)
                putBoolean("web_date_desc_v1", true)
            }
        }
        val fieldIdx = cachePrefs.getInt("sort_field", -1)
        if (fieldIdx < 0) return
        val field = SortField.entries.getOrNull(fieldIdx) ?: return
        val dir = SortDir.entries.getOrNull(cachePrefs.getInt("sort_dir", 0)) ?: SortDir.ASC
        _state.value = _state.value.copy(sort = SortState(field, dir))
    }

    /** Load the persisted library so content shows immediately on cold start. */
    private fun readCache(): LibraryState? {
        if (mutPrefs.getMUT().isEmpty()) return null
        try {
            val playlists = (contentCache.getString("playlists")?.let { playlistsAdapter.fromJson(it) } ?: emptyList()).filter { !com.applemusicktv.data.VideoPlaybackPolicy.isVideo(it.playlistType.orEmpty()) }
            val albums    = (contentCache.getString("albums")?.let { albumsAdapter.fromJson(it) } ?: emptyList()).filter { com.applemusicktv.data.VideoPlaybackPolicy.allows(it.id,it.type) }
            val artists   = contentCache.getString("artists")?.let { artistsAdapter.fromJson(it) } ?: emptyList()
            val songs = (contentCache.getString("songs")?.let { songsAdapter.fromJson(it) } ?: emptyList()).filter { com.applemusicktv.data.VideoPlaybackPolicy.allows(it) }
            if (playlists.isNotEmpty() || albums.isNotEmpty() || songs.isNotEmpty()) {
                return LibraryState(hasMut = true, playlists = playlists, albums = albums, artists = artists, songs = songs,
                    songsNewestFirst = cachePrefs.getBoolean("songs_newest_first", false))
            }
        } catch (_: Exception) {}
        return null
    }

    fun setSort(field: SortField) {
        val cur = _state.value.sort
        val newDir = if (cur.field == field && cur.dir == SortDir.ASC) SortDir.DESC else SortDir.ASC
        setSort(field, newDir)
    }

    /** Explicit field + direction — used by the sort popup, which picks both. */
    fun setSort(field: SortField, dir: SortDir) {
        val newSort = SortState(field, dir)
        _state.value = _state.value.copy(sort = newSort)
        cachePrefs.edit {
            putInt("sort_field", newSort.field.ordinal)
            putInt("sort_dir", newSort.dir.ordinal)
        }
    }

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
        cachePrefs.edit { putString("pinned_playlists", cur.joinToString(",")) }
    }

    fun isPinned(id: String) = id in _pinnedIds.value

    fun sortedPlaylists(): List<PlaylistDto> {
        val s = _state.value
        val pinned = _pinnedIds.value
        val list = s.playlists
        val sorted = when (s.sort.field) {
            SortField.NAME -> list.sortedBy { it.name.lowercase() }
            SortField.DATE, SortField.DEFAULT -> when { list.all { it.additionOrder != null } -> list.sortedByDescending { it.additionOrder }; list.all { !it.dateAdded.isNullOrBlank() } -> list.sortedBy { it.dateAdded }; else -> list }
            else -> list
        }
        val ordered = if (s.sort.dir == SortDir.DESC && (s.sort.field == SortField.NAME || (list.all { it.additionOrder != null } || list.all { !it.dateAdded.isNullOrBlank() }))) sorted.reversed() else sorted
        val (pins, rest) = ordered.partition { it.id in pinned }
        return pins + rest
    }

    fun sortedAlbums(): List<Album> {
        val s = _state.value
        val list = s.albums
        val sorted = when (s.sort.field) {
            SortField.NAME   -> list.sortedBy { it.title.lowercase() }
            SortField.ARTIST -> list.sortedBy { it.artistName.lowercase() }
            SortField.DATE, SortField.DEFAULT -> when { list.all { it.additionOrder != null } -> list.sortedByDescending { it.additionOrder }; list.all { !it.dateAdded.isNullOrBlank() } -> list.sortedBy { it.dateAdded }; else -> list }
            else -> list
        }
        return if (s.sort.dir == SortDir.DESC && (s.sort.field == SortField.NAME || s.sort.field == SortField.ARTIST || (list.all { it.additionOrder != null } || list.all { !it.dateAdded.isNullOrBlank() }))) sorted.reversed() else sorted
    }

    fun sortedSongs(): List<Song> {
        val s = _state.value
        if (s.sort.field == SortField.DATE || s.sort.field == SortField.DEFAULT) {
            // Standard library responses may omit addition dates. Preserve the API's
            // original order in that case instead of presenting a reversed alphabet as dates.
            if (!s.songsNewestFirst && !hasSongAdditionDates()) return s.songs
            val newestFirst = if (s.songsNewestFirst) s.songs else s.songs.sortedByDescending { it.dateAdded }
            return if (s.sort.dir == SortDir.ASC) newestFirst.reversed() else newestFirst
        }
        val sorted = when (s.sort.field) {
            SortField.ARTIST -> s.songs.sortedBy { it.artistName.lowercase() }
            else -> s.songs.sortedBy { it.title.lowercase() }
        }
        return if (s.sort.dir == SortDir.DESC) sorted.reversed() else sorted
    }

    fun hasSongAdditionDates(): Boolean = _state.value.songs.let { songs ->
        songs.isNotEmpty() && songs.all { !it.dateAdded.isNullOrBlank() }
    }

    fun sortedArtists(): List<Artist> {
        val s = _state.value
        val list = s.artists
        val sorted = when (s.sort.field) {
            SortField.NAME -> list.sortedBy { it.name.lowercase() }
            else           -> list
        }
        return if (s.sort.dir == SortDir.DESC) sorted.reversed() else sorted
    }

    fun playPlaylist(id: String, playerVm: PlayerViewModel): kotlinx.coroutines.Job {
        val request = playerVm.reservePlaybackRequest()
        return viewModelScope.launch {
            val songs = repo.getPlaylistTracks(id).getOrDefault(emptyList())
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (songs.isNotEmpty()) playerVm.playAlbumIfCurrent(request, songs)
        }
    }

    /** Warm the initial visible category after authorization without pretending the page is open. */
    fun preloadAfterLogin() {
        refresh()
        if (!visible) selectSection(activeSection, markVisible = false)
    }

    /** Explicit refresh invalidates all categories; hidden categories load on their next entry. */
    fun refresh() {
        refreshPolicy.invalidate()
        ++loadGeneration
        loadJob?.cancel()
        loadingSection = null
        _state.update { it.copy(isLoading = false) }
        val account = mutPrefs.getMUT()
        if (account != loadedAccount) resetAccount(account)
        if (visible) selectSection(activeSection)
    }
}
