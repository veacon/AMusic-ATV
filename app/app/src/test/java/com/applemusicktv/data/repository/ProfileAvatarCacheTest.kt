package com.applemusicktv.data.repository

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ProfileAvatarCacheTest {
    @Test fun persistentSuccessSurvivesRecreationWithoutUsingAnEvictableCache() {
        val root = Files.createTempDirectory("avatar-test").toFile()
        try {
            val image = byteArrayOf(1, 2, 3)
            ProfileAvatarCache(root).save("account-a", image)
            assertArrayEquals(image, ProfileAvatarCache(root).read("account-a")!!.readBytes())
            assertNull(ProfileAvatarCache(root).read("account-b"))
            assertFalse(root.listFiles()!!.any { "account-a" in it.name })
        } finally { root.deleteRecursively() }
    }
    @Test fun signOutClearsAllImagesAndPendingWrites() {
        val root = Files.createTempDirectory("avatar-test").toFile()
        try {
            val cache = ProfileAvatarCache(root)
            cache.save("account-a", byteArrayOf(1))
            cache.clear()
            assertNull(cache.read("account-a"))
            assertTrue(root.listFiles()!!.isEmpty())
        } finally { root.deleteRecursively() }
    }
}
