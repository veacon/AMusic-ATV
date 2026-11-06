package com.applemusicktv.ui.components

import com.applemusicktv.i18n.tr
import com.applemusicktv.R

import com.applemusicktv.ui.components.TouchCard as Card

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import coil.compose.AsyncImage
import com.applemusicktv.data.model.Album

/** Parse Apple's bare-hex `artworkBgColor` ("1a2b3c") into an opaque Compose color. */
private fun bgColor(hex: String?, fallback: Long = 0xFF1A1A1E): Color {
    if (hex.isNullOrBlank()) return Color(fallback)
    return runCatching { Color(("FF" + hex.removePrefix("#").take(6)).toLong(16)) }.getOrDefault(Color(fallback))
}

/** The small uppercase label above a spotlight card, derived from the item type (Apple's own). */
private fun spotlightLabel(album: Album): String = when {
    album.tagline?.isNotBlank() == true -> album.tagline!!.uppercase()
    album.type == "playlists" -> tr(R.string.msg_293)
    album.type == "stations" || album.id.startsWith("ra.") -> tr(R.string.msg_294)
    album.type == "songs" -> tr(R.string.msg_295)
    album.type == "music-videos" -> tr(R.string.msg_296)
    else -> tr(R.string.msg_297)
}

/**
 * Big landscape editorial card for the Browse "新内容" spotlight row. Layout matches apple.com: the
 * label / title / subtitle sit ABOVE the image; the wide art (its own aspect ratio) sits below with
 * the short editorial blurb captioned bottom-left over a soft scrim.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SpotlightHeroCard(album: Album, width: Int = 360, onClick: () -> Unit) {
    val tint = bgColor(album.artworkBgColor)
    Card(
        onClick = onClick,
        modifier = Modifier.width(width.dp),
        scale = CardDefaults.scale(focusedScale = 1.055f, pressedScale = 0.97f),
        glow = CardDefaults.glow(glow = Glow.None, focusedGlow = Glow.None, pressedGlow = Glow.None),
        border = CardDefaults.border(border = Border.None, focusedBorder = Border.None, pressedBorder = Border.None),
        colors = CardDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
        shape = CardDefaults.shape(RoundedCornerShape(9.dp)),
    ) {
        Column(Modifier.width(width.dp).padding(horizontal = 2.dp)) {
            // Fixed-height text block so every card's image starts at the same Y — radio/curator
            // cards have no artist line and were misaligning the row.
            Column(Modifier.height(58.dp)) {
                Text(spotlightLabel(album), color = com.applemusicktv.ui.theme.AppPalette.secondary, fontSize = 10.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp, maxLines = 1)
                Spacer(Modifier.height(3.dp))
                Text(album.title, color = com.applemusicktv.ui.theme.AppPalette.primary, fontSize = 15.sp, fontWeight = FontWeight.Bold,                     letterSpacing = (-0.2).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(album.artistName.ifBlank { " " }, color = com.applemusicktv.ui.theme.AppPalette.secondary, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp))
            }
            Spacer(Modifier.height(9.dp))
            // Native-ratio artwork; focus uses scale instead of a frame.
            Box(Modifier.fillMaxWidth().aspectRatio(1.86f)
                .focusedArtworkBorder(RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp))
                .background(tint)) {
                val art = album.wideArtworkUrl ?: album.artworkUrl(width * 2)
                if (art != null) {
                    AsyncImage(model = art, contentDescription = album.title,
                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                album.editorialNotes?.takeIf { it.isNotBlank() }?.let { note ->
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                        0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.78f))))
                    Text(note, color = Color.White.copy(alpha = 0.95f), fontSize = 10.5.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.BottomStart).padding(10.dp))
                }
            }
        }
    }
}

/**
 * Big gradient card for Home's personalized rows ("为你精选", "为你打造的播放列表").
 * Square artwork fills the card; a color-tinted gradient scrim carries a bold title at the bottom.
 * Focused cards on the "为你打造的播放列表" shelf play their motion loop.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun GradientCard(album: Album, width: Int = 250, onClick: () -> Unit, onLongClick: () -> Unit = {}, motionEnabled: Boolean = true) {
    var focused by remember { mutableStateOf(false) }
    val tint = bgColor(album.artworkBgColor)
    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier.width(width.dp).onFocusChanged { focused = it.isFocused || it.hasFocus },
        scale = CardDefaults.scale(focusedScale = 1.066f, pressedScale = 0.97f),
        glow = CardDefaults.glow(glow = Glow.None, focusedGlow = Glow.None, pressedGlow = Glow.None),
        border = CardDefaults.border(border = Border.None, focusedBorder = Border.None, pressedBorder = Border.None),
        colors = CardDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
        shape = CardDefaults.shape(RoundedCornerShape(16.dp)),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).focusedArtworkBorder(RoundedCornerShape(15.dp)).clip(RoundedCornerShape(15.dp)).background(tint)) {
            if (album.artworkUrl != null) {
                AsyncImage(model = album.artworkUrl(width * 2), contentDescription = album.title,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                if (motionEnabled) album.motionUrl?.let { mu -> MotionArtwork(mu, play = focused, modifier = Modifier.fillMaxSize()) }
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                0.4f to Color.Transparent,
                1f to tint.copy(alpha = 0.92f),
            )))
            Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
                Text(album.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,                     letterSpacing = (-0.2).sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (album.artistName.isNotBlank()) {
                    Text(album.artistName, color = Color.White.copy(alpha = 0.78f), fontSize = 11.5.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}
