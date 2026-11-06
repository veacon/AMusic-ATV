package com.applemusicktv.ui.wall

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import coil.Coil
import com.applemusicktv.data.cache.AppDiskCache
import com.applemusicktv.data.model.Album
import com.applemusicktv.data.model.Song
import com.applemusicktv.data.network.PlaylistDto
import com.applemusicktv.ui.screens.BrowseShelf
import com.applemusicktv.ui.viewmodel.HomeSection
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class CachedWallCard(val info: WallDescriptor, val bitmap: Bitmap)

/** Join page metadata with existing Coil entries. Never fetch or infer a target from pixels. */
internal class CachedWallCards(private val context: Context, private val moshi: Moshi,
    private val imageCache: () -> coil.disk.DiskCache? = { Coil.imageLoader(context).diskCache }) {
    private inline fun <reified T> read(raw: String?): List<T> = runCatching {
        moshi.adapter<List<T>>(Types.newParameterizedType(List::class.java, T::class.java))
            .fromJson(raw ?: "[]").orEmpty()
    }.getOrDefault(emptyList())

    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    suspend fun load(account: String, requestedCount: Int = 100): List<CachedWallCard> {
        val cache = AppDiskCache.get(context)
        val home = read<HomeSection>(cache.content("home_cache").getString("sections_audio_v75"))
            .flatMap { it.albums }.mapNotNull { fromAlbum(it, "home") }
        val accountHash = if (account.isBlank()) "guest" else MessageDigest.getInstance("SHA-256")
            .digest(account.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        val browse = read<BrowseShelf>(cache.content("browse_cache").getString("shelves_audio_v75_$accountHash"))
            .flatMap { it.albums }.mapNotNull { fromAlbum(it, "browse") }
        val library = buildList {
            read<Album>(cache.content("library_cache").getString("albums"))
                .mapNotNullTo(this) { fromAlbum(it, "library") }
            read<PlaylistDto>(cache.content("library_cache").getString("playlists"))
                .filter { !com.applemusicktv.data.VideoPlaybackPolicy.isVideo(it.playlistType.orEmpty()) }
                .mapNotNullTo(this) { wallDescriptor(it.id, it.name, it.curatorName, it.artworkUrl, "playlists", "library") }
            read<Song>(cache.content("library_cache").getString("songs"))
                .filter(com.applemusicktv.data.VideoPlaybackPolicy::allows)
                .mapNotNullTo(this) { song -> song.albumId?.let { wallDescriptor(it, song.albumName,
                    song.artistName, song.artworkUrl, "albums", "library") } }
        }
        val candidates = wallAlbumsFirst(listOf(home.shuffled(), browse.shuffled(), library.shuffled()))
        val disk = imageCache() ?: return emptyList()
        val cards = mutableListOf<CachedWallCard>()
        val imageKeys = hashSetOf<String>()
        val loadedTargets = hashSetOf<String>()
        val fingerprints = mutableListOf<IntArray>()
        for (candidate in candidates) {
            currentCoroutineContext().ensureActive()
            if (cards.size >= requestedCount.coerceIn(1, 160)) break
            if (candidate.key in loadedTargets) continue
            val imageKey = candidate.artworkUrl.replace(Regex("/[0-9{][^/]*$"), "/artwork")
            if (imageKey in imageKeys) continue
            val urls = buildList {
                // Original fixed URL plus the exact sizes used by page cards and the player.
                add(candidate.artworkUrl)
                for (size in listOf(420, 500, 720, 300, 260, 320, 360, 400, 480, 600, 640, 800, 900, 1000))
                    add(candidate.artworkUrl.replace("{w}", "$size").replace("{h}", "$size").replace("{f}", "jpg"))
            }.distinct()
            var thumb: Bitmap? = null
            for (url in urls) {
                val snapshot = runCatching { disk.openSnapshot(url) }.getOrNull() ?: continue
                snapshot.use {
                    val file = it.data.toFile()
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.path, bounds)
                    val w = bounds.outWidth; val h = bounds.outHeight
                    if (w < 256 || h < 256 || kotlin.math.abs(w - h).toFloat() / maxOf(w, h) > .08f) return@use
                    var sample = 1
                    while (minOf(w, h) / (sample * 2) >= 320) sample *= 2
                    val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
                        inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565
                    }) ?: return@use
                    thumb = Bitmap.createScaledBitmap(decoded, 320, 320, true)
                    if (thumb !== decoded) decoded.recycle()
                }
                if (thumb != null) break
            }
            val bitmap = thumb ?: continue
            val tiny = Bitmap.createScaledBitmap(bitmap, 8, 8, true)
            val pixels = IntArray(64); tiny.getPixels(pixels, 0, 8, 0, 0, 8, 8); tiny.recycle()
            val duplicate = fingerprints.any { prior ->
                var difference = 0
                for (i in pixels.indices) for (shift in listOf(0, 8, 16))
                    difference += kotlin.math.abs(((pixels[i] shr shift) and 255) - ((prior[i] shr shift) and 255))
                difference < 64 * 3 * 14
            }
            if (duplicate) bitmap.recycle() else {
                fingerprints += pixels; imageKeys += imageKey; loadedTargets += candidate.key
                cards += CachedWallCard(candidate, bitmap)
            }
        }
        return cards
    }

    private fun fromAlbum(album: Album, source: String) =
        if (com.applemusicktv.data.VideoPlaybackPolicy.allows(album.id, album.type)) wallDescriptor(album.id, album.title, album.artistName, album.artworkUrl, album.type, source) else null
}
