package com.applemusicktv.ui.viewmodel

import com.applemusicktv.data.cache.AppDiskCache
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.applemusicktv.data.model.Song
import com.applemusicktv.data.repository.MusicRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlaylistDetailState(
    val loading: Boolean = false,
    val tracks: List<Song> = emptyList(),
    val artworkUrl: String? = null,
    val curatorName: String = "",
    val motionUrl: String? = null,
)

@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    private val repo: MusicRepository,
    private val moshi: Moshi,
    @ApplicationContext private val context: Context,
    private val videoQueue: com.applemusicktv.media.VideoQueue,
) : ViewModel() {

    private val _state = MutableStateFlow(PlaylistDetailState())
    val state: StateFlow<PlaylistDetailState> = _state

    /** Seed the shared video queue with every music video in this playlist, positioned at
     *  [current], so the video player's prev/next walk the list like the audio queue. */
    fun seedVideoQueue(current: Song) {
        val vids = _state.value.tracks.filter { it.isMusicVideo }
            .map { com.applemusicktv.media.VideoItem(it.id, it.title, it.artistName) }
        val idx = vids.indexOfFirst { it.id == current.id }.coerceAtLeast(0)
        videoQueue.set(vids, idx)
    }

    private var loadedId: String? = null
    private val prefs = AppDiskCache.get(context).content("playlist_detail_cache")
    private val listType = Types.newParameterizedType(List::class.java, Song::class.java)

    fun load(playlistId: String, initialArtworkUrl: String? = null) {
        if (loadedId == playlistId && _state.value.tracks.isNotEmpty()) return
        loadedId = playlistId

        viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) { readCache(playlistId) }
            if (loadedId != playlistId) return@launch
            if (cached != null) {
                _state.value = cached.copy(loading = true, artworkUrl = initialArtworkUrl ?: cached.artworkUrl)
            } else {
                _state.value = PlaylistDetailState(loading = true, artworkUrl = initialArtworkUrl)
            }

            launch {
                repo.getPlaylistMotion(playlistId).onSuccess { url ->
                    if (loadedId == playlistId && url != null)
                        _state.update { it.copy(motionUrl = url) }
                }
            }

            launch {
                repo.getPlaylistTracks(playlistId).onSuccess { songs ->
                    if (loadedId != playlistId) return@onSuccess
                    val newState = PlaylistDetailState(
                        tracks     = songs,
                        artworkUrl = initialArtworkUrl ?: songs.firstOrNull()?.artworkUrl,
                        curatorName = _state.value.curatorName,
                        motionUrl  = _state.value.motionUrl,
                    )
                    _state.value = newState
                    withContext(Dispatchers.IO) { writeCache(playlistId, newState) }
                }.onFailure {
                    if (loadedId == playlistId) _state.update { it.copy(loading = false) }
                }
            }
        }
    }

    private fun readCache(playlistId: String): PlaylistDetailState? {
        val json = prefs.getString("tracks_$playlistId") ?: return null
        return try {
            val tracks = moshi.adapter<List<Song>>(listType).fromJson(json) ?: return null
            PlaylistDetailState(
                tracks     = com.applemusicktv.data.VideoPlaybackPolicy.audio(tracks),
                artworkUrl = prefs.getString("art_$playlistId"),
                curatorName = prefs.getString("curator_$playlistId") ?: "",
            )
        } catch (_: Exception) { null }
    }

    private fun writeCache(playlistId: String, state: PlaylistDetailState) {
        try {
            prefs.putString("tracks_$playlistId", moshi.adapter<List<Song>>(listType).toJson(state.tracks))
            prefs.putString("art_$playlistId", state.artworkUrl)
            prefs.putString("curator_$playlistId", state.curatorName)
        } catch (_: Exception) {}
    }
}
