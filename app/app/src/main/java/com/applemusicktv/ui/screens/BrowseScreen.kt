package com.applemusicktv.ui.screens

import com.applemusicktv.i18n.tr
import com.applemusicktv.R

import com.applemusicktv.ui.components.focusedArtworkBorder
import com.applemusicktv.data.cache.AppDiskCache
import android.content.Context
import com.applemusicktv.ui.components.pageHorizontalInset
import com.applemusicktv.ui.components.FadeThrough
import com.applemusicktv.ui.components.TouchSurface as Surface

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import com.applemusicktv.ui.navigation.ReturnLazyColumn as LazyColumn
import com.applemusicktv.ui.navigation.ReturnLazyRow as LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import com.applemusicktv.ui.components.returnToTabOnUp
import com.applemusicktv.ui.components.rememberTabAwareListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.applemusicktv.data.repository.CategoryGroup
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.*
import com.applemusicktv.ui.components.AlbumCard
import com.applemusicktv.ui.viewmodel.HomeSection
import com.applemusicktv.data.MutPreferences
import androidx.compose.foundation.shape.RoundedCornerShape
import com.applemusicktv.ui.viewmodel.PlayerViewModel
import com.applemusicktv.data.repository.MusicRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.core.content.edit
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import javax.inject.Inject

/** A Browse shelf — albums/playlists OR a music-video row (mirrors Apple's Browse page). */
data class BrowseShelf(
    val title: String,
    val albums: List<com.applemusicktv.data.model.Album> = emptyList(),
    val videos: List<com.applemusicktv.data.model.Song> = emptyList(),
    /** Apple editorial room behind this shelf — when set the row ends with a "更多" see-all card. */
    val roomId: String? = null,
    /** Presentation hint: "spotlight" (big landscape editorial cards) | null (normal square shelf). */
    val style: String? = null,
)

data class BrowseUiState(
    val isLoading: Boolean           = true,
    val error:     String?           = null,
    val shelves:   List<BrowseShelf> = emptyList(),
    /** Suppress the empty-feed skeleton until the saved shelves have been checked. */
    val isRestoringCache: Boolean    = false,
)

internal object BrowseFeedPolicy {
    fun sanitize(shelf: BrowseShelf) = shelf.copy(albums = shelf.albums.filter { com.applemusicktv.data.VideoPlaybackPolicy.allows(it.id, it.type) }, videos = emptyList())
    fun hasContent(shelf: BrowseShelf): Boolean = sanitize(shelf).albums.isNotEmpty()

    fun shouldShowSkeleton(state: BrowseUiState): Boolean =
        !state.isRestoringCache && state.isLoading && state.shelves.isEmpty()

    /** Never replace a usable saved feed with an empty or malformed refresh response. */
    fun select(fresh: List<BrowseShelf>, cached: List<BrowseShelf>): List<BrowseShelf> {
        val usableFresh = fresh.map(::sanitize).filter(::hasContent)
        return usableFresh.ifEmpty { cached.map(::sanitize).filter(::hasContent) }
    }
}

