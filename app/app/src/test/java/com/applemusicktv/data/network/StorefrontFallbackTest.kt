package com.applemusicktv.data.network

import com.applemusicktv.data.datasource.DirectMusicDataSource
import com.squareup.moshi.Moshi
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class StorefrontFallbackTest {
    private val requests=java.util.Collections.synchronizedList(mutableListOf<Request>())
    private var region: String? = null
    private fun source(): DirectMusicDataSource {
        val client=OkHttpClient.Builder().addInterceptor { chain ->
            val request=chain.request(); requests += request
            val isRegion=request.url.encodedPath=="/v1/me/storefront"
            val code=if(isRegion && region==null) 403 else 200
            val body=if(isRegion && region!=null) """{"data":[{"id":"$region","type":"storefronts","attributes":{}}]}"""
                else if(isRegion) """{"errors":[{"status":"403"}]}""" else """{"results":{}}"""
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture").body(body.toResponseBody()).build()
        }.build()
        return DirectMusicDataSource(Retrofit.Builder().baseUrl("https://amp-api-edge.music.apple.com/")
            .client(client).addConverterFactory(MoshiConverterFactory.create(Moshi.Builder().build()))
            .build().create(DirectAppleApi::class.java))
    }
    @Test fun failedRegionLookupKeepsChinaFallbackForActualCatalogRequests()=runBlocking {
        val source=source()
        source.detectStorefront() // Reproduces the startup 403.
        source.search("我很愉快").getOrThrow()
        assertEquals("cn",source.storefront)
        assertTrue(requests.filter { it.url.encodedPath.contains("/catalog/") }.all { it.url.encodedPath.startsWith("/v1/catalog/cn/") })
        assertTrue(requests.any { it.url.encodedPath.contains("/catalog/cn/") })
    }
    @Test fun successfulAccountRegionStillOverridesFallbackAndSurvivesLaterFailure()=runBlocking {
        val source=source();region="jp";source.detectStorefront()
        region=null;source.detectStorefront();source.search("test").getOrThrow()
        assertEquals("jp",source.storefront)
        assertTrue(requests.filter { it.url.encodedPath.contains("/catalog/") }.all { it.url.encodedPath.startsWith("/v1/catalog/jp/") })
    }
}
