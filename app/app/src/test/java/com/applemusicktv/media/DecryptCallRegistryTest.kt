package com.applemusicktv.media

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

class DecryptCallRegistryTest {
    private class FakeCall:Call {
        val cancelled=AtomicBoolean();var executions=0
        override fun request()=Request.Builder().url("https://fixture.example/media").build()
        override fun execute():Response {executions++;return Response.Builder().request(request()).protocol(Protocol.HTTP_1_1)
            .code(200).message("fixture").body("fixture".toResponseBody()).build()}
        override fun enqueue(responseCallback:Callback) { throw UnsupportedOperationException() }
        override fun cancel() {cancelled.set(true)}
        override fun isExecuted()=executions>0
        override fun isCanceled()=cancelled.get()
        override fun timeout()=Timeout.NONE
        public override fun clone():Call=FakeCall()
    }
    @Test fun switchingSongsCancelsOldBodyReadAndPreservesPromotedNextSong()=runBlocking {
        val registry=DecryptCallRegistry();val old=FakeCall();val next=FakeCall();val entered=CountDownLatch(2)
        suspend fun download(call:FakeCall,id:String)=withContext(Dispatchers.IO) {
            registry.read(call,id,currentCoroutineContext()[Job]!!) {
                entered.countDown()
                val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
                while(!call.cancelled.get() && System.nanoTime()<deadline) Thread.sleep(5)
                if(call.cancelled.get()) throw IOException("cancelled fixture body")
                "done"
            }
        }
        val a=async { runCatching { download(old,"old") } }
        val b=async { runCatching { download(next,"next") } }
        withContext(Dispatchers.IO) { assertTrue(entered.await(3,TimeUnit.SECONDS)) }
        registry.cancelExcept("next")
        assertTrue(old.isCanceled());assertFalse(next.isCanceled());assertTrue(a.await().isFailure)
        registry.cancelExcept(null);assertTrue(b.await().isFailure)
    }
    @Test fun cancelledJobCannotRegisterInTheGapBeforeExecute() {
        val job=Job();job.cancel();val call=FakeCall()
        try { DecryptCallRegistry().read(call,"old",job) { fail("must not read") };fail("must cancel") }
        catch(_:CancellationException) {}
        assertEquals(0,call.executions)
    }
    @Test fun completedCallsAreRemovedAndNoLongerAffectedByLaterSelections() {
        val registry=DecryptCallRegistry();val call=FakeCall()
        assertEquals("fixture",registry.read(call,"done",Job()) { it.body!!.string() })
        registry.cancelExcept(null);assertFalse(call.isCanceled())
    }
    @Test fun cancellingMetadataAfterHeadersStillCancelsSocketAndClosesBody()=runBlocking {
        val fake=FakeCall();val entered=CountDownLatch(1);val closed=CountDownLatch(1)
        val call=object:Call by fake {
            override fun enqueue(callback:Callback) {
                val self=this
                Thread {
                    val source=object:okio.Source {
                        override fun read(sink:okio.Buffer,byteCount:Long):Long {
                            entered.countDown()
                            while(!fake.isCanceled()) Thread.sleep(2)
                            throw IOException("cancelled body fixture")
                        }
                        override fun timeout()=Timeout.NONE
                        override fun close() {closed.countDown()}
                    }
                    val body=object:ResponseBody() {
                        override fun contentType():MediaType?=null
                        override fun contentLength()=-1L
                        override fun source()=source.buffer()
                    }
                    callback.onResponse(self,fake.execute().newBuilder().body(body).build())
                }.apply { isDaemon=true;start() }
            }
        }
        val job=launch(Dispatchers.IO) {call.awaitTextResponse()}
        withContext(Dispatchers.IO) { assertTrue(entered.await(3,TimeUnit.SECONDS)) }
        job.cancelAndJoin()
        assertTrue(fake.isCanceled())
        withContext(Dispatchers.IO) {assertTrue(closed.await(3,TimeUnit.SECONDS))}
    }

}
