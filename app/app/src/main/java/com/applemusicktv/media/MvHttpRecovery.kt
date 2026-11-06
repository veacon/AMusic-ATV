package com.applemusicktv.media

import android.net.Uri
import android.util.Log
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import java.security.MessageDigest

/** One fresh playback resolution per explicit play/quality selection, preserving quality. */
internal class MvHttpRecovery {
    private var attempted = false
    fun reset() { attempted = false }
    fun claimRefresh(status: Int, host: String): Boolean {
        if (attempted || status != 403 || host.lowercase() != "mvod.itunes.apple.com") return false
        attempted = true
        return true
    }
    companion object {
        fun httpFailure(error: Throwable): HttpDataSource.InvalidResponseCodeException? =
            generateSequence(error) { it.cause?.takeUnless { c -> c === it } }
                .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
        fun isDecoderError(code: Int) = code in setOf(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED, PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)
        private fun fingerprint(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .take(8).joinToString("") { "%02x".format(it.toInt() and 255) }
        fun logFailure(error: HttpDataSource.InvalidResponseCodeException, mv: MusicVideoResult?) {
            val uri = error.dataSpec.uri.toString()
            val track = when {
                mv?.videoText?.lineSequence()?.any { it.trim() == uri } == true -> "video"
                mv?.audioText?.lineSequence()?.any { it.trim() == uri } == true -> "audio"
                else -> "unknown"
            }
            Log.w("MVHTTP", "http=${error.responseCode} track=$track host=${error.dataSpec.uri.host}" +
                " uriHash=${fingerprint(uri)} offset=${error.dataSpec.position} length=${error.dataSpec.length}")
        }
        /** Public catalogue paths only: excludes all URL query credentials and non-catalogue paths. */
        internal fun cataloguePath(url: String): String? {
            val uri = Uri.parse(url)
            if (uri.scheme != "https" || uri.host != "mvod.itunes.apple.com" || !uri.query.isNullOrEmpty()) return null
            val path = uri.encodedPath.orEmpty()
            if (!path.matches(Regex("/itunes-assets/(?:Video|Audio|HLS)[A-Za-z0-9]*/v[0-9]+/[A-Za-z0-9_./-]+"))) return null
            return uri.host + path
        }
        fun noteCatalogue(mv: MusicVideoResult) {
            for ((track, text) in listOf("video" to mv.videoText, "audio" to mv.audioText)) {
                text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
                    .distinct().take(256).forEachIndexed { index, url ->
                        cataloguePath(url)?.let { Log.d("MVAsset", "mv=${mv.adamId} track=$track index=$index uriHash=${fingerprint(url)} catalogueAsset=$it") }
                    }
            }
        }
    }
}
