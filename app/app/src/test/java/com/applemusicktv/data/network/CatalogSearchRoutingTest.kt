package com.applemusicktv.data.network

import com.applemusicktv.data.datasource.DirectMusicDataSource
import com.squareup.moshi.Moshi
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class CatalogSearchRoutingTest {
    private val requests = java.util.Collections.synchronizedList(mutableListOf<Request>())
    private fun api(body: (Request) -> String): DirectAppleApi {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body(body(request).toResponseBody("application/json".toMediaType())).build()
        }.build()
        return Retrofit.Builder().baseUrl("https://amp-api-edge.music.apple.com/")
            .client(client).addConverterFactory(MoshiConverterFactory.create(Moshi.Builder().build()))
            .build().create(DirectAppleApi::class.java)
    }

    @Test fun catalogSearchUsesOfficialHostAndPreservesQuery() = runBlocking {
        api { "{\"results\":{}}" }.search("cn", "晴天 + live", 20)
        val url = requests.single().url
        assertEquals("api.music.apple.com", url.host)
        assertEquals("/v1/catalog/cn/search", url.encodedPath)
        assertEquals("晴天 + live", url.queryParameter("term"))
        assertEquals("20", url.queryParameter("limit"))
        assertEquals("songs,albums,artists,playlists", url.queryParameter("types"))
    }

    @Test fun otherSearchAndLibraryEndpointsKeepEdgeHost() = runBlocking {
        val service = api { "{}" }
        service.librarySongs()
        service.searchRaw("cn", "晴天", "songs")
        service.edSearch("cn", "华语", "curators,apple-curators")
        assertEquals(3, requests.size)
        assertTrue(requests.all { it.url.host == "amp-api-edge.music.apple.com" })
        assertEquals("/v1/me/library/songs", requests.first().url.encodedPath)
    }

    private val allTypes = """{"results":{
        "songs":{"data":[{"id":"song1","type":"songs","attributes":{"name":"晴天","artistName":"周杰伦"}}]},
        "albums":{"data":[{"id":"album1","type":"albums","attributes":{"name":"范特西"}}]},
        "artists":{"data":[{"id":"artist1","type":"artists","attributes":{"name":"周杰伦"}}]},
        "playlists":{"data":[{"id":"pl.test","type":"playlists","attributes":{"name":"华语经典","playlistType":"editorial"}}]}
    }}"""

    @Test fun officialResponseKeepsAllFourResourceTypes() = runBlocking {
        val result = DirectMusicDataSource(api { request ->
            if (request.url.host == "api.music.apple.com") allTypes else "{\"results\":{}}"
        }).search("晴天").getOrThrow()
        assertEquals("晴天", result.songs.single().title)
        assertEquals("范特西", result.albums.single().title)
        assertEquals("周杰伦", result.artists.single().name)
        assertEquals("pl.test", result.playlists.single().id)
        assertEquals("华语经典", result.playlists.single().name)
        assertEquals(1, requests.count { it.url.host == "api.music.apple.com" })
    }

    @Test fun missingCategoriesAreRetriedOnOfficialHost() = runBlocking {
        val result = DirectMusicDataSource(api { request ->
            if (request.url.host != "api.music.apple.com") "{\"results\":{}}"
            else if (request.url.queryParameter("types")!!.contains(','))
                """{"results":{"artists":{"data":[{"id":"artist1","type":"artists","attributes":{"name":"周杰伦"}}]}}}"""
            else allTypes
        }).search("周杰伦").getOrThrow()
        assertEquals(1, result.songs.size)
        assertEquals(1, result.albums.size)
        assertEquals(1, result.artists.size)
        assertEquals(1, result.playlists.size)
        val catalog = requests.filter { it.url.host == "api.music.apple.com" }
        assertEquals(setOf("songs,albums,artists,playlists", "songs", "albums", "playlists"),
            catalog.map { it.url.queryParameter("types") }.toSet())
    }
}
