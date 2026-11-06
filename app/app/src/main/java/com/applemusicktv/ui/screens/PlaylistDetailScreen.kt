package com.applemusicktv.ui.screens

import com.applemusicktv.i18n.tr
import com.applemusicktv.ui.components.menuInitialFocus
import com.applemusicktv.R

import com.applemusicktv.ui.components.pageHorizontalInset
import com.applemusicktv.ui.components.FadeThrough
import com.applemusicktv.ui.components.TouchSurface as Surface

import com.applemusicktv.ui.components.ContentActionShape
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import com.applemusicktv.ui.navigation.ReturnLazyColumn as LazyColumn
import com.applemusicktv.ui.components.returnToTabOnUp
import com.applemusicktv.ui.components.rememberTabAwareListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.*
import coil.compose.AsyncImage
import com.applemusicktv.data.model.Song
import com.applemusicktv.ui.components.Glyph
import com.applemusicktv.ui.components.Icon
import com.applemusicktv.ui.viewmodel.PlaylistDetailViewModel
import com.applemusicktv.ui.viewmodel.PlayerViewModel

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    playlistId: String,
    playlistName: String,
    artworkUrl: String? = null,
    playerVm: PlayerViewModel,
    onBack: () -> Unit,
    onArtistClick: (String) -> Unit = {},
    onAlbumClick: (String) -> Unit = {},
    onMusicVideoClick: (Song) -> Unit = {},
    modifier: Modifier = Modifier,
    onReturnToTab: (() -> Unit)? = null,
    tabBarFocused: Boolean = false,
    vm: PlaylistDetailViewModel = hiltViewModel(),
) {
    val tabAwareListState = rememberTabAwareListState(tabBarFocused)
    val state by vm.state.collectAsState()
    val playEntryFocus = com.applemusicktv.ui.navigation.rememberDetailPlayEntryFocus(
        ready = state.tracks.isNotEmpty(),
        loading = state.loading,
        beforeFocus = { tabAwareListState.scrollToItem(0) },
    )

    LaunchedEffect(playlistId) { vm.load(playlistId, artworkUrl) }

    // Sort is remembered PER PLAYLIST (keyed by id) in SharedPreferences.
    val context = androidx.compose.ui.platform.LocalContext.current
    val sortPrefs = remember { context.getSharedPreferences("playlist_sort", android.content.Context.MODE_PRIVATE) }
    var sort by remember(playlistId) {
        mutableStateOf(
            sortPrefs.getString("f_$playlistId", null)
                ?.let { runCatching { PlaylistSort.valueOf(it) }.getOrNull() } ?: PlaylistSort.DEFAULT
        )
    }
    var descending by remember(playlistId) { mutableStateOf(sortPrefs.getBoolean("d_$playlistId", false)) }
    var showSortDialog by remember { mutableStateOf(false) }

    FadeThrough(showSortDialog, contentKey = { if (it) true else null }) { visible ->
            if (visible) {
            PlaylistSortDialog(
                current = sort, descending = descending,
                onPick = { f, desc ->
                    sort = f; descending = desc
                    sortPrefs.edit().putString("f_$playlistId", f.name).putBoolean("d_$playlistId", desc).apply()
                },
                onDismiss = { showSortDialog = false },
            )
        }
    }
    val sortedTracks = remember(state.tracks, sort, descending) {
        val base = when (sort) {
            PlaylistSort.DEFAULT -> state.tracks
            PlaylistSort.TITLE   -> state.tracks.sortedBy { it.title.lowercase() }
            PlaylistSort.ARTIST  -> state.tracks.sortedBy { it.artistName.lowercase() }
            PlaylistSort.ALBUM   -> state.tracks.sortedBy { it.albumName.lowercase() }
        }
        // Direction applies to real sort fields; DEFAULT reversed = reverse order.
        if (descending) base.reversed() else base
    }

    var menuSongState by remember { mutableStateOf<Song?>(null) }
    var menuFromRemote by remember {mutableStateOf(false)}
    var addToSong by remember { mutableStateOf<Song?>(null) }
    var lastDismissMs by remember { mutableStateOf(0L) }
    val dismissMenu: () -> Unit = {
        lastDismissMs = System.currentTimeMillis()
        menuSongState = null
    }
    menuSongState?.let { s ->
        LaunchedEffect(s.id) {
            if (s.artistId == null || s.albumId == null) {
                val (aId, alId) = playerVm.lookupSongIds(s.id)
                if (aId != null || alId != null)
                    menuSongState = s.copy(artistId = aId ?: s.artistId, albumId = alId ?: s.albumId)
            }
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize().background(com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFFFFFFF), Color(0xFF101012)))) {
    val inset = pageHorizontalInset
    val contentWidth = (maxWidth - inset * 2).coerceAtLeast(0.dp)
    val artworkWidth = 244.dp.coerceAtMost(contentWidth * 0.38f)
    val columnGap = 28.dp.coerceAtMost(contentWidth * 0.05f)
    Row(modifier = Modifier.fillMaxSize().padding(horizontal = inset), horizontalArrangement = Arrangement.spacedBy(columnGap)) {
        // Left panel — artwork + info, same familiar layout as an album (album cover
        // is a touch bigger; here the "year" slot becomes the song count and the
        // artist line becomes the playlist maker).
        Column(
            modifier = Modifier.width(artworkWidth).fillMaxHeight().padding(top = 112.dp, bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFF0EDF6), Color(0xFF28282C))),
            ) {
                if (state.artworkUrl != null) {
                    AsyncImage(
                        model = state.artworkUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                state.motionUrl?.let { MotionCover(url = it, modifier = Modifier.fillMaxSize()) }
            }
            Spacer(Modifier.height(20.dp))
            Text(playlistName, fontSize = 20.sp, color = com.applemusicktv.ui.theme.AppPalette.primary, fontWeight = FontWeight.Bold,
                lineHeight = 26.sp, maxLines = 2)
            if (state.curatorName.isNotEmpty())
                Text(state.curatorName, fontSize = 15.sp, color = Color(0xFFFA233B), modifier = Modifier.padding(top = 4.dp))
            if (state.tracks.isNotEmpty())
                Text(com.applemusicktv.i18n.AppLanguage.quantity(R.plurals.song_count, state.tracks.size), fontSize = 12.sp, color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF8E8E93), Color(0xFFA3A3AA)), modifier = Modifier.padding(top = 4.dp))
        }

        // Right panel — Play/Shuffle header + track list
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            FadeThrough(state to sortedTracks, modifier = Modifier.fillMaxSize(), contentKey = { (state, _) ->
                Triple(sort, descending, when {
                    state.loading && state.tracks.isEmpty() -> "loading"
                    state.tracks.isEmpty() -> "empty"
                    else -> "content"
                }) }) { (state, sortedTracks) ->
                when {
                    state.loading && state.tracks.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        CircularProgressIndicator(color = Color(0xFFFA233B))
                    }
                    state.tracks.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Text(tr(R.string.msg_127), color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF8E8E93), Color(0xFFA3A3AA)), fontSize = 14.sp)
                    }
                    else -> {
                        LazyColumn(
                        state = tabAwareListState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 72.dp, bottom = 48.dp),
                    ) {
                        // Sticky Play/Shuffle header — sort is a round button pushed to the right.
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth().returnToTabOnUp(onReturnToTab)
                                    .background(com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFFFFFFF), Color(0xFF101012)))
                                    .padding(vertical = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                PillButton(Glyph.PLAY, tr(R.string.msg_001), Color(0xFFFA233B), modifier = playEntryFocus) {
                                    playerVm.playAlbum(sortedTracks, 0)   // mixed queue; videos route to the video player
                                }
                                PillButton(Glyph.SHUFFLE, tr(R.string.msg_098), com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D))) {
                                    playerVm.playAlbum(sortedTracks, sortedTracks.indices.randomOrNull() ?: 0, shuffle = true)
                                }
                                Spacer(Modifier.weight(1f))
                                SortCircleButton(onClick = { showSortDialog = true })
                            }
                        }
                        trackItems(sortedTracks, playerVm, onLongPress = {song ->
                            val now=System.currentTimeMillis()
                            if(menuSongState==null && now-lastDismissMs>600) {menuFromRemote=false;menuSongState=song}
                        }, onContextMenu = {song -> menuFromRemote=true;menuSongState=song})
                    }
                    }  // end else block
                }
            }

        }
    } // Row

    // Fullscreen context menu overlay — no Dialog API, no focus/dismiss races
    FadeThrough(menuSongState, contentKey = { it?.id }) { retained ->
            retained?.let { s ->
            androidx.activity.compose.BackHandler(enabled = menuSongState != null) { dismissMenu() }
            com.applemusicktv.ui.navigation.ReturnFocusLayer {
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
                    PlaylistContextItem(Glyph.PLAY_NEXT, tr(R.string.msg_128),    { if (!clickBlocked) { playerVm.playNext(s);    dismissMenu() } }, Modifier.focusRequester(firstFocus))
                    PlaylistContextItem(Glyph.QUEUE_ADD, tr(R.string.msg_129), { if (!clickBlocked) { playerVm.addToQueue(s); dismissMenu() } })
                    PlaylistContextItem(Glyph.RADIO, tr(R.string.msg_147), { if (!clickBlocked) { playerVm.createSongStation(s); dismissMenu() } })
                    PlaylistContextItem(Glyph.ADD_TO, tr(R.string.msg_148), { if (!clickBlocked) { addToSong = s; dismissMenu() } })
                    HorizontalDivider(color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D)), thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 8.dp))
                    s.artistId?.let { aid -> PlaylistContextItem(Glyph.ARTIST, tr(R.string.msg_132), onClick = { if (!clickBlocked) { onArtistClick(aid); dismissMenu() } }) }
                    s.albumId?.let  { alid -> PlaylistContextItem(Glyph.ALBUM, tr(R.string.msg_133),  onClick = { if (!clickBlocked) { onAlbumClick(alid);  dismissMenu() } }) }
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

