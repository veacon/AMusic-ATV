package com.applemusicktv.data.network

import android.app.Application
import com.applemusicktv.data.MutPreferences
import com.applemusicktv.data.datasource.DirectLyricsSource
import com.applemusicktv.media.AppleDirectClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class LyricsStorefrontFallbackTest {
    @Test fun blankRegionUsesChinaWithoutOverridingExplicitAccountRegion()=runBlocking {
        val urls=mutableListOf<String>()
        val client=OkHttpClient.Builder().addInterceptor { chain ->
            urls += chain.request().url.toString()
            val body=JSONObject().put("data",JSONArray().put(JSONObject().put("attributes",JSONObject()
                .put("ttml","""<tt><body><p begin="0s" end="1s">测试文本</p></body></tt>""")))).toString()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture").body(body.toResponseBody()).build()
        }.build()
        val apple=AppleDirectClient()
        apple.javaClass.getDeclaredField("cachedBearer").apply { isAccessible=true }.set(apple,"fixture-no-network")
        val prefs=MutPreferences(RuntimeEnvironment.getApplication());prefs.setMUT("fixture")
        val source=DirectLyricsSource(apple,prefs,client)
        assertEquals(1,source.getLyrics("123","").size)
        assertEquals(1,source.getLyrics("456","jp").size)
        assertTrue(urls[0].contains("/catalog/cn/songs/123/"))
        assertTrue(urls[1].contains("/catalog/jp/songs/456/"))
    }
}
