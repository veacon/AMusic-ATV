package com.applemusicktv.data.cache

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.*
import java.io.IOException

/** HLS keeps its original DRM key tag and byte ranges; only the encrypted payload's location changes. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class SystemAudioDataSource(private val store:DiskBudgetStore, private val scope:String,
    private val playlist:ByteArray, private val network:DataSource):DataSource {
    private var active:DataSource?=null
    private var lease:DiskBudgetStore.Lease?=null
    private var requested:Uri?=null
    private val listeners=mutableListOf<TransferListener>()
    override fun addTransferListener(listener:TransferListener) {listeners+=listener;network.addTransferListener(listener)}
    override fun open(spec:DataSpec):Long {
        requested=spec.uri
        try {
            val actual:DataSpec
            val source=when(spec.uri.scheme) {
                "amtv-playlist"->{actual=spec;ByteArrayDataSource(playlist)}
                "amtv-audio"->{
                    val name=spec.uri.lastPathSegment ?: throw IOException("Missing cached audio name")
                    if(!name.matches(Regex("encrypted_[A-Za-z0-9._-]+\\.cache"))) throw IOException("Invalid cached audio name")
                    val held=store.acquire(name,CacheLimits.MAX_MEDIA_ENTRY_BYTES) ?: throw IOException("Cached audio was evicted")
                    lease=held
                    val entry=EncryptedAudioStore.readHeader(name,scope,held.file) ?: throw IOException("Invalid cached audio")
                    if(spec.position>entry.bytes) throw IOException("Cached audio range exceeds payload")
                    val length=if(spec.length==C.LENGTH_UNSET.toLong()) entry.bytes-spec.position else minOf(spec.length,entry.bytes-spec.position)
                    actual=spec.buildUpon().setUri(Uri.fromFile(held.file)).setPosition(entry.offset+spec.position).setLength(length).build()
                    FileDataSource()
                }
                else->{actual=spec;network}
            }
            if(source!==network) listeners.forEach(source::addTransferListener)
            active=source
            return source.open(actual)
        } catch(e:Exception) {close();throw e}
    }
    override fun read(buffer:ByteArray,offset:Int,length:Int)=active!!.read(buffer,offset,length)
    override fun getUri():Uri?=requested
    override fun getResponseHeaders():Map<String,List<String>> = active?.responseHeaders ?: emptyMap()
    override fun close() {try {active?.close()} finally {active=null;lease?.close();lease=null;requested=null}}
}
