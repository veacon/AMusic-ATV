package com.applemusicktv.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.applemusicktv.R

/** Render just the Apple symbol from the original supplied wordmark; the source image is unchanged. */
@Composable
fun AppleSymbol(modifier: Modifier = Modifier, color: Color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF1D1D1F), Color(0xFFF5F5F7))) {
    val mark = ImageBitmap.imageResource(R.drawable.apple_music_wordmark)
    Canvas(modifier) {
        drawImage(mark, srcOffset = IntOffset.Zero, srcSize = IntSize(230, 300),
            dstOffset = IntOffset.Zero, dstSize = IntSize(size.width.toInt(), size.height.toInt()),
            colorFilter = ColorFilter.tint(color))
    }
}

@Composable
fun ProfileAvatar(url: String?, modifier: Modifier = Modifier, onLoadError: () -> Unit = {}) {
    var failed by remember(url) { mutableStateOf(false) }
    Box(modifier.size(44.dp).clip(CircleShape).background(Color.White)
        .border(0.5.dp, com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFD9D9DE), Color(0xFF48484D)), CircleShape), Alignment.Center) {
        AppleSymbol(Modifier.size(width = 23.dp, height = 30.dp))
        if (url != null && !failed) AsyncImage(model = url, contentDescription = null,
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            onError = { failed = true; onLoadError() })
    }
}
