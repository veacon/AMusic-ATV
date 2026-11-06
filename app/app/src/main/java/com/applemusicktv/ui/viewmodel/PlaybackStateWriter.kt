package com.applemusicktv.ui.viewmodel

import android.content.SharedPreferences
import com.applemusicktv.data.model.Song
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** An immutable bookmark captured on the player thread; persistence never reads ExoPlayer. */
internal data class PlaybackBookmark(
    val song: Song, val queue: List<Song>, val queueIndex: Int, val positionMs: Long,
    val fullStream: Boolean,
)

/** One IO consumer preserves write ordering and coalesces pending progress bookmarks.
 * It owns its scope so onCleared can close/drain the final save after ViewModel cancellation.
 */
internal class PlaybackStateWriter(
    private val prefs: SharedPreferences,
    moshi: Moshi,
    private val onError: (Throwable) -> Unit = {},
    private val encodeQueue: ((List<Song>) -> String)? = null,
) {
    private data class Request(val sequence: Long, val value: PlaybackBookmark)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = Channel<Request>(Channel.CONFLATED)
    private val saved = MutableStateFlow(0L)
    private var sequence = 0L
    private val adapter by lazy { moshi.adapter(Song::class.java) }
    private val queueAdapter by lazy { moshi.adapter<List<Song>>(
        Types.newParameterizedType(List::class.java, Song::class.java)) }
    private var previousQueue: List<Song>? = null
    private var previousSong: Song? = null
    val worker = scope.launch {
        try {
            for (request in requests) {
                try {
                    val value = request.value
                    val queueJson = if (previousQueue != value.queue) (encodeQueue?.invoke(value.queue) ?: queueAdapter.toJson(value.queue)) else null
                    val songJson = if (previousSong != value.song) adapter.toJson(value.song) else null
                    val edit = prefs.edit()
                    if (queueJson != null) edit.putString("queue", queueJson)
                    if (songJson != null) edit.putString("song", songJson)
                    edit.putInt("queue_index", value.queueIndex)
                        .putLong("position_ms", value.positionMs)
                        .putBoolean("full_stream", value.fullStream)
                        .apply()
                    previousQueue = value.queue
                    previousSong = value.song
                } catch (error: Exception) {
                    onError(error) // Retry encoding on the next bookmark; never touch playback.
                } finally {
                    saved.value = request.sequence
                }
            }
        } finally { scope.cancel() }
    }

    @Synchronized fun submit(value: PlaybackBookmark): Long {
        val next = ++sequence
        check(requests.trySend(Request(next, value)).isSuccess) { "Bookmark writer is closed" }
        return next
    }
    internal suspend fun awaitSaved(sequence: Long) { saved.first { it >= sequence } }
    fun close() { requests.close() }
}
