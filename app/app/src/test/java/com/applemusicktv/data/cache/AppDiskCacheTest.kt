package com.applemusicktv.data.cache

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@OptIn(coil.annotation.ExperimentalCoilApi::class)
class AppDiskCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun context(): Context {
        val app = RuntimeEnvironment.getApplication()
        val directory = temporary.newFolder()
        return object : ContextWrapper(app) {
            override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
            override fun getApplicationInfo() = ApplicationInfo(app.applicationInfo).apply { dataDir = directory.path }
            override fun getSharedPreferences(name: String, mode: Int) =
                app.getSharedPreferences("${directory.name}_$name", mode)
        }
    }
    @Test fun migratesAllHistoricalPageCachesAndPreservesUserSettings() {
        val ctx = context()
        val values = mapOf("home_cache" to "sections", "browse_cache" to "shelves_v1_account",
            "library_cache" to "songs", "album_detail_cache" to "album_123", "playlist_detail_cache" to "tracks_456")
        values.forEach { (name, key) -> ctx.getSharedPreferences(name, 0).edit().putString(key, "[\"$key\"]").commit() }
        val library = ctx.getSharedPreferences("library_cache", 0)
        library.edit().putInt("sort_field", 3).putString("pinned_playlists", "my-list").putBoolean("songs_newest_first", true).commit()
        ctx.getSharedPreferences("player_state", 0).edit().putString("queue", "saved-queue").commit()
        ctx.getSharedPreferences("account", 0).edit().putString("token", "saved-token").commit()
        val cache = AppDiskCache(ctx); cache.initialize()
        values.forEach { (name, key) ->
            assertEquals("[\"$key\"]", AppDiskCache(ctx).content(name).getString(key))
            assertFalse(ctx.getSharedPreferences(name, 0).contains(key))
        }
        assertEquals(3, library.getInt("sort_field", -1)); assertEquals("my-list", library.getString("pinned_playlists", null))
        assertTrue(library.getBoolean("songs_newest_first", false))
        assertEquals("saved-queue", ctx.getSharedPreferences("player_state", 0).getString("queue", null))
        assertEquals("saved-token", ctx.getSharedPreferences("account", 0).getString("token", null))
    }
    @Test fun coldReadRestoresHistoryWithoutWaitingForStartupMigration() {
        val ctx = context()
        ctx.getSharedPreferences("browse_cache", 0).edit().putString("shelves_v1_account", "historical-content").commit()
        val cache = AppDiskCache(ctx)
        assertEquals("historical-content", cache.content("browse_cache").getString("shelves_v1_account"))
        assertFalse(ctx.getSharedPreferences("browse_cache", 0).contains("shelves_v1_account"))
        assertEquals("historical-content", AppDiskCache(ctx).content("browse_cache").getString("shelves_v1_account"))
    }
    @Test fun freshManagedContentWinsOverLegacyAndDailyWipeIsAbsent() {
        val ctx = context(); val cache = AppDiskCache(ctx)
        assertTrue(cache.content("home_cache").putString("sections", "fresh"))
        ctx.getSharedPreferences("home_cache", 0).edit().putString("sections", "stale").commit()
        val artwork = File(ctx.cacheDir, "image_cache/old-artwork").apply { parentFile!!.mkdirs(); writeText("keep"); setLastModified(1) }
        cache.initialize()
        assertEquals("fresh", cache.content("home_cache").getString("sections"))
        assertTrue(artwork.exists())
    }
    @Test fun decimalLimitsAndSafeAudioNamesAreExplicit() {
        assertEquals(1_000_000_000L, CacheLimits.TOTAL_BYTES)
        assertEquals(500_000_000L, CacheLimits.CLEANUP_BYTES)
        assertEquals(setOf("clear_a_b.mp4", "standalone_a_b.m3u8", "encrypted_a_b.cache"), AppDiskCache.audioNames(listOf("a/b", "")))
    }

    @Test fun capacityDefaultsToOneAndClampsAndPersistsWholeGigabytes() {
        val ctx = context(); val cache = AppDiskCache(ctx)
        assertEquals(1, cache.capacityGb)
        assertEquals(1_000_000_000L, cache.store.limitBytes)
        cache.setCapacityGb(6)
        assertEquals(6_000_000_000L, cache.store.limitBytes)
        assertEquals(6, AppDiskCache(ctx).capacityGb)
        for (gb in 7..10) {
            cache.setCapacityGb(gb)
            assertEquals(gb, AppDiskCache(ctx).capacityGb)
            assertEquals(gb * CacheLimits.GIGABYTE, AppDiskCache(ctx).store.limitBytes)
        }
        cache.setCapacityGb(20); assertEquals(10, cache.capacityGb)
        cache.setCapacityGb(0); assertEquals(1, cache.capacityGb)
        assertEquals(1_000_000_000L, AppDiskCache(ctx).store.limitBytes)
    }
    @Test fun manualClearUsesCoilApiAndPreservesFontsAccountSortAndActiveAudio() {
        val ctx = context()
        val images = coil.disk.DiskCache.Builder().directory(File(ctx.cacheDir, "image_cache"))
            .maxSizeBytes(CacheLimits.IMAGE_BYTES).build()
        images.openEditor("cover")!!.let { editor ->
            images.fileSystem.write(editor.data) { write(ByteArray(100)) }; editor.commit()
        }
        val font = File(ctx.filesDir, "fonts/persistent-test-font").apply { parentFile!!.mkdirs(); writeText("font") }
        val library = ctx.getSharedPreferences("library_cache", 0)
        library.edit().putString("songs", "legacy-list").putInt("sort_field", 3).commit()
        ctx.getSharedPreferences("account", 0).edit().putString("token", "keep").commit()
        val cache = AppDiskCache(ctx, imageCache = { images })
        cache.store.put("clear_active.mp4", ByteArray(100))
        cache.store.put("clear_idle.mp4", ByteArray(100))
        cache.content("home_cache").putString("sections", "page-cache")
        cache.protect("playing") { setOf("clear_active.mp4") }
        val result = cache.clear()
        assertTrue(result.removedBytes > 100)
        assertTrue(File(ctx.cacheDir, "clear_active.mp4").exists())
        assertFalse(File(ctx.cacheDir, "clear_idle.mp4").exists())
        assertNull(cache.content("home_cache").getString("sections"))
        assertNull(library.getString("songs", null)); assertEquals(3, library.getInt("sort_field", -1))
        assertNull(images.openSnapshot("cover"))
        assertEquals("keep", ctx.getSharedPreferences("account", 0).getString("token", null))
        assertEquals("font", font.readText())
        assertEquals(cache.store.sizeBytes(), result.afterBytes)
        images.openEditor("after-clear")!!.let { editor -> images.fileSystem.write(editor.data) { writeUtf8("still-writable") }; editor.commit() }
        images.openSnapshot("after-clear")!!.use { assertEquals("still-writable", images.fileSystem.read(it.data) { readUtf8() }) }
    }

    @Test fun completeSongIsReusedAfterCacheRecreationWithoutRewritingBytes() {
        val ctx = context(); val first = AppDiskCache(ctx)
        val song = first.putMedia("clear_replay.mp4", ByteArray(256) { 42 })!!
        val modified = song.lastModified()
        val restarted = AppDiskCache(ctx)
        val reused = restarted.cachedMedia("clear_replay.mp4")!!
        assertEquals(song.canonicalPath, reused.canonicalPath)
        assertArrayEquals(ByteArray(256) { 42 }, reused.readBytes())
        assertTrue(reused.lastModified() >= modified)
        assertEquals(256L, restarted.sizeBytes())
    }
}
