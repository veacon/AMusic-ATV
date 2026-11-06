package com.applemusicktv.ui.screens

import com.applemusicktv.i18n.tr
import com.applemusicktv.ui.components.menuInitialFocus
import com.applemusicktv.R

import com.applemusicktv.ui.components.focusedArtworkBorder
import com.applemusicktv.ui.components.pageHorizontalInset
import com.applemusicktv.ui.components.FadeThrough
import com.applemusicktv.ui.components.TouchSurface as Surface

import com.applemusicktv.ui.components.ContentActionShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.itemsIndexed
import coil.compose.AsyncImage
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.Brush
import com.applemusicktv.ui.viewmodel.PlayerViewModel
import androidx.hilt.navigation.compose.hiltViewModel
import com.applemusicktv.ui.components.returnToTabOnUp
import com.applemusicktv.ui.components.rememberTabAwareListState
import com.applemusicktv.ui.navigation.ReturnLazyColumn as LazyColumn
import com.applemusicktv.ui.navigation.ReturnLazyRow as LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import com.applemusicktv.ui.navigation.ReturnLazyVerticalGrid as LazyVerticalGrid
import com.applemusicktv.ui.navigation.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.tv.material3.*
import com.applemusicktv.ui.components.AlbumCard
import com.applemusicktv.ui.viewmodel.SearchViewModel
import com.applemusicktv.data.repository.Curator

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SearchScreen(playerVm: PlayerViewModel, onAlbumClick: (String) -> Unit = {}, onArtistClick: (String) -> Unit = {}, onPlaylistClick: (id: String, name: String, artworkUrl: String) -> Unit = { _, _, _ -> }, onCuratorClick: (id: String, kind: String) -> Unit = { _, _ -> }, modifier: Modifier = Modifier, onReturnToTab: (() -> Unit)? = null, tabBarFocused: Boolean = false, vm: SearchViewModel = hiltViewModel()) {
    val listState = rememberTabAwareListState(tabBarFocused)
    val state by vm.state.collectAsState()
    val recents by vm.recentSearches.collectAsState()
    val focusRequester = remember { FocusRequester() }
    // Keyboard only opens once the user explicitly selects the search box —
    // otherwise entering the tab auto-focuses the field and pops the IME.
    var editing by remember { mutableStateOf(false) }
    var menuSong by remember { mutableStateOf<com.applemusicktv.data.model.Song?>(null) }
    // Same guard the other screens use: the OK release that ends a long-press would
    // otherwise land on the first menu item the moment it takes focus.
    var clickBlocked by remember { mutableStateOf(false) }
    var menuFromRemote by remember {mutableStateOf(false)}
    /** Set once the text field has really taken focus — see onFocusChanged below. */
    var hasFocused by remember { mutableStateOf(false) }
    LaunchedEffect(editing) {
        // Only grab focus when the user opened the field. Re-running this after every
        // query change is what kept popping the keyboard back up while typing.
        if (editing) runCatching { focusRequester.requestFocus() } else hasFocused = false
    }

    val ctx = LocalContext.current
    val view = LocalView.current
    /** Compose's hide() is a no-op on Fire TV; go through the platform IMM as well. */
    fun closeEditor() {
        editing = false
        runCatching {
            ctx.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }
    // Back closes the editor instead of leaving the tab with the IME still up.
    androidx.activity.compose.BackHandler(enabled = editing) { closeEditor() }

    // Match Listen Now: full-width rail viewports. Headings and first items use the shared page inset, the content lists
    // carry their own horizontal contentPadding so rows bleed to the screen edge.
    Column(modifier = modifier.fillMaxSize().padding(top = 100.dp)) {
        Text(tr(R.string.msg_090), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = com.applemusicktv.ui.theme.AppPalette.primary,
            modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(10.dp))

        if (editing) {
                com.applemusicktv.ui.navigation.ReturnFocusLayer {
            Row(
                modifier = Modifier.align(Alignment.CenterHorizontally).fillMaxWidth().padding(horizontal = pageHorizontalInset).height(56.dp)
                    .background(com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFF5F5F7), Color(0xFF222225)), RoundedCornerShape(50))
                    .padding(horizontal = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                com.applemusicktv.ui.components.Icon(com.applemusicktv.ui.components.Glyph.SEARCH,
                    size = 20.dp, color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF8E8E93), Color(0xFFA3A3AA)))
                Spacer(Modifier.width(12.dp))
                BasicTextField(
                    value = state.query, onValueChange = vm::onQueryChange,
                    textStyle = TextStyle(color = com.applemusicktv.ui.theme.AppPalette.primary, fontSize = 18.sp),
                    cursorBrush = SolidColor(Color(0xFFFA233B)), singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Search,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onSearch = { vm.commitSearch(); closeEditor() },
                        onDone = { vm.commitSearch(); closeEditor() },
                    ),
                    modifier = Modifier.weight(1f).focusRequester(focusRequester)
                        // Only close once it has actually held focus: onFocusChanged
                        // fires with isFocused=false on mount, before the
                        // FocusRequester runs, which closed the editor instantly.
                        .onFocusChanged {
                            if (it.isFocused) hasFocused = true
                            else if (hasFocused && editing) editing = false
                        },
                    decorationBox = { inner ->
                        if (state.query.isEmpty()) Text(tr(R.string.msg_152), color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF8E8E93), Color(0xFFA3A3AA)), fontSize = 18.sp)
                        inner()
                    }
                )
                if (state.query.isNotEmpty()) {
                    Surface(
                        onClick = { vm.onQueryChange(""); runCatching { focusRequester.requestFocus() } },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                        colors = com.applemusicktv.ui.components.contentActionColors(Color.Transparent),
                        border = com.applemusicktv.ui.components.contentActionBorder(RoundedCornerShape(50)),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
                    ) {
                        Box(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                            com.applemusicktv.ui.components.Icon(com.applemusicktv.ui.components.Glyph.CLOSE, size = 13.dp, color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)))
                        }
                    }
                }
            }
        }
            } else {
            Row(
                modifier = Modifier.align(Alignment.CenterHorizontally).fillMaxWidth().padding(horizontal = pageHorizontalInset).returnToTabOnUp(onReturnToTab),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    onClick = { editing = true },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                    colors = com.applemusicktv.ui.components.contentActionColors(com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFF5F5F7), Color(0xFF222225))),
                    border = com.applemusicktv.ui.components.contentActionBorder(RoundedCornerShape(50)),
                    modifier = Modifier.weight(1f).height(56.dp),
                ) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        com.applemusicktv.ui.components.Icon(com.applemusicktv.ui.components.Glyph.SEARCH,
                            size = 20.dp, color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF8E8E93), Color(0xFFA3A3AA)))
                        Text(
                            state.query.ifEmpty { tr(R.string.msg_153) },
                            color = if (state.query.isEmpty()) com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)) else com.applemusicktv.ui.theme.AppPalette.primary,
                            fontSize = 18.sp,
                        )
                    }
                }
                if (state.query.isNotEmpty()) {
                    Surface(
                        onClick = { vm.onQueryChange("") },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                        colors = com.applemusicktv.ui.components.contentActionColors(com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFF5F5F7), Color(0xFF222225))),
                        border = com.applemusicktv.ui.components.contentActionBorder(RoundedCornerShape(50)),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
                        modifier = Modifier.size(40.dp),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            com.applemusicktv.ui.components.Icon(com.applemusicktv.ui.components.Glyph.CLOSE, size = 14.dp, color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)))
                        }
                    }
                }
            }
        }

        FadeThrough(menuSong, contentKey = { it?.id }) { ms ->
            if (ms != null) {
                val firstFocus = remember { FocusRequester() }
                LaunchedEffect(ms) {
                    if(menuFromRemote) withFrameNanos {} else kotlinx.coroutines.delay(800)
                    clickBlocked = false
                }
                com.applemusicktv.ui.components.MotionDialog(onDismissRequest = { menuSong = null }) {
                    Column(
                        Modifier.menuInitialFocus(firstFocus).width(320.dp).clip(RoundedCornerShape(14.dp))
                            .background(com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFF5F5F7), Color(0xFF222225))).padding(vertical = 4.dp),
                    ) {
                        Text(ms.title, fontSize = 13.sp, color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)), fontWeight = FontWeight.Medium,
                            maxLines = 1, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                        SearchContextItem("▶", tr(R.string.msg_128), { if (!clickBlocked) { playerVm.playNext(ms); menuSong = null } }, Modifier.focusRequester(firstFocus))
                        SearchContextItem("+", tr(R.string.msg_129), { if (!clickBlocked) { playerVm.addToQueue(ms); menuSong = null } })
                        if (ms.artistId != null) SearchContextItem("♪", tr(R.string.msg_132), { if (!clickBlocked) { onArtistClick(ms.artistId); menuSong = null } })
                        if (ms.albumId != null) SearchContextItem("◉", tr(R.string.msg_133), { if (!clickBlocked) { onAlbumClick(ms.albumId); menuSong = null } })
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        FadeThrough(state, modifier = Modifier.weight(1f).fillMaxWidth(), contentKey = { when { it.isLoading -> "loading"; it.error != null -> "error"; it.selectedGenreId != null -> "genre-${it.selectedGenreId}-${it.genreLoading}"; it.query.isBlank() -> "browse-${it.categories.isNotEmpty()}"; it.results != null -> "results"; else -> "empty" } }) { state ->
            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFFFA233B))
                }
                state.results != null && (state.results!!.songs.isNotEmpty() || state.results!!.albums.isNotEmpty() || state.results!!.artists.isNotEmpty() || state.results!!.playlists.isNotEmpty() || state.results!!.curators.isNotEmpty()) -> {
                    val results = state.results!!
                    val albumColumns = (((androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp - pageHorizontalInset * 2 + 16.dp) / 126.dp).toInt()).coerceIn(1, 7)
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(24.dp), contentPadding = PaddingValues(top = 4.dp, bottom = 102.dp)) {
                        // Top Results — the strongest hit of each kind up front, the way Apple Music leads
                        // its search page. Editorial playlists first (what the user missed), then artist,
                        // album and the #1 song, so the best match is one focus move away.
                        item {
                            Text(tr(R.string.msg_154), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = com.applemusicktv.ui.theme.AppPalette.primary, modifier = Modifier.padding(start = pageHorizontalInset, end = pageHorizontalInset, bottom = 10.dp))
                            // contentPadding (not Modifier.padding) so a focused, scaled card can scroll
                            // fully into view and its halo isn't sliced at the row edges.
                            LazyRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(horizontal = pageHorizontalInset, vertical = 12.dp)) {
                                // Editorial category first — it's the strongest match for terms like
                                // "formula 1" or "tomorrowland", and the one users can't find otherwise.
                                items(results.curators.take(1), key = { "top-cu-${it.id}" }) { cur ->
                                    CuratorCard(cur, size = 150, onClick = { onCuratorClick(cur.id, cur.kind) })
                                }
                                items(results.playlists.take(2), key = { "top-pl-${it.id}" }) { pl ->
                                    AlbumCard(album = pl, size = 150, onClick = { onPlaylistClick(pl.id, pl.title, pl.artworkUrl(500) ?: "") })
                                }
                                results.songs.firstOrNull()?.let { song ->
                                    item(key = "top-song-${song.id}") {
                                        AlbumCard(
                                            album = com.applemusicktv.data.model.Album(id = song.id, title = song.title,
                                                artistName = song.artistName, artworkUrl = song.artworkUrl,
                                                artworkBgColor = song.artworkBgColor),
                                            size = 150,
                                            onClick = { playerVm.playSong(song) },
                                        )
                                    }
                                }
                                results.artists.firstOrNull()?.let { artist ->
                                    item(key = "top-ar-${artist.id}") {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(150.dp)) {
                                            Surface(
                                                onClick = { onArtistClick(artist.id) },
                                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                                                colors = ClickableSurfaceDefaults.colors(containerColor = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D)), focusedContainerColor = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFDCDCE1), Color(0xFF414146))),
                                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
                                                modifier = Modifier.size(120.dp),
                                            ) {
                                                Box(Modifier.fillMaxSize().focusedArtworkBorder(RoundedCornerShape(50))) {
                                                    if (artist.artworkUrl != null) AsyncImage(
                                                        model = artist.artworkUrl.replace("{w}", "240").replace("{h}", "240").replace("{f}", "jpg"),
                                                        contentDescription = artist.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                                                    )
                                                }
                                            }
                                            Spacer(Modifier.height(6.dp))
                                            Text(artist.name, fontSize = 12.sp, color = com.applemusicktv.ui.theme.AppPalette.primary, maxLines = 1)
                                            Text(tr(R.string.msg_071), fontSize = 10.sp, color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)))
                                        }
                                    }
                                }
                                items(results.albums.take(2), key = { "top-al-${it.id}" }) { album ->
                                    val isPlaylist = album.id.startsWith("pl.") || album.id.startsWith("p.")
                                    AlbumCard(album = album, size = 150, onClick = {
                                        if (isPlaylist) onPlaylistClick(album.id, album.title, album.artworkUrl(500) ?: "") else onAlbumClick(album.id)
                                    })
                                }
                            }
                        }
                        if (results.curators.isNotEmpty()) {
                            item {
                                Text(tr(R.string.msg_155), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = com.applemusicktv.ui.theme.AppPalette.primary, modifier = Modifier.padding(start = pageHorizontalInset, end = pageHorizontalInset, bottom = 10.dp))
                                LazyRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(horizontal = pageHorizontalInset, vertical = 12.dp)) {
                                    items(results.curators, key = { it.id }) { cur ->
                                        CuratorCard(cur, onClick = { onCuratorClick(cur.id, cur.kind) })
                                    }
                                }
                            }
                        }
                        if (results.playlists.isNotEmpty()) {
                            item {
                                Text(tr(R.string.msg_069), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = com.applemusicktv.ui.theme.AppPalette.primary, modifier = Modifier.padding(start = pageHorizontalInset, end = pageHorizontalInset, bottom = 10.dp))
                                LazyRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(horizontal = pageHorizontalInset, vertical = 12.dp)) {
                                    items(results.playlists, key = { it.id }) { pl ->
                                        AlbumCard(album = pl, size = 130, onClick = { onPlaylistClick(pl.id, pl.title, pl.artworkUrl(500) ?: "") })
                                    }
                                }
                            }
                        }
                        if (results.artists.isNotEmpty()) {
                            item {
                                Text(tr(R.string.msg_071), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = com.applemusicktv.ui.theme.AppPalette.primary, modifier = Modifier.padding(start = pageHorizontalInset, end = pageHorizontalInset, bottom = 10.dp))
                                LazyRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(horizontal = pageHorizontalInset, vertical = 12.dp)) {
                                    items(results.artists, key = { it.id }) { artist ->
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            modifier = Modifier.width(96.dp),
                                        ) {
                                            Surface(
                                                onClick = { onArtistClick(artist.id) },
                                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                                                colors = ClickableSurfaceDefaults.colors(containerColor = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D)), focusedContainerColor = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFDCDCE1), Color(0xFF414146))),
                                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
                                                modifier = Modifier.size(80.dp),
                                            ) {
                                                Box(Modifier.fillMaxSize().focusedArtworkBorder(RoundedCornerShape(50))) {
                                                    if (artist.artworkUrl != null) {
                                                        coil.compose.AsyncImage(
                                                            model = artist.artworkUrl.replace("{w}", "160").replace("{h}", "160").replace("{f}", "jpg"),
                                                            contentDescription = artist.name,
                                                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                                            modifier = Modifier.fillMaxSize(),
                                                        )
                                                    }
                                                }
                                            }
                                            Spacer(Modifier.height(6.dp))
                                            Text(artist.name, fontSize = 11.sp, color = com.applemusicktv.ui.theme.AppPalette.primary, maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                        }
                                    }
                                }
                            }
                        }
                        // Two columns of compact rows — a TV is wide, one column wasted it.
                        if (results.songs.isNotEmpty()) {
                            item {
                                Text(tr(R.string.msg_068), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = com.applemusicktv.ui.theme.AppPalette.primary, modifier = Modifier.padding(start = pageHorizontalInset, end = pageHorizontalInset, bottom = 8.dp))
                            }
                            val rows = results.songs.chunked(2)
                            itemsIndexed(rows, key = { _, r -> r.first().id }) { rowIdx, row ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = pageHorizontalInset).padding(bottom = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    row.forEachIndexed { colIdx, song ->
                                        val flatIdx = rowIdx * 2 + colIdx
                                        Surface(
                                            onClick = { playerVm.playAlbum(results.songs, flatIdx) },
                                            onLongClick = { menuFromRemote=false;menuSong = song; clickBlocked = true },
                                            onContextMenu = {menuFromRemote=true;clickBlocked=false;menuSong=song},
                                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                                            colors = ClickableSurfaceDefaults.colors(
                                                containerColor = Color.Transparent,
                                                focusedContainerColor = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D)),
                                            ),
                                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
                                            modifier = Modifier.weight(1f),
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                AsyncImage(
                                                    model = song.artworkUrl(100),
                                                    contentDescription = song.title,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.size(34.dp).focusedArtworkBorder(RoundedCornerShape(4.dp)).clip(RoundedCornerShape(4.dp)),
                                                )
                                                Spacer(Modifier.width(10.dp))
                                                Column(Modifier.weight(1f)) {
                                                    Text(song.title, fontSize = 13.sp, color = com.applemusicktv.ui.theme.AppPalette.primary, maxLines = 1)
                                                    Text(
                                                        listOf(song.artistName, song.albumName)
                                                            .filter { it.isNotBlank() }
                                                            .joinToString(" — "),
                                                        fontSize = 11.sp, color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)), maxLines = 1,
                                                    )
                                                }
                                                Text(song.durationFormatted, fontSize = 11.sp, color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)))
                                            }
                                        }
                                    }
                                    if (row.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                        if (results.albums.isNotEmpty()) {
                            item(key = "album-heading") {
                                Text(tr(R.string.msg_070), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = com.applemusicktv.ui.theme.AppPalette.primary, modifier = Modifier.padding(start = pageHorizontalInset, end = pageHorizontalInset, bottom = 10.dp))
                            }
                            itemsIndexed(results.albums.chunked(albumColumns), key = { _, row -> "album-row-${row.first().id}" }) { _, row ->
                                Row(Modifier.fillMaxWidth().padding(horizontal = pageHorizontalInset).padding(bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    row.forEach { album ->
                                        androidx.compose.runtime.key(album.id) {
                                            val isPlaylist = album.id.startsWith("pl.") || album.id.startsWith("p.")
                                            AlbumCard(album = album, size = 110, onClick = {
                                                if (isPlaylist) onPlaylistClick(album.id, album.title, album.artworkUrl(500) ?: "")
                                                else onAlbumClick(album.id)
                                            })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                state.query.isNotBlank() && state.error != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(tr(R.string.msg_072), color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)), fontSize = 16.sp)
                }
                state.query.isNotBlank() && state.results != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(tr(R.string.msg_156), color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF8E8E93), Color(0xFFA3A3AA)), fontSize = 16.sp)
                }
                state.query.isBlank() -> {
                    // Category tiles + recents when not searching
                    if (state.categories.isNotEmpty() || recents.isNotEmpty()) {
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(24.dp), contentPadding = PaddingValues(top = 4.dp, bottom = 102.dp)) {
                            // Recent searches first — retyping on a remote is the slow part.
                            if (recents.isNotEmpty()) {
                                item {
                                    Row(Modifier.padding(horizontal = pageHorizontalInset), verticalAlignment = Alignment.CenterVertically) {
                                        Text(tr(R.string.msg_157), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = com.applemusicktv.ui.theme.AppPalette.primary)
                                        Spacer(Modifier.width(12.dp))
                                        Surface(
                                            onClick = { vm.clearRecents() },
                                            shape = ClickableSurfaceDefaults.shape(ContentActionShape),
                                            colors = com.applemusicktv.ui.components.contentActionColors(com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D))),
                                            border = com.applemusicktv.ui.components.contentActionBorder(ContentActionShape),
                                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
                                        ) {
                                            Text(tr(R.string.msg_158), fontSize = 12.sp, color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)),
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
                                        }
                                    }
                                    Spacer(Modifier.height(10.dp))
                                    LazyRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(horizontal = pageHorizontalInset, vertical = 12.dp)) {
                                        items(recents, key = { it }) { term ->
                                            Surface(
                                                onClick = { vm.runRecent(term) },
                                                onLongClick = { vm.removeRecent(term) },
                                                shape = ClickableSurfaceDefaults.shape(ContentActionShape),
                                                colors = com.applemusicktv.ui.components.contentActionColors(com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D))),
                                                border = com.applemusicktv.ui.components.contentActionBorder(ContentActionShape),
                                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
                                            ) {
                                                Text(term, fontSize = 13.sp, color = com.applemusicktv.ui.theme.AppPalette.primary,
                                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                                            }
                                        }
                                    }
                                }
                            }
                            // Genre / mood / decade tiles — Apple's "按音乐类型浏览" grid.
                            state.categories.forEach { group ->
                                item {
                                    Text(group.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = com.applemusicktv.ui.theme.AppPalette.primary, modifier = Modifier.padding(start = pageHorizontalInset, end = pageHorizontalInset, bottom = 10.dp))
                                    // vertical contentPadding so the focused tile's border/glow isn't
                                    // sliced at the row's top/bottom edge.
                                    LazyRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(horizontal = pageHorizontalInset, vertical = 12.dp)) {
                                        items(group.items, key = { it.id }) { cur ->
                                            CategoryTile(cur.name, cur.artworkUrl) { onCuratorClick(cur.id, cur.kind) }
                                        }
                                    }
                                }
                            }
                            // "探索更多" — the editorial link list from apple.com's Browse footer.
                            // Each opens an editorial room via the "room" curator kind.
                            item {
                                Text(tr(R.string.msg_159), fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                                    color = com.applemusicktv.ui.theme.AppPalette.primary, modifier = Modifier.padding(start = pageHorizontalInset, end = pageHorizontalInset, top = 4.dp, bottom = 12.dp))
                                // Triple(label, id, kind): rooms open editorial rooms, grouping opens a
                                // catalog grouping (Music Videos), apple-curator opens a curator page.
                                val links = listOf(
                                    Triple(tr(R.string.msg_160), "1554941247", "apple-curator"),
                                    Triple(tr(R.string.msg_161), "6503108310", "room"),
                                    Triple(tr(R.string.msg_162), "6456176470", "room"),
                                    Triple(tr(R.string.msg_163), "6456176472", "room"),
                                    Triple(tr(R.string.msg_164), "6456176471", "room"),
                                )
                                Column(Modifier.padding(horizontal = pageHorizontalInset), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    links.forEach { (label, id, kind) ->
                                        ExploreLink(label) { onCuratorClick(id, kind) }
                                    }
                                }
                            }
                        }
                    } else {
                        Box(Modifier.fillMaxSize(), Alignment.Center) {
                            Text(tr(R.string.msg_165), color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF8E8E93), Color(0xFFA3A3AA)), fontSize = 16.sp)
                        }
                    }
                }
            }
        }

    }
}

