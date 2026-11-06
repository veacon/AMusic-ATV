package com.applemusicktv.data.auth

import android.app.Application
import com.applemusicktv.data.MutPreferences
import com.applemusicktv.data.di.DirectNetworkModule
import com.applemusicktv.data.datasource.DirectLyricsSource
import com.applemusicktv.media.AppleDirectClient
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class AppleAuthorizationTest {
    private fun response(request:Request,code:Int,body:String="{}")=Response.Builder().request(request)
        .protocol(Protocol.HTTP_1_1).code(code).message("fixture").body(body.toResponseBody()).build()
    private fun cached(apple:AppleDirectClient,token:String) {
        AppleDirectClient::class.java.getDeclaredField("cachedBearer").apply { isAccessible=true }.set(apple,token)
    }
    private fun intercept(apple:AppleDirectClient,handler:(Request)->Response) {
        val field=AppleDirectClient::class.java.getDeclaredField("http").apply { isAccessible=true }
        val original=field.get(apple) as OkHttpClient
        field.set(apple,original.newBuilder().addInterceptor { handler(it.request()) }.build())
    }
    private fun jwt(exp:Long):String {
        val payload=android.util.Base64.encodeToString("{\"exp\":$exp}".toByteArray(),android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        return "eyJhbGciOiJIUzI1NiJ9.$payload.fixture"
    }
    @Test fun publicLyricsAndRedirectBoundariesNeverCarryAppleCredentials() {
        val prefs=MutPreferences(RuntimeEnvironment.getApplication());prefs.setMUT("synthetic-user-token")
        val apple=AppleDirectClient();cached(apple,"synthetic-bearer")
        var requests=0
        val base=DirectNetworkModule.provideDirectOkHttpClient(prefs,apple)
        val public=base.newBuilder().addInterceptor {
            requests++; val r=it.request()
            listOf("Authorization","Media-User-Token","Music-User-Token","X-Music-User-Token","Cookie").forEach { header -> assertNull(header,r.header(header)) }
            response(r,200)
        }.build()
        public.newCall(Request.Builder().url("https://lrclib.net/api/get").header("Authorization","stale")
            .header("Music-User-Token","stale").header("Cookie","stale").build()).execute().close()
        assertEquals(1,requests)
        // A redirect is observed at the network interceptor, after application interceptors have run.
        val foreign=Request.Builder().url("https://untrusted.example/redirect-target").header("Authorization","stale")
            .header("Media-User-Token","stale").header("Music-User-Token","stale").header("Cookie","stale").build()
        val chain=object:Interceptor.Chain {
            override fun request()=foreign
            override fun proceed(request:Request):Response {
                listOf("Authorization","Media-User-Token","Music-User-Token","Cookie").forEach { assertNull(request.header(it)) }
                return response(request,200)
            }
            override fun connection():Connection?=null
            override fun call()=public.newCall(foreign)
            override fun connectTimeoutMillis()=10_000
            override fun readTimeoutMillis()=10_000
            override fun writeTimeoutMillis()=10_000
            override fun withConnectTimeout(timeout:Int,unit:java.util.concurrent.TimeUnit)=this
            override fun withReadTimeout(timeout:Int,unit:java.util.concurrent.TimeUnit)=this
            override fun withWriteTimeout(timeout:Int,unit:java.util.concurrent.TimeUnit)=this
        }
        base.networkInterceptors.single().intercept(chain).close()
        // Exercise the actual fallback, which uses its own unauthenticated client.
        val source=DirectLyricsSource(apple,prefs,base)
        val fallback=OkHttpClient.Builder().addInterceptor {
            assertEquals("lrclib.net",it.request().url.host)
            assertNull(it.request().header("Authorization"));assertNull(it.request().header("Media-User-Token"))
            response(it.request(),200,"{\"syncedLyrics\":\"[00:01.00]fixture lyric\"}")
        }.build()
        DirectLyricsSource::class.java.getDeclaredField("publicHttp").apply { isAccessible=true }.set(source,fallback)
        assertFalse(runBlocking { source.tryLrclib("fixture","artist",60L) }.isEmpty())
    }
    @Test fun direct401RefreshesOnceAndUser403IsDistinctFromCatalog403() {
        val state=AppleAuthState();var refreshes=0;var attempts=0;var code=401
        val client=OkHttpClient.Builder().addInterceptor(AppleAuthorizationInterceptor({"old"},{refreshes++;"new"},{"user"},state))
            .addInterceptor { attempts++; assertEquals(1,it.request().headers.values("Authorization").size);response(it.request(),code) }.build()
        client.newCall(Request.Builder().url("https://api.music.apple.com/v1/me/library/songs").build()).execute().close()
        assertEquals(2,attempts);assertEquals(1,refreshes);assertEquals(AuthorizationIssue.DeveloperUnavailable,state.issue.value)
        state.recovered();code=403
        client.newCall(Request.Builder().url("https://api.music.apple.com/v1/catalog/us/songs/1").build()).execute().close()
        assertNull(state.issue.value)
        client.newCall(Request.Builder().url("https://api.music.apple.com/v1/me/library/songs").build()).execute().close()
        assertEquals(AuthorizationIssue.UserRejected,state.issue.value)
        code=200
        client.newCall(Request.Builder().url("https://api.music.apple.com/v1/me/library/songs").build()).execute().close()
        assertNull(state.issue.value)
    }
    @Test fun optionalProfileFailuresNeverChangeGlobalAuthorizationState() {
        val state = AppleAuthState(); var code = 403
        val client = OkHttpClient.Builder().addInterceptor(AppleAuthorizationInterceptor({"old"},{"new"},{"user"},state))
            .addInterceptor { response(it.request(), code) }.build()
        for (path in listOf("/v1/me/account", "/v1/me/social/profile")) {
            for (status in listOf(403, 401)) {
                code = status
                client.newCall(Request.Builder().url("https://api.music.apple.com$path").build()).execute().close()
                assertNull(state.issue.value)
            }
        }
        state.userRejected(); code = 200
        client.newCall(Request.Builder().url("https://api.music.apple.com/v1/me/account").build()).execute().close()
        assertEquals(AuthorizationIssue.UserRejected, state.issue.value)
    }
    @Test fun expiredBearerRefreshAndConcurrent401RefreshAreCoalesced()=runBlocking {
        val apple=AppleDirectClient();val old=jwt(System.currentTimeMillis()/1000-10)
        val fresh=jwt(System.currentTimeMillis()/1000+3600);cached(apple,old)
        val scripts=AtomicInteger()
        intercept(apple) { r ->
            if(r.url.encodedPath=="/") response(r,200,"<script crossorigin src=\"/assets/index-fixture.js\"></script>")
            else {scripts.incrementAndGet();response(r,200,"token='$fresh'")}
        }
        assertEquals(fresh,apple.getBearer())
        val tokens=(0..7).map { async(Dispatchers.IO) { apple.refreshBearer(old) } }.awaitAll()
        assertTrue(tokens.all { it==fresh });assertEquals(1,scripts.get());assertEquals(fresh,apple.getBearer())
    }
    @Test fun tokenValidationSeparatesWrongTokenFromNetworkAndDeveloperFailures()=runBlocking {
        val apple=AppleDirectClient();cached(apple,"synthetic-bearer")
        var code=403;var storefrontCalls=0
        val fresh=jwt(System.currentTimeMillis()/1000+3600)
        intercept(apple) { r -> when(r.url.host) {
            "music.apple.com" -> if(r.url.encodedPath=="/") response(r,200,"<script crossorigin src=\"/assets/index-fixture.js\">") else response(r,200,fresh)
            else -> { storefrontCalls++; if(code==0) throw java.io.IOException("offline fixture")
                response(r,code,"{\"data\":[{\"id\":\"us\"}]}") }
        } }
        assertEquals(TokenVerification.Invalid,apple.validateUserToken("wrong-token-fixture-123456"))
        assertNull(apple.authorization.issue.value) // A rejected candidate does not invalidate the saved account.
        code=0;assertEquals(TokenVerification.Unavailable,apple.validateUserToken("network-fixture-token-123456"))
        code=401;val before=storefrontCalls
        assertEquals(TokenVerification.Unavailable,apple.validateUserToken("developer-fixture-token-123456"))
        assertEquals(2,storefrontCalls-before)
        code=200;assertEquals(TokenVerification.Valid,apple.validateUserToken("valid-fixture-token-123456"))
    }
    @Test fun playbackClientAlsoRefreshes401OnlyOnce()=runBlocking {
        val apple=AppleDirectClient();cached(apple,"synthetic-old")
        val fresh=jwt(System.currentTimeMillis()/1000+3600);var calls=0
        intercept(apple) { r -> when(r.url.host) {
            "music.apple.com" -> if(r.url.encodedPath=="/") response(r,200,"<script crossorigin src=\"/assets/index-fixture.js\">") else response(r,200,fresh)
            else -> { calls++;response(r,401) }
        } }
        val field=AppleDirectClient::class.java.getDeclaredField("http").apply { isAccessible=true }
        (field.get(apple) as OkHttpClient).newCall(Request.Builder().url("https://play.itunes.apple.com/fixture")
            .header("Authorization","Bearer synthetic-old").build()).execute().close()
        assertEquals(2,calls);assertEquals(AuthorizationIssue.DeveloperUnavailable,apple.authorization.issue.value)
    }
    @Test fun decryptCancellationStopsBetweenFragmentsWithoutChangingDefaultOutput() {
        val decryptor=com.applemusicktv.media.widevine.CencDecryptor("00".repeat(16))
        val box=byteArrayOf(0,0,0,8,102,116,121,112)
        val data=box+box+box+box
        val expected=decryptor.decryptWhole(data)
        assertArrayEquals(expected,decryptor.decryptWhole(data) {})
        var checks=0
        try {decryptor.decryptWhole(data) { if(++checks==3) throw CancellationException("fixture") };fail("must stop")}
        catch(_:CancellationException) {}
        assertEquals(3,checks)
    }

}
