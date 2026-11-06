package com.applemusicktv.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Shared page gutter, capped on wide screens. */
internal fun settingsPageInset(viewportWidth: Dp): Dp =
    (40.dp + (viewportWidth - 880.dp).coerceAtLeast(0.dp) / 2).coerceAtMost(120.dp)

private val LocalPageHorizontalInset = staticCompositionLocalOf<Dp?> { null }

/** Read at the full page, never from the width of a nested card/column. */
val pageHorizontalInset: Dp
    @Composable get() = LocalPageHorizontalInset.current
        ?: settingsPageInset(LocalConfiguration.current.screenWidthDp.dp)

@Composable
fun PageLayout(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalPageHorizontalInset provides settingsPageInset(maxWidth)) {
            content()
        }
    }
}
