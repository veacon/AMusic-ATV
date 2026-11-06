package com.applemusicktv.data.network

import com.applemusicktv.data.datasource.DirectMusicDataSource
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LibraryAndArtistPaginationTest {
    private fun source(handler: (String, Array<out Any?>) -> Any?): DirectMusicDataSource {
        val api = Proxy.newProxyInstance(DirectAppleApi::class.java.classLoader, arrayOf(DirectAppleApi::class.java)) { _, method, args ->
            handler(method.name, args ?: emptyArray())
        } as DirectAppleApi
        return DirectMusicDataSource(api)
    }

    @Test fun confirmedDateOrderFollowsPagesAndSurvivesMapping() = runBlocking {
        val requested = mutableListOf<String>()
        val source = source { method, args ->
            check(method == "artistViewPage")
            val url = args[0] as String
            requested += url
            if (!url.contains("sort=")) mapOf("meta" to mapOf("sorts" to listOf(mapOf("name" to "dateAdded")), "total" to 2))
            else if (url.contains("offset=0")) mapOf("meta" to mapOf("currentSort" to "-dateAdded"), "data" to listOf(mapOf("id" to "a", "attributes" to mapOf("name" to "A"))), "next" to "/v1/me/library/albums?offset=1")
            else mapOf("meta" to mapOf("currentSort" to "-dateAdded"), "data" to listOf(mapOf("id" to "b", "attributes" to mapOf("name" to "B"))))
        }
        val albums = source.libraryAlbums().getOrThrow().albums
        assertEquals(listOf("a", "b"), albums.map { it.id })
        assertEquals(listOf(0, 1), albums.map { it.additionOrder })
        assertEquals(3, requested.size)
    }

    @Test fun fallbackPlaylistReadsEveryPage() = runBlocking {
        val source = source { method, args -> when (method) {
            "artistViewPage" -> mapOf("meta" to emptyMap<String, Any>())
            "libraryPlaylists" -> if (args[1] == 0) AppleList(listOf(AppleItem(id = "p1", attributes = ApplePlaylistAttrs(name = "First"))), "/next")
                else AppleList(listOf(AppleItem(id = "p2", attributes = ApplePlaylistAttrs(name = "Second"))))
            else -> error(method)
        } }
        assertEquals(listOf("p1", "p2"), source.libraryPlaylists().getOrThrow().playlists.map { it.id })
    }
    @Test fun artistFullAlbumsIncludesContinuation() = runBlocking {
        val source = source { method, _ -> when (method) {
            "catalogArtistFull" -> mapOf("data" to listOf(mapOf("id" to "artist", "attributes" to mapOf("name" to "Artist"), "views" to mapOf("full-albums" to mapOf("data" to listOf(mapOf("id" to "a", "attributes" to mapOf("name" to "A"))), "next" to "/v1/catalog/cn/artists/artist/view/full-albums?offset=1")))))
            "artistViewPage" -> mapOf("data" to listOf(mapOf("id" to "b", "attributes" to mapOf("name" to "B"))))
            else -> error(method)
        } }
        assertEquals(listOf("a", "b"), source.artistFull("artist").getOrThrow().albums.map { it.id })
    }

}
