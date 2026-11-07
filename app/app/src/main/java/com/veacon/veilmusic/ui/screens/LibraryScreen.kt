package com.veacon.veilmusic.ui.screens

import com.veacon.veilmusic.i18n.tr
import com.veacon.veilmusic.ui.components.menuInitialFocus
import com.veacon.veilmusic.R

import com.veacon.veilmusic.ui.components.focusedArtworkBorder
import com.veacon.veilmusic.ui.components.pageHorizontalInset
import com.veacon.veilmusic.ui.components.FadeThrough
import com.veacon.veilmusic.ui.components.TouchSurface as Surface

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import com.veacon.veilmusic.ui.navigation.ReturnLazyColumn as LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import com.veacon.veilmusic.ui.navigation.ReturnLazyVerticalGrid as LazyVerticalGrid
import com.veacon.veilmusic.ui.navigation.items
import com.veacon.veilmusic.ui.navigation.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import com.veacon.veilmusic.ui.components.returnToTabOnUp
import com.veacon.veilmusic.ui.components.rememberTabAwareListState
import com.veacon.veilmusic.ui.components.rememberTabAwareGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.*
import coil.compose.AsyncImage
import com.veacon.veilmusic.data.model.Album
import com.veacon.veilmusic.data.model.Artist
import com.veacon.veilmusic.data.model.Song
import com.veacon.veilmusic.data.local.LocalLibraryStore
import com.veacon.veilmusic.data.network.PlaylistDto
import com.veacon.veilmusic.ui.components.AlbumCard
import com.veacon.veilmusic.ui.viewmodel.LibraryViewModel
import com.veacon.veilmusic.ui.viewmodel.LibrarySection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.veacon.veilmusic.ui.viewmodel.PlayerViewModel
import com.veacon.veilmusic.ui.viewmodel.SortField

private fun com.veacon.veilmusic.ui.viewmodel.LibraryState.hasContent(section: LibrarySection): Boolean = when (section) {
    LibrarySection.Songs -> songs.isNotEmpty()
    LibrarySection.Playlists -> playlists.isNotEmpty()
    LibrarySection.Albums -> albums.isNotEmpty()
    LibrarySection.Artists -> artists.isNotEmpty()
    LibrarySection.Recents -> recents.isNotEmpty()
}

