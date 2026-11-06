package com.applemusicktv.data.repository

import com.applemusicktv.data.network.AppleArtwork
import com.applemusicktv.data.network.AppleProfileAttributes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ProfileAvatarLoaderTest {
    private val account = AppleProfileAttributes(avatarArtwork = AppleArtwork("https://is1-ssl.mzstatic.com/{w}x{h}{c}.{f}?q={q}"))
    private val social = AppleProfileAttributes(artwork = AppleArtwork("https://is2-ssl.mzstatic.com/avatar.jpg"))
    @Test fun accountSuccessStopsBeforeSocial() = runBlocking {
        var socialCalls = 0
        val loader = ProfileAvatarLoader({ account }, { socialCalls++; social }, { url ->
            assertEquals("https://is1-ssl.mzstatic.com/256x256cc.jpg?q=90", url)
            AvatarAttempt.Success(byteArrayOf(1))
        })
        assertTrue(loader.load() is AvatarAttempt.Success)
        assertEquals(0, socialCalls)
    }
    @Test fun badAccountImageFallsBackToSocialImage() = runBlocking {
        val urls = mutableListOf<String>()
        val loader = ProfileAvatarLoader({ account }, { social }, { url ->
            urls.add(url)
            if (urls.size == 1) AvatarAttempt.Unavailable else AvatarAttempt.Success(byteArrayOf(2))
        })
        assertTrue(loader.load() is AvatarAttempt.Success)
        assertEquals(2, urls.size)
    }
    @Test fun transientFailureSurvivesMissingOtherSource() = runBlocking {
        val loader = ProfileAvatarLoader({ throw IOException() }, { null }, { AvatarAttempt.Unavailable })
        assertEquals(AvatarAttempt.Transient, loader.load())
    }
    @Test fun missingBothSourcesUsesSlowRetry() = runBlocking {
        assertEquals(AvatarAttempt.Unavailable, ProfileAvatarLoader({ null }, { null }, { AvatarAttempt.Unavailable }).load())
    }
    @Test fun malformedAccountUrlStillTriesSocial() = runBlocking {
        val loader = ProfileAvatarLoader({ AppleProfileAttributes(avatarArtwork = AppleArtwork("https://evil.example/a")) }, { social }, { AvatarAttempt.Success(byteArrayOf(1)) })
        assertTrue(loader.load() is AvatarAttempt.Success)
    }
    @Test fun cancellationDoesNotStartAnotherRequest() = runBlocking {
        var calls = 0
        val loader = ProfileAvatarLoader({ throw CancellationException() }, { calls++; social }, { AvatarAttempt.Unavailable })
        try { loader.load(); fail() } catch (_: CancellationException) { }
        assertEquals(0, calls)
    }
    @Test fun onlyResolvedHttpsAppleCdnUrlsAreAccepted() {
        listOf(null, "", "http://is1-ssl.mzstatic.com/a", "https://other.example/a", "https://user:secret@is1-ssl.mzstatic.com/a", "https://is1-ssl.mzstatic.com/{other}").forEach { assertNull(ProfileAvatarLoader.artworkUrl(it)) }
        assertNotNull(ProfileAvatarLoader.artworkUrl("https://is1-ssl.mzstatic.com/a"))
    }
}
