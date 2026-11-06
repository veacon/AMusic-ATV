package com.applemusicktv.ui.viewmodel

import com.applemusicktv.i18n.tr
import com.applemusicktv.R

internal enum class PlaybackOrder(private val labelId: Int) {
    Sequential(R.string.msg_074), RepeatAll(R.string.msg_075), RepeatOne(R.string.msg_076), Shuffle(R.string.msg_077);
    val label: String get() = tr(labelId)


}

internal fun PlayerState.playbackOrder(): PlaybackOrder = when {
    isShuffled -> PlaybackOrder.Shuffle
    repeatMode == RepeatMode.All -> PlaybackOrder.RepeatAll
    repeatMode == RepeatMode.One -> PlaybackOrder.RepeatOne
    else -> PlaybackOrder.Sequential
}

/** Changing modes retains the exact playing occurrence and never starts a new song. */
internal fun PlayerState.nextPlaybackOrder(shuffle: (List<com.applemusicktv.data.model.Song>) -> List<com.applemusicktv.data.model.Song> = { it.shuffled() }): PlayerState =
    when (playbackOrder()) {
        PlaybackOrder.Sequential -> copy(repeatMode = RepeatMode.All)
        PlaybackOrder.RepeatAll -> copy(repeatMode = RepeatMode.One)
        PlaybackOrder.RepeatOne -> {
            val split = (queueIndex + 1).coerceIn(0, queue.size)
            copy(isShuffled = true, repeatMode = RepeatMode.All, originalQueue = queue,
                queue = queue.take(split) + shuffle(queue.drop(split)))
        }
        PlaybackOrder.Shuffle -> {
            val (restored, index) = restoreQueueOrder(queue, originalQueue, queueIndex)
            copy(isShuffled = false, repeatMode = RepeatMode.Off, originalQueue = emptyList(),
                queue = restored, queueIndex = index)
        }
    }
