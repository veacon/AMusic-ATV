package com.applemusicktv.ui.navigation

import com.applemusicktv.i18n.tr
import com.applemusicktv.R

enum class TopNavTab(private val labelId: Int) {
    NowPlaying(R.string.msg_086),
    ListenNow(R.string.msg_087),
    Browse(R.string.msg_088),
    Library(R.string.msg_089),
    Search(R.string.msg_090),
    Dev(0);
    val label: String get() = if (labelId == 0) "⚙" else tr(labelId)


}