@HiltViewModel
class BrowseViewModel @Inject constructor(
    private val repo: MusicRepository,
    @ApplicationContext context: Context,
    moshi: Moshi,
    private val mutPrefs: MutPreferences,
) : ViewModel() {
    private val _state = MutableStateFlow(BrowseUiState(isRestoringCache = true))
    val state: StateFlow<BrowseUiState> = _state
    private val prefs = AppDiskCache.get(context).content("browse_cache")
    private val shelfAdapter = moshi.adapter<List<BrowseShelf>>(
        Types.newParameterizedType(List::class.java, BrowseShelf::class.java),
    )
    private var loadJob: kotlinx.coroutines.Job? = null
    private var loadedKey = cacheKey(mutPrefs.getMUT())

    init {
        // Restore this account's last successful shelves before refreshing. Read/parse away from
        // the UI thread, but sequence the network call after the cache snapshot to avoid a race.
        loadJob = viewModelScope.launch {
            val key = cacheKey(mutPrefs.getMUT())
            val cached = withContext(Dispatchers.IO) {
                runCatching { prefs.getString(key)?.let(shelfAdapter::fromJson).orEmpty() }
                    .getOrDefault(emptyList())
            }.map(BrowseFeedPolicy::sanitize).filter(BrowseFeedPolicy::hasContent)
            _state.value = BrowseUiState(isLoading = true, shelves = cached, isRestoringCache = false)
            fetch(key, cached)
        }
    }

    fun load(force: Boolean = false) {
        if (!force && _state.value.isLoading) return
        loadJob?.cancel()
        val key = cacheKey(mutPrefs.getMUT())
        val cached = if (loadedKey == key) _state.value.shelves.filter(BrowseFeedPolicy::hasContent) else emptyList()
        loadedKey = key
        _state.value = BrowseUiState(isLoading = true, shelves = cached)
        loadJob = viewModelScope.launch {
            fetch(key, cached)
        }
    }

    private suspend fun fetch(key: String, cached: List<BrowseShelf>) {
        val result = repo.getBrowse()
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if (key != cacheKey(mutPrefs.getMUT())) return
        result
            .onSuccess { resp ->
                val fresh = resp.sections.map { s ->
                    BrowseShelf(s.title, s.albums.map(repo::albumFromDto), s.videos.map(repo::songFromDto), s.roomId, s.style)
                }.map(BrowseFeedPolicy::sanitize).filter(BrowseFeedPolicy::hasContent)
                val visible = BrowseFeedPolicy.select(fresh, cached)
                _state.value = BrowseUiState(isLoading = false, shelves = visible)
                if (fresh.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        runCatching { prefs.putString(key, shelfAdapter.toJson(fresh)) }
                    }
                }
            }
            .onFailure { failure ->
                _state.value = if (cached.isNotEmpty()) {
                    BrowseUiState(isLoading = false, shelves = cached)
                } else {
                    BrowseUiState(isLoading = false, error = failure.message)
                }
            }
    }

    /** Store a one-way account fingerprint in the key; never persist the Music User Token itself. */
    private fun cacheKey(mut: String): String {
        val account = if (mut.isBlank()) "guest" else MessageDigest.getInstance("SHA-256")
            .digest(mut.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return "shelves_audio_v75_$account"
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun BrowseScreen(
    playerVm: PlayerViewModel,
    onAlbumClick: (String) -> Unit = {},
    onPlaylistClick: (id: String, name: String, artworkUrl: String) -> Unit = { _, _, _ -> },
    onGenreClick: (id: String, name: String) -> Unit = { _, _ -> },
    /** A curator/radio-show card (id prefixed ac-/c-/mr-) opens its category page. */
    onCuratorClick: (id: String) -> Unit = {},
    /** Opens a shelf's full editorial room (the trailing "更多" card). */
    onSeeAll: (roomId: String) -> Unit = {},
    modifier: Modifier = Modifier,
    onReturnToTab: (() -> Unit)? = null,
    tabBarFocused: Boolean = false,
    vm: BrowseViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    val listState = rememberTabAwareListState(tabBarFocused)

    FadeThrough(state, modifier = modifier.fillMaxSize(), contentKey = { when { it.isRestoringCache -> "content"; it.isLoading && it.shelves.isEmpty() -> "loading"; it.error != null -> "error"; it.shelves.isEmpty() -> "empty"; else -> "content" } }) { state ->
        if (state.isRestoringCache) {
            Box(Modifier.fillMaxSize())
            return@FadeThrough
        }

        if (BrowseFeedPolicy.shouldShowSkeleton(state)) {
            Box(Modifier.fillMaxSize()) { com.applemusicktv.ui.components.ShelfSkeleton(topPadding = 100.dp) }
            return@FadeThrough
        }

        if (state.error != null) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(tr(R.string.msg_021), color = Color(0xFFFF453A), fontSize = 16.sp)
                    Text(state.error ?: "", color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF8E8E93), Color(0xFFA3A3AA)), fontSize = 12.sp)
                    Spacer(Modifier.height(16.dp))
                    Surface(onClick = { vm.load() }, colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFFFA233B))) {
                        Text(tr(R.string.msg_138), color = Color.White, modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp))
                    }
                }
            }
            return@FadeThrough
        }

        if (state.shelves.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(tr(R.string.msg_092), color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF8E8E93), Color(0xFFA3A3AA)), fontSize = 14.sp)
            }
            return@FadeThrough
        }

        val firstContentRow = state.shelves.indexOfFirst { it.albums.isNotEmpty() || it.videos.isNotEmpty() }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 100.dp, bottom = 102.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            itemsIndexed(state.shelves, key = { _, item -> item.title }) { index, shelf ->
                val firstRow = index == firstContentRow
                Box(Modifier.returnToTabOnUp(if (firstRow) onReturnToTab else null)) {
                    when {
                        shelf.style == "spotlight" && shelf.albums.isNotEmpty() ->
                            SpotlightRow(com.applemusicktv.i18n.AppLanguage.heading(shelf.title), shelf.albums, onAlbumClick, onPlaylistClick, onCuratorClick, playerVm)
                        shelf.videos.isNotEmpty() -> BrowseVideoRow(com.applemusicktv.i18n.AppLanguage.heading(shelf.title), shelf.videos, playerVm)
                        else -> BrowseRow(com.applemusicktv.i18n.AppLanguage.heading(shelf.title), shelf.albums, onAlbumClick, onPlaylistClick, playerVm,
                            shelf.roomId, onSeeAll, onCuratorClick)
                    }
                }
            }
        }
    }
}

