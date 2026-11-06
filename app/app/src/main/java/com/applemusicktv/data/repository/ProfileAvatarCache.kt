package com.applemusicktv.data.repository

import java.io.File
import java.security.MessageDigest

/** Private persistent files: kept until sign-out, not an evictable image-loader cache. */
internal class ProfileAvatarCache(private val directory: File) {
    private fun key(token: String) = MessageDigest.getInstance("SHA-256")
        .digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
    fun read(token: String): File? = if (token.isBlank()) null else File(directory, key(token)).takeIf { it.isFile && it.length() > 0 }
    fun save(token: String, bytes: ByteArray): File {
        require(token.isNotBlank() && bytes.isNotEmpty())
        directory.mkdirs()
        val target = File(directory, key(token))
        val temporary = File(directory, "pending")
        temporary.writeBytes(bytes)
        check(temporary.renameTo(target)) { "Avatar commit failed" }
        return target
    }
    fun clear() { directory.listFiles()?.forEach { it.delete() } }
}
