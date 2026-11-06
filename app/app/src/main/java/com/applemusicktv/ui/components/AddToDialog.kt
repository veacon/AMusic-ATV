package com.applemusicktv.ui.components

import com.applemusicktv.i18n.tr
import com.applemusicktv.R

import com.applemusicktv.ui.components.TouchSurface as Surface

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.*
import com.applemusicktv.data.model.Song
import com.applemusicktv.data.network.PlaylistDto
import com.applemusicktv.ui.viewmodel.PlayerViewModel

/**
 * The single "添加到…" sheet used everywhere a song can be added — long-press menus and the Now
 * Playing ··· menu. First row is **Library**, then the user's editable playlists (Apple's own order).
 * Tapping one adds the song and dismisses. Reused so every surface stays identical.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AddToDialog(playerVm: PlayerViewModel, song: Song, onDismiss: () -> Unit) {
    var playlists by remember { mutableStateOf<List<PlaylistDto>?>(null) }
    LaunchedEffect(Unit) { playlists = playerVm.editablePlaylists() }
    com.applemusicktv.ui.components.MotionDialog(onDismissRequest = onDismiss) {
        val firstFocus = remember { FocusRequester() }
        Column(
            Modifier.menuInitialFocus(firstFocus).width(360.dp).heightIn(max = 480.dp)
                .clip(RoundedCornerShape(16.dp)).background(com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFF5F5F7), Color(0xFF222225))).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(tr(R.string.msg_148), fontSize = 15.sp, color = com.applemusicktv.ui.theme.AppPalette.primary, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D))))
            Spacer(Modifier.height(4.dp))
            // Library always first.
            AddRow(Glyph.PLUS, tr(R.string.msg_089), { playerVm.addToLibrary(song); onDismiss() },
                Modifier.focusRequester(firstFocus))
            Box(Modifier.fillMaxWidth().height(1.dp).background(com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D))).padding(vertical = 2.dp))
            val pls = playlists
            when {
                pls == null -> Box(Modifier.fillMaxWidth().padding(20.dp), Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator(color = Color(0xFFFA233B))
                }
                pls.isEmpty() -> Text(tr(R.string.msg_136), color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)), fontSize = 13.sp,
                    modifier = Modifier.padding(16.dp))
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(pls) { pl ->
                        AddRow(Glyph.QUEUE, pl.name, { playerVm.addToPlaylist(pl.id, pl.name, song); onDismiss() })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AddRow(icon: Glyph, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
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
            Text(label, fontSize = 15.sp, color = com.applemusicktv.ui.theme.AppPalette.primary, maxLines = 1)
        }
    }
}
