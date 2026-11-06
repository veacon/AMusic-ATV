package com.applemusicktv.ui.screens

import com.applemusicktv.i18n.tr
import com.applemusicktv.R

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import com.applemusicktv.ui.navigation.ReturnLazyColumn as LazyColumn
import com.applemusicktv.ui.navigation.ReturnLazyRow as LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import com.applemusicktv.ui.components.returnToTabOnUp
import com.applemusicktv.ui.components.rememberTabAwareListState
import com.applemusicktv.ui.viewmodel.CategoryUiState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.material3.CircularProgressIndicator
import androidx.tv.material3.Text
import com.applemusicktv.ui.components.pageHorizontalInset
import com.applemusicktv.ui.components.FadeThrough
import com.applemusicktv.ui.components.TouchSurface as Surface
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.applemusicktv.data.model.Album
import com.applemusicktv.ui.components.AlbumCard
import com.applemusicktv.ui.viewmodel.CategoryViewModel

/** Editorial category page (Apple "multiroom") — a title, a blurb, and several playlist/album shelves. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CategoryScreen(
    onAlbumClick: (String) -> Unit = {},
    onPlaylistClick: (id: String, name: String, artworkUrl: String) -> Unit = { _, _, _ -> },
    /** A nested curator/category tile (a genre/mood room lists apple-curators) opens another page. */
    onCuratorClick: (id: String) -> Unit = {},
    onArtistClick: (id: String) -> Unit = {},
    /** Present for grouping pages (Music Videos) so video shelves can play in the video player. */
    playerVm: com.applemusicktv.ui.viewmodel.PlayerViewModel? = null,
    modifier: Modifier = Modifier,
    onReturnToTab: (() -> Unit)? = null,
    tabBarFocused: Boolean = false,
) {
    val vm: CategoryViewModel = hiltViewModel()
    val state by vm.state.collectAsState()

    CategoryContent(state, onAlbumClick, onPlaylistClick, onCuratorClick, onArtistClick, playerVm,
        modifier, onReturnToTab, tabBarFocused)
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun CategoryContent(
    state: CategoryUiState,
    onAlbumClick: (String) -> Unit = {},
    onPlaylistClick: (String, String, String) -> Unit = { _, _, _ -> },
    onCuratorClick: (String) -> Unit = {},
    onArtistClick: (String) -> Unit = {},
    playerVm: com.applemusicktv.ui.viewmodel.PlayerViewModel? = null,
    modifier: Modifier = Modifier,
    onReturnToTab: (() -> Unit)? = null,
    tabBarFocused: Boolean = false,
) {
    val listState = rememberTabAwareListState(tabBarFocused)
    FadeThrough(state, modifier = modifier.fillMaxSize(), contentKey = { when { it.isLoading -> "loading"; it.error != null -> "error"; else -> "content" } }) { state ->
        Box(Modifier.fillMaxSize().background(com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFFFFFFF), Color(0xFF101012)))) {
            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFFFA233B))
                }
                state.error != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(tr(R.string.msg_166), color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)), fontSize = 15.sp)
                }
                state.sections.all {it.albums.isEmpty() && it.videos.isEmpty()} -> Box(Modifier.fillMaxSize(),Alignment.Center) {
                    Text(tr(R.string.msg_167),color=com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)),fontSize=15.sp)
                }
                // contentPadding (not Modifier.padding) so a focused card's scaled border and
                // glow can bleed into the margin instead of being clipped at the edges.
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(bottom = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    item {
                        CategoryHeader(state.title, state.description, state.artworkUrl)
                    }
                    fun norm(s: String) = s.filter { it.isLetterOrDigit() }.lowercase().removePrefix("applemusic").removePrefix("apple")
                    val pageTitleNorm = norm(state.title)
                    val firstContent = state.sections.indexOfFirst { it.albums.isNotEmpty() || (it.videos.isNotEmpty() && playerVm != null) }
                    itemsIndexed(state.sections, key = { index, section -> "$index:${section.title}" }) { index, section ->
                        Column(Modifier.fillMaxWidth()
                            .returnToTabOnUp(if (index == firstContent) onReturnToTab else null)) {
                            // A shelf named after the page itself (curator/room pages do this) would print
                            // the title twice — hide the shelf header when it matches the page title after
                            // normalising away the "Apple Music" prefix and case/whitespace.
                            if (norm(section.title) != pageTitleNorm) {
                                Text(section.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                                    color = com.applemusicktv.ui.theme.AppPalette.primary, modifier = Modifier.padding(bottom = 10.dp, start = pageHorizontalInset, end = pageHorizontalInset))
                            }
                            if (section.videos.isNotEmpty() && playerVm != null) {
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    contentPadding = PaddingValues(horizontal = pageHorizontalInset, vertical = 12.dp),
                                ) {
                                    items(section.videos.size) { idx ->
                                        val v = section.videos[idx]
                                        val art = (v.artworkUrl ?: "").replace("{w}", "480").replace("{h}", "270").replace("{f}", "jpg")
                                        Surface(
                                            onClick = { playerVm.playVideos(section.videos, idx) },
                                            // RectangleShape so the surface never clips the title/artist
                                            // text below the thumbnail (a rounded surface rounded them off).
                                            shape = ClickableSurfaceDefaults.shape(androidx.compose.ui.graphics.RectangleShape),
                                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
                                            colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
                                        ) {
                                            Column(Modifier.width(230.dp).padding(bottom = 8.dp)) {
                                                Box(Modifier.width(230.dp).height(129.dp).clip(RoundedCornerShape(10.dp)).background(com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFF5F5F7), Color(0xFF222225)))) {
                                                    if (v.artworkUrl != null) AsyncImage(model = art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                                }
                                                Spacer(Modifier.height(6.dp))
                                                Text(v.title, fontSize = 12.5.sp, color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF1D1D1F), Color(0xFFF5F5F7)), maxLines = 1, fontWeight = FontWeight.SemiBold)
                                                Text(v.artistName, fontSize = 10.5.sp, color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)), maxLines = 1)
                                            }
                                        }
                                    }
                                }
                                return@Column
                            }
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                // Room on all sides so the focus glow/scale never clips.
                                contentPadding = PaddingValues(horizontal = pageHorizontalInset, vertical = 12.dp),
                            ) {
                                items(section.albums, key = { it.id }) { dto ->
                                    val isPlaylist = dto.id.startsWith("pl.") || dto.id.startsWith("p.")
                                    val isCurator = dto.id.startsWith("ac-") || dto.id.startsWith("c-") || dto.id.startsWith("mr-")
                                    val isStation = dto.id.startsWith("ra.")
                                    val isArtist = dto.type == "artists" || dto.id.startsWith("r.")
                                    val isSong = dto.type == "songs"
                                    AlbumCard(
                                        album = Album(
                                            id = dto.id, title = dto.title, artistName = dto.artistName,
                                            artworkUrl = dto.artworkUrl, artworkBgColor = dto.artworkBgColor,
                                            type = dto.type,
                                        ),
                                        size = 150,
                                        onClick = {
                                            when {
                                                isCurator  -> onCuratorClick(dto.id)
                                                isStation  -> playerVm?.playStation(dto.id, (dto.artworkUrl ?: "").replace("{w}", "600").replace("{h}", "600").replace("{f}", "jpg"), dto.title)
                                                isArtist   -> onArtistClick(dto.id)
                                                isSong     -> playerVm?.playSong(Album(id = dto.id, title = dto.title, artistName = dto.artistName, artworkUrl = dto.artworkUrl, artworkBgColor = dto.artworkBgColor, type = "songs"))
                                                isPlaylist -> onPlaylistClick(dto.id, dto.title, (dto.artworkUrl ?: "").replace("{w}", "500").replace("{h}", "500").replace("{f}", "jpg"))
                                                else       -> onAlbumClick(dto.id)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(20.dp)) }
                }
            }
        }
    }
}


@Composable
internal fun CategoryHeader(title: String, description: String?, artwork: String?) {
    Column {
        if (!artwork.isNullOrBlank()) {
            Box(Modifier.fillMaxWidth().height(300.dp)) {
                AsyncImage(model = artwork.replace("{w}", "1200").replace("{h}", "800").replace("{f}", "jpg"),
                    contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x33000000), com.applemusicktv.ui.theme.AppPalette.background))))
            }
        } else Spacer(Modifier.height(112.dp))
        Column(Modifier.padding(horizontal = pageHorizontalInset), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = com.applemusicktv.ui.theme.AppPalette.primary)
            description?.takeIf { it.isNotBlank() }?.let {
                Text(it, fontSize = 13.sp, lineHeight = 19.sp, color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)))
            }
        }
    }
}