/** Sorted snapshots let a sort/section fade finish before the new order is shown. */
private data class LibraryItems(
    val playlists: List<LocalLibraryStore.LocalPlaylist> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val songs: List<Song> = emptyList(),
    val recents: List<Song> = emptyList(),
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LibraryScreen(
    vm: LibraryViewModel,
    playerVm: PlayerViewModel,
    onAlbumClick: (String) -> Unit = {},
    onPlaylistClick: (id: String, name: String, artworkUrl: String?) -> Unit = { _, _, _ -> },
    onArtistClick: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    contentFocusRequester: FocusRequester? = null,
    onReturnToTab: (() -> Unit)? = null,
    tabBarFocused: Boolean = false,
) {
    // Survive navigation away/back (saved in the nav backstack), so returning to Library
    // lands on the same section instead of resetting to Playlists.
    var section by rememberSaveable { mutableStateOf(LibrarySection.Songs) }
    val sectionStates=androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    LaunchedEffect(section) { vm.selectSection(section) }
    DisposableEffect(vm) { onDispose { vm.onHidden() } }
    val state by vm.state.collectAsStateWithLifecycle()
    val localEntryFocus = remember { FocusRequester() }
    val listFocus = remember { FocusRequester() }
    val sortRoute = remember { LibrarySortFocusRoute() }
    val sortFocus = sortRoute.control
    val hasCurrentItems = state.hasContent(section)
    val returnNavigation = com.veacon.veilmusic.ui.navigation.LocalNavigationReturn.current
    val listReady = state.error == null && hasCurrentItems

    var showImport by remember { mutableStateOf(false) }
    // 在 FadeThrough 外面取好：里面的 section 是解构出来的 val，不能赋值。
    val openImport: () -> Unit = { section = LibrarySection.Playlists; showImport = true }
    var importing by remember { mutableStateOf(false) }
    var playlistMenu by remember { mutableStateOf<LocalLibraryStore.LocalPlaylist?>(null) }
    val toastContext = androidx.compose.ui.platform.LocalContext.current
    fun toast(message: String) {
        android.widget.Toast.makeText(toastContext, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    val pinnedIds by vm.pinnedIds.collectAsState()
    val orderedItems = remember(section, state, pinnedIds) {
        when (section) {
            LibrarySection.Playlists -> LibraryItems(playlists = vm.sortedPlaylists())
            LibrarySection.Albums -> LibraryItems(albums = vm.sortedAlbums())
            LibrarySection.Artists -> LibraryItems(artists = vm.sortedArtists())
            LibrarySection.Songs -> LibraryItems(songs = vm.sortedSongs())
            LibrarySection.Recents -> LibraryItems(recents = state.recents)
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
    val inset = pageHorizontalInset
    val sidebarWidth = 208.dp.coerceAtMost((maxWidth - inset * 2) * 0.32f)
    Row(Modifier.fillMaxSize().padding(horizontal = inset)) {
        // Modern left sidebar (Songs / Playlists / Albums / Artists).
        SectionSidebar(section, onReturnToTab, sidebarWidth, sortRoute::request) { section = it }

        // Always mount this entry group so Down is safe even during first loading.
        // Prefer the list itself over both sidebar and sort control when data exists.
        Box(modifier = Modifier.weight(1f).fillMaxHeight()
            .focusRequester(contentFocusRequester ?: localEntryFocus)
            .libraryEntryFocus(listFocus,listReady,returnNavigation?.restoring==true,sortRoute).focusGroup()) {
            FadeThrough(Triple(section, state, orderedItems), modifier = Modifier.fillMaxSize(),
                contentKey = { (section, state, _) -> Triple(section, state.sort, when {
                    state.error != null -> "error"
                    else -> "content"
                }) }) { (section, state, orderedItems) ->
                when {
                    state.error != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Text(tr(R.string.msg_096, com.veacon.veilmusic.i18n.AppLanguage.message(state.error)), color = Color(0xFFFF3B30), fontSize = 14.sp)
                    }
                    else -> Column(Modifier.fillMaxSize().padding(top = 80.dp)) {
                        SortBar(section, state.sort.field, state.sort.dir, onReturnToTab, sortFocus, listFocus) { f, d -> vm.setSort(f, d) }
                        Box(Modifier.weight(1f).focusRequester(listFocus).focusGroup()) {
                            CompositionLocalProvider(LocalLibrarySortFocus provides sortRoute) {
                            Box(Modifier.fillMaxSize().librarySortOnRight(section==LibrarySection.Songs || section==LibrarySection.Artists || section==LibrarySection.Recents)) {
                            sectionStates.SaveableStateProvider(section.name) {
                            when (section) {
                                LibrarySection.Playlists -> {
                                    PlaylistGrid(
                                        tabBarFocused = tabBarFocused,
                                        onReturnToTab = onReturnToTab,
                                        playlists  = orderedItems.playlists,
                                        pinnedIds  = pinnedIds,
                                        importing  = importing,
                                        onOpen     = { playlist ->
                                            onPlaylistClick(playlist.id, playlist.name, playlist.coverUrl)
                                        },
                                        onPlay     = { vm.playPlaylist(it.id, playerVm) },
                                        onMenu     = { playlistMenu = it },
                                        onImport   = { showImport = true },
                                    )
                                }
                                LibrarySection.Albums  -> AlbumGrid(orderedItems.albums, onAlbumClick, onReturnToTab, tabBarFocused)
                                LibrarySection.Artists -> ArtistList(orderedItems.artists, onArtistClick, onReturnToTab, tabBarFocused)
                                LibrarySection.Songs -> SongList(orderedItems.songs, playerVm, vm, onArtistClick, onAlbumClick, onReturnToTab, tabBarFocused,
                                    onImportPlaylist = openImport)
                                LibrarySection.Recents -> RecentsList(orderedItems.recents, playerVm, vm, onArtistClick, onAlbumClick, onReturnToTab, tabBarFocused)
                            }
                            }
                            }
                            }
                        }
                    }
                }
            }

        }
    }

    // 导入歌单：贴链接 → 解析 → 落本地
    FadeThrough(showImport, contentKey = { if (it) true else null }) { visible ->
        if (visible) {
            ImportPlaylistDialog(
                importing = importing,
                serverUrl = vm.serverUrl(),
                onDismiss = { if (!importing) showImport = false },
                onImport = { raw ->
                    importing = true
                    vm.importPlaylist(raw) { message ->
                        importing = false
                        showImport = false
                        toast(message)
                    }
                },
            )
        }
    }

    // 歌单长按菜单
    FadeThrough(playlistMenu, contentKey = { it?.id }) { retained ->
        retained?.let { pl ->
            PlaylistMenuDialog(
                playlist   = pl,
                pinned     = pl.id in pinnedIds,
                onDismiss  = { playlistMenu = null },
                onReimport = {
                    playlistMenu = null
                    val url = pl.sourceUrl
                    if (url == null) {
                        toast("文本歌单无法重新导入")
                    } else {
                        importing = true
                        vm.importPlaylist(url) { message -> importing = false; toast(message) }
                    }
                },
                onTogglePin = { playlistMenu = null; vm.togglePin(pl.id) },
                onDelete = {
                    playlistMenu = null
                    vm.deletePlaylist(pl.id)
                    toast("已删除「${pl.name}」")
                },
            )
        }
    }
}

} // page bounds

/** Modern left sidebar — vertical section nav. Selected row = red-tinted pill with a
 *  bright accent bar; unselected are quiet. Bigger, cleaner than the old sidebar. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SectionSidebar(selected: LibrarySection, onReturnToTab: (() -> Unit)?, sidebarWidth: androidx.compose.ui.unit.Dp, onRequestSort: () -> Boolean, onSelect: (LibrarySection) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(sidebarWidth)
            .background(com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFFFFFFF), Color(0xFF101012)))
            .padding(end = 16.dp, top = 96.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            tr(R.string.msg_089),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = com.veacon.veilmusic.ui.theme.AppPalette.primary,
            modifier = Modifier.padding(bottom = 14.dp),
        )
        LibrarySection.entries.forEach { s ->
            val isSel = s == selected
            Surface(
                onClick = { onSelect(s) },
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = if (isSel) Color(0x33FA233B) else Color.Transparent,
                    focusedContainerColor = if (isSel) Color(0x4DFA233B) else com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D)),
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
                // The first content row goes straight Up to tabs; keep sort reachable from the sidebar.
                modifier = Modifier.fillMaxWidth()
                    .onPreviewKeyEvent {event ->
                        if(event.type==KeyEventType.KeyDown && event.key==Key.DirectionRight) onRequestSort() else false
                    }
                    .returnToTabOnUp(if (s == LibrarySection.Songs) onReturnToTab else null),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        Modifier.width(3.dp).height(18.dp).clip(RoundedCornerShape(2.dp))
                            .background(if (isSel) Color(0xFFFA233B) else Color.Transparent)
                    )
                    Text(
                        text = s.label,
                        fontSize = 16.sp,
                        fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (isSel) com.veacon.veilmusic.ui.theme.AppPalette.primary else com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun SortBar(
    section: LibrarySection,
    currentField: SortField,
    currentDir: com.veacon.veilmusic.ui.viewmodel.SortDir,
    onReturnToTab: (() -> Unit)?,
    sortFocus: FocusRequester,
    listFocus: FocusRequester? = null,
    onSort: (SortField, com.veacon.veilmusic.ui.viewmodel.SortDir) -> Unit,
) {
    val fields = when (section) {
        LibrarySection.Playlists -> listOf(SortField.DATE to tr(R.string.msg_117), SortField.NAME to tr(R.string.msg_116))
        LibrarySection.Albums    -> listOf(SortField.DATE to tr(R.string.msg_117), SortField.NAME to tr(R.string.msg_116), SortField.ARTIST to tr(R.string.msg_071))
        LibrarySection.Artists   -> listOf(SortField.DATE to tr(R.string.msg_117), SortField.NAME to tr(R.string.msg_116))
        LibrarySection.Songs     -> listOf(SortField.DATE to tr(R.string.msg_117), SortField.NAME to tr(R.string.msg_118), SortField.ARTIST to tr(R.string.msg_071))
        LibrarySection.Recents   -> listOf(SortField.DATE to tr(R.string.msg_117), SortField.NAME to tr(R.string.msg_118), SortField.ARTIST to tr(R.string.msg_071))
    }
    val curLabel = fields.firstOrNull { it.first == currentField }?.second ?: tr(R.string.msg_119)
    val dirArrow = if (currentDir == com.veacon.veilmusic.ui.viewmodel.SortDir.ASC) "↑" else "↓"
    var showDialog by remember { mutableStateOf(false) }

    // Round ⇅ sort button, right-aligned — matches the playlist detail's sort control.
    Row(
        modifier = Modifier.fillMaxWidth().returnToTabOnUp(onReturnToTab).background(com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFFFFFFF), Color(0xFF101012))).padding(start = 32.dp, top = 10.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$curLabel · ${if (currentDir == com.veacon.veilmusic.ui.viewmodel.SortDir.DESC) tr(R.string.msg_120) else tr(R.string.msg_121)}",
            color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)), fontSize = 13.sp,
            modifier = Modifier.padding(end = 12.dp))
        Surface(
            onClick = { showDialog = true },
            modifier = Modifier.focusRequester(sortFocus).semantics {contentDescription=tr(R.string.msg_122)}
                .focusProperties {if(listFocus!=null) {left=listFocus;down=listFocus}},
            shape  = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
            colors = com.veacon.veilmusic.ui.components.contentActionColors(com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D))),
            border = com.veacon.veilmusic.ui.components.contentActionBorder(RoundedCornerShape(50)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        ) {
            Box(Modifier.requiredSize(44.dp), contentAlignment = Alignment.Center) {
                com.veacon.veilmusic.ui.components.Icon(com.veacon.veilmusic.ui.components.Glyph.SORT, size = 22.dp, color = com.veacon.veilmusic.ui.theme.AppPalette.primary)
            }
        }
    }

    FadeThrough(showDialog, contentKey = { if (it) true else null }) { visible ->
            if (visible) {
            SortDialog(
                fields = fields,
                currentField = currentField,
                currentDir = currentDir,
                onPick = { f, d -> onSort(f, d) },
                onDismiss = { showDialog = false },
            )
        }
    }
}

/** Sort chooser popup: pick a field, then a direction. Real Dialog so D-pad focus
 *  stays inside it (a plain overlay Box lets focus walk out to the grid behind). */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SortDialog(
    fields: List<Pair<SortField, String>>,
    currentField: SortField,
    currentDir: com.veacon.veilmusic.ui.viewmodel.SortDir,
    onPick: (SortField, com.veacon.veilmusic.ui.viewmodel.SortDir) -> Unit,
    onDismiss: () -> Unit,
) {
    val Asc = com.veacon.veilmusic.ui.viewmodel.SortDir.ASC
    val Desc = com.veacon.veilmusic.ui.viewmodel.SortDir.DESC
    com.veacon.veilmusic.ui.components.MotionDialog(onDismissRequest = onDismiss) {
        val firstFocus = remember { FocusRequester() }
        Surface(
            shape = RoundedCornerShape(16.dp),
            colors = androidx.tv.material3.SurfaceDefaults.colors(containerColor = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFF5F5F7), Color(0xFF222225))),
            modifier = Modifier.width(340.dp),
        ) {
            Column(Modifier.menuInitialFocus(firstFocus).padding(vertical = 18.dp)) {
                Text(tr(R.string.msg_123), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = com.veacon.veilmusic.ui.theme.AppPalette.primary,
                    modifier = Modifier.padding(start = 22.dp, bottom = 10.dp))
                fields.forEachIndexed { i, (field, label) ->
                    SortRow(
                        label = label,
                        selected = field == currentField,
                        modifier = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier,
                    ) { onPick(field, currentDir) }
                }
                Box(Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 22.dp)
                    .height(1.dp).background(com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFDCDCE1), Color(0xFF414146))))
                SortRow(tr(R.string.msg_121), currentDir == Asc) { onPick(currentField, Asc) }
                SortRow(tr(R.string.msg_120), currentDir == Desc) { onPick(currentField, Desc) }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SortRow(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent, focusedContainerColor = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D)),
        ),
        modifier = modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, fontSize = 14.sp, color = com.veacon.veilmusic.ui.theme.AppPalette.primary,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.weight(1f))
            if (selected) Text("✓", fontSize = 15.sp, color = Color(0xFFFA233B))
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PlaylistGrid(
    playlists: List<LocalLibraryStore.LocalPlaylist>,
    pinnedIds: Set<String>,
    importing: Boolean,
    onOpen: (LocalLibraryStore.LocalPlaylist) -> Unit,
    onPlay: (LocalLibraryStore.LocalPlaylist) -> Unit,
    onMenu: (LocalLibraryStore.LocalPlaylist) -> Unit,
    onImport: () -> Unit,
    onReturnToTab: (() -> Unit)? = null,
    tabBarFocused: Boolean = false,
) {
    val gridState = rememberTabAwareGridState(tabBarFocused)
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(4),
        contentPadding = PaddingValues(start = 32.dp, top = 20.dp, bottom = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        // 第一格永远是「导入歌单」——本地资料库的主要入口。
        item(key = "import-card") {
            Box(Modifier.returnToTabOnUp(onReturnToTab)) {
                ImportCard(importing = importing, onClick = onImport)
            }
        }
        gridItemsIndexed(playlists, key = { _, item -> item.id }) { index, playlist ->
            Box(Modifier.librarySortOnRight(index%4==3 || index==playlists.lastIndex).returnToTabOnUp(if (index < 4) onReturnToTab else null)) {
                PlaylistCard(
                    playlist    = playlist,
                    pinned      = playlist.id in pinnedIds,
                    onOpen      = { onOpen(playlist) },
                    onPlay      = { onPlay(playlist) },
                    onMenu      = { onMenu(playlist) },
                )
            }
        }
    }
}