private enum class PlaylistSort(private val labelId: Int) {
    DEFAULT(R.string.msg_149), TITLE(R.string.msg_118), ARTIST(R.string.msg_071), ALBUM(R.string.msg_070);
    val label: String get() = tr(labelId)


}

/** Round sort button (right side of the header) — opens the sort popup. */
@OptIn(ExperimentalTvMaterial3Api::class)
@androidx.compose.runtime.Composable
private fun SortCircleButton(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = com.applemusicktv.ui.components.contentActionColors(com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D))),
            border = com.applemusicktv.ui.components.contentActionBorder(RoundedCornerShape(50)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
    ) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            Text("⇅", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = com.applemusicktv.ui.theme.AppPalette.primary)
        }
    }
}

/** Sort chooser popup for a playlist's track list. Real Dialog so D-pad focus stays in. */
@OptIn(ExperimentalTvMaterial3Api::class)
@androidx.compose.runtime.Composable
private fun PlaylistSortDialog(
    current: PlaylistSort,
    descending: Boolean,
    onPick: (PlaylistSort, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    com.applemusicktv.ui.components.MotionDialog(onDismissRequest = onDismiss) {
        val firstFocus = remember { FocusRequester() }
        Surface(
            shape = RoundedCornerShape(16.dp),
            colors = androidx.tv.material3.SurfaceDefaults.colors(containerColor = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFF5F5F7), Color(0xFF222225))),
            modifier = Modifier.width(340.dp),
        ) {
            Column(Modifier.menuInitialFocus(firstFocus).padding(vertical = 18.dp)) {
                Text(tr(R.string.msg_123), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = com.applemusicktv.ui.theme.AppPalette.primary,
                    modifier = Modifier.padding(start = 22.dp, bottom = 10.dp))
                PlaylistSort.entries.forEachIndexed { i, opt ->
                    PlaylistSortRow(opt.label, opt == current,
                        modifier = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier) {
                        onPick(opt, descending)
                    }
                }
                Box(Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 22.dp)
                    .height(1.dp).background(com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFDCDCE1), Color(0xFF414146))))
                PlaylistSortRow(tr(R.string.msg_121), !descending) { onPick(current, false) }
                PlaylistSortRow(tr(R.string.msg_120), descending) { onPick(current, true) }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@androidx.compose.runtime.Composable
