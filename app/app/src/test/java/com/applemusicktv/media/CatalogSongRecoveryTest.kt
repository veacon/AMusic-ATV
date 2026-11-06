package com.applemusicktv.media

import android.app.Application
import com.applemusicktv.data.model.Song
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class CatalogSongRecoveryTest {
    private val song = Song("i.eoD8YYgfZzEddl","除此之外","范逸臣","范逸臣第一张个人专辑",269707,null,null,null)
    private val requests = mutableListOf<Request>()
    private var library = JSONObject().put("data",JSONArray().put(JSONObject().put("attributes",JSONObject().put("playParams",JSONObject().put("catalogId","1528692369")))))
    private val catalog = mutableMapOf<String,JSONObject>()
    private var hits = listOf<JSONObject>()
    private var searchCode = 200
    private var searchBody: String? = null
    private fun row(id:String="1854817602", title:String=song.title, artist:String=song.artistName,
        album:String=song.albumName, duration:Long=song.durationMs, playable:Boolean=true):JSONObject =
        JSONObject().put("id",id).put("type","songs").put("attributes",JSONObject().put("name",title)
            .put("artistName",artist).put("albumName",album).put("durationInMillis",duration).apply {
                if(playable) put("playParams",JSONObject().put("id",id))
            })
    private fun resolver(): CatalogSongRecovery = CatalogSongRecovery(OkHttpClient.Builder().addInterceptor { chain ->
        val req=chain.request();requests += req
        val path=req.url.encodedPath
        val isSearch=path.endsWith("/search")
        val isLibrary=path.contains("/me/library/")
        val found=catalog[path.substringAfterLast('/')]
        val code=if(isSearch) searchCode else if(isLibrary || found!=null) 200 else 404
        val body=if(isSearch) searchBody ?: JSONObject().put("results",JSONObject().put("songs",JSONObject().put("data",JSONArray(hits)))).toString()
            else if(isLibrary) library.toString() else JSONObject().put("data",JSONArray().apply { if(found!=null) put(found) }).toString()
        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("fixture").body(body.toResponseBody()).build()
    }.build())
    private suspend fun find(s:Song=song,sf:String="cn")=resolver().find(s,sf,"fixture-bearer","fixture-user")?.id

    @Test fun withdrawnLibraryReleaseUsesOfficialChinaSearchAndFindsRecordedReplacement()=runBlocking {
        // Captured scene: stale 1528692369 is 404; both releases occur in the official cn results.
        hits=listOf(row("1854823106",album="无乐不作",duration=268240),row())
        assertEquals("1854817602",find())
        val search=requests.last()
        assertEquals("api.music.apple.com",search.url.host)
        assertEquals("/v1/catalog/cn/search",search.url.encodedPath)
        assertEquals("除此之外 范逸臣",search.url.queryParameter("term"))
        assertEquals("Bearer fixture-bearer",search.header("Authorization"))
        assertEquals("fixture-user",search.header("Music-User-Token"))
        assertFalse(requests.any { it.url.encodedPath.contains("/us/") })
    }
    @Test fun reportingIdIsOnlyAcceptedAfterCatalogAndSongValidation()=runBlocking {
        library.getJSONArray("data").getJSONObject(0).getJSONObject("attributes").getJSONObject("playParams").put("reportingId","1854817602")
        catalog["1854817602"]=row()
        assertEquals("1854817602",find())
        assertTrue(requests.any { it.url.encodedPath=="/v1/catalog/cn/songs/1854817602" })
        assertFalse(requests.any { it.url.encodedPath.endsWith("/search") })
    }
    @Test fun wrongReportingHintCannotPlayAnotherArtistAndSearchStillRecovers()=runBlocking {
        library.getJSONArray("data").getJSONObject(0).getJSONObject("attributes").getJSONObject("playParams").put("reportingId","999")
        catalog["999"]=row("999",artist="其他歌手");hits=listOf(row())
        assertEquals("1854817602",find())
        assertTrue(requests.last().url.encodedPath.endsWith("/search"))
    }
    @Test fun accountRegionIsHonoredAndFailedCatalogIdIsNotRetriedAsAHint()=runBlocking {
        val english=song.copy(id="1528692369",title="Chu Ci Zhi Wai",artistName="Van Fan",albumName="First Album")
        hits=listOf(row("1854817602",english.title,english.artistName,english.albumName),row("1528692369",english.title,english.artistName,english.albumName))
        assertEquals("1854817602",find(english,"us"))
        assertEquals(1,requests.size)
        assertEquals("/v1/catalog/us/search",requests.single().url.encodedPath)
    }
    @Test fun rejectsTitleOnlyMatchesAndArtistSubstrings()=runBlocking {
        hits=listOf(row(artist="范逸臣翻唱团"),row("8",artist="其他歌手"),row("9",title="另首歌"))
        assertNull(find())
    }
    @Test fun rejectsLiveInstrumentalAndDifferentDurationVersions()=runBlocking {
        hits=listOf(row(title="除此之外 (Live)"),row("8",title="除此之外（伴奏）"),row("9",duration=300000))
        assertNull(find())
    }
    @Test fun prefersOriginalAlbumOverCompilationEvenWhenCompilationDurationIsCloser()=runBlocking {
        hits=listOf(row("8",album="精选集"),row(duration=270000))
        assertEquals("1854817602",find())
    }
    @Test fun noPlayableParamsOrUnknownDurationCannotPassWhenSourceDurationIsKnown()=runBlocking {
        hits=listOf(row(playable=false),row("8",duration=0))
        assertNull(find())
    }
    @Test fun unknownSourceDurationRequiresMatchingAlbum()=runBlocking {
        hits=listOf(row("8",album="精选集"),row())
        assertEquals("1854817602",find(song.copy(durationMs=0)))
        assertNull(find(song.copy(durationMs=0,albumName="")))
    }
    @Test fun normalizesCaseWidthAndSpacingWithoutDiscardingVersionWords()=runBlocking {
        val source=song.copy(title="Ａ Song",artistName="Artist",albumName="Album")
        hits=listOf(row(title="a song",artist="ARTIST",album="album"))
        assertEquals("1854817602",find(source))
    }
    @Test fun malformedOrUnauthorizedSearchCannotProduceAReplacement()=runBlocking {
        searchCode=401;hits=listOf(row());assertNull(find())
        searchCode=200;searchBody="not-json";assertNull(find())
        searchBody="{}";assertNull(find())
    }
    @Test fun verifiedHintIncludesArtworkForDisplayRecovery()=runBlocking {
        val current=row()
        current.getJSONObject("attributes").put("artwork",JSONObject().put("url","https://is1-ssl.mzstatic.com/image/{w}x{h}bb.jpg").put("bgColor","aabbcc"))
        library.getJSONArray("data").getJSONObject(0).getJSONObject("attributes").getJSONObject("playParams").put("reportingId","1854817602")
        catalog["1854817602"]=current
        val found=resolver().find(song,"cn","fixture","fixture")!!
        assertEquals("1854817602",found.id)
        assertEquals("https://is1-ssl.mzstatic.com/image/{w}x{h}bb.jpg",found.artworkUrl)
        assertEquals("aabbcc",found.artworkBgColor)
    }
    @Test fun searchResultIncludesArtworkAndRejectsNonHttpsUrls()=runBlocking {
        val current=row()
        current.getJSONObject("attributes").put("artwork",JSONObject().put("url","https://is1-ssl.mzstatic.com/image/{w}x{h}bb.jpg").put("bgColor","aabbcc"))
        hits=listOf(current)
        assertEquals("aabbcc",resolver().find(song,"cn","fixture","fixture")!!.artworkBgColor)
        current.getJSONObject("attributes").getJSONObject("artwork").put("url","file:///private/image").put("bgColor","invalid")
        val found=resolver().find(song,"cn","fixture","fixture")!!
        assertNull(found.artworkUrl);assertNull(found.artworkBgColor)
    }
    @Test fun blankRegionUsesChinaAndMissingCredentialsOrVideoMakeNoRequests()=runBlocking {
        hits=listOf(row());assertEquals("1854817602",find(sf=""))
        assertTrue(requests.last().url.encodedPath.contains("/cn/"))
        requests.clear()
        val r=resolver()
        assertNull(r.find(song,"cn","","fixture"))
        assertNull(r.find(song,"cn","fixture",""))
        assertNull(r.find(song.copy(type="music-videos"),"cn","fixture","fixture"))
        assertTrue(requests.isEmpty())
    }
}
