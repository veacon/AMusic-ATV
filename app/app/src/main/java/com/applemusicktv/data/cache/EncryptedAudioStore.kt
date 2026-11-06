package com.applemusicktv.data.cache

import com.applemusicktv.media.WebPlaybackResult
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.RandomAccessFile
import java.net.URI
import java.security.MessageDigest

/** Atomic, account-bound bundle. The payload stays encrypted; licenses are still obtained by MediaDrm. */
internal class EncryptedAudioStore(private val store: DiskBudgetStore) {
    data class Entry(val name:String, val scope:String, val playback:WebPlaybackResult,
        val offset:Long, val bytes:Long)

    fun read(id:String, scope:String):Entry? {
        val name=name(id)
        val lease=store.acquire(name,CacheLimits.MAX_MEDIA_ENTRY_BYTES) ?: return null
        return lease.use { readHeader(name,scope,it.file) }
    }

    fun put(id:String, scope:String, playback:WebPlaybackResult, payload:ByteArray):Entry? {
        if(payload.isEmpty() || resource(playback)==null || payload.size.toLong()<requiredBytes(playback)) return null
        val header=JSONObject().put("version",1).put("scope",scope).put("adamId",playback.adamId)
            .put("hlsUrl",playback.hlsUrl).put("keyUri",playback.keyUri).put("hlsText",playback.hlsText)
            .put("bytes",payload.size).toString().toByteArray(Charsets.UTF_8)
        if(header.size>MAX_HEADER || payload.size.toLong()+header.size+8>CacheLimits.MAX_MEDIA_ENTRY_BYTES) return null
        val stream=ByteArrayOutputStream(payload.size+header.size+8)
        DataOutputStream(stream).use { it.writeInt(MAGIC);it.writeInt(header.size);it.write(header);it.write(payload) }
        val name=name(id)
        val file=store.put(name,stream.toByteArray(),headroomBytes=CacheLimits.MEDIA_HEADROOM_BYTES) ?: return null
        return readHeader(name,scope,file)
    }

    companion object {
        private const val MAGIC=0x414D4131
        private const val MAX_HEADER=256*1024
        fun name(id:String)="encrypted_${id.replace(Regex("[^A-Za-z0-9._-]"),"_")}.cache"
        fun scope(token:String)=MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
            .joinToString("") {"%02x".format(it.toInt() and 255)}

        fun readHeader(name:String,scope:String,file:java.io.File):Entry?=runCatching {
            RandomAccessFile(file,"r").use { input->
                require(input.readInt()==MAGIC)
                val size=input.readInt();require(size in 1..MAX_HEADER)
                val header=ByteArray(size);input.readFully(header)
                val json=JSONObject(String(header,Charsets.UTF_8))
                require(json.getInt("version")==1 && json.getString("scope")==scope)
                val bytes=json.getLong("bytes");val offset=8L+size
                require(bytes>0 && file.length()==offset+bytes && file.length()<=CacheLimits.MAX_MEDIA_ENTRY_BYTES)
                val playback=WebPlaybackResult(json.getString("adamId"),json.getString("hlsUrl"),
                    json.getString("keyUri"),json.getString("hlsText"))
                require(resource(playback)!=null && bytes>=requiredBytes(playback))
                Entry(name,scope,playback,offset,bytes)
            }
        }.getOrNull()

        /** Cache only finite, single-resource fMP4 audio. Preserve signed URL queries during download. */
        fun resource(wb:WebPlaybackResult):String?=runCatching {
            require(wb.hlsText.lineSequence().any {it.trim()=="#EXT-X-ENDLIST"})
            val base=URI(wb.hlsUrl)
            val urls=wb.hlsText.lineSequence().map {it.trim()}.mapNotNull { line->
                when {
                    line.startsWith("#EXT-X-MAP:")->Regex("URI=\"([^\"]+)\"").find(line)?.groupValues?.get(1)
                    line.isNotEmpty() && !line.startsWith("#")->line
                    else->null
                }
            }.map {base.resolve(it)}.toList()
            require(urls.isNotEmpty() && urls.all {it.scheme in listOf("https","http")})
            val identities=urls.map {URI(it.scheme,it.authority,it.path,null,null).toString()}.distinct()
            require(identities.size==1)
            urls.first().toString()
        }.getOrNull()

        /** A partial HTTP response must never be promoted to a complete audio cache entry. */
        fun requiredBytes(wb:WebPlaybackResult):Long {
            var next=0L;var end=0L
            for(raw in wb.hlsText.lineSequence()) {
                val line=raw.trim()
                val range=when {
                    line.startsWith("#EXT-X-MAP:")->Regex("BYTERANGE=\"([^\"]+)\"").find(line)?.groupValues?.get(1)
                    line.startsWith("#EXT-X-BYTERANGE:")->line.substringAfter(':')
                    else->null
                } ?: continue
                val parts=range.split('@')
                val length=parts[0].toLongOrNull() ?: return Long.MAX_VALUE
                val offset=if(parts.size>1) parts[1].toLongOrNull() ?: return Long.MAX_VALUE else next
                if(length<=0 || offset<0 || offset>Long.MAX_VALUE-length) return Long.MAX_VALUE
                next=offset+length;end=maxOf(end,next)
            }
            return end
        }

        fun localPlaylist(rewritten:String, mediaUri:String):String=rewritten.lineSequence().joinToString("\n") { raw->
            val line=raw.trim()
            when {
                line.startsWith("#EXT-X-MAP:")->Regex("URI=\"([^\"]+)\"").replace(line,"URI=\"$mediaUri\"")
                line.isNotEmpty() && !line.startsWith("#")->mediaUri
                else->line
            }
        }
    }
}
