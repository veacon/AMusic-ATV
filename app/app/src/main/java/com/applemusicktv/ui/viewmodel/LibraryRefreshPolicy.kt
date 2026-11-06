package com.applemusicktv.ui.viewmodel

import com.applemusicktv.i18n.tr
import com.applemusicktv.R

import android.os.SystemClock

enum class LibrarySection(private val labelId: Int) {
    Songs(R.string.msg_068), Playlists(R.string.msg_069), Albums(R.string.msg_070), Artists(R.string.msg_071);
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
