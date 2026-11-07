package com.veacon.veilmusic.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.veacon.veilmusic.R
import com.veacon.veilmusic.data.fonts.FontDownloadState
import com.veacon.veilmusic.data.fonts.FontDownloadPhase

val LocalAppFontState = androidx.compose.runtime.compositionLocalOf { FontDownloadState() }

/** 苹方（PingFang SC）——默认界面字体，随包内置，不支持切换。
 *
 * Apple Music 中文界面用的就是这套。资源里只放一个 Regular 字重：Android 在请求更粗的
 * 字重时会用 Typeface 合成加粗（fake bold），TV 上 2 米外观看足够。别把同一 resId 按多个
 * FontWeight 重复声明——那样 Compose 会认为"字重已命中"、反而不做合成加粗，标题全变 Regular。
 */
internal val PingFangFamily = FontFamily(Font(R.font.pingfang, FontWeight.Normal))

/** Retain system fallback until all persistent fonts pass validation, then update inherited
 * styles in place. Never key/recreate the app subtree: playback, navigation and focus survive.
 *
 * 苹方已内置，直接作为默认 family；运行时下载的 Noto CJK 保留为「苹方不可用」时的兜底，
 * 不再参与正常切换。
 */
@Composable
fun ProvideTvFontWeights(content: @Composable () -> Unit) {
    // 苹方已随包内置，字体无需再下载：提供一个恒 Ready 的状态，歌词 gate 直接放行，
    // 不再有「歌词字体加载中 0%」。运行时下载的 Noto CJK 机制彻底弃用。
    val state = FontDownloadState(
        downloadedBytes = FontDownloadState().totalBytes,
        phase = FontDownloadPhase.Ready,
    )
    val family = PingFangFamily
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
