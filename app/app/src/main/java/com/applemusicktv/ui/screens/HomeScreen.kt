package com.applemusicktv.ui.screens

import com.applemusicktv.i18n.tr
import com.applemusicktv.R

import com.applemusicktv.ui.components.pageHorizontalInset
import com.applemusicktv.ui.components.FadeThrough
import com.applemusicktv.ui.components.TouchSurface as Surface

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.applemusicktv.ui.viewmodel.PlayerViewModel
import androidx.hilt.navigation.compose.hiltViewModel
import com.applemusicktv.ui.navigation.ReturnLazyColumn as LazyColumn
import com.applemusicktv.ui.navigation.ReturnLazyRow as LazyRow
import androidx.compose.foundation.lazy.items
import com.applemusicktv.ui.components.returnToTabOnUp
import com.applemusicktv.ui.components.rememberTabAwareListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.tv.material3.*
import com.applemusicktv.data.model.Album
import com.applemusicktv.ui.components.AlbumCard
import com.applemusicktv.ui.viewmodel.HomeSection
import com.applemusicktv.ui.viewmodel.HomeViewModel

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeScreen(
    playerVm: PlayerViewModel,
    onAlbumClick: (String) -> Unit = {},
    onPlaylistClick: (id: String, name: String, artworkUrl: String) -> Unit = { _, _, _ -> },
    onCategoryClick: (String) -> Unit = {},
    vm: HomeViewModel = hiltViewModel(),
    modifier: Modifier = Modifier,
    onReturnToTab: (() -> Unit)? = null,
    tabBarFocused: Boolean = false,
    restoreTopAfterLogin: Boolean = false,
    onPostLoginTopRestored: () -> Unit = {},
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val lowPower by remember(playerVm) {
        playerVm.state.map { it.lowPowerMode }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(playerVm.state.value.lowPowerMode)
    val listState = rememberTabAwareListState(tabBarFocused)

    FadeThrough(state, modifier = modifier.fillMaxSize(), contentKey = { when { it.isLoading && it.sections.isEmpty() -> "loading"; it.error != null -> "error"; it.sections.isEmpty() -> "empty"; else -> "content" } }) { state ->
        if (state.isLoading && state.sections.isEmpty()) {
            com.applemusicktv.ui.components.StartupBrand(Modifier)
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

        if (state.sections.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(tr(R.string.msg_150), color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF8E8E93), Color(0xFFA3A3AA)), fontSize = 14.sp)
            }
            return@FadeThrough
        }

        RestorePostLoginHomeTop(listState, restoreTopAfterLogin, onPostLoginTopRestored)
        val firstContentRow = state.sections.indexOfFirst { it.albums.isNotEmpty() }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 100.dp, bottom = 102.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            itemsIndexed(state.sections, key = { _, item -> item.title }) { index, section ->
                val firstRow = index == firstContentRow
                Box(Modifier.returnToTabOnUp(if (firstRow) onReturnToTab else null)) {
                    // The hero row (a recommendation, never Recently Played) is chosen + styled server-side
                    // and on the direct path, so just render whatever style the section carries.
                    ContentRow(section, playerVm, onAlbumClick, onPlaylistClick, onCategoryClick, motionEnabled = !lowPower)
                }
            }
        }
    }
}

/** Consume once after real content is mounted; normal tab returns keep their saved position. */
@Composable
internal fun RestorePostLoginHomeTop(
    listState: androidx.compose.foundation.lazy.LazyListState,
    pending: Boolean,
    onRestored: () -> Unit,
) {
    val restored by rememberUpdatedState(onRestored)
    LaunchedEffect(pending) {
        if (pending) {
            withFrameNanos { }
            listState.scrollToItem(0, 0)
            restored()
        }
    }
}

