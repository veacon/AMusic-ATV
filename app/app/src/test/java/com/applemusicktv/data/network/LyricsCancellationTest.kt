package com.applemusicktv.data.network

import android.app.Application
import com.applemusicktv.data.MutPreferences
import com.applemusicktv.data.datasource.DirectLyricsSource
import com.applemusicktv.media.AppleDirectClient
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[23],application=Application::class)
class LyricsCancellationTest {
    @Test fun windowCancellationCancelsActualHttpCallWithoutStartingFallback()=runBlocking {
        val entered=CompletableDeferred<Unit>();val finish=CountDownLatch(1);val held=AtomicReference<Call>();var requests=0
        val http=OkHttpClient.Builder().addInterceptor {chain->
            requests++;held.set(chain.call());entered.complete(Unit);finish.await()
            if(chain.call().isCanceled()) throw java.io.IOException("cancelled")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture").body("{}".toResponseBody()).build()
        }.build()
        val apple=AppleDirectClient()
        apple.javaClass.getDeclaredField("cachedBearer").apply {isAccessible=true}.set(apple,"fixture-no-network")
        val prefs=MutPreferences(RuntimeEnvironment.getApplication());prefs.setMUT("fixture")
        val source=DirectLyricsSource(apple,prefs,http)
        val fallback=OkHttpClient.Builder().addInterceptor {error("cancelled Apple request must not start public fallback")}.build()
        source.javaClass.getDeclaredField("publicHttp").apply {isAccessible=true}.set(source,fallback)
        try {
            withTimeout(5_000L) {
                val request=launch {source.getLyrics("123","cn","song","artist",60L)}
                entered.await();request.cancelAndJoin()
                assertTrue(held.get().isCanceled());assertEquals(1,requests)
            }
        } finally {finish.countDown()}
    }
}
