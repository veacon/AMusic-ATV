package com.applemusicktv.ui.viewmodel

import android.graphics.BitmapFactory
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.applemusicktv.data.MutPreferences
import com.applemusicktv.data.network.DirectAppleApi
import com.applemusicktv.data.repository.AvatarAttempt
import com.applemusicktv.data.repository.ProfileAvatarLoader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltViewModel
class UserProfileViewModel @Inject constructor(api: DirectAppleApi, private val account: MutPreferences) : ViewModel() {
    private val _avatar = MutableStateFlow<String?>(null)
    val avatar: StateFlow<String?> = _avatar
    private val retry = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val images = OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS).build()
    private val loader = ProfileAvatarLoader(
        account = { withTimeout(10_000) { api.accountProfile().data.firstOrNull()?.attributes } },
        social = { withTimeout(10_000) { api.socialProfile().data.firstOrNull()?.attributes } },
        download = { url -> download(url) },
        diagnostic = { Log.d("ProfileAvatar", it) },
    )
    init {
        viewModelScope.launch {
            account.accountChanges.map { account.getMUT() }.distinctUntilChanged().collectLatest { token ->
                _avatar.value = null
                if (token.isBlank()) return@collectLatest
                var failures = 0
                while (currentCoroutineContext().isActive && account.getMUT() == token) {
                    val cached = withContext(Dispatchers.IO) {
                        try { account.cachedAvatar(token)?.takeIf { usable(it.readBytes()) } }
                        catch (_: Exception) { null }
                    }
                    if (cached != null && account.getMUT() == token) publish(cached)
                    val age = cached?.let { (System.currentTimeMillis() - it.lastModified()).coerceAtLeast(0) }
                    val wait = if (age != null && age < REFRESH_MS) REFRESH_MS - age else {
                        val result = withContext(Dispatchers.IO) { loader.load() }
                        ensureActive()
                        when (result) {
                            is AvatarAttempt.Success -> {
                                val file = withContext(Dispatchers.IO) {
                                    try { account.saveAvatar(token, result.bytes) } catch (_: Exception) { null }
                                }
                                if (file != null && account.getMUT() == token) { publish(file); failures = 0; REFRESH_MS }
                                else transientDelay(failures++)
                            }
                            AvatarAttempt.Transient -> transientDelay(failures++)
                            AvatarAttempt.Unavailable -> { failures = 0; UNAVAILABLE_MS }
                        }
                    }
                    // A resume or display failure can retry sooner; no failure is shown to the user.
                    withTimeoutOrNull(wait) { retry.first() }
                }
            }
        }
    }
    private fun publish(file: java.io.File) { _avatar.value = file.toURI().toString() + "#" + file.lastModified() }
    private fun transientDelay(attempt: Int) = listOf(2_000L, 5_000L, 10_000L, 30_000L, 120_000L, 600_000L)[attempt.coerceAtMost(5)]
    private fun download(url: String): AvatarAttempt = try {
        images.newCall(Request.Builder().url(url).build()).execute().use { response ->
            val body = response.body
            when {
                !response.isSuccessful -> if (response.code == 429 || response.code >= 500 || response.code == 408) AvatarAttempt.Transient else AvatarAttempt.Unavailable
                body == null || body.contentLength() > 1_048_576 -> AvatarAttempt.Unavailable
                else -> {
                    val bytes = body.byteStream().use { it.readBytesLimited() }
                    if (usable(bytes)) AvatarAttempt.Success(bytes) else AvatarAttempt.Unavailable
                }
            }
        }
    } catch (_: Exception) { AvatarAttempt.Transient }
    private fun usable(bytes: ByteArray): Boolean {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false
        val valid = bitmap.width > 0 && bitmap.height > 0
        bitmap.recycle()
        return valid
    }
    private fun java.io.InputStream.readBytesLimited(): ByteArray {
        val result = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) return result.toByteArray()
            check(result.size() + count <= 1_048_576)
            result.write(buffer, 0, count)
        }
    }
    fun onResume() { retry.tryEmit(Unit) }
    fun onAvatarDisplayError() { account.discardAvatar(account.getMUT()); retry.tryEmit(Unit) }
    companion object {
        private const val REFRESH_MS = 24 * 60 * 60 * 1000L
        private const val UNAVAILABLE_MS = 6 * 60 * 60 * 1000L
    }
}
