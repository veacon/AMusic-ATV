package com.applemusicktv.media

import com.applemusicktv.data.cache.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import okhttp3.OkHttpClient
import okhttp3.Request

/** Shared preparation for foreground, prefetch and replay. Never decrypts or persists a license. */
internal class SystemAudioLoader(private val scope:CoroutineScope,private val cache:EncryptedAudioStore,
    private val token:()->String,private val bearer:suspend ()->String,
    private val playback:suspend (String,String,String)->WebPlaybackResult,
    private val log:(String)->Unit,private val http:OkHttpClient=OkHttpClient.Builder()
        .connectTimeout(30,java.util.concurrent.TimeUnit.SECONDS).readTimeout(60,java.util.concurrent.TimeUnit.SECONDS).build()) {
    data class Asset(val playback:WebPlaybackResult,val entry:EncryptedAudioStore.Entry?,val scope:String,val hit:Boolean)
    private data class Work(val scope:String,val metadata:CompletableDeferred<WebPlaybackResult>,val job:Deferred<EncryptedAudioStore.Entry?>)
    private val lock=Any()
    private val jobs=mutableMapOf<String,Work>()
    private val calls=DecryptCallRegistry()
    private val download=Mutex()
    private val invalid=mutableSetOf<String>()

    fun cancelExcept(id:String?)=synchronized(lock) {
        jobs.filterKeys {it!=id}.values.forEach {it.job.cancel()}
        calls.cancelExcept(id)
    }
    fun invalidate(id:String)=synchronized(lock) {invalid+=id}

    suspend fun prepare(id:String,foreground:Boolean):Asset {
        val mut=token();require(mut.isNotEmpty()) {"Apple Music login required"}
        val fingerprint=EncryptedAudioStore.scope(mut)
        val cached=withContext(Dispatchers.IO) {
            if(synchronized(lock){id in invalid}) null else cache.read(id,fingerprint)
        }
        if(cached!=null) {log("hit song=$id bytes=${cached.bytes}");return Asset(cached.playback,cached,fingerprint,true)}
        val work=synchronized(lock) {
            jobs[id]?.takeIf {it.scope==fingerprint && it.job.isActive} ?: run {
                jobs[id]?.job?.cancel()
                val metadata=CompletableDeferred<WebPlaybackResult>()
                val job=scope.async(Dispatchers.IO,start=CoroutineStart.LAZY) {
                    val ownerJob=currentCoroutineContext()[Job]!!
                    try {
                        log("miss song=$id; preparing ${if(foreground) "foreground" else "prefetch"}")
                        val wb=playback(id,bearer(),mut)
                        metadata.complete(wb)
                        val url=EncryptedAudioStore.resource(wb) ?: return@async null
                        download.lock()
                        try {
                            currentCoroutineContext().ensureActive()
                            val call=http.newCall(Request.Builder().url(url).build())
                            val bytes=calls.read(call,id,ownerJob) { response->
                                val body=response.body
                                if(response.code!=200 || response.header("Content-Range")!=null || body==null || body.contentLength()>CacheLimits.MAX_MEDIA_ENTRY_BYTES) null
                                else body.byteStream().use {input->
                                    val out=java.io.ByteArrayOutputStream()
                                    val buffer=ByteArray(64*1024)
                                    while(true) {
                                        ownerJob.ensureActive()
                                        val count=input.read(buffer);if(count<0)break
                                        if(out.size().toLong()+count>CacheLimits.MAX_MEDIA_ENTRY_BYTES) throw java.io.IOException("Audio cache entry too large")
                                        out.write(buffer,0,count)
                                    }
                                    out.toByteArray()
                                }
                            } ?: return@async null
                            currentCoroutineContext().ensureActive()
                            if(EncryptedAudioStore.scope(token())!=fingerprint) throw CancellationException("Account changed")
                            val saved=cache.put(id,fingerprint,wb,bytes)
                            if(saved!=null) {synchronized(lock){invalid.remove(id)};log("stored song=$id bytes=${saved.bytes}")}
                            else log("not-stored song=$id; cache quota or unsupported payload")
                            saved
                        } finally {download.unlock()}
                    } catch(e:CancellationException) {metadata.cancel(e);throw e}
                    catch(e:Exception) {metadata.completeExceptionally(e);log("failed song=$id error=${e.javaClass.simpleName}");null}
                    finally {synchronized(lock) {if(jobs[id]?.job===ownerJob) jobs.remove(id)}}
                }
                Work(fingerprint,metadata,job).also {
                    jobs[id]=it
                    job.invokeOnCompletion { cause ->
                        if(cause!=null) metadata.completeExceptionally(cause)
                        synchronized(lock) {if(jobs[id]?.job===job) jobs.remove(id)}
                    }
                    job.start()
                }
            }
        }
        val wb=work.metadata.await()
        // An uncached large song may stream immediately rather than wait for a whole-file download.
        val entry=if(foreground) withTimeoutOrNull(1_000L) {work.job.await()} else work.job.await()
        return Asset(wb,entry,fingerprint,false)
    }
}
