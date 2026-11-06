package com.applemusicktv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.applemusicktv.util.TvDevice
import com.applemusicktv.data.fonts.AppFonts
import com.applemusicktv.data.fonts.FontDownloadState
import java.io.File

val LocalAppFontState = androidx.compose.runtime.compositionLocalOf { FontDownloadState() }

/** Retain system fallback until all persistent fonts pass validation, then update inherited
 * styles in place. Never key/recreate the app subtree: playback, navigation and focus survive.
 */
@Composable
fun ProvideTvFontWeights(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val fonts = remember(context) { AppFonts.get(context) }
    val state by fonts.state.collectAsState()
    val family = remember(context, state.ready) {
        if (state.ready) {
            val (regular, medium, bold) = fonts.files()
            FontFamily(
                Font(regular, FontWeight.Normal),
                Font(medium, FontWeight.Medium),
                // Existing 600 requests deliberately use the real 700 outlines.
                Font(bold, FontWeight.SemiBold),
                Font(bold, FontWeight.Bold),
                Font(bold, FontWeight.ExtraBold),
                Font(bold, FontWeight.Black),
            )
        } else {
            val regular = File("/system/fonts/Roboto-Regular.ttf")
            val bold = File("/system/fonts/MILanTingBold.ttf")
            if (TvDevice.usesTouchControls(context) || !regular.canRead() || !bold.canRead()) null
            else FontFamily(
                Font(regular, FontWeight.Normal),
                Font(bold, FontWeight.Medium),
                Font(bold, FontWeight.SemiBold),
                Font(bold, FontWeight.Bold),
                Font(bold, FontWeight.ExtraBold),
                Font(bold, FontWeight.Black),
            )
        }
    }
    ProvideAppFontState(state, family, content)
}

@Composable
internal fun ProvideAppFontState(state: FontDownloadState, family: FontFamily?, content: @Composable () -> Unit) {
    val material = androidx.compose.material3.LocalTextStyle.current
    val television = androidx.tv.material3.LocalTextStyle.current
    // Keep the exact same content call site even when the initial system family is null.
    // Adding/removing a provider branch here would remount AppShell on the first download.
    CompositionLocalProvider(
        LocalAppFontState provides state,
        androidx.compose.material3.LocalTextStyle provides material.copy(fontFamily = family ?: material.fontFamily),
        androidx.tv.material3.LocalTextStyle provides television.copy(fontFamily = family ?: television.fontFamily),
        content = content,
    )
}