/** A full-width "探索更多" pill: label left, chevron right — opens an editorial room. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ExploreLink(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(ContentActionShape),
        colors = com.applemusicktv.ui.components.contentActionColors(com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFF5F5F7), Color(0xFF222225))),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.015f),
        border = com.applemusicktv.ui.components.contentActionBorder(ContentActionShape),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFFA233B))
            Spacer(Modifier.weight(1f))
            com.applemusicktv.ui.components.Icon(
                com.applemusicktv.ui.components.Glyph.NEXT, size = 16.dp, color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)))
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SearchContextItem(icon: String, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
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
            Text(icon, fontSize = 16.sp, color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)), modifier = Modifier.width(22.dp))
            Text(label, fontSize = 15.sp, color = com.applemusicktv.ui.theme.AppPalette.primary)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CuratorCard(cur: Curator, size: Int = 130, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(size.dp)) {
        Surface(
            onClick = onClick,
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
            colors = ClickableSurfaceDefaults.colors(containerColor = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D)), focusedContainerColor = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D))),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
            modifier = Modifier.size(size.dp),
        ) {
            Box(Modifier.fillMaxSize().focusedArtworkBorder(RoundedCornerShape(12.dp)), Alignment.Center) {
                if (cur.artworkUrl != null) AsyncImage(
                    model = cur.artworkUrl, contentDescription = cur.name,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                ) else com.applemusicktv.ui.components.Icon(com.applemusicktv.ui.components.Glyph.MUSIC_NOTE, size = 40.dp, color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(cur.name, fontSize = 12.sp, color = com.applemusicktv.ui.theme.AppPalette.primary, maxLines = 2)
        Text(tr(R.string.msg_155), fontSize = 10.sp, color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)))
    }
}

/** Colourful category tile (genre/mood/decade) — editorial art with label on a scrim. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CategoryTile(name: String, artworkUrl: String?, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFF5F5F7), Color(0xFF222225)), focusedContainerColor = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFF5F5F7), Color(0xFF222225))),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border.None),
        modifier = Modifier.width(180.dp).height(110.dp),
    ) {
        Box(Modifier.fillMaxSize().focusedArtworkBorder(RoundedCornerShape(12.dp))) {
            if (artworkUrl != null) AsyncImage(
                model = artworkUrl, contentDescription = name,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            )
            Box(Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color(0x22000000), 1f to Color(0xCC000000)),
            ))
            Text(name, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White,
                maxLines = 2, modifier = Modifier.align(Alignment.BottomStart).padding(12.dp))
        }
    }
}