/** 「＋ 导入歌单」卡片：与歌单同尺寸，点击弹出导入对话框。 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ImportCard(importing: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFF5F5F7), Color(0xFF1C1C1F)),
            focusedContainerColor = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFEDEDF0), Color(0xFF2C2C31)),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
    ) {
        Column(modifier = Modifier.padding(bottom = 8.dp)) {
            Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                    .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
                    .background(com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF232327))),
                contentAlignment = Alignment.Center,
            ) {
                if (importing) {
                    CircularProgressIndicator(color = Color(0xFFFA233B), modifier = Modifier.size(28.dp))
                } else {
                    Text("＋", fontSize = 40.sp, color = Color(0xFFFA233B))
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
                Text("导入歌单", fontSize = 12.sp, color = com.veacon.veilmusic.ui.theme.AppPalette.primary, fontWeight = FontWeight.Medium)
                Text("QQ 音乐 / 网易云 / 酷狗", fontSize = 10.sp, maxLines = 1,
                    color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)))
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PlaylistCard(
    playlist: LocalLibraryStore.LocalPlaylist,
    pinned: Boolean,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onMenu: () -> Unit,
) {
    Surface(
        onClick      = onOpen,
        onLongClick  = onMenu,
        shape  = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFF5F5F7), Color(0xFF222225)), focusedContainerColor = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFEDEDF0), Color(0xFF414146))),
        scale  = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
    ) {
        Column(modifier = Modifier.padding(bottom = 8.dp)) {
            Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                    .focusedArtworkBorder(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp)).clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
                    .background(com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D))),
            ) {
                if (playlist.coverUrl != null) {
                    AsyncImage(
                        model = playlist.coverUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                        com.veacon.veilmusic.ui.components.Icon(com.veacon.veilmusic.ui.components.Glyph.MUSIC_NOTE, size = 32.dp, color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF8E8E93), Color(0xFFA3A3AA)))
                    }
                }
                if (pinned) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xCC000000))
                            .padding(horizontal = 5.dp, vertical = 3.dp),
                    ) {
                        Text("📌", fontSize = 10.sp)
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(playlist.name, fontSize = 12.sp, color = com.veacon.veilmusic.ui.theme.AppPalette.primary, maxLines = 1, fontWeight = FontWeight.Medium)
                    Text("${playlist.sourceLabel} · ${playlist.trackCount} 首", fontSize = 10.sp,
                        color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)), maxLines = 1)
                }
                Surface(
                    onClick = onPlay,
                    shape  = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                    colors = com.veacon.veilmusic.ui.components.contentActionColors(com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D))),
                    border = com.veacon.veilmusic.ui.components.contentActionBorder(RoundedCornerShape(50)),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
                    modifier = Modifier.size(28.dp),
                ) {
                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                        com.veacon.veilmusic.ui.components.Icon(com.veacon.veilmusic.ui.components.Glyph.PLAY, size = 11.dp, color = com.veacon.veilmusic.ui.theme.AppPalette.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumGrid(albums: List<Album>, onAlbumClick: (String) -> Unit, onReturnToTab: (() -> Unit)? = null, tabBarFocused: Boolean = false) {
    val gridState = rememberTabAwareGridState(tabBarFocused)
    if (albums.isEmpty()) { Box(Modifier.fillMaxSize(), Alignment.Center) { Text(tr(R.string.msg_125), color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF8E8E93), Color(0xFFA3A3AA))) }; return }
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(4),
        contentPadding = PaddingValues(start = 32.dp, top = 20.dp, bottom = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        gridItemsIndexed(albums, key = { _, item -> item.id }) { index, album ->
            AlbumCard(album = album, size = 150, onClick = { onAlbumClick(album.id) },
                modifier = Modifier.librarySortOnRight(index%4==3 || index==albums.lastIndex).returnToTabOnUp(if (index < 4) onReturnToTab else null))
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ArtistList(artists: List<Artist>, onArtistClick: (String) -> Unit, onReturnToTab: (() -> Unit)? = null, tabBarFocused: Boolean = false) {
    val listState = rememberTabAwareListState(tabBarFocused)
    if (artists.isEmpty()) { Box(Modifier.fillMaxSize(), Alignment.Center) { Text(tr(R.string.msg_126), color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF8E8E93), Color(0xFFA3A3AA))) }; return }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 32.dp, top = 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        itemsIndexed(artists, key = { _, item -> item.id }) { index, artist ->
            Surface(onClick = { onArtistClick(artist.id) }, modifier = Modifier.fillMaxWidth().returnToTabOnUp(if (index == 0) onReturnToTab else null), shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFF5F5F7), Color(0xFF222225))), scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(50)).background(com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D)))) {
                        if (artist.artworkUrl != null) AsyncImage(model = artist.artworkUrl(88), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    Text(artist.name, fontSize = 14.sp, color = com.veacon.veilmusic.ui.theme.AppPalette.primary, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RecentsList(
    songs: List<Song>,
    playerVm: PlayerViewModel,
    vm: LibraryViewModel,
    onArtistClick: (String) -> Unit = {},
    onAlbumClick: (String) -> Unit = {},
    onReturnToTab: (() -> Unit)? = null,
    tabBarFocused: Boolean = false,
) {
    SongList(
        songs = songs,
        playerVm = playerVm,
        vm = vm,
        onArtistClick = onArtistClick,
        onAlbumClick = onAlbumClick,
        onReturnToTab = onReturnToTab,
        tabBarFocused = tabBarFocused,
        emptyTitle = tr(R.string.msg_recent),
        emptyHint = tr(R.string.msg_recent_empty),
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SongList(
    songs: List<Song>,
    playerVm: PlayerViewModel,
    vm: LibraryViewModel,
    onArtistClick: (String) -> Unit = {},
    onAlbumClick: (String) -> Unit = {},
    onReturnToTab: (() -> Unit)? = null,
    tabBarFocused: Boolean = false,
    onImportPlaylist: (() -> Unit)? = null,
    emptyTitle: String = "还没有收藏的歌曲",
    emptyHint: String = "任意歌曲上长按 →「收藏到资料库」；歌单可在「播放列表」里导入 QQ 音乐 / 网易云 / 酷狗。",
) {
    val listState = rememberTabAwareListState(tabBarFocused)
    if (songs.isEmpty()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(emptyTitle, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    color = com.veacon.veilmusic.ui.theme.AppPalette.primary)
                Text(emptyHint,
                    fontSize = 13.sp,
                    color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)))
                // 空页如果只有文字，遥控器在资料库页会卡在右上角排序按钮上出不来。
                if (onImportPlaylist != null) {
                    Spacer(Modifier.height(6.dp))
                    Surface(
                        onClick = onImportPlaylist,
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color(0xFFFA233B),
                            focusedContainerColor = Color(0xFFFF4757),
                        ),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
                    ) {
                        Text(tr(R.string.msg_327), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            color = Color.White, modifier = Modifier.padding(horizontal = 24.dp, vertical = 11.dp))
                    }
                }
            }
        }
        return
    }
    var menuSong by remember { mutableStateOf<Song?>(null) }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 32.dp, top = 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val returnKeys=com.veacon.veilmusic.ui.navigation.occurrenceKeys(songs.map { it.id })
    items(songs.size, key = { returnKeys[it] }) { idx ->
            val song = songs[idx]
            Surface(
                onClick = { playerVm.playAlbum(songs, idx) },
                onLongClick = { menuSong = song },
                onContextMenu = { menuSong = song },
                modifier = Modifier.fillMaxWidth().returnToTabOnUp(if (idx == 0) onReturnToTab else null),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFF5F5F7), Color(0xFF222225))), scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f)) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(5.dp)).background(com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D)))) {
                        if (song.artworkUrl != null) AsyncImage(model = song.artworkUrl(80), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    Column(Modifier.weight(1f)) {
                        Text(song.title, fontSize = 13.sp, color = com.veacon.veilmusic.ui.theme.AppPalette.primary, maxLines = 1, fontWeight = FontWeight.Medium)
                        Text(song.artistName, fontSize = 11.sp, color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)), maxLines = 1)
                    }
                    Text(song.durationFormatted, fontSize = 11.sp, color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF8E8E93), Color(0xFFA3A3AA)))
                }
            }
        }
    }
    var pickerSong by remember { mutableStateOf<Song?>(null) }
    FadeThrough(menuSong, contentKey = { it?.id }) { retained ->
            retained?.let { s ->
            SongContextMenu(
                song = s,
                onDismiss = { menuSong = null },
                onPlayNext = { playerVm.playNext(s); menuSong = null },
                onAddToQueue = { playerVm.addToQueue(s); menuSong = null },
                onAddToLibrary = { playerVm.addToLibrary(s); menuSong = null },
                addToLibraryLabel = if (vm.isFavorite(s.id)) "取消收藏" else "收藏到资料库",
                onAddToPlaylist = { pickerSong = s; menuSong = null },
                onGoToArtist = s.artistId?.let { id -> { onArtistClick(id); menuSong = null } },
                onGoToAlbum  = s.albumId?.let  { id -> { onAlbumClick(id);  menuSong = null } },
                removeFromPlaylist = { vm.removeFavorite(s.id); menuSong = null },
            )
        }
    }
    FadeThrough(pickerSong, contentKey = { it?.id }) { retained ->
            retained?.let { s ->
            PlaylistPickerDialog(playerVm = playerVm, song = s, onDismiss = { pickerSong = null })
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun SongContextMenu(
    song: Song,
    onDismiss: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToLibrary: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    onGoToArtist: (() -> Unit)? = null,
    onGoToAlbum:  (() -> Unit)? = null,
    addToLibraryLabel: String? = null,
    removeFromPlaylist: (() -> Unit)? = null,
) {
    com.veacon.veilmusic.ui.components.MotionDialog(onDismissRequest = onDismiss) {
        val firstFocus = remember { androidx.compose.ui.focus.FocusRequester() }
        var awaitingOpeningRelease by remember { mutableStateOf(true) }
        Column(
            Modifier.menuInitialFocus(firstFocus).onPreviewKeyEvent { event ->
                if (awaitingOpeningRelease && event.key in setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)) {
                    if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
                        awaitingOpeningRelease = false
                        false
                    } else {
                        if (event.type == KeyEventType.KeyUp) awaitingOpeningRelease = false
                        true
                    }
                } else false
            }.width(320.dp).clip(RoundedCornerShape(16.dp)).background(com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFF5F5F7), Color(0xFF222225))).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // Header with artwork + song info
            Row(Modifier.padding(horizontal = 8.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)).background(com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D)))) {
                    if (song.artworkUrl != null) AsyncImage(model = song.artworkUrl(88), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Column {
                    Text(song.title, fontSize = 13.sp, color = com.veacon.veilmusic.ui.theme.AppPalette.primary, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(song.artistName, fontSize = 11.sp, color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)), maxLines = 1)
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D))))
            Spacer(Modifier.height(2.dp))
            ContextMenuItem(tr(R.string.msg_128), onPlayNext, Modifier.focusRequester(firstFocus))
            ContextMenuItem(tr(R.string.msg_129), onAddToQueue)
            if (onAddToLibrary  != null) ContextMenuItem(addToLibraryLabel ?: tr(R.string.msg_130),  onAddToLibrary)
            if (onAddToPlaylist != null) ContextMenuItem(tr(R.string.msg_131), onAddToPlaylist)
            if (onGoToArtist != null) ContextMenuItem(tr(R.string.msg_132), onGoToArtist)
            if (onGoToAlbum  != null) ContextMenuItem(tr(R.string.msg_133),  onGoToAlbum)
            if (removeFromPlaylist != null) ContextMenuItem("从歌单移除", removeFromPlaylist)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ContextMenuItem(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.padding(horizontal = 6.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D))),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Text(label, fontSize = 14.sp, color = com.veacon.veilmusic.ui.theme.AppPalette.primary, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp))
    }
}

@Composable
private fun NoMutPlaceholder() {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(tr(R.string.msg_134), color = com.veacon.veilmusic.ui.theme.AppPalette.primary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(tr(R.string.msg_135), color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)), fontSize = 13.sp)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SidebarItem(label: String, isSelected: Boolean, onClick: () -> Unit) {
    val bg     by animateColorAsState(if (isSelected) com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFF5F5F7), Color(0xFF222225)) else Color.Transparent, tween(225))
    val accent by animateColorAsState(if (isSelected) Color(0xFFFA233B) else Color.Transparent, tween(225))
    val text   by animateColorAsState(if (isSelected) com.veacon.veilmusic.ui.theme.AppPalette.primary else com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)), tween(225))
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(end = 12.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = bg, focusedContainerColor = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFEDEDF0), Color(0xFF414146)))) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.width(2.dp).height(20.dp).background(accent, RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp)))
            Text(label, fontSize = 13.sp, color = text, fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal, modifier = Modifier.padding(start = 14.dp))
        }
    }
}

/** "添加到播放列表" picker — lists the user's editable library playlists; tap one to append the song. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PlaylistPickerDialog(
    playerVm: com.veacon.veilmusic.ui.viewmodel.PlayerViewModel,
    song: Song,
    onDismiss: () -> Unit,
) {
    var playlists by remember { mutableStateOf<List<LocalLibraryStore.LocalPlaylist>?>(null) }
    LaunchedEffect(Unit) { playlists = playerVm.editablePlaylists() }
    com.veacon.veilmusic.ui.components.MotionDialog(onDismissRequest = onDismiss) {
        val firstFocus = remember { androidx.compose.ui.focus.FocusRequester() }
        Column(
            Modifier.menuInitialFocus(firstFocus, targetAvailable = !playlists.isNullOrEmpty()).width(360.dp).heightIn(max = 460.dp)
                .clip(RoundedCornerShape(16.dp)).background(com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFF5F5F7), Color(0xFF222225))).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(tr(R.string.msg_131), fontSize = 15.sp, color = com.veacon.veilmusic.ui.theme.AppPalette.primary, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D))))
            Spacer(Modifier.height(4.dp))
            val pls = playlists
            when {
                pls == null -> Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator(color = Color(0xFFFA233B))
                }
                pls.isEmpty() -> Text(tr(R.string.msg_136), color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)), fontSize = 13.sp,
                    modifier = Modifier.padding(16.dp))
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(pls.size) { i ->
                        val pl = pls[i]
                        ContextMenuItem(pl.name,
                            { playerVm.addToPlaylist(pl.id, pl.name, song); onDismiss() },
                            if (i == 0) Modifier.focusRequester(firstFocus) else Modifier)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SongLoadError(message: String, isLoading: Boolean, onRetry: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 32.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(message, fontSize = 14.sp, color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)), modifier = Modifier.weight(1f))
        Surface(onClick = { if (!isLoading) onRetry() },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
            colors = com.veacon.veilmusic.ui.components.contentActionColors(com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFF5F5F7), Color(0xFF222225))),
            border = com.veacon.veilmusic.ui.components.contentActionBorder(RoundedCornerShape(50))) {
            Text(if (isLoading) tr(R.string.msg_137) else tr(R.string.msg_138), color = Color(0xFFFA233B), fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
        }
    }
}

/** 导入歌单弹窗：粘贴链接或「歌名 - 歌手」列表。 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ImportPlaylistDialog(
    importing: Boolean,
    serverUrl: String,
    onDismiss: () -> Unit,
    onImport: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val fieldFocus = remember { FocusRequester() }
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fieldFocus.requestFocus() } }

    com.veacon.veilmusic.ui.components.MotionDialog(onDismissRequest = { if (!importing) onDismiss() }) {
        Column(
            Modifier.width(760.dp).clip(RoundedCornerShape(18.dp))
                .background(com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFF5F5F7), Color(0xFF222225)))
                .padding(26.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("导入歌单", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                color = com.veacon.veilmusic.ui.theme.AppPalette.primary)
            Text(
                "粘贴 QQ 音乐 / 网易云 / 酷狗的公开歌单链接；也可以直接粘贴多行「歌名 - 歌手」。",
                fontSize = 12.sp,
                color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)),
            )
            Text(
                "遥控器输入不便时，可在手机上打开「设置 · 手机配置网页」，用网页里的导入表单完成同样的事。",
                fontSize = 12.sp,
                color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF8E8E93), Color(0xFFA3A3AA)),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.weight(1f).height(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFFFFFFF), Color(0xFF2C2C31)))
                        .padding(horizontal = 18.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        textStyle = TextStyle(color = com.veacon.veilmusic.ui.theme.AppPalette.primary, fontSize = 15.sp),
                        cursorBrush = SolidColor(Color(0xFFFA233B)),
                        modifier = Modifier.fillMaxWidth().focusRequester(fieldFocus)
                            .onPreviewKeyEvent { ev ->
                                // 单行输入框会把方向键当光标移动、把返回当输入处理：
                                // 遥控器一旦进来就既到不了按钮也关不掉弹窗，必须在这里开口子。
                                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (ev.key) {
                                    Key.Back -> { if (!importing) onDismiss(); true }
                                    Key.DirectionDown -> runCatching { cancelFocus.requestFocus() }.isSuccess
                                    else -> false
                                }
                            },
                        decorationBox = { inner ->
                            if (text.isEmpty()) Text(
                                "https://y.qq.com/n/ryqq/playlist/…",
                                fontSize = 14.sp,
                                color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF8E8E93), Color(0xFFA3A3AA)),
                            )
                            inner()
                        },
                    )
                }
                // 二维码：扫码直达手机配置网页，用手机粘贴链接导入（遥控器输入不便的兜底）。
                if (serverUrl.isNotBlank()) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        com.veacon.veilmusic.ui.components.QrCode(content = serverUrl, size = 104.dp, cornerRadius = 12.dp, padding = 8.dp)
                        Text("扫码用手机导入", fontSize = 10.sp,
                            color = com.veacon.veilmusic.ui.theme.AppPalette.secondary)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    onClick = { if (!importing) onDismiss() },
                    modifier = Modifier.focusRequester(cancelFocus),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                    colors = com.veacon.veilmusic.ui.components.contentActionColors(com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D))),
                    border = com.veacon.veilmusic.ui.components.contentActionBorder(RoundedCornerShape(50)),
                ) {
                    Text("取消", fontSize = 14.sp, color = com.veacon.veilmusic.ui.theme.AppPalette.primary,
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 11.dp))
                }
                Surface(
                    onClick = { if (!importing && text.isNotBlank()) onImport(text) },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (importing) Color(0xFFB23A46) else Color(0xFFFA233B),
                        focusedContainerColor = Color(0xFFFF4757),
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
                ) {
                    Row(Modifier.padding(horizontal = 24.dp, vertical = 11.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        if (importing) CircularProgressIndicator(
                            color = Color.White, modifier = Modifier.size(14.dp), strokeWidth = 2.dp,
                        )
                        Text(if (importing) "导入中…" else "导入", fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold, color = Color.White)
                    }
                }
            }
        }
    }
}

/** 歌单长按菜单：置顶 / 重新导入 / 删除。 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PlaylistMenuDialog(
    playlist: LocalLibraryStore.LocalPlaylist,
    pinned: Boolean,
    onDismiss: () -> Unit,
    onReimport: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
) {
    com.veacon.veilmusic.ui.components.MotionDialog(onDismissRequest = onDismiss) {
        val firstFocus = remember { FocusRequester() }
        Column(
            Modifier.menuInitialFocus(firstFocus).width(320.dp).clip(RoundedCornerShape(16.dp))
                .background(com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFF5F5F7), Color(0xFF222225)))
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(playlist.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                color = com.veacon.veilmusic.ui.theme.AppPalette.primary, maxLines = 1,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
            Text("${playlist.sourceLabel} · ${playlist.trackCount} 首", fontSize = 11.sp,
                color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)),
                modifier = Modifier.padding(horizontal = 14.dp).padding(bottom = 6.dp))
            Box(Modifier.fillMaxWidth().height(1.dp)
                .background(com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D))))
            Spacer(Modifier.height(2.dp))
            ContextMenuItem(if (pinned) "取消置顶" else "置顶", onTogglePin, Modifier.focusRequester(firstFocus))
            if (playlist.sourceUrl != null) ContextMenuItem("重新导入", onReimport)
            ContextMenuItem("删除歌单", onDelete)
        }
    }
}