@Composable
private fun ContentRow(
    section: HomeSection,
    playerVm: PlayerViewModel,
    onAlbumClick: (String) -> Unit,
    onPlaylistClick: (id: String, name: String, artworkUrl: String) -> Unit,
    onCategoryClick: (String) -> Unit,
    motionEnabled: Boolean = true,
) {
    val openCard: (Album) -> Unit = { album ->
        val isPlaylist = album.id.startsWith("pl.") || album.id.startsWith("p.")
        val isStation = album.id.startsWith("ra.")
        val isCategory = album.id.startsWith("ac-") || album.id.startsWith("c-") || album.id.startsWith("mr-")
        when {
            isCategory -> onCategoryClick(album.id)
            isStation  -> playerVm.playStation(album.id, album.artworkUrl(600), album.title)
            isPlaylist -> onPlaylistClick(album.id, album.title, album.artworkUrl(500) ?: "")
            else       -> onAlbumClick(album.id)
        }
    }

    // "为你精选" hero — big square lockups with the caption below (like apple.com).
    if (section.style == "picks") {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(homeSectionTitle(section.title), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = com.applemusicktv.ui.theme.AppPalette.primary,
                modifier = Modifier.padding(start = pageHorizontalInset, end = pageHorizontalInset, bottom = 14.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = pageHorizontalInset, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(section.albums, key = { it.id }, contentType = { "picks" }) { album ->
                    AlbumCard(
                        album = album, size = 210,
                        onClick = { openCard(album) },
                        onLongClick = { if (album.id.startsWith("pl.") || album.id.startsWith("p.")) playerVm.shufflePlayPlaylist(album.id) },
                        motionEnabled = motionEnabled,
                    )
                }
            }
        }
        return
    }

    // Spotlight rows ("为你打造的播放列表") render as big gradient cards.
    if (section.style == "gradient") {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(homeSectionTitle(section.title), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = com.applemusicktv.ui.theme.AppPalette.primary,
                modifier = Modifier.padding(start = pageHorizontalInset, end = pageHorizontalInset, bottom = 14.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = pageHorizontalInset, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(section.albums, key = { it.id }, contentType = { "gradient" }) { album ->
                    com.applemusicktv.ui.components.GradientCard(
                        album = album, width = 250,
                        onClick = { openCard(album) },
                        onLongClick = { if (album.id.startsWith("pl.") || album.id.startsWith("p.")) playerVm.shufflePlayPlaylist(album.id) },
                        motionEnabled = motionEnabled,
                    )
                }
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text       = homeSectionTitle(section.title),
            fontSize   = 22.sp,
            fontWeight = FontWeight.Bold,             color      = com.applemusicktv.ui.theme.AppPalette.primary,
            modifier   = Modifier.padding(start = pageHorizontalInset, end = pageHorizontalInset, bottom = 14.dp),
        )
        LazyRow(
            contentPadding        = PaddingValues(horizontal = pageHorizontalInset, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(section.albums, key = { it.id }, contentType = { "album" }) { album ->
                val isPlaylist = album.id.startsWith("pl.") || album.id.startsWith("p.")
                val isStation = album.id.startsWith("ra.")
                // "Find Your Mood" cards carry the CategoryScreen prefix ("ac-"/"c-").
                val isCategory = album.id.startsWith("ac-") || album.id.startsWith("c-") || album.id.startsWith("mr-")
                AlbumCard(
                    album = album,
                    size = 130,
                    onClick = {
                        when {
                            isCategory -> onCategoryClick(album.id)
                            isStation  -> playerVm.playStation(album.id, album.artworkUrl(600), album.title)
                            isPlaylist -> onPlaylistClick(album.id, album.title, album.artworkUrl(500) ?: "")
                            else       -> onAlbumClick(album.id)
                        }
                    },
                    onLongClick = {
                        if (isPlaylist) playerVm.shufflePlayPlaylist(album.id)
                    },
                    motionEnabled = motionEnabled,
                )
            }
        }
    }
}

/** Localize known editorial headings while retaining source titles and shelf identity. */
private fun homeSectionTitle(title: String): String =
    if (title.trim().equals("Top Picks for You", ignoreCase = true)) tr(R.string.msg_151) else com.applemusicktv.i18n.AppLanguage.heading(title)
