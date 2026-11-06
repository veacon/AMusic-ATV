package com.applemusicktv.data.datasource

import com.applemusicktv.i18n.tr
import com.applemusicktv.R

import com.applemusicktv.data.network.*
import com.applemusicktv.data.NetworkLog
import retrofit2.HttpException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl.Companion.toHttpUrl
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DirectMusicDataSource @Inject constructor(private val api: DirectAppleApi) {

    var storefront: String = "cn"
        private set

    // ── Library writes (standalone path) ────────────────────────────────
    /** Add a catalog item to the user's library. type is "songs"/"albums"/"music-videos"/"playlists". */
    suspend fun addToLibrary(id: String, type: String): Result<Unit> = runCatching {
        val res = api.addToLibrary(mapOf("ids[$type]" to id))
        if (!res.isSuccessful) error("HTTP ${res.code()}")
    }

    /** Append a song to an editable library playlist. */
    suspend fun addToPlaylist(playlistId: String, songId: String, type: String = "songs"): Result<Unit> = runCatching {
        val res = api.addTracksToPlaylist(playlistId,
            com.applemusicktv.data.network.AddTracksBody(listOf(com.applemusicktv.data.network.AddTrackRef(songId, type))))
        if (!res.isSuccessful) error("HTTP ${res.code()}")
    }

    suspend fun detectStorefront() {
        runCatching {
            val sf = api.storefront().data.firstOrNull()?.id
            if (!sf.isNullOrEmpty()) storefront = sf
        }
    }

    suspend fun search(term: String, limit: Int = 20): Result<SearchResponse> = runCatching {
        coroutineScope {
            val combined = runCatching { api.search(storefront, term, limit) }
            currentCoroutineContext().ensureActive()
            val initial = combined.getOrNull()?.results ?: AppleSearchResults()
            // Some responses contain only the top resource type. Query missing types separately,
            // keeping successful categories even if an unrelated editorial request fails.
            val songs = async { if (initial.songs.data.isNotEmpty()) Result.success(initial.songs.data)
                else runCatching { api.search(storefront, term, limit, types = "songs").results.songs.data } }
            val albums = async { if (initial.albums.data.isNotEmpty()) Result.success(initial.albums.data)
                else runCatching { api.search(storefront, term, limit, types = "albums").results.albums.data } }
            val artists = async { if (initial.artists.data.isNotEmpty()) Result.success(initial.artists.data)
                else runCatching { api.search(storefront, term, limit, types = "artists").results.artists.data } }
            val playlists = async { if (initial.playlists.data.isNotEmpty()) Result.success(initial.playlists.data)
                else runCatching { api.search(storefront, term, limit, types = "playlists").results.playlists.data } }
            val curators = async { searchCurators(term) }
            val sr = songs.await(); val ar = albums.await(); val artistResult = artists.await(); val pr = playlists.await()
            currentCoroutineContext().ensureActive()
            if (combined.isFailure && listOf(sr, ar, artistResult, pr).all { it.isFailure })
                throw (combined.exceptionOrNull() ?: IllegalStateException(tr(R.string.msg_305)))
            SearchResponse(
                songs = sr.getOrDefault(emptyList()).map { it.toSongDto() },
                albums = ar.getOrDefault(emptyList()).map { it.toAlbumDto() },
                artists = artistResult.getOrDefault(emptyList()).map { it.toArtistDto() },
                playlists = pr.getOrDefault(emptyList()).sortedByDescending { it.attributes?.playlistType == "editorial" }
                    .map { it.toPlaylistDto() },
                curators = curators.await(),
            )
        }
    }

    // ── Editorial categories (curators + multirooms) — standalone parity ──────
    private fun resolveArt(url: String?, size: Int = 400): String? = url
        ?.replace("{w}", "$size")?.replace("{h}", "$size")?.replace("{f}", "jpg")

    private fun edItemToAlbumDto(it: EdItem): AlbumDto? {
        val a = it.attributes ?: return null
        // Curators (genre/mood/decade tiles, radio shows) carry editorial artwork, not `artwork`,
        // and MUST be id-prefixed so the Category screen opens them as a nested page (not an album).
        if (it.type == "apple-curators" || it.type == "curators") {
            val ea = a.editorialArtwork
            val curUrl = (ea?.subscriptionCover ?: ea?.brandLogo)?.url ?: a.artwork?.url ?: return null
            val name = (a.name ?: "Unknown").replace(Regex("^Apple Music (?=\\S)"), "").replace(Regex("^Apple (?=\\S)"), "")
            return AlbumDto(
                id = (if (it.type == "apple-curators") "ac-" else "c-") + it.id,
                title = name, artistName = "", artworkUrl = curUrl, type = "curators",
                artworkBgColor = null, releaseDate = null,
            )
        }
        val url = a.artwork?.url ?: return null   // require artwork, same as the proxy
        return AlbumDto(
            id             = it.id,
            title          = a.name ?: "Unknown",
            artistName     = a.artistName ?: a.curatorName ?: "",
            artworkUrl     = url,
            type           = it.type ?: "albums",
            artworkBgColor = a.artwork.bgColor,
            releaseDate    = null,
        )
    }

    /** Curators + multirooms, tagged by kind (multirooms first). Empty on any failure. */
    suspend fun searchCurators(term: String): List<CuratorDto> = runCatching {
        val res = api.edSearch(
            storefront, term,
            // editorial-items (multirooms) is only accepted alongside a broad type set.
            types = "artists,albums,songs,playlists,curators,apple-curators,music-videos,stations,editorial-items",
        )
        val multirooms = mutableListOf<CuratorDto>()
        val curators   = mutableListOf<CuratorDto>()
        val seen = HashSet<String>()
        val idRe = Regex("""multi-?room[s]?/(\d+)""")
        for (group in res.results.values) for (item in group.data) {
            val a = item.attributes ?: continue
            when (item.type) {
                "editorial-items" -> {
                    if (a.link?.feature != "multirooms") continue
                    val id = idRe.find(a.link.url ?: a.url ?: "")?.groupValues?.get(1) ?: continue
                    if (!seen.add("mr$id")) continue
                    multirooms += CuratorDto(
                        id = id, name = a.editorialNotes?.name ?: a.name ?: "Unknown",
                        kind = "multiroom", isApple = false,
                        artworkUrl = resolveArt(a.editorialArtwork?.subscriptionCover?.url ?: a.editorialArtwork?.brandLogo?.url),
                    )
                }
                "curators", "apple-curators" -> {
                    if (!seen.add(item.id)) continue
                    curators += CuratorDto(
                        id = item.id, name = a.name ?: "Unknown",
                        kind = if (item.type == "apple-curators") "apple-curator" else "curator",
                        isApple = item.type == "apple-curators",
                        artworkUrl = resolveArt(a.artwork?.url),
                    )
                }
            }
        }
        multirooms + curators
    }.getOrDefault(emptyList())

    // Genre/mood/decade grid — three editorial rooms of apple-curators. Mirrors the proxy.
    suspend fun getCategories(): List<CategorySectionDto> {
        val rooms = listOf("Genres" to "6456176470", "Moods & Activities" to "6456176472", "Decades" to "6456176471")
        val drop = Regex("rewind|replay|year in|wrapped", RegexOption.IGNORE_CASE)
        return rooms.mapNotNull { (title, room) ->
            val items = runCatching {
                api.edRoom(storefront, room).data.firstOrNull()?.relationships?.contents?.data.orEmpty()
                    .filter { it.type == "apple-curators" || it.type == "curators" }
                    .filter { !drop.containsMatchIn(it.attributes?.name ?: "") }
                    .map {
                        val a = it.attributes
                        val ea = a?.editorialArtwork
                        CuratorDto(
                            id = it.id,
                            name = (a?.name ?: "Unknown").replace(Regex("^Apple Music (?=\\S)"), "").replace(Regex("^Apple (?=\\S)"), ""),
                            kind = if (it.type == "apple-curators") "apple-curator" else "curator",
                            isApple = it.type == "apple-curators",
                            artworkUrl = resolveArt(ea?.subscriptionCover?.url ?: ea?.brandLogo?.url ?: a?.artwork?.url, 600),
                        )
                    }
            }.getOrDefault(emptyList())
            if (items.isEmpty()) null else CategorySectionDto(title, items)
        }
    }

    suspend fun getCurator(id: String, isApple: Boolean): MultiRoomDto {
        val kind = if (isApple) "apple-curators" else "curators"
        val cur = api.edCurator(storefront, kind, id).data.firstOrNull()
            ?: return MultiRoomDto(id = id)
        var sections = cur.relationships?.grouping?.data?.firstOrNull()?.id
            ?.let { groupingSections(it) } ?: emptyList()
        if (sections.isEmpty()) {
            val items = cur.relationships?.playlists?.data?.mapNotNull(::edItemToAlbumDto).orEmpty()
            if (items.isNotEmpty()) sections = listOf(HomeSection("Playlists", items))
        }
        val ea = cur.attributes?.editorialArtwork
        val hero = resolveArt(ea?.superHeroWide?.url ?: ea?.brandLogo?.url ?: cur.attributes?.artwork?.url, 1600)
        return MultiRoomDto(id = id, title = cur.attributes?.name ?: "", artworkUrl = hero, sections = sections)
    }

    private suspend fun groupingSections(groupingId: String): List<HomeSection> = runCatching {
        val tab = api.edGrouping(storefront, groupingId).data.firstOrNull()
            ?.relationships?.tabs?.data?.firstOrNull()
        (tab?.relationships?.children?.data ?: emptyList()).mapNotNull { k ->
            val kind = k.attributes?.editorialElementKind
            if (kind != "326" && kind != "345") return@mapNotNull null
            val title = k.attributes?.name ?: k.attributes?.title ?: return@mapNotNull null
            val albums = k.relationships?.contents?.data?.mapNotNull(::edItemToAlbumDto).orEmpty()
            if (albums.isEmpty()) null else HomeSection(title, albums)
        }
    }.getOrDefault(emptyList())

    // Multiroom hero blurb is skipped on the direct path (its description is a string|object
    // union that the typed parser can't take); shelves are what matter.
    /** A plain editorial ROOM — the "see all" page behind a Browse shelf. Its contents are a flat
     *  list (e.g. room 6503108310 "Daily Top 100" = 100 country playlists), so it renders as one
     *  section. This is what the "More" card at the end of a shelf opens. */
    suspend fun getRoom(id:String):MultiRoomDto {
        if(id!="6503108310") return editorialRoom(id)
        val editorial=runCatching {editorialRoom(id)}.getOrNull()
        currentCoroutineContext().ensureActive()
        if(editorial!=null && editorial.sections.any {it.albums.isNotEmpty() || it.videos.isNotEmpty()}) return editorial
        return chartCategory(id,tr(R.string.msg_161),api.charts(storefront,types="songs,albums,playlists",limit=50),false)
    }

    suspend fun getGrouping(id:String):MultiRoomDto {
        if(id!="34") return editorialGrouping(id)
        val editorial=runCatching {editorialGrouping(id)}.getOrNull()
        currentCoroutineContext().ensureActive()
        if(editorial!=null && editorial.sections.any {it.albums.isNotEmpty() || it.videos.isNotEmpty()}) return editorial
        return chartCategory(id,tr(R.string.msg_100),api.charts(storefront,types="music-videos",limit=50),true)
    }

    private suspend fun editorialRoom(id: String): MultiRoomDto {
        val room = api.edRoom(storefront, id).data.firstOrNull() ?: return MultiRoomDto(id = id)
        val items = room.relationships?.contents?.data?.mapNotNull(::edItemToAlbumDto).orEmpty()
        val title = room.attributes?.title ?: room.attributes?.name ?: ""
        return MultiRoomDto(
            id = id, title = title,
            sections = if (items.isEmpty()) emptyList() else listOf(HomeSection(title, items)),
        )
    }

    /** A catalog grouping (e.g. Music Videos = grouping 34). Video shelves become `videos` sections
     *  so CategoryScreen plays them in the video player; everything else is album/curator cards. */
    private suspend fun editorialGrouping(id: String): MultiRoomDto {
        val g = api.edGrouping(storefront, id).data.firstOrNull() ?: return MultiRoomDto(id = id)
        val tab = g.relationships?.tabs?.data?.firstOrNull()
        val sections = (tab?.relationships?.children?.data ?: emptyList()).mapNotNull { k ->
            val kind = k.attributes?.editorialElementKind
            if (kind != "326" && kind != "327") return@mapNotNull null
            val title = k.attributes?.name ?: k.attributes?.title ?: return@mapNotNull null
            val contents = k.relationships?.contents?.data ?: emptyList()
            val types = contents.map { it.type }.toSet()
            if (types.isNotEmpty() && types.all { it == "music-videos" || it == "uploaded-videos" }) {
                val videos = contents.mapNotNull(::edItemToVideo)
                if (videos.isEmpty()) null else HomeSection(title, videos = videos)
            } else {
                val albums = contents.mapNotNull(::edItemToAlbumDto)
                if (albums.isEmpty()) null else HomeSection(title, albums)
            }
        }
        return MultiRoomDto(id = id, title = g.attributes?.title ?: g.attributes?.name ?: "", sections = sections)
    }

    private fun edItemToVideo(it: EdItem): com.applemusicktv.data.network.SongDto? {
        val a = it.attributes ?: return null
        val url = a.artwork?.url ?: return null
        return com.applemusicktv.data.network.SongDto(
            id = it.id, type = "music-videos", title = a.name ?: "Unknown",
            artistName = a.artistName ?: "", albumName = "", durationMs = 0L,
            artworkUrl = url, artworkBgColor = a.artwork.bgColor, previewUrl = null, previewHlsUrl = null,
        )
    }

    suspend fun getMultiRoom(id: String): MultiRoomDto {
        val room = api.edMultiRoom(storefront, id).data.firstOrNull()
            ?: return MultiRoomDto(id = id)
        val sections = (room.relationships?.children?.data ?: emptyList()).mapNotNull { k ->
            if (k.attributes?.editorialElementKind != "345") return@mapNotNull null
            val title = k.attributes.title ?: return@mapNotNull null
            val albums = k.relationships?.contents?.data?.mapNotNull(::edItemToAlbumDto).orEmpty()
            if (albums.isEmpty()) null else HomeSection(title, albums)
        }
        return MultiRoomDto(id = id, title = room.attributes?.title ?: "", sections = sections)
    }

    suspend fun librarySongs(): Result<LibrarySongsResponse> {
        val ordered = runCatching { webLibrarySongs() }
        currentCoroutineContext().ensureActive()
        if (ordered.isSuccess) return ordered
        val failure = ordered.exceptionOrNull()
        val reason = if (failure is HttpException) "HTTP ${failure.code()}" else failure?.javaClass?.simpleName ?: "unknown"
        NetworkLog.line("LibrarySort", "web sort unavailable ($reason); using standard library order")
        // Keep the known working endpoint independent of the private web sort protocol.
        return standardLibrarySongs()
    }

    private suspend fun webLibrarySongs(): LibrarySongsResponse {
        val base = "https://amp-api.music.apple.com/v1/me/library/songs".toHttpUrl()
        fun requestUrl(offset: Int, sort: String? = null) = base.newBuilder()
            .addQueryParameter("platform", "web")
            .addQueryParameter("limit", "100")
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("meta", "sorts")
            .addQueryParameter("include[library-songs]", "catalog")
            .addQueryParameter("format[resources]", "map")
            .apply { if (sort != null) addQueryParameter("sort", sort) }
            .build().toString()
        val probe = api.webLibrarySongs(requestUrl(0))
        val sort = newestAdditionSort(probe.meta.sorts)
            ?: error("Web library has no supported newest-addition sort")
        NetworkLog.line("LibrarySort", "advertised=${probe.meta.sorts.joinToString { it.name }}; requested=$sort")
        var page = if (probe.meta.currentSort == sort) probe else api.webLibrarySongs(requestUrl(0, sort))
        check(page.meta.currentSort == sort) { "Web library did not confirm requested sort" }
        val all = mutableListOf<SongDto>()
        val seen = HashSet<String>()
        var received = 0
        val visited = HashSet<String>()
        while (true) {
            currentCoroutineContext().ensureActive()
            check(page.meta.currentSort == null || page.meta.currentSort == sort) { "Web library sort changed during pagination" }
            val batch = page.data.map { ref ->
                val song = page.resources.librarySongs[ref.id] ?: page.resources.songs[ref.id]
                    ?: ref.takeIf { it.attributes != null }
                    ?: error("Missing web library song resource")
                check(song.attributes != null && ref.id.isNotBlank()) { "Incomplete web library song" }
                // The identifier array is authoritative for both order and library identity.
                song.copy(id = ref.id, type = ref.type.ifBlank { "library-songs" }).toSongDto()
            }
            val fresh = batch.filter { seen.add(it.id) }
            check(batch.isEmpty() || fresh.isNotEmpty()) { "Repeated web library page" }
            all += fresh
            received += page.data.size
            val next = page.next ?: break
            check(page.data.isNotEmpty()) { "Empty web library page with continuation" }
            val nextUrl = base.resolve(next) ?: error("Invalid web library continuation")
            check(nextUrl.scheme == "https" && nextUrl.host == base.host && nextUrl.encodedPath == base.encodedPath) {
                "Unexpected web library continuation"
            }
            val offset = nextUrl.queryParameter("offset")?.toIntOrNull() ?: received
            check(offset == received && visited.add(next)) { "Invalid web library page offset" }
            // Reapply the same sort and map parameters to every page, as the web client does.
            page = api.webLibrarySongs(requestUrl(offset, sort))
        }
        probe.meta.total?.let { check(all.size >= it) { "Incomplete web library list" } }
        NetworkLog.line("LibrarySort", "confirmed=$sort; complete=${all.size}; newest first")
        return LibrarySongsResponse(songs = all, songsNewestFirst = true)
    }

    private suspend fun standardLibrarySongs(): Result<LibrarySongsResponse> = runCatching {
        val all = mutableListOf<AppleItem<AppleSongAttrs>>()
        var offset = 0
        val seen = HashSet<String>()
        while (true) {
            val page = api.librarySongs(limit = 100, offset = offset)
            val fresh = page.data.filter { seen.add(it.id) }
            if (page.data.isNotEmpty() && fresh.isEmpty()) break
            all += fresh
            if (page.next == null || page.data.isEmpty()) break
            offset += page.data.size
        }
        LibrarySongsResponse(songs = all.map { it.toSongDto() })
    }

    /** Web library order is accepted only when the response confirms dateAdded sorting. */
    private suspend fun webOrderedLibrary(kind: String): List<Pair<String, Any>> {
        val base = "https://amp-api.music.apple.com/v1/me/library/$kind".toHttpUrl()
        fun url(offset: Int, sort: String? = null) = base.newBuilder()
            .addQueryParameter("platform", "web").addQueryParameter("limit", "100")
            .addQueryParameter("offset", offset.toString()).addQueryParameter("meta", "sorts")
            .addQueryParameter("include[library-$kind]", "catalog")
            .addQueryParameter("format[resources]", "map")
            .apply { if (sort != null) addQueryParameter("sort", sort) }.build().toString()
        fun meta(page: Map<String, Any>) = page["meta"] as? Map<*, *> ?: emptyMap<Any, Any>()
        val probe = api.artistViewPage(url(0))
        val sorts = (meta(probe)["sorts"] as? List<*>)?.mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            AppleLibrarySort(name = m["name"] as? String ?: "")
        }.orEmpty()
        val sort = newestAdditionSort(sorts) ?: error("No addition date sort for $kind")
        var page = if (meta(probe)["currentSort"] == sort) probe else api.artistViewPage(url(0, sort))
        check(meta(page)["currentSort"] == sort) { "Library sort not confirmed" }
        val all = mutableListOf<Pair<String, Any>>()
        val seen = HashSet<String>()
        val visited = HashSet<String>()
        var received = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val current = meta(page)["currentSort"] as? String
            check(current == null || current == sort) { "Library sort changed" }
            val data = page["data"] as? List<*> ?: error("Missing library items")
            val resources = page["resources"] as? Map<*, *>
            val mapped = resources?.get("library-$kind") as? Map<*, *>
            for (entry in data) {
                val ref = entry as? Map<*, *> ?: error("Invalid library item")
                val id = ref["id"] as? String ?: error("Missing library id")
                val item = mapped?.get(id) as? Map<*, *> ?: ref
                val attrs = item["attributes"] ?: error("Missing library attributes")
                if (seen.add(id)) all += id to attrs
            }
            received += data.size
            val next = page["next"] as? String ?: break
            check(data.isNotEmpty()) { "Empty library continuation" }
            val nextUrl = base.resolve(next) ?: error("Invalid library continuation")
            check(nextUrl.scheme == "https" && nextUrl.host == base.host && nextUrl.encodedPath == base.encodedPath && visited.add(nextUrl.toString())) { "Unexpected library continuation" }
            val offset = nextUrl.queryParameter("offset")?.toIntOrNull() ?: received
            check(offset == received) { "Invalid library offset" }
            page = api.artistViewPage(url(offset, sort))
        }
        val total = (meta(probe)["total"] as? Number)?.toInt()
        if (total != null) check(all.size >= total) { "Incomplete library" }
        return all
    }

    suspend fun libraryAlbums(): Result<LibraryAlbumsResponse> = runCatching {
        val ordered = runCatching {
            val adapter = com.squareup.moshi.Moshi.Builder().build().adapter(AppleAlbumAttrs::class.java)
            webOrderedLibrary("albums").mapIndexed { index, (id, attrs) ->
                AppleItem(id = id, attributes = adapter.fromJsonValue(attrs) ?: error("Invalid album")).toAlbumDto().copy(additionOrder = index)
            }
        }
        currentCoroutineContext().ensureActive()
        ordered.getOrNull()?.let { return@runCatching LibraryAlbumsResponse(albums = it) }
        val all = mutableListOf<AppleItem<AppleAlbumAttrs>>()
        var offset = 0
        while (true) {
            val page = api.libraryAlbums(limit = 100, offset = offset)
            all += page.data
            if (page.next == null || page.data.isEmpty()) break
            offset += page.data.size
        }
        LibraryAlbumsResponse(albums = all.map { it.toAlbumDto() })
    }

    suspend fun libraryPlaylists(): Result<LibraryPlaylistsResponse> = runCatching {
        val ordered = runCatching {
            val adapter = com.squareup.moshi.Moshi.Builder().build().adapter(ApplePlaylistAttrs::class.java)
            webOrderedLibrary("playlists").mapIndexed { index, (id, attrs) ->
                AppleItem(id = id, attributes = adapter.fromJsonValue(attrs) ?: error("Invalid playlist")).toPlaylistDto().copy(additionOrder = index)
            }
        }
        currentCoroutineContext().ensureActive()
        ordered.getOrNull()?.let { return@runCatching LibraryPlaylistsResponse(playlists = it) }
        val all = mutableListOf<AppleItem<ApplePlaylistAttrs>>()
        var offset = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = api.libraryPlaylists(limit = 100, offset = offset)
            all += page.data
            if (page.next == null || page.data.isEmpty()) break
            offset += page.data.size
        }
        LibraryPlaylistsResponse(playlists = all.distinctBy { it.id }.map { it.toPlaylistDto() })
    }

    suspend fun recommendations(): Result<List<Pair<String, List<AlbumDto>>>> = runCatching {
        val recs = api.recommendations()
        val sections = mutableListOf<Pair<String, List<AlbumDto>>>()
        for (rec in recs.data) {
            val title = rec.attributes?.title?.stringForDisplay?.takeIf { it.isNotEmpty() } ?: "For You"
            val items = rec.relationships?.contents?.data
                ?.mapNotNull { item -> item.takeIf { it.attributes != null }?.toAlbumDto() }
                ?: emptyList()
            if (items.isNotEmpty()) sections.add(title to items)
        }
        // Recently added
        val recent = api.recentlyAdded(20).data
            .mapNotNull { it.takeIf { i -> i.attributes != null }?.toAlbumDto() }
        if (recent.isNotEmpty()) sections.add("Recently Added" to recent)
        sections
    }

    /**
     * Library ids (l./r./i.) have to go through the library endpoints; everything else
     * is a catalog id. Where a library row carries a catalog relationship we prefer the
     * catalog copy — it has the richer metadata the detail screens expect.
     */
    private fun isLibraryId(id: String) =
        id.startsWith("l.") || id.startsWith("r.") || id.startsWith("i.") || id.startsWith("p.")

    private suspend fun catalogIdForAlbum(id: String): String? = runCatching {
        api.libraryAlbum(id).data.firstOrNull()?.relationships?.catalog?.data?.firstOrNull()?.id
    }.getOrNull()

    private suspend fun catalogIdForArtist(id: String): String? = runCatching {
        api.libraryArtist(id).data.firstOrNull()?.relationships?.catalog?.data?.firstOrNull()?.id
    }.getOrNull()

    private suspend fun catalogIdForSong(id: String): String? = runCatching {
        api.librarySong(id).data.firstOrNull()?.relationships?.catalog?.data?.firstOrNull()?.id
    }.getOrNull()

    suspend fun album(id: String): Result<AlbumDto> = runCatching {
        if (isLibraryId(id)) {
            val catId = catalogIdForAlbum(id)
            if (catId != null) return@runCatching api.catalogAlbum(storefront, catId).data.first().toAlbumDto()
            api.libraryAlbum(id).data.first().toAlbumDto()
        } else {
            api.catalogAlbum(storefront, id).data.first().toAlbumDto()
        }
    }

    suspend fun albumTracks(id: String): Result<List<SongDto>> = runCatching {
        if (isLibraryId(id)) {
            val catId = catalogIdForAlbum(id)
            if (catId != null) return@runCatching api.catalogAlbumTracks(storefront, catId).data.map { it.toSongDto() }
            api.libraryAlbumTracks(id).data.map { it.toSongDto() }
        } else {
            api.catalogAlbumTracks(storefront, id).data.map { it.toSongDto() }
        }
    }

    suspend fun song(id: String): Result<SongDto> = runCatching {
        if (isLibraryId(id)) {
            val catId = catalogIdForSong(id)
            if (catId != null) return@runCatching api.catalogSong(storefront, catId).data.first().toSongDto()
            api.librarySong(id).data.first().toSongDto()
        } else {
            api.catalogSong(storefront, id).data.first().toSongDto()
        }
    }

    suspend fun artist(id: String): Result<ArtistDto> = runCatching {
        val catId = if (isLibraryId(id)) catalogIdForArtist(id) else id
        api.catalogArtist(storefront, catId ?: id).data.first().toArtistDto()
    }

    /** Related albums, standalone: Apple has no "related" endpoint, so use other albums by the same
     *  artist (derived from the album's first track), minus the album itself. */
    suspend fun relatedAlbums(id: String): Result<List<AlbumDto>> = runCatching {
        val artistId = albumTracks(id).getOrNull()?.firstOrNull()?.artistId ?: return@runCatching emptyList()
        artistAlbums(artistId).getOrNull().orEmpty().filter { it.id != id }
    }

    /** Apple system status (Music services), standalone: fetch Apple's public status feed directly. */
    suspend fun appleStatus(): Result<com.applemusicktv.data.network.AppleStatusResponse> = runCatching {
        val txt = plainHttpText("https://www.apple.com/support/systemstatus/data/system_status_en_US.js")
        val json = txt.trim().let { if (it.startsWith("{")) it else it.replace(Regex("^[^(]*\\("), "").replace(Regex("\\)\\s*;?\\s*$"), "") }
        val obj = org.json.JSONObject(json)
        val now = System.currentTimeMillis()
        val keywords = listOf("Apple Music", "iTunes Match")
        val out = ArrayList<com.applemusicktv.data.network.AppleServiceStatus>()
        val services = obj.optJSONArray("services")
        if (services != null) for (i in 0 until services.length()) {
            val svc = services.getJSONObject(i)
            val name = svc.optString("serviceName")
            if (keywords.none { name.contains(it) }) continue
            val events = svc.optJSONArray("events")
            var live = 0
            if (events != null) for (j in 0 until events.length()) {
                val end = events.getJSONObject(j).opt("epochEndDate")
                val ongoing = end == null || end == org.json.JSONObject.NULL ||
                    ((end as? Number)?.toLong() ?: 0L) > now
                if (ongoing) live++
            }
            out += com.applemusicktv.data.network.AppleServiceStatus(name, if (live > 0) "issue" else "operational")
        }
        com.applemusicktv.data.network.AppleStatusResponse(
            ok = out.all { it.status == "operational" }, services = out, checkedAt = "")
    }

    /** Standalone autoplay: Apple's song radio needs a bearer we don't hold on-disk, so
     *  derive a comparable queue from the seed's artist + similar artists' top songs. */
    suspend fun relatedSongs(seedId: String): Result<List<SongDto>> = runCatching {
        val artistId = song(seedId).getOrNull()?.artistId ?: return@runCatching emptyList()
        val seed = artistFull(artistId).getOrNull() ?: return@runCatching emptyList()
        val pool = seed.topSongs.toMutableList()
        seed.similarArtists.take(3).forEach { sim ->
            artistFull(sim.id).getOrNull()?.let { pool += it.topSongs }
        }
        pool.distinctBy { it.id }.filter { it.id != seedId }.shuffled()
    }

    /** Top songs for a genre (Apple charts) → a shuffled genre station queue. */
    @Suppress("UNCHECKED_CAST")
    /** Build a playable queue from a station's rolling next-tracks feed (standalone path). */
    suspend fun stationTracks(id: String): Result<List<SongDto>> = runCatching {
        val out = LinkedHashMap<String, SongDto>()
        for (batchIndex in 0 until 12) {
            currentCoroutineContext().ensureActive()
            if (out.size >= 20) break
            val batch = runCatching { api.stationNextTracks(id).data }.getOrDefault(emptyList())
            // Retrofit cancellation must stop this multi-request operation, not become an empty batch.
            currentCoroutineContext().ensureActive()
            for (item in batch) {
                val dto = item.toSongDto()
                if (dto.id.isNotEmpty()) out[dto.id] = dto
            }
        }
        out.values.toList()
    }

    /** Live-radio stream + Widevine key server, no proxy. Mirrors the server's /station/:id/stream:
     *  play/assets for the live HLS + key server, then the media playlist for the Widevine EXT-X-KEY
     *  URI (the license body's `uri`). The CDN rejects the auth headers our Retrofit client injects,
     *  so the m3u8 fetches use a bare OkHttp client. */
    suspend fun stationStream(id: String): Result<StationStreamResponse> = runCatching {
        val res = api.playAssets(
            "https://amp-api.music.apple.com/v1/play/assets",
            mapOf("format" to "stream", "hasDrm" to "true", "id" to id,
                  "kind" to "radioStation", "mediaType" to "0", "keyFormat" to "web"),
        )
        val asset = res.results?.assets?.firstOrNull() ?: error("no play asset")
        val url = asset.url ?: error("no stream url")
        val wvKeyUri = runCatching {
            val master = plainHttpText(url)
            val mediaUrl = master.lineSequence().firstOrNull { it.startsWith("http") }?.trim()
                ?: return@runCatching null
            val media = plainHttpText(mediaUrl)
            media.lineSequence().firstOrNull {
                it.startsWith("#EXT-X-KEY") && it.contains("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")
            }?.let { Regex("URI=\"([^\"]*)\"").find(it)?.groupValues?.getOrNull(1) }
        }.getOrNull()
        StationStreamResponse(
            liveStreamUrl = url,
            drmKeyUri     = asset.keyServerUrl,
            certUrl       = asset.widevineKeyCertificateUrl,
            wvKeyUri      = wvKeyUri,
            adamId        = id.removePrefix("ra."),
        )
    }

    private val plainHttp by lazy { okhttp3.OkHttpClient() }
    private suspend fun plainHttpText(url: String): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        plainHttp.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { r ->
            r.body?.string() ?: ""
        }
    }

    suspend fun genreStationSongs(genreId: String): Result<List<SongDto>> = runCatching {
        val raw = api.charts(storefront, types = "songs", limit = 50, genre = genreId)
        val results = raw["results"] as? Map<*, *> ?: return@runCatching emptyList()
        val songCharts = results["songs"] as? List<*> ?: return@runCatching emptyList()
        val out = mutableListOf<SongDto>()
        for (chart in songCharts) {
            val data = (chart as? Map<*, *>)?.get("data") as? List<*> ?: continue
            for (e in data) {
                val n = e as? Map<*, *> ?: continue
                val a = n["attributes"] as? Map<*, *> ?: continue
                val art = a["artwork"] as? Map<*, *>
                out += SongDto(
                    id = n["id"] as? String ?: continue,
                    title = a["name"] as? String ?: "",
                    artistName = a["artistName"] as? String ?: "",
                    albumName = a["albumName"] as? String ?: "",
                    durationMs = (a["durationInMillis"] as? Number)?.toLong() ?: 0L,
                    artworkUrl = art?.get("url") as? String,
                    artworkBgColor = art?.get("bgColor") as? String,
                    previewUrl = null,
                    previewHlsUrl = null,
                    hasLyrics = (a["hasLyrics"] as? Boolean) ?: false,
                )
            }
        }
        out.distinctBy { it.id }.shuffled()
    }

    /**
     * The artist page's payload, mapped from Apple's `views` into the same shape the
     * proxy returns so the screen doesn't care which path it came from.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun artistFull(id: String): Result<ArtistFullDto> = runCatching {
        val catId = (if (isLibraryId(id)) catalogIdForArtist(id) else id) ?: id
        val raw = api.catalogArtistFull(storefront, catId)
        val item = (raw["data"] as? List<*>)?.firstOrNull() as? Map<*, *>
            ?: error("artist $catId not found")
        val attrs = item["attributes"] as? Map<*, *> ?: emptyMap<String, Any>()
        val views = item["views"] as? Map<*, *> ?: emptyMap<String, Any>()

        fun artwork(m: Map<*, *>?): String? =
            (m?.get("artwork") as? Map<*, *>)?.get("url") as? String

        fun songs(key: String, forceType: String? = null): List<SongDto> =
            ((views[key] as? Map<*, *>)?.get("data") as? List<*>)?.mapNotNull { e ->
                val n = e as? Map<*, *> ?: return@mapNotNull null
                val a = n["attributes"] as? Map<*, *> ?: return@mapNotNull null
                SongDto(
                    id = n["id"] as? String ?: return@mapNotNull null,
                    type = forceType ?: (n["type"] as? String ?: "songs"),
                    title = a["name"] as? String ?: "",
                    artistName = a["artistName"] as? String ?: "",
                    albumName = a["albumName"] as? String ?: "",
                    durationMs = (a["durationInMillis"] as? Number)?.toLong() ?: 0L,
                    artworkUrl = artwork(a),
                    artworkBgColor = (a["artwork"] as? Map<*, *>)?.get("bgColor") as? String,
                    previewUrl = ((a["previews"] as? List<*>)?.firstOrNull() as? Map<*, *>)
                        ?.get("url") as? String,
                    previewHlsUrl = null,
                    trackNumber = (a["trackNumber"] as? Number)?.toInt(),
                )
            } ?: emptyList()

        fun parseAlbums(data: List<*>?): List<AlbumDto> =
            data?.mapNotNull { e ->
                val n = e as? Map<*, *> ?: return@mapNotNull null
                val a = n["attributes"] as? Map<*, *> ?: return@mapNotNull null
                AlbumDto(
                    id = n["id"] as? String ?: return@mapNotNull null,
                    title = a["name"] as? String ?: "",
                    artistName = a["artistName"] as? String ?: "",
                    artworkUrl = artwork(a),
                    artworkBgColor = (a["artwork"] as? Map<*, *>)?.get("bgColor") as? String,
                    releaseDate = a["releaseDate"] as? String,
                    trackCount = (a["trackCount"] as? Number)?.toInt() ?: 0,
                )
            } ?: emptyList()

        fun albums(key: String): List<AlbumDto> = parseAlbums((views[key] as? Map<*, *>)?.get("data") as? List<*>)
        val fullView = views["full-albums"] as? Map<*, *>
        val fullAlbums = albums("full-albums").toMutableList()
        val seenAlbums = fullAlbums.mapTo(HashSet()) { it.id }
        val visited = HashSet<String>()
        val base = "https://amp-api-edge.music.apple.com/v1/catalog/$storefront/artists/$catId/view/full-albums".toHttpUrl()
        var next = fullView?.get("next") as? String
        while (next != null) {
            currentCoroutineContext().ensureActive()
            val url = base.resolve(next) ?: error("Invalid artist album continuation")
            check(url.scheme == "https" && url.host == base.host && url.encodedPath == base.encodedPath && visited.add(url.toString())) { "Unexpected artist album continuation" }
            val page = api.artistViewPage(url.toString())
            val batch = parseAlbums(page["data"] as? List<*>)
            check(batch.isNotEmpty()) { "Empty artist album continuation" }
            fullAlbums += batch.filter { seenAlbums.add(it.id) }
            next = page["next"] as? String
        }

        val similar = ((views["similar-artists"] as? Map<*, *>)?.get("data") as? List<*>)
            ?.mapNotNull { e ->
                val n = e as? Map<*, *> ?: return@mapNotNull null
                val a = n["attributes"] as? Map<*, *> ?: return@mapNotNull null
                SimilarArtistDto(
                    id = n["id"] as? String ?: return@mapNotNull null,
                    name = a["name"] as? String ?: "",
                    artworkUrl = artwork(a),
                )
            } ?: emptyList()

        ArtistFullDto(
            id = item["id"] as? String ?: catId,
            name = attrs["name"] as? String ?: "",
            artworkUrl = artwork(attrs),
            genreNames = (attrs["genreNames"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
            editorialNotes = ((attrs["editorialNotes"] as? Map<*, *>)?.get("standard")
                ?: (attrs["editorialNotes"] as? Map<*, *>)?.get("short")) as? String,
            topSongs = songs("top-songs"),
            musicVideos = emptyList(),
            latestRelease = albums("latest-release").firstOrNull(),
            albums = fullAlbums,
            featuredAlbums = albums("featured-albums"),
            similarArtists = similar,
        )
    }

    suspend fun artistAlbums(id: String): Result<List<AlbumDto>> = runCatching {
        val catId = if (isLibraryId(id)) catalogIdForArtist(id) else id
        api.catalogArtistAlbums(storefront, catId ?: id).data.map { it.toAlbumDto() }
    }

    suspend fun libraryArtists(): Result<List<ArtistDto>> = runCatching {
        val libArtists = mutableListOf<AppleItem<AppleArtistAttrs>>()
        var offset = 0
        while (true) {
            val page = api.libraryArtists(limit = 100, offset = offset)
            libArtists += page.data
            if (page.next == null || page.data.isEmpty()) break
            offset += 100
            if (libArtists.size >= 1000) break
        }
        // Library artist objects carry no artwork — resolve it from the catalog artist
        // the library entry points at (batched, ~20 ids/request).
        val catIdByLib = libArtists.associate { it.id to it.relationships?.catalog?.data?.firstOrNull()?.id }
        val artworkByCatId = HashMap<String, String?>()
        catIdByLib.values.filterNotNull().distinct().chunked(20).forEach { chunk ->
            runCatching {
                api.catalogArtistsByIds(storefront, chunk.joinToString(",")).data.forEach { ca ->
                    artworkByCatId[ca.id] = ca.attributes?.artwork?.url
                }
            }
        }
        libArtists.map { item ->
            val dto = item.toArtistDto()
            if (dto.artworkUrl != null) return@map dto
            val art = catIdByLib[item.id]?.let { artworkByCatId[it] }
            if (art != null) dto.copy(artworkUrl = art) else dto
        }
    }

    /**
     * Charts, shaped like the proxy's Browse/genre payload. Apple nests these as
     * results.albums[] -> { name, data[] }, one entry per chart.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun charts(genre: String? = null): Result<List<Pair<String, List<AlbumDto>>>> = runCatching {
        val raw = api.charts(storefront, genre = genre)
        val results = raw["results"] as? Map<*, *> ?: return@runCatching emptyList()
        val out = mutableListOf<Pair<String, List<AlbumDto>>>()
        for (kind in listOf("albums", "playlists")) {
            val charts = results[kind] as? List<*> ?: continue
            for (chart in charts) {
                val c = chart as? Map<*, *> ?: continue
                val title = c["name"] as? String ?: continue
                val items = (c["data"] as? List<*>)?.mapNotNull { e ->
                    val n = e as? Map<*, *> ?: return@mapNotNull null
                    val a = n["attributes"] as? Map<*, *> ?: return@mapNotNull null
                    AlbumDto(
                        id = n["id"] as? String ?: return@mapNotNull null,
                        title = a["name"] as? String ?: "",
                        artistName = (a["artistName"] ?: a["curatorName"]) as? String ?: "",
                        artworkUrl = (a["artwork"] as? Map<*, *>)?.get("url") as? String,
                        type = if (kind == "playlists") "playlists" else "albums",
                        artworkBgColor = (a["artwork"] as? Map<*, *>)?.get("bgColor") as? String,
                        releaseDate = a["releaseDate"] as? String,
                        trackCount = (a["trackCount"] as? Number)?.toInt() ?: 0,
                    )
                } ?: emptyList()
                if (items.isNotEmpty()) out.add(title to items)
            }
        }
        out
    }

    suspend fun genres(): Result<List<GenreDto>> = runCatching {
        api.catalogGenres(storefront).data.map { GenreDto(id = it.id, name = it.attributes?.name ?: "") }
    }

    /**
     * Motion artwork hangs off the album, not the song, so resolve song -> album first.
     * Returns null rather than failing — a missing loop is not an error.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun motion(songId: String): Result<String?> = runCatching {
        val catSongId = if (isLibraryId(songId)) catalogIdForSong(songId) ?: return@runCatching null else songId
        val song = api.catalogSong(storefront, catSongId).data.firstOrNull() ?: return@runCatching null
        val albumId = song.relationships?.albums?.data?.firstOrNull()?.id
            ?: song.relationships?.catalog?.data?.firstOrNull()?.id
            ?: return@runCatching null
        val raw = api.catalogAlbumWithMotion(storefront, albumId)
        val data = (raw["data"] as? List<*>)?.firstOrNull() as? Map<*, *> ?: return@runCatching null
        val attrs = data["attributes"] as? Map<*, *> ?: return@runCatching null
        val video = attrs["editorialVideo"] as? Map<*, *> ?: return@runCatching null
        val square = (video["motionSquareVideo1x1"] ?: video["motionDetailSquare"]) as? Map<*, *>
            ?: return@runCatching null
        ((square["video"] as? String))
    }

    /** Motion artwork for an album card directly (l.* resolves to catalog first). Null if none. */
    @Suppress("UNCHECKED_CAST")
    suspend fun albumMotion(albumId: String): Result<String?> = runCatching {
        val catId = if (isLibraryId(albumId)) catalogIdForAlbum(albumId) ?: return@runCatching null else albumId
        val raw = api.catalogAlbumWithMotion(storefront, catId)
        val attrs = ((raw["data"] as? List<*>)?.firstOrNull() as? Map<*, *>)?.get("attributes") as? Map<*, *>
        val ev = attrs?.get("editorialVideo") as? Map<*, *> ?: return@runCatching null
        val square = (ev["motionSquareVideo1x1"] ?: ev["motionDetailSquare"]) as? Map<*, *>
            ?: return@runCatching null
        (square["video"] as? String)
    }

    /** Probe: log what a personalized station (ra.*) actually returns. */
    suspend fun probeStation(id: String): Result<String> = runCatching {
        val raw = api.catalogStation(storefront, id)
        val json = org.json.JSONObject(raw as Map<String, Any?>).toString()
        android.util.Log.i("StationProbe", "id=$id keys=${(raw["data"] as? List<*>)?.firstOrNull()?.let { (it as? Map<*,*>)?.keys }}")
        android.util.Log.i("StationProbe", "body=${json.take(1200)}")
        json
    }

    /** Motion artwork for an editorial playlist (pl.*). Null for user playlists. */
    @Suppress("UNCHECKED_CAST")
    suspend fun playlistMotion(playlistId: String): Result<String?> = runCatching {
        if (!playlistId.startsWith("pl.")) return@runCatching null
        val raw = api.catalogPlaylistWithMotion(storefront, playlistId)
        val data = (raw["data"] as? List<*>)?.firstOrNull() as? Map<*, *> ?: return@runCatching null
        val attrs = data["attributes"] as? Map<*, *> ?: return@runCatching null
        val video = attrs["editorialVideo"] as? Map<*, *> ?: return@runCatching null
        val square = (video["motionSquareVideo1x1"] ?: video["motionDetailSquare"]) as? Map<*, *>
            ?: return@runCatching null
        (square["video"] as? String)
    }

    suspend fun playlistTracks(id: String): Result<LibrarySongsResponse> = runCatching {
        // "pl.*" = catalog playlist; "p.*" (and everything else) = the user's library
        // playlist — routing library ids to the catalog endpoint 404'd them. Paginate
        // both so playlists longer than 100 tracks come back whole.
        val isCatalog = id.startsWith("pl.")
        val all = mutableListOf<AppleItem<AppleSongAttrs>>()
        var offset = 0
        while (true) {
            val page = if (isCatalog) api.catalogPlaylistTracks(storefront, id, limit = 100, offset = offset)
                       else api.playlistTracks(id, limit = 100, offset = offset)
            all += page.data
            if (page.next == null || page.data.isEmpty()) break
            offset += 100
            if (all.size >= 1000) break
        }
        LibrarySongsResponse(songs = all.map { it.toSongDto() })
    }
}

/** The web API advertises dateAdded as a field; its minus-prefixed form is descending.
 * Both forms and their reverse order were confirmed on the account's actual API response. */
internal fun newestAdditionSort(sorts: List<AppleLibrarySort>): String? {
    val dateField = sorts.firstOrNull { it.name == "dateAdded" || it.name == "-dateAdded" }
    if (dateField != null) return "-dateAdded"
    return sorts.firstOrNull { sort ->
        val key = sort.name.lowercase().filter { it.isLetterOrDigit() }
        (key == "dateadded" && sort.name.startsWith("-")) ||
            (key == "recentlyadded" && !sort.name.startsWith("-")) ||
            key == "dateaddeddesc" || key == "dateaddeddescending" ||
            key == "recentlyaddeddesc" || key == "recentlyaddeddescending"
    }?.name
}
