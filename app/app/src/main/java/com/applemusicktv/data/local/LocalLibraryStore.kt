package com.applemusicktv.data.local

import android.content.Context
import android.util.Log
import com.applemusicktv.data.model.Song
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本地资料库：收藏曲目 + 自建/导入歌单。
 *
 * 完全脱离 Apple 账号（`/v1/me/library` 未登录 403），所有内容落在
 * `filesDir/local_library.json`。文件很小（几百首 ≈ 200KB），因此不做 Room，
 * 直接用 Moshi 全量读写：写入放在单线程执行器，读取只在首次访问时发生。
 */
@Singleton
class LocalLibraryStore @Inject constructor(
    @ApplicationContext private val context: Context,
    moshi: Moshi,
) {

    data class LocalPlaylist(
        val id: String,
        val name: String,
        val coverUrl: String? = null,
        /** local / qq / netease / kugou / text —— 用于界面标注来源。 */
        val source: String = SOURCE_LOCAL,
        val sourceUrl: String? = null,
        val songs: List<Song> = emptyList(),
        val createdAt: Long = 0L,
        val updatedAt: Long = 0L,
    ) {
        val trackCount: Int get() = songs.size
        val sourceLabel: String get() = when (source) {
            SOURCE_QQ -> "QQ 音乐"
            SOURCE_NETEASE -> "网易云"
            SOURCE_KUGOU -> "酷狗"
            SOURCE_TEXT -> "文本导入"
            else -> "本地"
        }
        val isImported: Boolean get() = source != SOURCE_LOCAL
    }

    private data class Payload(
        val favorites: List<Song> = emptyList(),
        val playlists: List<LocalPlaylist> = emptyList(),
        val recents: List<RecentPlay> = emptyList(),
        val version: Int = 1,
    )

    /** 最近播放：有效播放（>10s）过的曲目，带时间戳，最新的在前。 */
    data class RecentPlay(
        val song: Song,
        val playedAt: Long,
    )

    private val file = File(context.filesDir, "local_library.json")
    private val adapter = moshi.adapter(Payload::class.java)
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "local-library-writer").apply { isDaemon = true }
    }
    private val lock = Any()

    private val _favorites = MutableStateFlow<List<Song>>(emptyList())
    val favorites: StateFlow<List<Song>> = _favorites.asStateFlow()

    private val _playlists = MutableStateFlow<List<LocalPlaylist>>(emptyList())
    val playlists: StateFlow<List<LocalPlaylist>> = _playlists.asStateFlow()

    private val _recents = MutableStateFlow<List<RecentPlay>>(emptyList())
    val recents: StateFlow<List<RecentPlay>> = _recents.asStateFlow()

    init {
        runCatching {
            if (file.exists()) {
                val payload = adapter.fromJson(file.readText()) ?: Payload()
                _favorites.value = payload.favorites
                _playlists.value = payload.playlists
                _recents.value = payload.recents
                Log.i(TAG, "loaded favorites=${payload.favorites.size} playlists=${payload.playlists.size} recents=${payload.recents.size}")
            }
        }.onFailure { Log.w(TAG, "load failed: ${it.message}") }
    }

    // ── 收藏 ────────────────────────────────────────────────────────────────

    fun isFavorite(songId: String): Boolean = _favorites.value.any { it.id == songId }

    /** 返回操作后的状态：true = 已收藏，false = 已取消。 */
    fun toggleFavorite(song: Song): Boolean = synchronized(lock) {
        val current = _favorites.value
        val exists = current.any { it.id == song.id }
        _favorites.value = if (exists) current.filterNot { it.id == song.id }
        else listOf(song) + current.filterNot { it.id == song.id }
        persistLocked()
        !exists
    }

    fun addFavorite(song: Song) = synchronized(lock) {
        _favorites.value = listOf(song) + _favorites.value.filterNot { it.id == song.id }
        persistLocked()
    }

    fun removeFavorite(songId: String) = synchronized(lock) {
        _favorites.value = _favorites.value.filterNot { it.id == songId }
        persistLocked()
    }

    fun clearFavorites() = synchronized(lock) {
        _favorites.value = emptyList()
        persistLocked()
    }

    // ── 歌单 ────────────────────────────────────────────────────────────────

    fun createPlaylist(name: String, songs: List<Song> = emptyList()): LocalPlaylist = synchronized(lock) {
        val now = System.currentTimeMillis()
        val playlist = LocalPlaylist(
            id = "lp-" + UUID.randomUUID().toString().take(8),
            name = name.ifBlank { "新建歌单" },
            songs = songs,
            createdAt = now,
            updatedAt = now,
        )
        _playlists.value = listOf(playlist) + _playlists.value
        persistLocked()
        playlist
    }

    /** 导入结果落库；同源同 id 覆盖（重新导入即刷新），否则追加。 */
    fun saveImported(playlist: LocalPlaylist): LocalPlaylist = synchronized(lock) {
        val now = System.currentTimeMillis()
        val existing = _playlists.value.firstOrNull { it.id == playlist.id }
        val merged = playlist.copy(
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        _playlists.value = listOf(merged) + _playlists.value.filterNot { it.id == playlist.id }
        persistLocked()
        merged
    }

    fun deletePlaylist(playlistId: String) = synchronized(lock) {
        _playlists.value = _playlists.value.filterNot { it.id == playlistId }
        persistLocked()
    }

    fun renamePlaylist(playlistId: String, name: String) = synchronized(lock) {
        if (name.isBlank()) return@synchronized
        _playlists.value = _playlists.value.map {
            if (it.id == playlistId) it.copy(name = name.trim(), updatedAt = System.currentTimeMillis()) else it
        }
        persistLocked()
    }

    fun addSongToPlaylist(playlistId: String, song: Song) = synchronized(lock) {
        _playlists.value = _playlists.value.map {
            if (it.id != playlistId || it.songs.any { s -> s.id == song.id }) it
            else it.copy(songs = it.songs + song, updatedAt = System.currentTimeMillis())
        }
        persistLocked()
    }

    fun removeSongFromPlaylist(playlistId: String, songId: String) = synchronized(lock) {
        _playlists.value = _playlists.value.map {
            if (it.id != playlistId) it
            else it.copy(songs = it.songs.filterNot { s -> s.id == songId }, updatedAt = System.currentTimeMillis())
        }
        persistLocked()
    }

    fun playlist(playlistId: String): LocalPlaylist? = _playlists.value.firstOrNull { it.id == playlistId }

    // ── 最近播放 ────────────────────────────────────────────────────────────

    /** 记录一次有效播放（>10s）。同曲去重置顶，最多保留 [RECENT_MAX]。 */
    fun recordRecent(song: Song) = synchronized(lock) {
        val now = System.currentTimeMillis()
        _recents.value = listOf(RecentPlay(song, now)) +
            _recents.value.filterNot { it.song.id == song.id }
        if (_recents.value.size > RECENT_MAX) {
            _recents.value = _recents.value.take(RECENT_MAX)
        }
        persistLocked()
    }

    fun clearRecents() = synchronized(lock) {
        _recents.value = emptyList()
        persistLocked()
    }

    // ── 聚合（专辑/艺人页用收藏与歌单派生，不碰 Apple library） ──────────────

    /** 收藏 + 所有歌单曲目，按 id 去重。 */
    fun allSongs(): List<Song> {
        val seen = HashSet<String>()
        return (_favorites.value + _playlists.value.flatMap { it.songs }).filter { seen.add(it.id) }
    }

    // ── 持久化 ──────────────────────────────────────────────────────────────

    private fun persistLocked() {
        val payload = Payload(favorites = _favorites.value, playlists = _playlists.value, recents = _recents.value)
        val json = runCatching { adapter.toJson(payload) }.getOrNull() ?: return
        writer.execute {
            runCatching {
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(json)
                if (!tmp.renameTo(file)) {
                    file.writeText(json)
                    tmp.delete()
                }
            }.onFailure { Log.w(TAG, "persist failed: ${it.message}") }
        }
    }

    companion object {
        private const val TAG = "LocalLibrary"
        const val SOURCE_LOCAL = "local"
        const val SOURCE_QQ = "qq"
        const val SOURCE_NETEASE = "netease"
        const val SOURCE_KUGOU = "kugou"
        const val SOURCE_TEXT = "text"
        /** 最近播放最多保留条数。 */
        const val RECENT_MAX = 100
    }
}
