package com.applemusicktv.data.fonts

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import okhttp3.OkHttpClient
import okhttp3.Request

/** Persistent resources, deliberately separate from AppDiskCache and its 800 MB budget.
 * A final file is published only after size, hash AND platform font validation succeed.
 */
internal class FontFileStore(
    val directory: File,
    val assets: List<FontAsset>,
    private val client: OkHttpClient,
    private val validateFont: (File) -> Unit = {},
) {
    private val verified = mutableSetOf<String>()
    val totalBytes = assets.sumOf { it.bytes }
    fun file(asset: FontAsset) = File(directory, asset.fileName)
    private fun partial(asset: FontAsset) = File(directory, asset.fileName + ".part")

    fun downloadedBytes(): Long = assets.sumOf {
        val final = file(it)
        (if (it.fileName in verified && final.length() == it.bytes) it.bytes
        else partial(it).length()).coerceIn(0, it.bytes)
    }

    /** The caller serializes rounds. No UI or account request shares this HTTP client. */
    fun prepare(progress: (Long) -> Unit): List<File> {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create font directory")
        assets.forEach { asset ->
            val final = file(asset)
            if (asset.fileName !in verified || final.length() != asset.bytes) {
                verified.remove(asset.fileName)
                if (final.isFile && valid(final, asset)) {
                    validateFont(final)
                    verified += asset.fileName
                } else if (final.exists() && !final.delete()) throw IOException("Cannot remove invalid font")
            }
        }
        progress(downloadedBytes())
        assets.forEach { asset ->
            if (asset.fileName in verified) return@forEach
            val part = partial(asset)
            if (part.length() > asset.bytes) part.delete()
            var error: Exception? = null
            for (url in asset.urls) {
                try {
                    if (part.length() < asset.bytes) download(asset, url, progress)
                    if (!valid(part, asset)) {
                        part.delete()
                        throw IOException("Font checksum mismatch: ${asset.fileName}")
                    }
                    try { validateFont(part) } catch (e: Exception) {
                        part.delete()
                        throw IOException("Font cannot be loaded: ${asset.fileName}", e)
                    }
                    if (!part.renameTo(file(asset))) throw IOException("Cannot publish font")
                    verified += asset.fileName
                    error = null
                    progress(downloadedBytes())
                    break
                } catch (e: Exception) {
                    error = e
                    progress(downloadedBytes())
                }
            }
            if (error != null || asset.fileName !in verified)
                throw IOException("Font sources exhausted: ${asset.fileName}", error)
        }
        return assets.map(::file)
    }

    private fun download(asset: FontAsset, url: String, progress: (Long) -> Unit, resume: Boolean = true) {
        val part = partial(asset)
        val offset = if (resume) part.length() else 0L
        // Let OkHttp negotiate/decode gzip for fresh downloads. For Range requests it
        // omits Accept-Encoding automatically; explicit "identity" breaks some CDN ranges.
        val request = Request.Builder().url(url)
            .header("User-Agent", "LavandeMusic-Fonts/1")
            .apply { if (offset > 0) header("Range", "bytes=$offset-") }.build()
        client.newCall(request).execute().use { response ->
            if (response.code == 416 && offset > 0) {
                response.close()
                // A source may not support this range representation. Retry it once in full,
                // preserving the old partial until a valid replacement response is accepted.
                download(asset, url, progress, resume = false)
                return
            }
            if (response.code !in listOf(200, 206)) throw IOException("Font HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty font response")
            val append = response.code == 206
            if (append) {
                val range = Regex("bytes (\\d+)-(\\d+)/(\\d+)")
                    .matchEntire(response.header("Content-Range").orEmpty())
                    ?: throw IOException("Invalid font range")
                val (start, end, total) = range.destructured
                if (start.toLong() != offset || total.toLong() != asset.bytes ||
                    end.toLong() < offset || end.toLong() >= asset.bytes ||
                    (body.contentLength() >= 0 && body.contentLength() != end.toLong() - offset + 1))
                    throw IOException("Mismatched font range")
            } else if (body.contentLength() >= 0 && body.contentLength() != asset.bytes) {
                throw IOException("Mismatched font length")
            }
            // A server ignoring Range returns 200: truncate rather than append duplicate bytes.
            FileOutputStream(part, append).use { output ->
                var length = if (append) offset else 0L
                var lastProgress = System.nanoTime()
                body.byteStream().use { input ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (length + read > asset.bytes) {
                            output.close(); part.delete()
                            throw IOException("Oversized font response")
                        }
                        output.write(buffer, 0, read)
                        length += read
                        val now = System.nanoTime()
                        if (now - lastProgress >= 200_000_000L) {
                            progress(downloadedBytes()); lastProgress = now
                        }
                    }
                }
                output.flush()
                output.fd.sync()
            }
            progress(downloadedBytes())
            if (part.length() != asset.bytes) throw IOException("Incomplete font response")
        }
    }

    private fun valid(file: File, asset: FontAsset): Boolean {
        if (!file.isFile || file.length() != asset.bytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == asset.sha256
    }
}
