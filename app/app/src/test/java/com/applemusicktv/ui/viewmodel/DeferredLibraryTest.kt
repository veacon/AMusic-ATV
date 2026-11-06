package com.applemusicktv.ui.viewmodel

import android.app.Application
import android.os.Looper
import com.applemusicktv.data.*
import com.applemusicktv.data.di.NetworkModule
import com.applemusicktv.data.network.*
import com.applemusicktv.data.repository.MusicRepository
import androidx.lifecycle.ViewModelStore
import java.lang.reflect.Proxy
import java.io.IOException
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class DeferredLibraryTest {
    private val calls=mutableListOf<String>()
    private var failSongs=false
    private var holdSongs=false
    private var pending:Continuation<LibrarySongsResponse>?=null
    private val song=SongDto("song","songs","Title","Artist",null,null,"Album",123000,null,null,null,null)
    private val context get()=RuntimeEnvironment.getApplication()
    private val account by lazy {MutPreferences(context).also {it.setMUT("test-account")}}
    private fun viewModel():LibraryViewModel {
        val api=Proxy.newProxyInstance(ProxyApi::class.java.classLoader,arrayOf(ProxyApi::class.java)) {_,method,args->
            calls+=method.name
            when(method.name) {
                "getLibrarySongs" -> {
                    if(failSongs) throw IOException("test offline")
                    if(holdSongs) {
                        @Suppress("UNCHECKED_CAST")
                        pending=args!!.last() as Continuation<LibrarySongsResponse>
                        COROUTINE_SUSPENDED
                    } else LibrarySongsResponse(listOf(song),true)
                }
                "getLibraryAlbums" -> LibraryAlbumsResponse()
                "getLibraryPlaylists" -> LibraryPlaylistsResponse()
                "getLibraryArtists" -> LibraryArtistsResponse()
                else -> throw AssertionError("Unexpected ${method.name}")
            }
        } as ProxyApi
        // This repository path uses only these collaborators. Match existing test-only Unsafe fixtures;
        // no test branch or injection hook is added to production networking.
        val type=Class.forName("sun.misc.Unsafe");val f=type.getDeclaredField("theUnsafe").apply {isAccessible=true}
        val repository=type.getMethod("allocateInstance",Class::class.java).invoke(f.get(null),MusicRepository::class.java) as MusicRepository
        val server=ServerPreferences(context).also {it.setPcServerIp("http://unused.test/");it.serverReachable=true}
        val standalone=StandalonePreferences(context).also {it.setEnabled(false)}
        for((name,value) in mapOf("api" to api,"mutPrefs" to account,"serverPrefs" to server,"standalonePrefs" to standalone)) {
            MusicRepository::class.java.getDeclaredField(name).apply {isAccessible=true}.set(repository,value)
        }
        return LibraryViewModel(context,repository,account,NetworkModule.provideMoshi())
    }
    private fun waitUntil(condition:()->Boolean) {
        val deadline=System.nanoTime()+5_000_000_000L
        while(!condition() && System.nanoTime()<deadline) {shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5)}
        assertTrue("Library task did not settle",condition())
    }
    private fun clear(vm:LibraryViewModel) {ViewModelStore().apply {put("test",vm);clear()}}

    @Test fun constructionAndHiddenRefreshDoNotFetchAndOnlySelectedCategoryLoads() {
        val vm=viewModel()
        try {
            shadowOf(Looper.getMainLooper()).idle();vm.refresh();assertTrue(calls.isEmpty())
            vm.selectSection(LibrarySection.Songs);waitUntil {!vm.state.value.isLoading && calls.isNotEmpty()}
            assertEquals(listOf("getLibrarySongs"),calls);assertTrue(vm.state.value.songsNewestFirst)
            assertEquals("song",vm.state.value.songs.single().id)
            vm.onHidden();vm.selectSection(LibrarySection.Songs);assertEquals(1,calls.size)
            vm.selectSection(LibrarySection.Albums);waitUntil {!vm.state.value.isLoading}
            assertEquals(listOf("getLibrarySongs","getLibraryAlbums"),calls)
            // Empty album success is cached too, rather than refetched on every entry.
            vm.selectSection(LibrarySection.Albums);assertEquals(2,calls.size)
        } finally {clear(vm)}
    }
    @Test fun hiddenRefreshWaitsForReentryAndActiveRetryRetainsPreviouslyLoadedSongs() {
        val vm=viewModel()
        try {
            vm.selectSection(LibrarySection.Songs);waitUntil {!vm.state.value.isLoading}
            vm.onHidden();vm.refresh();assertEquals(1,calls.size)
            failSongs=true;vm.selectSection(LibrarySection.Songs);waitUntil {!vm.state.value.isLoading}
            assertEquals(2,calls.size);assertEquals("song",vm.state.value.songs.single().id)
            assertNotNull(vm.state.value.songsError)
            failSongs=false;vm.refresh();waitUntil {!vm.state.value.isLoading}
            assertEquals(3,calls.size);assertNull(vm.state.value.songsError)
        } finally {clear(vm)}
    }
    @Test fun lateCancelledCategoryCannotReplaceCurrentCategoryLoadingOrListState() {
        val vm=viewModel()
        try {
            holdSongs=true;vm.selectSection(LibrarySection.Songs);waitUntil {pending!=null}
            val preservedSongs=vm.state.value.songs // Restored cache is allowed to remain visible.
            vm.selectSection(LibrarySection.Artists);waitUntil {!vm.state.value.isLoading}
            pending!!.resume(LibrarySongsResponse(listOf(song.copy(id="stale-response")),true));shadowOf(Looper.getMainLooper()).idle()
            assertEquals(preservedSongs,vm.state.value.songs);assertFalse(vm.state.value.isLoading)
            assertEquals(listOf("getLibrarySongs","getLibraryArtists"),calls)
        } finally {clear(vm)}
    }
    @Test fun signOutDuringRequestClearsVisibleLibraryAndIgnoresItsLateResponse() {
        val vm=viewModel()
        try {
            holdSongs=true;vm.selectSection(LibrarySection.Songs);waitUntil {pending!=null}
            account.setMUT("");waitUntil {!vm.state.value.hasMut}
            pending!!.resume(LibrarySongsResponse(listOf(song),true));shadowOf(Looper.getMainLooper()).idle()
            assertTrue(vm.state.value.songs.isEmpty());assertFalse(vm.state.value.isLoading)
        } finally {clear(vm)}
    }
}
