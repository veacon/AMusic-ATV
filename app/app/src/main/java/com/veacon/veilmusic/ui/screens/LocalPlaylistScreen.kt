package com.veacon.veilmusic.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicTextField
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.*
import coil.compose.AsyncImage
import com.veacon.veilmusic.data.model.Song
import com.veacon.veilmusic.ui.components.TouchSurface as Surface
import com.veacon.veilmusic.ui.components.FadeThrough
import com.veacon.veilmusic.ui.components.contentActionColors
import com.veacon.veilmusic.ui.components.contentActionBorder
import com.veacon.veilmusic.ui.components.pageHorizontalInset
import com.veacon.veilmusic.ui.components.rememberTabAwareListState
import com.veacon.veilmusic.ui.viewmodel.LibraryViewModel
import com.veacon.veilmusic.ui.viewmodel.PlayerViewModel

/**
 * 本地歌单详情：收藏/导入的歌单都在这里打开。
 * 数据来自 [LibraryViewModel]（底层 LocalLibraryStore），曲目点击直接走落雪解析播放。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LocalPlaylistScreen(
    playlistId: String,
    playerVm: PlayerViewModel,
    onBack: () -> Unit = {},
    onArtistClick: (String) -> Unit = {},
    onAlbumClick: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    onReturnToTab: (() -> Unit)? = null,
    tabBarFocused: Boolean = false,
    vm: LibraryViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val playlist = remember(state.playlists, playlistId) {
        state.playlists.firstOrNull { it.id == playlistId }
    }
    val songs = playlist?.songs.orEmpty()
    val listState = rememberTabAwareListState(tabBarFocused)
    var menuSong by remember { mutableStateOf<Song?>(null) }
    var pickerSong by remember { mutableStateOf<Song?>(null) }
    var showRename by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current

    Column(modifier.fillMaxSize().padding(horizontal = pageHorizontalInset)) {
        // 头部：封面 + 名称 + 来源 + 播放全部
        Row(
            Modifier.fillMaxWidth().padding(top = 92.dp, bottom = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(150.dp).clip(RoundedCornerShape(10.dp))
                    .background(com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D))),
                contentAlignment = Alignment.Center,
            ) {
                val cover = playlist?.coverUrl
                if (cover != null) {
                    AsyncImage(model = cover, contentDescription = null,
                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    com.veacon.veilmusic.ui.components.Icon(
                        com.veacon.veilmusic.ui.components.Glyph.MUSIC_NOTE, size = 40.dp,
                        color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF8E8E93), Color(0xFFA3A3AA)),
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    playlist?.name ?: "本地歌单",
                    fontSize = 28.sp, fontWeight = FontWeight.Bold,
                    color = com.veacon.veilmusic.ui.theme.AppPalette.primary,
                )
                Text(
                    buildString {
                        playlist?.let { append(it.sourceLabel).append(" · ") }
                        append(songs.size).append(" 首")
                        if (playlist?.isImported == true) append(" · 导入")
                    },
                    fontSize = 13.sp,
                    color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)),
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    onClick = { if (songs.isNotEmpty()) playerVm.playAlbum(songs, 0) },
                    modifier = com.veacon.veilmusic.ui.navigation.rememberDetailPlayEntryFocus(
                        ready = songs.isNotEmpty(), loading = playlist == null),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color(0xFFFA233B),
                        focusedContainerColor = Color(0xFFFF4757),
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
                ) {
                    Row(Modifier.padding(horizontal = 22.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        com.veacon.veilmusic.ui.components.Icon(
                            com.veacon.veilmusic.ui.components.Glyph.PLAY, size = 13.dp, color = Color.White)
                        Text("播放全部", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    }
                }
                Surface(
                    onClick = { showRename = true },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D)),
                        focusedContainerColor = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFD9D9DE), Color(0xFF4A4A50)),
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
                ) {
                    Text("改名", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        color = com.veacon.veilmusic.ui.theme.AppPalette.primary,
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp))
                }
                }
            }
        }

        if (songs.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), Alignment.Center) {
                Text("这个歌单还没有歌曲", fontSize = 14.sp,
                    color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF8E8E93), Color(0xFFA3A3AA)))
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(top = 8.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(songs, key = { _, s -> s.id }) { idx, song ->
                    Surface(
                        onClick = { playerVm.playAlbum(songs, idx) },
                        onLongClick = { menuSong = song },
                        onContextMenu = { menuSong = song },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color.Transparent,
                            focusedContainerColor = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFF5F5F7), Color(0xFF222225)),
                        ),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("${idx + 1}", fontSize = 12.sp, modifier = Modifier.width(26.dp),
                                color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF8E8E93), Color(0xFFA3A3AA)))
                            Box(
                                Modifier.size(40.dp).clip(RoundedCornerShape(5.dp))
                                    .background(com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D))),
                            ) {
                                song.artworkUrl?.let {
                                    AsyncImage(model = it, contentDescription = null,
                                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                }
                            }
                            Column(Modifier.weight(1f)) {
                                Text(song.title, fontSize = 13.sp, maxLines = 1, fontWeight = FontWeight.Medium,
                                    color = com.veacon.veilmusic.ui.theme.AppPalette.primary)
                                Text(
                                    listOf(song.artistName, song.albumName).filter { it.isNotBlank() }.joinToString(" · "),
                                    fontSize = 11.sp, maxLines = 1,
                                    color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)),
                                )
                            }
                            if (song.durationMs > 0) {
                                Text(song.durationFormatted, fontSize = 11.sp,
                                    color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF8E8E93), Color(0xFFA3A3AA)))
                            }
                        }
                    }
                }
            }
        }
    }

    FadeThrough(menuSong, contentKey = { it?.id }) { retained ->
        retained?.let { s ->
            SongContextMenu(
                song = s,
                onDismiss = { menuSong = null },
                onPlayNext = { playerVm.playNext(s); menuSong = null },
                onAddToQueue = { playerVm.addToQueue(s); menuSong = null },
                onAddToLibrary = {
                    val added = vm.toggleFavorite(s)
                    android.widget.Toast.makeText(
                        ctx,
                        if (added) "已收藏「${s.title}」" else "已取消收藏「${s.title}」",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                    menuSong = null
                },
                onAddToPlaylist = { pickerSong = s; menuSong = null },
                onGoToArtist = s.artistId?.let { id -> { onArtistClick(id); menuSong = null } },
                onGoToAlbum = s.albumId?.let { id -> { onAlbumClick(id); menuSong = null } },
                removeFromPlaylist = {
                    vm.removeSongFromPlaylist(playlistId, s.id)
                    android.widget.Toast.makeText(
                        ctx,
                        "已从歌单移除「${s.title}」", android.widget.Toast.LENGTH_SHORT,
                    ).show()
                    menuSong = null
                },
            )
        }
    }

    FadeThrough(pickerSong, contentKey = { it?.id }) { retained ->
        retained?.let { s ->
            com.veacon.veilmusic.ui.components.AddToDialog(
                playerVm = playerVm, song = s, onDismiss = { pickerSong = null },
            )
        }
    }

    if (showRename && playlist != null) {
        RenamePlaylistDialog(
            initialName = playlist.name,
            onDismiss = { showRename = false },
            onRename = { newName ->
                vm.renamePlaylist(playlistId, newName)
                showRename = false
            },
        )
    }
}

@Composable
private fun RenamePlaylistDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    val fieldFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fieldFocus.requestFocus() } }
    com.veacon.veilmusic.ui.components.MotionDialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(520.dp).clip(RoundedCornerShape(18.dp))
                .background(com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFF5F5F7), Color(0xFF222225)))
                .padding(26.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("修改歌单名称", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                color = com.veacon.veilmusic.ui.theme.AppPalette.primary)
            Box(
                Modifier.fillMaxWidth().height(52.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFFFFFFF), Color(0xFF2C2C31)))
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    textStyle = TextStyle(color = com.veacon.veilmusic.ui.theme.AppPalette.primary, fontSize = 15.sp),
                    cursorBrush = SolidColor(Color(0xFFFA233B)),
                    modifier = Modifier.fillMaxWidth().focusRequester(fieldFocus)
                        .onPreviewKeyEvent { ev ->
                            if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            if (ev.key == Key.Back) { onDismiss(); true } else false
                        },
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                Surface(
                    onClick = onDismiss,
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                    colors = com.veacon.veilmusic.ui.components.contentActionColors(
                        com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D))),
                    border = com.veacon.veilmusic.ui.components.contentActionBorder(RoundedCornerShape(50)),
                ) {
                    Text("取消", fontSize = 14.sp, color = com.veacon.veilmusic.ui.theme.AppPalette.primary,
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 11.dp))
                }
                Surface(
                    onClick = { if (name.isNotBlank()) onRename(name.trim()) },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color(0xFFFA233B),
                        focusedContainerColor = Color(0xFFFF4757),
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
                ) {
                    Text("保存", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 11.dp))
                }
            }
        }
    }
}
