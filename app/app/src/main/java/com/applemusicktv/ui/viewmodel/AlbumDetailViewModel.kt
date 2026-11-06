package com.applemusicktv.ui.viewmodel

import com.applemusicktv.data.cache.AppDiskCache
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.applemusicktv.data.model.Album
import com.applemusicktv.data.model.Song
import com.applemusicktv.data.repository.MusicRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AlbumDetailUiState(
    val isLoading:     Boolean     = true,
    val album:         Album?      = null,
    val tracks:        List<Song>  = emptyList(),
    val relatedAlbums: List<Album> = emptyList(),
    val motionUrl:     String?     = null,
    val error:         String?     = null,
)

@HiltViewModel
class AlbumDetailViewModel @Inject constructor(
    private val repo: MusicRepository,
    private val moshi: Moshi,
    @ApplicationContext private val context: Context,
    savedState: SavedStateHandle,
) : ViewModel() {

    private val albumId = savedState.get<String>("albumId") ?: ""
    private val _state  = MutableStateFlow(AlbumDetailUiState())
    val state: StateFlow<AlbumDetailUiState> = _state

    private val prefs = AppDiskCache.get(context).content("album_detail_cache")
    private val songListType  = Types.newParameterizedType(List::class.java, Song::class.java)
    private val albumListType = Types.newParameterizedType(List::class.java, Album::class.java)

    init { if (albumId.isNotEmpty()) load() }

    private fun load() {
        viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) { readCache(albumId) }
            if (cached != null) _state.value = cached.copy(isLoading = true)
            try {
                val albumD   = async { repo.getAlbum(albumId) }
                val tracksD  = async { repo.getAlbumTracks(albumId) }
                val relatedD = async { repo.getRelatedAlbums(albumId) }
                val tracks = tracksD.await().getOrDefault(emptyList())
                val motionUrl = tracks.firstOrNull()?.id?.let { repo.getMotion(it).getOrNull() }
                val newState = AlbumDetailUiState(
                    isLoading     = false,
                    album         = albumD.await().getOrNull(),
                    tracks        = tracks,
                    relatedAlbums = relatedD.await().getOrDefault(emptyList()),
                    motionUrl     = motionUrl,
                )
                _state.value = newState
                withContext(Dispatchers.IO) { writeCache(albumId, newState) }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    private fun readCache(id: String): AlbumDetailUiState? {
        return try {
            val tracksJson  = prefs.getString("tracks_$id") ?: return null
            val albumJson   = prefs.getString("album_$id") ?: return null
            val relatedJson = prefs.getString("related_$id")
            AlbumDetailUiState(
                isLoading     = false,
                album         = moshi.adapter(Album::class.java).fromJson(albumJson)?.takeIf { com.applemusicktv.data.VideoPlaybackPolicy.allows(it.id, it.type) },
                tracks        = com.applemusicktv.data.VideoPlaybackPolicy.audio(moshi.adapter<List<Song>>(songListType).fromJson(tracksJson) ?: emptyList()),
                relatedAlbums = (relatedJson?.let { moshi.adapter<List<Album>>(albumListType).fromJson(it) } ?: emptyList()).filter { com.applemusicktv.data.VideoPlaybackPolicy.allows(it.id, it.type) },
            )
        } catch (_: Exception) { null }
    }

    private fun writeCache(id: String, state: AlbumDetailUiState) {
        try {
            state.album?.let { prefs.putString("album_$id", moshi.adapter(Album::class.java).toJson(it)) }
            prefs.putString("tracks_$id", moshi.adapter<List<Song>>(songListType).toJson(state.tracks))
            prefs.putString("related_$id", moshi.adapter<List<Album>>(albumListType).toJson(state.relatedAlbums))
        } catch (_: Exception) {}
    }
}
