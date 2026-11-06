package com.applemusicktv.data.repository

import com.applemusicktv.data.network.AppleProfileAttributes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import retrofit2.HttpException

internal sealed class AvatarAttempt {
    data class Success(val bytes: ByteArray) : AvatarAttempt()
    object Transient : AvatarAttempt()
    object Unavailable : AvatarAttempt()
}

/** Tries each source through image validation before declaring success. */
internal class ProfileAvatarLoader(
    private val account: suspend () -> AppleProfileAttributes?,
    private val social: suspend () -> AppleProfileAttributes?,
    private val download: suspend (String) -> AvatarAttempt,
    private val diagnostic: (String) -> Unit = {},
) {
    suspend fun load(): AvatarAttempt {
        var transient = false
        for ((name, request) in listOf("account" to account, "social" to social)) {
            val attrs = try { request() }
            catch (e: TimeoutCancellationException) { diagnostic("${name}_timeout"); transient = true; continue }
            catch (e: CancellationException) { throw e }
            catch (e: HttpException) {
                val retry = e.code() == 429 || e.code() >= 500 || e.code() == 401
                diagnostic("${name}_http_${e.code()}")
                transient = transient || retry
                continue
            }
            catch (_: Exception) { diagnostic("${name}_network"); transient = true; continue }
            val artwork = if (name == "account") attrs?.avatarArtwork else attrs?.artwork
            val url = artworkUrl(artwork?.resolved(256)?.replace("{c}", "cc")?.replace("{q}", "90"))
            if (url == null) { diagnostic("${name}_missing_or_unsupported"); continue }
            when (val result = download(url)) {
                is AvatarAttempt.Success -> { diagnostic("${name}_success"); return result }
                AvatarAttempt.Transient -> { diagnostic("${name}_image_transient"); transient = true }
                AvatarAttempt.Unavailable -> diagnostic("${name}_image_unavailable")
            }
        }
        return if (transient) AvatarAttempt.Transient else AvatarAttempt.Unavailable
    }
    companion object {
        internal fun artworkUrl(value: String?): String? {
            if (value.isNullOrBlank() || '{' in value || '}' in value) return null
            val url = value.toHttpUrlOrNull() ?: return null
            if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
            if (url.host != "mzstatic.com" && !url.host.endsWith(".mzstatic.com")) return null
            return url.toString()
        }
    }
}
