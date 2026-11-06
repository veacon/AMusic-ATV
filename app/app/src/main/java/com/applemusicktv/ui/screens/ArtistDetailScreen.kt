package com.applemusicktv.ui.screens

import com.applemusicktv.i18n.tr
import com.applemusicktv.R

import com.applemusicktv.ui.components.focusedArtworkBorder
import com.applemusicktv.ui.components.pageHorizontalInset
import com.applemusicktv.ui.components.FadeThrough
import com.applemusicktv.ui.components.TouchSurface as Surface

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import com.applemusicktv.ui.navigation.ReturnLazyColumn as LazyColumn
import com.applemusicktv.ui.components.FirstTabReturnItem
import com.applemusicktv.ui.components.tabReturnItem
import com.applemusicktv.ui.components.rememberTabAwareListState
import com.applemusicktv.ui.navigation.ReturnLazyRow as LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.*
import coil.compose.AsyncImage
import com.applemusicktv.data.model.Album
import com.applemusicktv.data.model.Song
import com.applemusicktv.ui.components.AlbumCard
import com.applemusicktv.ui.viewmodel.ArtistDetailViewModel
import com.applemusicktv.ui.viewmodel.PlayerViewModel

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ArtistDetailScreen(
    playerVm: PlayerViewModel,
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    onReturnToTab: (() -> Unit)? = null,
    tabBarFocused: Boolean = false,
) {
    val vm: ArtistDetailViewModel = hiltViewModel()
    val state by vm.state.collectAsState()
    val listState = rememberTabAwareListState(tabBarFocused)
    val playEntryFocus = com.applemusicktv.ui.navigation.rememberDetailPlayEntryFocus(
        ready = !state.isLoading && state.error == null && state.topSongs.isNotEmpty(),
        loading = state.isLoading,
        beforeFocus = { listState.scrollToItem(if (state.bio != null) 3 else 2) },
    )

    FadeThrough(state, modifier = modifier.fillMaxSize(), contentKey = { when { it.isLoading -> "loading"; it.error != null -> "error"; else -> "content" } }) { state ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().background(com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFFFFFFF), Color(0xFF101012))), Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFFFA233B))
            }
            return@FadeThrough
        }
        if (state.error != null) {
            Box(Modifier.fillMaxSize().background(com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFFFFFFF), Color(0xFF101012))), Alignment.Center) {
                Text(tr(R.string.msg_096, com.applemusicktv.i18n.AppLanguage.message(state.error)), color = Color(0xFFFF3B30), fontSize = 14.sp)
            }
            return@FadeThrough
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().background(com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFFFFFFF), Color(0xFF101012))),
            state = listState,
            contentPadding = PaddingValues(bottom = 60.dp),
        ) {
            val firstInteractive = FirstTabReturnItem(onReturnToTab)
            // Hero header
            item {
                Box(Modifier.fillMaxWidth().height(300.dp)) {
                    if (state.artworkUrl != null) {
                        AsyncImage(
                            model = state.artworkUrl?.replace("{w}", "1200")?.replace("{h}", "1200")?.replace("{f}", "jpg"),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(listOf(Color(0x33000000), com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFFFFFFF), Color(0xFF101012)))),
                        ),
                    )
                    Column(Modifier.align(Alignment.BottomStart).padding(horizontal = pageHorizontalInset, vertical = 48.dp)) {
                        Text(state.name, fontSize = 44.sp, fontWeight = FontWeight.Bold, color = com.applemusicktv.ui.theme.AppPalette.primary, maxLines = 2)
                        if (state.genres.isNotEmpty())
                            Text(state.genres.joinToString(" • "), fontSize = 13.sp, color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)))
                    }
                }
            }

            if (state.bio != null) {
                item {
                    Text(
                        state.bio!!,
                        fontSize = 13.sp, color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)), lineHeight = 20.sp, maxLines = 3,
                        modifier = Modifier.padding(horizontal = pageHorizontalInset, vertical = 12.dp),
                    )
                }
            }

            // Top songs
            if (state.topSongs.isNotEmpty()) {
                item { SectionTitle(tr(R.string.msg_097)) }
                tabReturnItem(firstInteractive.claim()) {
                    Row(Modifier.padding(start = pageHorizontalInset, end = pageHorizontalInset, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton(com.applemusicktv.ui.components.Glyph.PLAY, tr(R.string.msg_001), Color(0xFFFA233B), modifier = playEntryFocus) { playerVm.playAlbum(state.topSongs, 0) }
                        PillButton(com.applemusicktv.ui.components.Glyph.SHUFFLE, tr(R.string.msg_098), com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D))) { playerVm.playAlbum(state.topSongs, state.topSongs.indices.randomOrNull() ?: 0, shuffle = true) }
                        PillButton(com.applemusicktv.ui.components.Glyph.RADIO, tr(R.string.msg_099), com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D))) { val request = playerVm.reserveStationRequest("artist-station:${state.name}", tr(R.string.msg_033, state.name), state.artworkUrl?.replace("{w}", "600")?.replace("{h}", "600")?.replace("{f}", "jpg")); val job = vm.playStation { songs -> playerVm.playAlbumIfCurrent(request, songs) }; playerVm.watchStationRequest(request, job) }
                    }
                }
                itemsIndexedTopSongs(state.topSongs, playerVm)
            }

            // Music Videos (Essentials) — 16:9 thumbnails, play via the MV path.
            if (state.musicVideos.isNotEmpty()) {
                item { SectionTitle(tr(R.string.msg_100)) }
                tabReturnItem(firstInteractive.claim()) {
                    LazyRow(contentPadding = PaddingValues(horizontal = pageHorizontalInset), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        itemsIndexed(state.musicVideos, key = { _, v -> v.id }) { i, v ->
                            ArtistVideoCard(v) { playerVm.playAlbum(state.musicVideos, i) }
                        }
                    }
                }
            }

            state.latestRelease?.let { latest ->
                item { SectionTitle(tr(R.string.msg_101)) }
                tabReturnItem(firstInteractive.claim()) {
                    Box(Modifier.padding(horizontal = pageHorizontalInset, vertical = 8.dp)) {
                        AlbumCard(album = latest, size = 180, onClick = { onAlbumClick(latest.id) })
                    }
                }
            }

            if (state.albums.isNotEmpty()) {
                item { SectionTitle(tr(R.string.msg_070)) }
                tabReturnItem(firstInteractive.claim()) { AlbumRow(state.albums, onAlbumClick) }
            }
            if (state.featuredAlbums.isNotEmpty()) {
                item { SectionTitle(tr(R.string.msg_102)) }
                tabReturnItem(firstInteractive.claim()) { AlbumRow(state.featuredAlbums, onAlbumClick) }
            }

            if (state.similarArtists.isNotEmpty()) {
                item { SectionTitle(tr(R.string.msg_103)) }
                tabReturnItem(firstInteractive.claim()) {
                    LazyRow(contentPadding = PaddingValues(horizontal = pageHorizontalInset), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        items(state.similarArtists, key = { it.id }) { a ->
                            Surface(
                                onClick = { onArtistClick(a.id) },
                                shape = ClickableSurfaceDefaults.shape(CircleShape),
                                colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFF5F5F7), Color(0xFF222225))),
                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
                            ) {
                                Column(Modifier.width(120.dp).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(Modifier.size(100.dp).focusedArtworkBorder(CircleShape).clip(CircleShape).background(com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D)))) {
                                        if (a.artworkUrl != null) AsyncImage(
                                            model = a.artworkUrl.replace("{w}", "200").replace("{h}", "200").replace("{f}", "jpg"),
                                            contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Text(a.name, fontSize = 12.sp, color = com.applemusicktv.ui.theme.AppPalette.primary, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = com.applemusicktv.ui.theme.AppPalette.primary,
        modifier = Modifier.padding(start = pageHorizontalInset, end = pageHorizontalInset, top = 24.dp, bottom = 12.dp))
}

/** 16:9 music-video thumbnail with title/artist below — matches the video shelves elsewhere. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ArtistVideoCard(video: Song, onPlay: () -> Unit) {
    Surface(
        onClick = onPlay,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        modifier = Modifier.width(300.dp),
    ) {
        Column {
            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                    .focusedArtworkBorder(RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp)).background(com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D)))
            ) {
                if (video.artworkUrl != null) AsyncImage(
                    model = video.artworkUrl(600), contentDescription = null,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                )
            }
            Text(video.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = com.applemusicktv.ui.theme.AppPalette.primary,
                maxLines = 1, modifier = Modifier.padding(top = 8.dp))
            Text(video.artistName, fontSize = 12.sp, color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)), maxLines = 1,
                modifier = Modifier.padding(top = 2.dp, bottom = 4.dp))
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AlbumRow(albums: List<Album>, onAlbumClick: (String) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = pageHorizontalInset), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        items(albums, key = { it.id }) { album ->
            AlbumCard(album = album, size = 150, onClick = { onAlbumClick(album.id) })
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
private fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexedTopSongs(
    songs: List<Song>,
    playerVm: PlayerViewModel,
) {
    val returnKeys=com.applemusicktv.ui.navigation.occurrenceKeys(songs.map { it.id })
    items(songs.size, key = { returnKeys[it] }) { idx ->
        val song = songs[idx]
        Surface(
            onClick = { playerVm.playAlbum(songs, idx) },
            onLongClick = { playerVm.addToQueue(song) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = pageHorizontalInset, vertical = 1.dp),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
            colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFF5F5F7), Color(0xFF222225))),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("${idx + 1}", fontSize = 13.sp, color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)), modifier = Modifier.width(28.dp))
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)).background(com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D)))) {
                    if (song.artworkUrl != null) AsyncImage(model = song.artworkUrl(88), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Column(Modifier.weight(1f)) {
                    Text(song.title, fontSize = 14.sp, color = com.applemusicktv.ui.theme.AppPalette.primary, maxLines = 1, fontWeight = FontWeight.Medium)
                    Text(song.albumName, fontSize = 11.sp, color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)), maxLines = 1)
                }
                Text(song.durationFormatted, fontSize = 11.sp, color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF8E8E93), Color(0xFFA3A3AA)))
            }
        }
    }
}