/** The "新内容" spotlight row: big landscape editorial cards with tagline + title over the wide art. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SpotlightRow(
    title: String,
    albums: List<com.applemusicktv.data.model.Album>,
    onAlbumClick: (String) -> Unit,
    onPlaylistClick: (id: String, name: String, artworkUrl: String) -> Unit,
    onCuratorClick: (id: String) -> Unit,
    playerVm: PlayerViewModel?,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = com.applemusicktv.ui.theme.AppPalette.primary,
            modifier = Modifier.padding(start = pageHorizontalInset, end = pageHorizontalInset, bottom = 14.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = pageHorizontalInset, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(albums, key = { it.id }, contentType = { "spotlight" }) { album ->
                val isPlaylist = album.id.startsWith("pl.") || album.id.startsWith("p.")
                val isStation = album.id.startsWith("ra.")
                val isCurator = album.id.startsWith("ac-") || album.id.startsWith("c-") || album.id.startsWith("mr-")
                val isSong = album.type == "songs"
                com.applemusicktv.ui.components.SpotlightHeroCard(album = album, width = 360, onClick = {
                    when {
                        isCurator -> onCuratorClick(album.id)
                        isStation -> playerVm?.playStation(album.id, album.artworkUrl(600), album.title)
                        isPlaylist -> onPlaylistClick(album.id, album.title, album.artworkUrl(500) ?: "")
                        isSong -> playerVm?.playSong(album)
                        else -> onAlbumClick(album.id)
                    }
                })
            }
        }
    }
}

/** Music-video shelf: wide 16:9 thumbnails; tapping plays the shelf into the fullscreen player. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun BrowseVideoRow(title: String, videos: List<com.applemusicktv.data.model.Song>, playerVm: PlayerViewModel) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = com.applemusicktv.ui.theme.AppPalette.primary,
            modifier = Modifier.padding(start = pageHorizontalInset, end = pageHorizontalInset, bottom = 14.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = pageHorizontalInset, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val returnKeys=com.applemusicktv.ui.navigation.occurrenceKeys(videos.map { it.id })
    items(videos.size, key = { returnKeys[it] }) { idx ->
                val v = videos[idx]
                Surface(
                    onClick = { playerVm.playAlbum(videos, idx) },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
                    colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
                ) {
                    Column(Modifier.width(230.dp)) {
                        Box(Modifier.width(230.dp).height(129.dp).focusedArtworkBorder(RoundedCornerShape(10.dp)).clip(RoundedCornerShape(10.dp)).background(com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFF5F5F7), Color(0xFF222225)))) {
                            if (v.artworkUrl != null)
                                AsyncImage(model = v.artworkUrl(480), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            Box(Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFFFA233B)).padding(horizontal = 5.dp, vertical = 1.dp)) {
                                Text("MV", fontSize = 9.sp, color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(v.title, fontSize = 12.5.sp, color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF1D1D1F), Color(0xFFF5F5F7)), maxLines = 1, fontWeight = FontWeight.Bold)
                        Text(v.artistName, fontSize = 10.5.sp, color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)), maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowseRow(
    title: String,
    albums: List<com.applemusicktv.data.model.Album>,
    onAlbumClick: (String) -> Unit,
    onPlaylistClick: (id: String, name: String, artworkUrl: String) -> Unit,
    playerVm: PlayerViewModel? = null,
    roomId: String? = null,
    onSeeAll: (String) -> Unit = {},
    onCuratorClick: (id: String) -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text       = title,
            fontSize   = 22.sp,
            fontWeight = FontWeight.Bold,             color      = com.applemusicktv.ui.theme.AppPalette.primary,
            modifier   = Modifier.padding(start = pageHorizontalInset, end = pageHorizontalInset, bottom = 14.dp),
        )
        LazyRow(
            contentPadding        = PaddingValues(horizontal = pageHorizontalInset, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(albums, key = { it.id }) { album ->
                val isPlaylist = album.id.startsWith("pl.") || album.id.startsWith("p.")
                val isStation = album.id.startsWith("ra.")
                val isCurator = album.id.startsWith("ac-") || album.id.startsWith("c-") || album.id.startsWith("mr-")
                val isSong = album.type == "songs"
                AlbumCard(album = album, size = 130, onClick = {
                    when {
                        isCurator -> onCuratorClick(album.id)
                        isStation -> playerVm?.playStation(album.id, album.artworkUrl(600), album.title)
                        isPlaylist -> onPlaylistClick(album.id, album.title, album.artworkUrl(500) ?: "")
                        isSong -> playerVm?.playSong(album)
                        else -> onAlbumClick(album.id)
                    }
                })
            }
            // "更多" — opens the shelf's full editorial room (e.g. Daily Top 100 → all 100 country
            // lists). Only shown when Apple actually gave this shelf a room.
            if (roomId != null) {
                item(key = "more-$roomId") { SeeAllCard(size = 130) { onSeeAll(roomId) } }
            }
        }
    }
}

/** The trailing "更多 →" card at the end of a shelf: a chevron over a subtle tile. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SeeAllCard(size: Int, onClick: () -> Unit) {
    Column(modifier = Modifier.width(size.dp)) {
        com.applemusicktv.ui.components.TouchCard(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            scale = androidx.tv.material3.CardDefaults.scale(focusedScale = 1.088f),
            glow = androidx.tv.material3.CardDefaults.glow(
                glow = androidx.tv.material3.Glow.None, focusedGlow = androidx.tv.material3.Glow.None,
                pressedGlow = androidx.tv.material3.Glow.None),
            border = androidx.tv.material3.CardDefaults.border(
                border = androidx.tv.material3.Border.None, focusedBorder = androidx.tv.material3.Border.None,
                pressedBorder = androidx.tv.material3.Border.None),
            colors = androidx.tv.material3.CardDefaults.colors(
                containerColor = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFF5F5F7), Color(0xFF222225)), focusedContainerColor = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D))),
            shape = androidx.tv.material3.CardDefaults.shape(
                androidx.compose.foundation.shape.RoundedCornerShape(12.dp)),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                com.applemusicktv.ui.components.Icon(
                    com.applemusicktv.ui.components.Glyph.NEXT, size = 26.dp, color = com.applemusicktv.ui.theme.AppPalette.primary)
            }
        }
        Text(tr(R.string.msg_139), fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF1D1D1F), Color(0xFFF5F5F7)),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
    }
}
