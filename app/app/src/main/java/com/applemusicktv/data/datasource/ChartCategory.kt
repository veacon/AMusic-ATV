package com.applemusicktv.data.datasource

import com.applemusicktv.data.network.*

/** Regional catalog charts replace unavailable/empty fixed editorial IDs, without changing storefront. */
internal fun chartCategory(id:String,title:String,raw:Map<String,Any>,videoOnly:Boolean):MultiRoomDto {
    val results=raw["results"] as? Map<*,*> ?: emptyMap<Any,Any>()
    val sections=results.flatMap { (type,groups) ->
        if((type=="music-videos")!=videoOnly) return@flatMap emptyList()
        (groups as? List<*>)?.mapNotNull { group ->
            val g=group as? Map<*,*>?:return@mapNotNull null
            val rows=(g["data"] as? List<*>)?.mapNotNull {it as? Map<*,*>}.orEmpty()
            val name=g["name"] as? String?:title
            val items=rows.mapNotNull { row ->
                val key=row["id"] as? String?:return@mapNotNull null
                val attrs=row["attributes"] as? Map<*,*>?:return@mapNotNull null
                val art=attrs["artwork"] as? Map<*,*>
                val url=art?.get("url") as? String?:return@mapNotNull null
                AlbumDto(key,attrs["name"] as? String?:return@mapNotNull null,
                    (attrs["artistName"]?:attrs["curatorName"]) as? String?:"",url,
                    type=row["type"] as? String?:type.toString(),artworkBgColor=art["bgColor"] as? String,releaseDate=null)
            }
            if(items.isEmpty()) null else if(videoOnly) HomeSection(name,videos=items.map {item->
                val attrs=rows.first {it["id"]==item.id}["attributes"] as Map<*,*>
                SongDto(item.id,"music-videos",item.title,item.artistName,albumName="",
                    durationMs=(attrs["durationInMillis"] as? Number)?.toLong()?:0L,
                    artworkUrl=item.artworkUrl,artworkBgColor=item.artworkBgColor,previewUrl=null,previewHlsUrl=null)
            }) else HomeSection(name,items)
        }.orEmpty()
    }
    return MultiRoomDto(id=id,title=title,sections=sections)
}
