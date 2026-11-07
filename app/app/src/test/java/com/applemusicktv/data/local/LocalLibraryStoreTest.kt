package com.applemusicktv.data.local

import android.app.Application
import com.applemusicktv.data.di.NetworkModule
import com.applemusicktv.data.model.Song
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * 本地资料库（收藏 + 歌单）的落盘行为。
 *
 * 之前的 DeferredLibraryTest 测的是已删除的 Apple 资料库网络链路，
 * 现在资料库完全本地化，这里改为验证「内存状态 → JSON 文件 → 重启后恢复」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class LocalLibraryStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun store() = LocalLibraryStore(context, NetworkModule.provideMoshi())
    private fun song(id: String, title: String = "Song $id") =
        Song(id, title, "Artist", "Album", 180_000L, null, null, null,
            artistId = "artist-1", albumId = "album-1")

    @Before fun clear() {
        File(context.filesDir, "local_library.json").delete()
    }

    /** 写入走单线程执行器，测试里必须等文件真正落盘再新建实例。
     *  [expectContains] 用于等待「最后一次写入」也落盘：多次 toggle 是异步排队，
     *  只等文件非空会读到中间态（如第一次写入的 [a] 而非 [b,a]）。 */
    private fun awaitFile(expectContains: String? = null, expectMissing: String? = null): File {
        val file = File(context.filesDir, "local_library.json")
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            val ready = file.exists() && file.length() > 0L
            if (!ready) { Thread.sleep(10); continue }
            val text = runCatching { file.readText() }.getOrDefault("")
            val okContains = expectContains == null || text.contains(expectContains)
            val okMissing = expectMissing == null || !text.contains(expectMissing)
            if (okContains && okMissing) return file
            Thread.sleep(10)
        }
        assertTrue("local_library.json 没有落盘（期望含：$expectContains 不含：$expectMissing）", file.exists() && file.length() > 0)
        return file
    }

    @Test fun favoriteIsNewestFirstAndSurvivesRestart() {
        val s = store()
        assertTrue(s.toggleFavorite(song("a")))
        assertTrue(s.toggleFavorite(song("b")))
        assertEquals(listOf("b", "a"), s.favorites.value.map { it.id })
        assertTrue(s.isFavorite("a"))

        awaitFile("\"b\"")
        val reopened = store()
        assertEquals(listOf("b", "a"), reopened.favorites.value.map { it.id })
    }

    @Test fun togglingTwiceRemovesFromDiskToo() {
        val s = store()
        s.toggleFavorite(song("a"))
        assertFalse(s.toggleFavorite(song("a")))
        awaitFile(expectMissing = "\"id\":\"a\"")
        assertTrue(store().favorites.value.isEmpty())
    }

    @Test fun reimportSamePlaylistOverwritesInsteadOfDuplicating() {
        val s = store()
        val first = s.saveImported(
            LocalLibraryStore.LocalPlaylist(id = "imp-qq-1", name = "旧名字", source = "qq", songs = listOf(song("a")))
        )
        Thread.sleep(5)
        s.saveImported(
            LocalLibraryStore.LocalPlaylist(id = "imp-qq-1", name = "新名字", source = "qq", songs = listOf(song("a"), song("b")))
        )
        assertEquals(1, s.playlists.value.size)
        assertEquals("新名字", s.playlists.value.single().name)
        assertEquals(2, s.playlists.value.single().trackCount)
        // 重新导入要保留首次创建时间（列表按更新时间排序时不会乱跳）
        assertEquals(first.createdAt, s.playlists.value.single().createdAt)
        assertTrue(s.playlists.value.single().isImported)
    }

    @Test fun playlistEditsAndDeletionPersist() {
        val s = store()
        val playlist = s.saveImported(
            LocalLibraryStore.LocalPlaylist(id = "imp-qq-2", name = "歌单", source = "qq", songs = listOf(song("a")))
        )
        s.addSongToPlaylist(playlist.id, song("b"))
        s.addSongToPlaylist(playlist.id, song("b")) // 重复加同一首不该出现两条
        s.removeSongFromPlaylist(playlist.id, "a")
        assertEquals(listOf("b"), s.playlist(playlist.id)!!.songs.map { it.id })

        // 移除 song("a") 后，文件里不应再有 id "a"（等待最后一次删除写入落盘）
        awaitFile(expectMissing = "\"id\":\"a\"")
        assertEquals(listOf("b"), store().playlist(playlist.id)!!.songs.map { it.id })

        s.deletePlaylist(playlist.id)
        assertNull(s.playlist(playlist.id))
        awaitFile(expectMissing = "imp-qq-2")
        assertTrue(store().playlists.value.isEmpty())
    }

    @Test fun allSongsDeduplicatesFavoritesAndPlaylistTracks() {
        val s = store()
        s.addFavorite(song("a"))
        s.saveImported(
            LocalLibraryStore.LocalPlaylist(id = "imp-qq-3", name = "歌单", source = "qq",
                songs = listOf(song("a"), song("b")))
        )
        assertEquals(listOf("a", "b"), s.allSongs().map { it.id })
    }
}
