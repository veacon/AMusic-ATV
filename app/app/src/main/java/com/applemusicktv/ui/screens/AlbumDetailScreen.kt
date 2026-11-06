package com.applemusicktv.ui.screens

import com.applemusicktv.i18n.tr
import com.applemusicktv.ui.components.menuInitialFocus
import com.applemusicktv.R

import com.applemusicktv.ui.components.pageHorizontalInset
import com.applemusicktv.ui.components.FadeThrough
import com.applemusicktv.ui.components.TouchSurface as Surface

import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.applemusicktv.ui.navigation.ReturnLazyColumn as LazyColumn
import com.applemusicktv.ui.components.returnToTabOnUp
import com.applemusicktv.ui.components.rememberTabAwareListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.tv.material3.*
import coil.compose.AsyncImage
import com.applemusicktv.data.model.Song
import com.applemusicktv.ui.components.Glyph
import com.applemusicktv.ui.components.Icon
import com.applemusicktv.ui.viewmodel.AlbumDetailViewModel
import com.applemusicktv.ui.viewmodel.PlayerViewModel

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    playerVm: PlayerViewModel,
    onBack: () -> Unit = {},
    onArtistClick: (String) -> Unit = {},
    onAlbumClick: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    onReturnToTab: (() -> Unit)? = null,
    tabBarFocused: Boolean = false,
    vm: AlbumDetailViewModel = hiltViewModel(),
) {
    val tabAwareListState = rememberTabAwareListState(tabBarFocused)
    val state by vm.state.collectAsState()
    val playEntryFocus = com.applemusicktv.ui.navigation.rememberDetailPlayEntryFocus(
        ready = state.album != null && state.tracks.isNotEmpty(),
        loading = state.isLoading,
        beforeFocus = { tabAwareListState.scrollToItem(0) },
    )

    FadeThrough(state, modifier = modifier.fillMaxSize(), contentKey = { when { it.isLoading && it.tracks.isEmpty() -> "loading"; it.album == null -> "empty"; else -> "content" } }) { state ->
        if (state.isLoading && state.tracks.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFFFA233B))
            }
            return@FadeThrough
        }

        val album = state.album ?: return@FadeThrough
        var menuSong by remember { mutableStateOf<Song?>(null) }
    var menuFromRemote by remember { mutableStateOf(false) }
        var addToSong by remember { mutableStateOf<Song?>(null) }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val inset = pageHorizontalInset
        val contentWidth = (maxWidth - inset * 2).coerceAtLeast(0.dp)
        val artworkWidth = 260.dp.coerceAtMost(contentWidth * 0.38f)
        val columnGap = 48.dp.coerceAtMost(contentWidth * 0.06f)
        Row(modifier = Modifier.fillMaxSize().padding(start = inset, end = inset, bottom = 48.dp), horizontalArrangement = Arrangement.spacedBy(columnGap)) {
            // Left: artwork + info only
            Column(modifier = Modifier.width(artworkWidth).padding(top = 120.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFF0F0F4), Color(0xFF28282C)))) {
                    if (album.artworkUrl != null) {
                        AsyncImage(
                            model = album.artworkUrl(520),
                            contentDescription = album.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    state.motionUrl?.let { MotionCover(url = it, modifier = Modifier.fillMaxSize()) }
                }
                Spacer(Modifier.height(20.dp))
                Text(album.title, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    color = com.applemusicktv.ui.theme.AppPalette.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val albumArtistId = album.artistId ?: state.tracks.firstOrNull()?.artistId
                if (albumArtistId != null) {
                    Surface(
                        onClick = { onArtistClick(albumArtistId) },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(4.dp)),
                        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color(0x1AFA233B)),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
                        modifier = Modifier.padding(top = 4.dp).returnToTabOnUp(onReturnToTab),
                    ) {
                        Text(album.artistName, fontSize = 15.sp, color = Color(0xFFFA233B),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                } else {
                    Text(album.artistName, fontSize = 15.sp, color = Color(0xFFFA233B),
                        modifier = Modifier.padding(top = 4.dp))
                }
                album.releaseDate?.let {
                    Text(it.take(4), fontSize = 12.sp, color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF8E8E93), Color(0xFFA3A3AA)), modifier = Modifier.padding(top = 4.dp))
                }
            }

            // Right: Play/Shuffle header + track list
            LazyColumn(
                state = tabAwareListState,
                contentPadding = PaddingValues(top = 120.dp),
                modifier = Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Play & Shuffle pinned above tracks
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp).returnToTabOnUp(onReturnToTab),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        PillButton(Glyph.PLAY, tr(R.string.msg_001), Color(0xFFFA233B), modifier = playEntryFocus) {
                            playerVm.playAlbum(state.tracks)
                        }
                        PillButton(Glyph.SHUFFLE, tr(R.string.msg_098), com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D))) {
                            playerVm.playAlbum(state.tracks.shuffled(), shuffle = true)
                        }
                    }
                }

                itemsIndexed(state.tracks, key = { _, t -> t.id }) { index, track ->
                    TrackRow(
                        track       = track,
                        index       = index + 1,
                        onClick     = { playerVm.playAlbum(state.tracks, index) },
                        onLongClick = { menuFromRemote=false;menuSong = track },
                        onContextMenu = {menuFromRemote=true;menuSong=track},
                    )
                }
            }
        } // Row

        // Fullscreen context menu overlay
        FadeThrough(menuSong, contentKey = { it?.id }) { retained ->
                retained?.let { s ->
            androidx.activity.compose.BackHandler(enabled = menuSong != null) { menuSong = null }
            com.applemusicktv.ui.navigation.ReturnFocusLayer {
                LaunchedEffect(s.id) {
                    if (s.artistId == null || s.albumId == null) {
                        val (aId, alId) = playerVm.lookupSongIds(s.id)
                        if (aId != null || alId != null)
                            menuSong = s.copy(artistId = aId ?: s.artistId, albumId = alId ?: s.albumId)
                    }
                }
                val dismissMenu = { menuSong = null }
                Box(
                    Modifier.fillMaxSize()
                        .background(Color(0x88000000))
                        .onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown &&
                                (event.key == Key.Back || event.key == Key.Escape)) {
                                dismissMenu(); true
                            } else false
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    val firstFocus = remember { FocusRequester() }
                    var clickBlocked by remember(s.id) { mutableStateOf(!menuFromRemote) }
                    LaunchedEffect(s.id) {
                        if(menuFromRemote) withFrameNanos {} else kotlinx.coroutines.delay(800)
                        clickBlocked = false
                    }
                    Column(
                        Modifier.menuInitialFocus(firstFocus).width(320.dp).heightIn(max = 340.dp).clip(RoundedCornerShape(14.dp))
                            .background(com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFF5F5F7), Color(0xFF222225)))
                            .verticalScroll(androidx.compose.foundation.rememberScrollState())
                            .padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                        Text(s.title, fontSize = 13.sp, color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)), fontWeight = FontWeight.Medium, maxLines = 1,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                        HorizontalDivider(color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D)), thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 8.dp))
                        AlbumContextItem(Glyph.PLAY_NEXT, tr(R.string.msg_128),    { if (!clickBlocked) { playerVm.playNext(s);    dismissMenu() } }, Modifier.focusRequester(firstFocus))
                        AlbumContextItem(Glyph.QUEUE_ADD, tr(R.string.msg_129), { if (!clickBlocked) { playerVm.addToQueue(s); dismissMenu() } })
                        AlbumContextItem(Glyph.ADD_TO, tr(R.string.msg_148), { if (!clickBlocked) { addToSong = s; dismissMenu() } })
                        AlbumContextItem(Glyph.RADIO, tr(R.string.msg_147), { if (!clickBlocked) { playerVm.createSongStation(s); dismissMenu() } })
                        val goArtist = s.artistId ?: album.artistId ?: state.tracks.firstOrNull()?.artistId
                        val goAlbum  = s.albumId ?: album.id
                        HorizontalDivider(color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D)), thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 8.dp))
                        if (goArtist != null) AlbumContextItem(Glyph.ARTIST, tr(R.string.msg_132), onClick = { if (!clickBlocked) { onArtistClick(goArtist); dismissMenu() } })
                        if (goAlbum  != null) AlbumContextItem(Glyph.ALBUM, tr(R.string.msg_133),  onClick = { if (!clickBlocked) { onAlbumClick(goAlbum);   dismissMenu() } })
                    }
                }
            }
        }
    }

        FadeThrough(addToSong, contentKey = { it?.id }) { retained ->
                retained?.let { s ->
                com.applemusicktv.ui.components.AddToDialog(playerVm, s, onDismiss = { addToSong = null })
            }
        }

        } // outer Box
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AlbumContextItem(icon: Glyph, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.padding(horizontal = 6.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D))),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
                Icon(icon, size = 17.dp, color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)))
            }
            Text(label, fontSize = 15.sp, color = com.applemusicktv.ui.theme.AppPalette.primary)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TrackRow(track: Song, index: Int, onClick: () -> Unit, onLongClick: () -> Unit = {}, onContextMenu: () -> Unit = onLongClick) {
    Surface(
        onClick     = onClick,
        onLongClick = onLongClick,
        onContextMenu = onContextMenu,
        modifier = Modifier.fillMaxWidth(),
        shape    = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
        colors   = ClickableSurfaceDefaults.colors(
            containerColor        = Color.Transparent,
            focusedContainerColor = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFF5F5F7), Color(0xFF222225)),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("$index", fontSize = 13.sp, color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF8E8E93), Color(0xFFA3A3AA)), modifier = Modifier.width(24.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(track.title, fontSize = 14.sp, color = com.applemusicktv.ui.theme.AppPalette.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (track.artistName.isNotEmpty()) {
                    Text(track.artistName, fontSize = 11.sp, color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)), modifier = Modifier.padding(top = 2.dp))
                }
            }
            Text(track.durationFormatted, fontSize = 12.sp, color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF8E8E93), Color(0xFFA3A3AA)))
        }
    }
}
