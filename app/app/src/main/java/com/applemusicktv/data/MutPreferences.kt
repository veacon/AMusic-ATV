package com.applemusicktv.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.channels.awaitClose
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MutPreferences @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("apple_music_tv", Context.MODE_PRIVATE)
    private val avatars = com.applemusicktv.data.repository.ProfileAvatarCache(java.io.File(context.filesDir, "profile-avatar"))
    val accountChanges: kotlinx.coroutines.flow.Flow<Unit> = kotlinx.coroutines.flow.callbackFlow {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "mut") trySend(Unit)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    fun getMUT(): String = prefs.getString("mut", "") ?: ""
    @Volatile private var verifiedToken: String? = null
    private var authorizationGeneration = 0L
    private val _authorizationRevision = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val authorizationRevision: kotlinx.coroutines.flow.StateFlow<Long> = _authorizationRevision
    @Synchronized fun markVerified(token: String) {
        if (getMUT() == token && token.isNotEmpty() && verifiedToken != token) {
            verifiedToken = token
            _authorizationRevision.value = ++authorizationGeneration
        }
    }
    fun isVerified(token: String) = token.isNotEmpty() && token == getMUT() && token == verifiedToken
    @Synchronized fun setMUT(token: String) {
        if (getMUT() != token) { verifiedToken = null; _authorizationRevision.value = 0L }
        if (getMUT() != token) avatars.clear()
        prefs.edit { putString("mut", token) }
    }
    @Synchronized fun cachedAvatar(token: String): java.io.File? =
        if (getMUT() == token) avatars.read(token) else null
    @Synchronized fun saveAvatar(token: String, bytes: ByteArray): java.io.File? =
        if (token.isNotBlank() && getMUT() == token) avatars.save(token, bytes) else null
    @Synchronized fun discardAvatar(token: String) { if (getMUT() == token) avatars.clear() }
    fun hasMUT(): Boolean = getMUT().isNotEmpty()
}
