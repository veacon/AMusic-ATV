package com.veacon.veilmusic.ui.screens

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.veacon.veilmusic.R
import com.veacon.veilmusic.ui.background.FluidQuality
import com.veacon.veilmusic.ui.background.IdleArtworkPool
import com.veacon.veilmusic.ui.background.MacBackground

/** Pick once per empty-player entry; later image completions never keep changing its palette. */
@Composable
internal fun IdlePlayerBackground(quality: FluidQuality, reduceMotion: Boolean, lowPower: Boolean,
    artworkSources: kotlinx.coroutines.flow.StateFlow<List<String>> = IdleArtworkPool.urls) {
    val loaded by artworkSources.collectAsState()
    var chosen by remember {mutableStateOf<String?>(null)}
    LaunchedEffect(loaded) {if(chosen==null && loaded.isNotEmpty()) chosen=loaded.random()}
    // The existing app artwork gives a palette even before the first shelf image finishes.
    val fallback="android.resource://${LocalContext.current.packageName}/${R.mipmap.ic_launcher}"
    MacBackground(artworkUrl=chosen?:fallback,quality=quality,playing=true,reduceMotion=reduceMotion,lowPower=lowPower)
}
