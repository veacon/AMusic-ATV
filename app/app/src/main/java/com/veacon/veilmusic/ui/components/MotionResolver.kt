package com.veacon.veilmusic.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import com.veacon.veilmusic.data.model.Album

/**
 * Lazily resolves a card's animated-artwork loop URL (Album -> motion HLS URL, or null).
 *
 * Provided once in AppShell so every AlbumCard can animate on focus without threading a callback
 * through eight callers. The default is a no-op so cards rendered outside the shell (previews,
 * onboarding) just stay static. Keep it a single-decoder-at-a-time affair — only the focused card
 * plays — because Fire TV has almost no memory headroom (it LMK-kills this app under pressure).
 */
val LocalCardMotion = staticCompositionLocalOf<suspend (Album) -> String?> { { null } }
