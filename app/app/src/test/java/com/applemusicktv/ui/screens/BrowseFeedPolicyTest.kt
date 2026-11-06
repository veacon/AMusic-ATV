package com.applemusicktv.ui.screens

import com.applemusicktv.data.model.Album
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseFeedPolicyTest {
    private val cached = listOf(
        BrowseShelf(
            title = "Recently Added",
            albums = listOf(Album(id = "album-1", title = "Album", artistName = "Artist", artworkUrl = null)),
        ),
    )

    @Test
    fun emptyRefreshKeepsPreviouslyCachedFeed() {
        assertEquals(cached, BrowseFeedPolicy.select(emptyList(), cached))
    }

    @Test
    fun skeletonWaitsUntilCacheRestoreConfirmsThereIsNoSavedFeed() {
        assertEquals(
            false,
            BrowseFeedPolicy.shouldShowSkeleton(BrowseUiState(isLoading = true, isRestoringCache = true)),
        )
        assertEquals(false, BrowseFeedPolicy.shouldShowSkeleton(BrowseUiState(isLoading = true, shelves = cached)))
        assertEquals(true, BrowseFeedPolicy.shouldShowSkeleton(BrowseUiState(isLoading = true)))
    }

    @Test
    fun usableRefreshReplacesCachedFeedAndDropsEmptyShelves() {
        val fresh = listOf(
            BrowseShelf(title = "Empty section"),
            BrowseShelf(
                title = "New",
                albums = listOf(Album(id = "album-2", title = "New album", artistName = "Artist", artworkUrl = null)),
            ),
        )

        assertEquals(listOf(fresh.last()), BrowseFeedPolicy.select(fresh, cached))
    }

    @Test
    fun browseShelvesCanBePersistedAndRestoredWithMoshi() {
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val type = Types.newParameterizedType(List::class.java, BrowseShelf::class.java)
        val adapter = moshi.adapter<List<BrowseShelf>>(type)

        val restored = adapter.fromJson(adapter.toJson(cached))

        assertEquals(cached, restored)
        assertTrue(restored.orEmpty().all(BrowseFeedPolicy::hasContent))
    }
}
