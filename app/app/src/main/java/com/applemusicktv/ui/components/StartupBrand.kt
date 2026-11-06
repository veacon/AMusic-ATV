package com.applemusicktv.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.applemusicktv.R

const val StartupLogoWidthDp = 245.76f
const val StartupLogoAspect = 74.117f / 14.076f

val StartupRed = Color(0xFFF53540)

@Composable
fun StartupBrand(modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize().background(StartupRed), Alignment.Center) {
        Image(painterResource(R.drawable.startup_wordmark), contentDescription = "Apple Music",
            modifier = Modifier.width((maxWidth * 0.8f).coerceAtMost(StartupLogoWidthDp.dp)).aspectRatio(StartupLogoAspect))
    }
}

/** The content is already drawn beneath the overlay when data (or an error/setup page) is ready. */
@Composable
fun StartupHost(content: @Composable (() -> Unit) -> Unit) {
    var ready by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(StartupRed)) {
        content { ready = true }
        AnimatedVisibility(visible = !ready, enter = EnterTransition.None, exit = fadeOut(tween(750))) {
            StartupBrand()
        }
    }
}
