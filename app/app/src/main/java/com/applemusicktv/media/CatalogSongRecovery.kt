package com.applemusicktv.media

import com.applemusicktv.data.NetworkLog
import com.applemusicktv.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

internal data class RecoveredCatalogSong(val id: String, val artworkUrl: String?, val artworkBgColor: String?)

/** Metadata-only recovery. Every hint must still exist in the account's current catalog. */
internal class CatalogSongRecovery(private val http: OkHttpClient) {
    suspend fun find(song: Song, storefront: String, bearer: String, mut: String): RecoveredCatalogSong? = withContext(Dispatchers.IO) {
        if (bearer.isBlank() || mut.isBlank() || song.isMusicVideo) return@withContext null
        val sf = storefront.trim().lowercase(Locale.ROOT).takeIf { it.matches(Regex("[a-z]{2}")) } ?: "cn"
        val headers = mapOf("Authorization" to "Bearer $bearer", "Music-User-Token" to mut,
            "Origin" to "https://music.apple.com")
        suspend fun get(url: okhttp3.HttpUrl): JSONObject? {
            coroutineContext.ensureActive()
            val request = Request.Builder().url(url).apply { headers.forEach { (k,v) -> header(k,v) } }.build()
            return try {
                http.newCall(request).execute().use { response ->
                    coroutineContext.ensureActive()
                    NetworkLog.line("CatalogRecovery", "GET ${url.host}${url.encodedPath} http=${response.code}")
                    if (response.isSuccessful) JSONObject(response.body?.string().orEmpty()) else null
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                NetworkLog.line("CatalogRecovery", "metadata request failed: ${e.javaClass.simpleName}")
                null
            }
        }
        val host = okhttp3.HttpUrl.Builder().scheme("https").host("api.music.apple.com")
        val hints = linkedSetOf<String>()
        if (song.id.startsWith("i.")) {
            val libraryUrl = okhttp3.HttpUrl.Builder().scheme("https").host("amp-api-edge.music.apple.com")
                .addPathSegments("v1/me/library/songs").addPathSegment(song.id).addQueryParameter("include","catalog").build()
            val row = get(libraryUrl)?.optJSONArray("data")?.optJSONObject(0)
            val linked = row?.optJSONObject("relationships")?.optJSONObject("catalog")?.optJSONArray("data")
            if (linked != null) for (i in 0 until linked.length()) hints += linked.optJSONObject(i)?.optString("id").orEmpty()
            val params = row?.optJSONObject("attributes")?.optJSONObject("playParams")
            // reportingId is only a hint: it must resolve to a matching catalog song below.
            hints += params?.optString("reportingId").orEmpty()
            hints += params?.optString("catalogId").orEmpty()
        }
        for (id in hints.filter { it.matches(Regex("[0-9]+")) && it != song.id }.take(3)) {
            val data = get(host.build().newBuilder().addPathSegments("v1/catalog/$sf/songs").addPathSegment(id).build())
                ?.optJSONArray("data")?.optJSONObject(0)
            val matched = data?.let { match(song, it) }
            if (matched != null) {
                NetworkLog.line("CatalogRecovery", "validated hint id=$id sf=$sf")
                return@withContext matched.song
            }
        }
        if (song.title.isBlank() || song.artistName.isBlank()) return@withContext null
        val url = host.build().newBuilder().addPathSegments("v1/catalog/$sf/search")
            .addQueryParameter("term", "${song.title} ${song.artistName}".trim())
            .addQueryParameter("types", "songs").addQueryParameter("limit", "10").build()
        val rows = get(url)?.optJSONObject("results")?.optJSONObject("songs")?.optJSONArray("data")
        val matches = if (rows == null) emptyList() else (0 until rows.length()).mapNotNull { i ->
            rows.optJSONObject(i)?.let { row -> match(song, row) }
        }
        val selected = matches.sortedWith(compareByDescending<Match> { it.sameAlbum }.thenBy { it.durationDelta }).firstOrNull()
        NetworkLog.line("CatalogRecovery", "search sf=$sf candidates=${rows?.length() ?: 0} matched=${matches.size} selected=${selected?.song?.id ?: "none"}")
        selected?.song
    }

    private data class Match(val song: RecoveredCatalogSong, val sameAlbum: Boolean, val durationDelta: Long)
    private fun match(song: Song, row: JSONObject): Match? {
        val id = row.optString("id")
        if (!id.matches(Regex("[0-9]+")) || id == song.id || row.optString("type") != "songs") return null
        val a = row.optJSONObject("attributes") ?: return null
        if (a.optJSONObject("playParams")?.optString("id") != id) return null
        val title = normalized(song.title); val artist = normalized(song.artistName)
        if (title.isEmpty() || artist.isEmpty() || title != normalized(a.optString("name")) || artist != normalized(a.optString("artistName"))) return null
        val duration = a.optLong("durationInMillis", 0)
        val delta = if (song.durationMs > 0 && duration > 0) abs(duration - song.durationMs) else Long.MAX_VALUE
        val sameAlbum = normalized(song.albumName).let { it.isNotEmpty() && it == normalized(a.optString("albumName")) }
        if (song.durationMs > 0 && (duration <= 0 || delta > (song.durationMs / 50).coerceIn(3000L,6000L))) return null
        if (song.durationMs <= 0 && !sameAlbum) return null
        val artwork = a.optJSONObject("artwork")
        val url = artwork?.optString("url")?.takeIf { it.startsWith("https://") }
        val bg = artwork?.optString("bgColor")?.takeIf { it.matches(Regex("[a-fA-F0-9]{6}")) }
        return Match(RecoveredCatalogSong(id, url, bg), sameAlbum, delta)
    }

    /** Keep version words such as Live/伴奏; never turn a different version into an exact match. */
    private fun normalized(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
}
