package com.applemusicktv.data.network

import com.applemusicktv.data.datasource.DirectMusicDataSource
import com.squareup.moshi.Moshi
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class ExploreCategoryRecoveryTest {
    private var previousLanguage = com.applemusicktv.i18n.LanguageChoice.System
    @org.junit.Before fun useChineseFixtureLanguage() {
        previousLanguage = com.applemusicktv.i18n.AppLanguage.choice
        com.applemusicktv.i18n.AppLanguage.select(com.applemusicktv.i18n.LanguageChoice.Chinese)
    }
    @org.junit.After fun restoreFixtureLanguage() {
        com.applemusicktv.i18n.AppLanguage.select(previousLanguage)
    }

    private val calls=mutableListOf<Request>()
    private fun fixture(name:String)=javaClass.getResource("/v9/$name.json")!!.readText()
    private var editorialWorks=false
    private var chartsFail=false
    private fun source():DirectMusicDataSource {
        val client=OkHttpClient.Builder().addInterceptor {chain->
            val request=chain.request();calls+=request
            val path=request.url.encodedPath
            var status=200
            val body=when {
                path.endsWith("/rooms/6503108310") -> if(editorialWorks)
                    """{"data":[{"id":"6503108310","type":"rooms","attributes":{"name":"Local charts"},"relationships":{"contents":{"data":[{"id":"pl.local","type":"playlists","attributes":{"name":"本区","artwork":{"url":"https://example.com/{w}.jpg"}}}]}}}]}"""
                    else fixture("api-cn-rooms-6503108310")
                path.endsWith("/groupings/34") -> {status=404;fixture("api-cn-groupings-34")}
                path.endsWith("/charts") -> {
                    if(chartsFail) {status=503;"{}"}
                    else if(request.url.queryParameter("types")=="music-videos") fixture("charts-music-videos")
                    else {
                        val moshi=Moshi.Builder().build();val adapter=moshi.adapter(Map::class.java)
                        val music=adapter.fromJson(fixture("charts-songs-albums"))!!
                        val lists=adapter.fromJson(fixture("charts-playlists"))!!
                        adapter.toJson(mapOf("results" to (music["results"] as Map<*,*>)+(lists["results"] as Map<*,*>)))
                    }
                }
                else->throw AssertionError("Unexpected category request $path")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("fixture").body(body.toResponseBody()).build()
        }.build()
        val api=Retrofit.Builder().baseUrl("https://amp-api-edge.music.apple.com/").client(client)
            .addConverterFactory(MoshiConverterFactory.create(com.applemusicktv.data.di.NetworkModule.provideMoshi()))
            .build().create(DirectAppleApi::class.java)
        return DirectMusicDataSource(api)
    }
    @Test fun realChinaEmptyEditorialRoomRecoversPlayableSongAlbumAndPlaylistCharts()=runBlocking {
        val page=source().getRoom("6503108310")
        assertEquals("排行榜",page.title);assertEquals(3,page.sections.size)
        assertEquals(setOf("songs","albums","playlists"),page.sections.flatMap {it.albums}.map {it.type}.toSet())
        assertTrue(page.sections.all {it.albums.size==3})
        assertTrue(calls.all {it.url.encodedPath.contains("/cn/")})
    }
    @Test fun realChinaGrouping404RecoversVideoCategoryInSameStorefront()=runBlocking {
        val page=source().getGrouping("34")
        assertEquals("音乐视频",page.title);assertEquals(3,page.sections.single().videos.size)
        assertTrue(page.sections.single().videos.all {it.type=="music-videos" && it.durationMs>0 && it.artworkUrl!=null})
        assertEquals(listOf("/v1/editorial/cn/groupings/34","/v1/catalog/cn/charts"),calls.map {it.url.encodedPath})
    }
    @Test fun availableEditorialContentIsPreservedWithoutExtraCatalogRequest()=runBlocking {
        editorialWorks=true;val page=source().getRoom("6503108310")
        assertEquals("pl.local",page.sections.single().albums.single().id);assertEquals(1,calls.size)
    }
    @Test fun catalogFailureDoesNotTurnIntoASuccessfulBlankCategory()=runBlocking {
        chartsFail=true
        assertTrue(runCatching {source().getGrouping("34")}.isFailure)
    }
}
