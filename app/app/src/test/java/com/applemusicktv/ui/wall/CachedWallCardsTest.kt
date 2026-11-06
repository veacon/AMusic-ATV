package com.applemusicktv.ui.wall

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import coil.disk.DiskCache
import com.applemusicktv.data.cache.AppDiskCache
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@OptIn(coil.annotation.ExperimentalCoilApi::class)
class CachedWallCardsTest {
    @Test fun joinsAllThreeMetadataCachesToLocalImagesAndKeepsCorrectTargets() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val cache = AppDiskCache.get(context)
        cache.clear()
        val root = File(context.cacheDir, "wall-image-test-${System.nanoTime()}")
        val disk = DiskCache.Builder().directory(root).maxSizeBytes(10_000_000).build()
        fun image(url: String, color: Int, size: Int = 320) {
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            val edit = disk.openEditor(url)!!
            edit.data.toFile().outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            edit.commit(); bitmap.recycle()
        }
        cache.content("home_cache").putString("sections_audio_v75", """[{"title":"Home","albums":[{"id":"1","title":"Home Album","artistName":"A","artworkUrl":"https://example.test/home/{w}x{h}.jpg"},{"id":"l.3","title":"Stale duplicate","artistName":"A","artworkUrl":"https://example.test/wrong/{w}x{h}.jpg"},{"id":"missing","title":"No local image","artistName":"A","artworkUrl":"https://example.test/missing/{w}x{h}.jpg"}]}]""")
        cache.content("browse_cache").putString("shelves_audio_v75_guest", """[{"title":"Browse","albums":[{"id":"pl.2","title":"Browse Mix","artistName":"B","type":"playlists","artworkUrl":"https://example.test/browse/{w}x{h}.jpg"}]}]""")
        cache.content("library_cache").putString("albums", """[{"id":"l.3","title":"Library Album","artistName":"C","artworkUrl":"https://example.test/library/{w}x{h}.jpg"},{"id":"small","title":"Too small","artistName":"C","artworkUrl":"https://example.test/small/{w}x{h}.jpg"}]""")
        cache.content("library_cache").putString("songs", "[]")
        cache.content("library_cache").putString("playlists", "[]")
        image("https://example.test/home/420x420.jpg", Color.RED)
        image("https://example.test/browse/260x260.jpg", Color.GREEN)
        image("https://example.test/library/300x300.jpg", Color.BLUE)
        image("https://example.test/small/300x300.jpg", Color.YELLOW, 128)
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val cards = CachedWallCards(context, moshi) { disk }.load("")
        assertEquals(setOf("1", "pl.2", "l.3"), cards.map { it.info.id }.toSet())
        assertEquals(setOf("home", "browse", "library"), cards.map { it.info.source }.toSet())
        assertEquals(WallKind.Playlist, cards.first { it.info.id == "pl.2" }.info.kind)
        assertTrue(cards.all { it.bitmap.width == 320 && it.bitmap.height == 320 })
        assertFalse(cards.any { it.info.id == "small" || it.info.id == "missing" })
        assertEquals(listOf(WallKind.Album, WallKind.Album, WallKind.Playlist), cards.map { it.info.kind })
        val albumOnly = CachedWallCards(context, moshi) { disk }.load("", 2)
        assertEquals(setOf("1", "l.3"), albumOnly.map { it.info.id }.toSet())
        assertTrue(albumOnly.all { it.info.kind == WallKind.Album })
        val supplement = CachedWallCards(context, moshi) { disk }.load("", 3)
        assertEquals(3, supplement.size)
        assertEquals(WallKind.Playlist, supplement.last().info.kind)
        disk.clear()
    }
    @Test fun retiredMetadataAndVideoLibrarySongsCannotCreateWallPlaybackTargets() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val cache = AppDiskCache.get(context); cache.clear()
        val disk = DiskCache.Builder().directory(File(context.cacheDir, "wall-audio-only-${System.nanoTime()}"))
            .maxSizeBytes(10_000_000).build()
        val url = "https://example.test/video/320x320.jpg"
        val bitmap = Bitmap.createBitmap(320,320,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val edit = disk.openEditor(url)!!
        edit.data.toFile().outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; edit.commit(); bitmap.recycle()
        cache.content("home_cache").putString("sections", """[{"title":"Old","albums":[{"id":"99","title":"Old video card","artistName":"A","artworkUrl":"$url"}]}]""")
        cache.content("library_cache").putString("songs", """[{"id":"v","type":"uploaded-videos","title":"Video","artistName":"A","albumName":"Video Album","albumId":"video-album","durationMs":1000,"artworkUrl":"$url","artworkBgColor":null,"previewUrl":null}]""")
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        assertTrue(CachedWallCards(context,moshi) { disk }.load("").isEmpty())
        disk.clear()
    }

}
