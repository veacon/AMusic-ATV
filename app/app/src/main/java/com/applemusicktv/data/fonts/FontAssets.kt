package com.applemusicktv.data.fonts

import com.applemusicktv.i18n.tr
import com.applemusicktv.R

internal data class FontAsset(val fileName: String, val bytes: Long, val sha256: String, val urls: List<String>)

internal object FontAssets {
    const val VERSION = "f8d157532fbfaeda587e826d4cd5b21a49186f7c"
    const val RETRY_MS = 60_000L
    private fun asset(weight: String, bytes: Long, sha: String): FontAsset {
        val name = "NotoSansCJKsc-$weight.otf"
        val path = "Sans/OTF/SimplifiedChinese/$name"
        return FontAsset(name, bytes, sha, listOf(
            "https://mirrors.nju.edu.cn/CTAN/fonts/notocjksc/$name",
            "https://mirrors.tuna.tsinghua.edu.cn/CTAN/fonts/notocjksc/$name",
            "https://cdn.jsdelivr.net/gh/notofonts/noto-cjk@$VERSION/$path",
        ))
    }
    val all = listOf(
        asset("Regular", 16_437_364, "2c76254f6fc379fddfce0a7e84fb5385bb135d3e399294f6eeb6680d0365b74b"),
        asset("Medium", 16_523_632, "ca094f6b0001fb048ca39ddd797a0cdb0179e1e55c6561e111c49c3e6a61d7b7"),
        asset("Bold", 17_002_248, "b5f0d1a190a7f9b43c310a8850630af12553df32c4c050543f9059732d9b4c0a"),
    )
    val totalBytes = all.sumOf { it.bytes }
}

enum class FontDownloadPhase { Loading, Failed, Ready }

data class FontDownloadState(
    val downloadedBytes: Long = 0,
    val totalBytes: Long = FontAssets.totalBytes,
    val phase: FontDownloadPhase = FontDownloadPhase.Loading,
) {
    val ready: Boolean get() = phase == FontDownloadPhase.Ready
    val percent: Int get() = if (ready) 100 else
        (downloadedBytes.coerceIn(0, totalBytes) * 100 / totalBytes.coerceAtLeast(1)).toInt().coerceAtMost(99)
    val lyricsMessage: String get() = if (phase == FontDownloadPhase.Failed)
        tr(R.string.msg_308) else tr(R.string.msg_309, percent)
}
