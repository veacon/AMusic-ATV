package com.veacon.veilmusic.ui.viewmodel

import com.veacon.veilmusic.i18n.tr
import com.veacon.veilmusic.R

import android.os.SystemClock

enum class LibrarySection(private val labelId: Int) {
    // 侧边栏顺序即声明顺序。「最近播放」排在第三位（歌曲 / 歌单 / 最近播放 / 专辑 / 艺人），
    // 因为它是日常使用频率最高的入口，不该沉到列表最末。
    Songs(R.string.msg_068), Playlists(R.string.msg_069), Recents(R.string.msg_recent),
    Albums(R.string.msg_070), Artists(R.string.msg_071);
    val label: String get() = tr(labelId)


}

/** Freshness belongs to each category, including successful empty results. No polling. */
internal class LibraryRefreshPolicy(private val now: () -> Long = SystemClock::elapsedRealtime) {
    private val refreshed = mutableMapOf<LibrarySection, Long>()
    fun isFresh(section: LibrarySection): Boolean = refreshed[section]?.let { now()-it in 0 until TTL_MS } == true
    fun succeeded(section: LibrarySection) { refreshed[section] = now() }
    fun invalidate() { refreshed.clear() }
    companion object { const val TTL_MS = 300_000L }
}