private fun PlaylistSortRow(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent, focusedContainerColor = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D)),
        ),
        modifier = modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, fontSize = 14.sp, color = com.applemusicktv.ui.theme.AppPalette.primary,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.weight(1f))
            if (selected) Text("✓", fontSize = 15.sp, color = Color(0xFFFA233B))
        }
    }
}

private fun LazyListScope.trackItems(tracks: List<Song>, playerVm: PlayerViewModel, onMusicVideoClick: (Song) -> Unit = {}, onLongPress: (Song) -> Unit = {}, onContextMenu: (Song) -> Unit = onLongPress) {
    val returnKeys=com.applemusicktv.ui.navigation.occurrenceKeys(tracks.map { it.id })
    items(tracks.size, key = { returnKeys[it] }) { idx ->
        val song = tracks[idx]
        @OptIn(ExperimentalTvMaterial3Api::class)
        Surface(
            onClick     = {
                // Mixed queue: tap plays the whole list from here; playQueueItem routes each
                // item to the audio engine or the video player.
                playerVm.playAlbum(tracks, idx)
            },
            onLongClick = { onLongPress(song) },
            onContextMenu = { onContextMenu(song) },
            modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFF5F5F7), Color(0xFF222225)),
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("${idx + 1}", fontSize = 12.sp, color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF8E8E93), Color(0xFFA3A3AA)), modifier = Modifier.width(24.dp))
                // Music-video frames are 16:9 — show the whole frame in a wide thumbnail
                // instead of a square crop that lops off the sides. Songs stay square.
                val thumbMod = if (song.isMusicVideo) Modifier.height(40.dp).aspectRatio(16f / 9f)
                               else Modifier.size(40.dp)
                Box(thumbMod.clip(RoundedCornerShape(5.dp)).background(com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D)))) {
                    if (song.artworkUrl != null)
                        AsyncImage(model = song.artworkUrl(120), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Column(Modifier.weight(1f)) {
                    Text(song.title, fontSize = 13.sp, color = com.applemusicktv.ui.theme.AppPalette.primary, maxLines = 1, fontWeight = FontWeight.Medium)
                    Text(song.artistName, fontSize = 11.sp, color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)), maxLines = 1)
                }
                if (song.isMusicVideo) {
                    Box(Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFFFA233B)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                        Text("MV", fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Text(song.durationFormatted, fontSize = 11.sp, color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF8E8E93), Color(0xFFA3A3AA)))
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PlaylistContextItem(icon: Glyph, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
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

/** Rounded action pill: stable color, original enlargement and a light-gray focus ring. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun PillButton(glyph: Glyph, label: String, base: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape  = ClickableSurfaceDefaults.shape(ContentActionShape),
        colors = com.applemusicktv.ui.components.contentActionColors(base),
        border = com.applemusicktv.ui.components.contentActionBorder(ContentActionShape),
        modifier = modifier,
        scale  = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
    ) {
        Row(
            Modifier.padding(horizontal = 26.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Icon(glyph, size = 15.dp, color = com.applemusicktv.ui.theme.AppPalette.contentOn(base))
            Text(label, fontSize = 14.sp, color = com.applemusicktv.ui.theme.AppPalette.contentOn(base), fontWeight = FontWeight.SemiBold)
        }
    }
}
