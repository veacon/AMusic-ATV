package com.applemusicktv.ui

import com.applemusicktv.i18n.tr
import com.applemusicktv.R

import com.applemusicktv.ui.navigation.contentFocusExitPolicy

import com.applemusicktv.ui.components.FadeThrough
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import com.applemusicktv.ui.components.UiMotion
import com.applemusicktv.ui.components.LocalUiMotion
import com.applemusicktv.ui.components.motionInput
import android.app.Activity
import android.view.WindowManager
import com.applemusicktv.ui.navigation.RootPageBackHandler
import com.applemusicktv.ui.navigation.NavigationReturnState
import com.applemusicktv.ui.navigation.LocalNavigationReturn
import com.applemusicktv.ui.navigation.returningComposable
import com.applemusicktv.ui.navigation.openDetail
import com.applemusicktv.ui.navigation.returnToPlayerAfterPlayback
import com.applemusicktv.ui.navigation.WALL_PLAYBACK_RETURN
import com.applemusicktv.ui.navigation.PLAYER_IS_WALL
import com.applemusicktv.ui.navigation.selectTab
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.applemusicktv.ui.viewmodel.shellUiState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.applemusicktv.ui.components.TopNavBar
import androidx.compose.ui.draw.clipToBounds
import com.applemusicktv.ui.navigation.BrowsingPageMotion
import com.applemusicktv.ui.navigation.Screen
import com.applemusicktv.ui.navigation.TopNavTab
import com.applemusicktv.ui.screens.*
import java.net.URLDecoder
import com.applemusicktv.ui.viewmodel.LibraryViewModel
import com.applemusicktv.ui.viewmodel.NavigationViewModel
import com.applemusicktv.ui.viewmodel.PlayerViewModel
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import coil.compose.AsyncImage
import com.applemusicktv.data.model.Song
import androidx.compose.material3.Text

@Composable
fun AppShell(modifier: Modifier = Modifier, onStartupReady: () -> Unit = {}) {
    val navController = rememberNavController()
    val returnState = remember(navController) { NavigationReturnState(navController) }
    var selectedTab   by remember { mutableStateOf(TopNavTab.ListenNow) }
    val playerVm: PlayerViewModel  = hiltViewModel()
    val navVm: NavigationViewModel = hiltViewModel()
    // Hoisted so the video survives navigation: fullscreen on Now Playing, in-app PiP elsewhere.
    val mvVm: com.applemusicktv.ui.viewmodel.MusicVideoViewModel = hiltViewModel()
    val tabFocus = remember { TopNavTab.entries.associateWith { FocusRequester() } }
    var pendingTabFocus by remember { mutableStateOf<TopNavTab?>(null) }
    var tabFocusRequestId by remember { mutableIntStateOf(0) }
    var focusedTopTab by remember { mutableStateOf<TopNavTab?>(null) }
    var navBarHasFocus by remember { mutableStateOf(false) }
    val libraryContentFocus = remember { FocusRequester() }
    fun requestTabFocus(tab: TopNavTab) {
        returnState.cancel()
        selectedTab = tab
        pendingTabFocus = tab
        tabFocusRequestId++
    }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val inputModeManager = androidx.compose.ui.platform.LocalInputModeManager.current
    val contentFocusHandoff = com.applemusicktv.ui.navigation.rememberNavigationFocusHandoff(navController, returnState)
    val profileVm: com.applemusicktv.ui.viewmodel.UserProfileViewModel = hiltViewModel()
    val avatarUrl by profileVm.avatar.collectAsState()
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, profileVm) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) profileVm.onResume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val contentFocus = remember { FocusRequester() }
    val videoFocus = remember { FocusRequester() }
    // Keep library identity across tabs; its cache/network work starts on Library entry only.
    val libraryVm: LibraryViewModel = hiltViewModel()
    // Hoisted so a server re-check from the Dev menu can refetch both screens —
    // reconnecting is useless if the stale "server down" content stays on screen.
    val homeVm: com.applemusicktv.ui.viewmodel.HomeViewModel = hiltViewModel()

    // First run: setup owns the whole screen. Nothing behind it is usable until the
    // server and token are configured, so there is no nav bar and nothing to browse.
    val onboardingVm: com.applemusicktv.ui.viewmodel.OnboardingViewModel = hiltViewModel()
    var showOnboarding by remember { mutableStateOf(!playerVm.onboardingCompleted()) }
    var postLoginHomeReturnPending by rememberSaveable { mutableStateOf(false) }
    var postLoginHomeScrollPending by rememberSaveable { mutableStateOf(false) }
    val authorizationRevision by playerVm.authorizationRevision.collectAsState()
    val warmPages = !showOnboarding || authorizationRevision > 0L
    val browseVm: BrowseViewModel? = if (warmPages) hiltViewModel<BrowseViewModel>() else null
    val searchVm: com.applemusicktv.ui.viewmodel.SearchViewModel? = if (warmPages) hiltViewModel<com.applemusicktv.ui.viewmodel.SearchViewModel>() else null
    // Authorization arrival, not leaving the login screen, starts each root page's first load.
    LaunchedEffect(authorizationRevision, browseVm, searchVm) {
        if (authorizationRevision > 0L) {
            homeVm.load(clearForLogin = true).invokeOnCompletion { cause -> if (cause == null) postLoginHomeReturnPending = true }
            browseVm?.load(force = true)
            libraryVm.preloadAfterLogin()
        }
    }
    // "重新设置" in Settings resets the pref and fires this — bring setup up right away
    // instead of only on the next launch.
    val replay by playerVm.replayOnboarding.collectAsState()
    LaunchedEffect(replay) {
        if (replay) { showOnboarding = true; playerVm.consumeReplayOnboarding() }
    }
    val initialHome by homeVm.state.collectAsState()
    val startupCanDismiss = showOnboarding || initialHome.sections.isNotEmpty() || !initialHome.isLoading
    LaunchedEffect(startupCanDismiss) {
        if (startupCanDismiss) { withFrameNanos { }; onStartupReady() }
    }
    val uiMotion by remember(playerVm) {
        playerVm.state.map { state ->
            UiMotion(enabled = !state.reduceMotion)
        }.distinctUntilChanged()
    }.collectAsState(UiMotion(enabled = !playerVm.state.value.reduceMotion))
    com.applemusicktv.ui.components.PageLayout {
    CompositionLocalProvider(LocalUiMotion provides uiMotion, LocalNavigationReturn provides returnState) {
    FadeThrough(showOnboarding, Modifier.fillMaxSize()) { displayedSetup ->
    if (displayedSetup) {
        OnboardingScreen(
            vm = onboardingVm,
            onDone = {
                showOnboarding = false
                playerVm.onOnboardingFinished()
                // Observe this login's actual load completion, not the pre-login cached feed.
                if (authorizationRevision == 0L) {
                    homeVm.load().invokeOnCompletion { cause ->
                        if (cause == null) postLoginHomeReturnPending = true
                    }
                    libraryVm.refresh()
                }
            },
            modifier = modifier,
        )
        return@FadeThrough
    }

    // Transient errors surface as a toast: a TV user is across the room and won't
    // find an inline banner, and silence reads as a crash.
    val appContext = LocalContext.current.applicationContext
    LaunchedEffect(Unit) {
        playerVm.toasts.collect { msg ->
            android.widget.Toast.makeText(appContext, com.applemusicktv.i18n.AppLanguage.message(msg), android.widget.Toast.LENGTH_LONG).show()
        }
    }

    val currentEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentEntry?.destination?.route
    LaunchedEffect(postLoginHomeReturnPending, initialHome.isLoading, initialHome.sections.isNotEmpty()) {
        if (postLoginHomeReturnPending && !initialHome.isLoading && initialHome.sections.isNotEmpty()) {
            navController.navigate(Screen.Home.route) {
                popUpTo(navController.graph.findStartDestination().id)
                launchSingleTop = true
            }
            postLoginHomeScrollPending = true
            requestTabFocus(TopNavTab.ListenNow)
            postLoginHomeReturnPending = false
        }
    }
    var tabFocusEntryId by remember { mutableIntStateOf(0) }
    val isOnNowPlaying = currentRoute == Screen.NowPlaying.route
    val destinationTab = returnState.owner(currentEntry)
    val idleLifecycle = remember { kotlinx.coroutines.flow.MutableStateFlow(androidx.lifecycle.Lifecycle.State.INITIALIZED) }
    val entryLifecycle by (currentEntry?.lifecycle?.currentStateFlow ?: idleLifecycle).collectAsState()
    // Route and actual focus share the same stable per-tab targets. Wait for a
    // remounted navigation bar after player transitions, then acknowledge real focus.
    LaunchedEffect(tabFocusRequestId, currentRoute, entryLifecycle) {
        val target = pendingTabFocus
        if (target != null && !contentFocusHandoff && destinationTab == target && !isOnNowPlaying &&
            entryLifecycle == androidx.lifecycle.Lifecycle.State.RESUMED) {
            // A touch entry leaves standard selectable Tabs in Touch input mode.
            // Explicit Back/Up means focus must be visible on a Tab immediately,
            // rather than waiting for a second D-pad event to enter keyboard mode.
            inputModeManager.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard)
            focusManager.clearFocus(force = true)
            repeat(12) {
                withFrameNanos { }
                if (focusedTopTab == target) { pendingTabFocus = null; return@LaunchedEffect }
                runCatching { tabFocus.getValue(target).requestFocus() }
            }
        }
    }
    LaunchedEffect(returnState.pending, returnState.pendingDetailFocus) { if (contentFocusHandoff) pendingTabFocus=null }
    LaunchedEffect(isOnNowPlaying) {
        if (isOnNowPlaying) { navBarHasFocus = false; focusedTopTab = null }
    }
    val browsingRoot = currentRoute in setOf(Screen.Home.route, Screen.Browse.route,
        Screen.Library.route, Screen.Search.route)
    LaunchedEffect(Unit) {
        playerVm.userPlaybackRequests.drop(1).collect {
            navVm.notePlayerInteraction()
            if (returnState.returnToPlayerAfterPlayback()) {
                selectedTab = TopNavTab.NowPlaying
                navVm.consumeNowPlayingNavigation()
                navVm.wakePlayerControls()
            } else navVm.navigateToNowPlaying()
        }
    }
    val videoActive by mvVm.active.collectAsState()
    val videoPlaying by remember(mvVm) { mvVm.state.map { it.playing }.distinctUntilChanged() }.collectAsState(false)
    // Integrated queue: PlayerViewModel owns one queue of songs AND videos. When the current
    // item is a video it emits it here; we hand it to the video player and jump to Now Playing.
    // The video's prev/next/auto-advance drive the same queue back through PlayerViewModel.
    val videoReq by playerVm.videoRequest.collectAsState()
    LaunchedEffect(Unit) {
        mvVm.onRequestNext = { playerVm.next() }
        mvVm.onRequestPrev = { playerVm.prev() }
    }
    LaunchedEffect(videoReq?.song?.id) {
        val v = videoReq
        if (v != null) {
            mvVm.show(v.song.id, v.song.title, v.song.artistName, startPaused = v.startPaused)
            // Only an explicit pick jumps to Now Playing. Auto-advance / skip keep the user
            // on whatever page they're on — the video keeps playing and shows when they visit.
            if (v.autoOpen) {
                selectedTab = TopNavTab.NowPlaying
                navController.navigate(Screen.NowPlaying.route) { launchSingleTop = true }
            }
        } else {
            mvVm.close()
        }
    }
    // Video plays fullscreen ON the Now Playing tab; anywhere else it's an in-app PiP window.
    val videoFullscreen = videoActive && isOnNowPlaying

    // Keep the nav-bar highlight in sync with the ACTUAL route — otherwise popping back from
    // a pushed screen (e.g. artist opened from the video player) left the white pill stuck on
    // Now Playing. Detail routes leave the pill on whatever top tab we came from.
    LaunchedEffect(currentRoute) {
        when (currentRoute) {
            Screen.Home.route      -> selectedTab = TopNavTab.ListenNow
            Screen.Browse.route    -> selectedTab = TopNavTab.Browse
            Screen.Library.route   -> selectedTab = TopNavTab.Library
            Screen.Search.route    -> selectedTab = TopNavTab.Search
            Screen.NowPlaying.route -> selectedTab = TopNavTab.NowPlaying
            Screen.DevMenu.route   -> selectedTab = TopNavTab.Dev
            else -> {}
        }
    }
    LaunchedEffect(isOnNowPlaying) { navVm.isOnNowPlaying = isOnNowPlaying }
    // Leaving Now Playing keeps the video's AUDIO playing (like a song) but disables the video
    // track — that frees the secure decoder and its SurfaceFlinger overlay so nothing bleeds onto
    // Library/Browse. Returning re-enables video and the recomposed PlayerView reattaches the
    // surface, so the picture comes back at position without ever stopping the audio.
    //
    // NOTE: attachVideo()/detachVideo() are driven ONLY from the video-surface AndroidView `update`
    // lambda below, where the codec-release and surface-teardown steps are ordered deterministically
    // against the visibility flip. Driving them from here as well raced that ordering (the bleed).
    // This effect is intentionally left as just a marker of the navigation transition.
    // Whenever a video is active, media keys must drive it (not the paused audio player) —
    // MainActivity reads this. Pause audio the moment a video starts.
    LaunchedEffect(videoActive) {
        navVm.isOnMusicVideo = videoActive
        if (videoActive) playerVm.pause()
    }

    // Keep the screen awake ONLY while music is actually playing. When paused, drop the
    // flag so Fire TV's own screensaver / sleep can take over (our ambient screensaver only
    // ever runs while playing).
    // Shell navigation does not render the playback clock. Ignore progress-only updates so
    // the entire navigation host is not invalidated five times a second during playback.
    val shellState = remember(playerVm) {
        playerVm.state.map { it.shellUiState() }.distinctUntilChanged()
    }
    val playerState by shellState.collectAsStateWithLifecycle(playerVm.state.value.shellUiState())
    // A regular song starting up takes over — the video player closes immediately so the
    // song's Now Playing (dynamic/projector) shows instead of a video stuck on top.
    LaunchedEffect(playerState.isPlaying) {
        if (playerState.isPlaying && videoActive) mvVm.close()
    }
    // Prefetch the next queue item's video metadata whenever it's a video — whether the current
    // track is a song or a video — so a song→video or video→video advance is fast. This runs in
    // parallel with PlayerViewModel's audio N+1 prefetch (which skips video items), so the two
    // prefetch paths cover the whole mixed queue together.
    LaunchedEffect(playerState.queueIndex, playerState.queue, videoActive) {
        val next = playerState.queue.getOrNull(playerState.queueIndex + 1) ?: return@LaunchedEffect
        if (next.isMusicVideo) mvVm.prefetch(next.id)
        else if (videoActive) playerVm.prefetchAudio(next)   // video playing → warm the next song
    }
    val keepScreenOn = playerState.isPlaying
    val activity = LocalContext.current as? Activity
    DisposableEffect(keepScreenOn) {
        if (keepScreenOn) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    val goToNowPlaying by navVm.goToNowPlaying.collectAsState()
    LaunchedEffect(goToNowPlaying) {
        if (goToNowPlaying) {
            selectedTab = TopNavTab.NowPlaying
            if (navController.currentDestination?.route != Screen.NowPlaying.route) {
                navController.navigate(Screen.NowPlaying.route) { launchSingleTop = true }
            }
            navVm.consumeNowPlayingNavigation()
        }
    }

    // Overlay layout: content fills the whole screen (so Now Playing's
    // background can run fullscreen to the very top), with the nav bar drawn
    // last → it sits on a higher layer. Non-fullscreen screens get top padding
    // equal to the bar so their content isn't hidden underneath it.

    var showExitDialog by remember { mutableStateOf(false) }
    BackHandler(enabled = showExitDialog) { showExitDialog = false }
    val returnToListenNow: () -> Unit = {
        requestTabFocus(TopNavTab.ListenNow)
        navController.navigate(Screen.Home.route) {
            popUpTo(navController.graph.findStartDestination().id)
            launchSingleTop = true
        }
    }
    val returnFromPlayer: () -> Unit = {
        focusManager.clearFocus(force = true)
        returnState.back()
    }
    LaunchedEffect(returnState.returnToTab) {
        returnState.returnToTab?.let { requestTabFocus(it); returnState.returnToTab=null }
    }
    // Register root-page handlers INSIDE each NavHost destination, before its
    // content. This beats NavHost's pop handler while preserving child dialogs/IME.
    BackHandler(enabled = !showExitDialog &&
        currentRoute == Screen.Radio.route) {
        returnToListenNow()
    }

    // Keep a stable light underlay. The player destination paints its own black
    // canvas, which now enters/exits as part of the same NavHost crossfade.
    val shellColor = com.applemusicktv.ui.theme.AppPalette.background
    androidx.compose.runtime.CompositionLocalProvider(
        com.applemusicktv.ui.components.LocalMotionArtworkEnabled provides
            (playerState.motionArtworkEnabled && !playerState.lowPowerMode),
    ) {
    Box(modifier = modifier.fillMaxSize().background(shellColor)
        .onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key !in setOf(Key.Back, Key.Escape)) returnState.cancel()
            false
        }
        .pointerInput(returnState) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed=false,pass=PointerEventPass.Initial)
                returnState.cancel()
            }
        }
        .pointerInput(isOnNowPlaying) {
            if (!isOnNowPlaying) return@pointerInput
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                navVm.notePlayerInteraction()
            }
        }) {
        NavHost(
            navController    = navController,
            startDestination = Screen.Home.route,
            enterTransition = { BrowsingPageMotion.enter(initialState.destination.route, targetState.destination.route, uiMotion) },
            exitTransition = { BrowsingPageMotion.exit(initialState.destination.route, targetState.destination.route, uiMotion) },
            popEnterTransition = { BrowsingPageMotion.enter(initialState.destination.route, targetState.destination.route, uiMotion) },
            popExitTransition = { BrowsingPageMotion.exit(initialState.destination.route, targetState.destination.route, uiMotion) },
            modifier         = Modifier
                .fillMaxSize()
                .clipToBounds()
                .focusRequester(contentFocus)
                .contentFocusExitPolicy(isOnNowPlaying, browsingRoot, tabFocus.getValue(destinationTab))
                .focusGroup(),
        ) {
            returningComposable(Screen.Home.route) {
                RootPageBackHandler(
                    enabled = !showExitDialog && currentRoute == Screen.Home.route,
                    isHome = true,
                    navBarHasFocus = navBarHasFocus,
                    onFocusTab = { requestTabFocus(destinationTab) },
                    onReturnHome = returnToListenNow,
                    onRequestExit = { showExitDialog = true },
                )
                HomeScreen(
                    restoreTopAfterLogin = postLoginHomeScrollPending,
                    onPostLoginTopRestored = { postLoginHomeScrollPending = false },
                    onReturnToTab = { requestTabFocus(destinationTab) },
                    tabBarFocused = navBarHasFocus,
                    playerVm = playerVm,
                    vm = homeVm,
                    onAlbumClick = { returnState.openDetail(Screen.AlbumDetail.route(it)) },
                    onPlaylistClick = { id, name, artworkUrl ->
                        returnState.openDetail(Screen.PlaylistDetail.route(id, name, artworkUrl))
                    },
                    // "Find Your Mood" cards are already prefixed (ac-/c-/mr-) for CategoryScreen.
                    onCategoryClick = { navController.navigate(Screen.Category.route(it)) },
                )
            }
            returningComposable(Screen.Browse.route) {
                RootPageBackHandler(
                    enabled = !showExitDialog && currentRoute == Screen.Browse.route,
                    isHome = false,
                    navBarHasFocus = navBarHasFocus,
                    onFocusTab = { requestTabFocus(destinationTab) },
                    onReturnHome = returnToListenNow,
                    onRequestExit = { showExitDialog = true },
                )
                BrowseScreen(
                    vm = requireNotNull(browseVm),
                    onReturnToTab = { requestTabFocus(destinationTab) },
                    tabBarFocused = navBarHasFocus,
                    playerVm       = playerVm,
                    onAlbumClick   = { returnState.openDetail(Screen.AlbumDetail.route(it)) },
                    onPlaylistClick = { id, name, art -> returnState.openDetail(Screen.PlaylistDetail.route(id, name, art)) },
                    onGenreClick   = { id, name -> navController.navigate(Screen.Genre.route(id, name)) },
                    onCuratorClick = { navController.navigate(Screen.Category.route(it)) },
                    // "更多" at the end of a shelf → that shelf's full editorial room page.
                    onSeeAll       = { navController.navigate(Screen.Category.route("room-$it")) },
                )
            }
            returningComposable(
                route     = Screen.Genre.route,
                arguments = listOf(
                    navArgument("genreId")   { type = NavType.StringType },
                    navArgument("genreName") { type = NavType.StringType },
                ),
            ) { back ->
                val gid  = URLDecoder.decode(back.arguments?.getString("genreId")   ?: "", "UTF-8")
                val gnm  = URLDecoder.decode(back.arguments?.getString("genreName") ?: "", "UTF-8")
                GenreScreen(
                    onReturnToTab = { requestTabFocus(destinationTab) },
                    tabBarFocused = navBarHasFocus,
                    genreId = gid, genreName = gnm, playerVm = playerVm,
                    onAlbumClick    = { returnState.openDetail(Screen.AlbumDetail.route(it)) },
                    onPlaylistClick = { id, name, art -> returnState.openDetail(Screen.PlaylistDetail.route(id, name, art)) },
                )
            }
            returningComposable(Screen.Category.route) {
                CategoryScreen(
                    onReturnToTab = { requestTabFocus(destinationTab) },
                    tabBarFocused = navBarHasFocus,
                    onAlbumClick    = { returnState.openDetail(Screen.AlbumDetail.route(it)) },
                    onPlaylistClick = { id, name, art -> returnState.openDetail(Screen.PlaylistDetail.route(id, name, art)) },
                    onCuratorClick  = { navController.navigate(Screen.Category.route(it)) },
                    onArtistClick   = { returnState.openDetail(Screen.ArtistDetail.route(it)) },
                    playerVm        = playerVm,
                )
            }
            returningComposable(Screen.Library.route) {
                RootPageBackHandler(
                    enabled = !showExitDialog && currentRoute == Screen.Library.route,
                    isHome = false,
                    navBarHasFocus = navBarHasFocus,
                    onFocusTab = { requestTabFocus(destinationTab) },
                    onReturnHome = returnToListenNow,
                    onRequestExit = { showExitDialog = true },
                )
                LibraryScreen(
                    onReturnToTab = { requestTabFocus(destinationTab) },
                    tabBarFocused = navBarHasFocus,
                    vm         = libraryVm,
                    playerVm   = playerVm,
                    contentFocusRequester = libraryContentFocus,
                    onAlbumClick    = { returnState.openDetail(Screen.AlbumDetail.route(it)) },
                    onPlaylistClick = { id, name, artworkUrl ->
                        returnState.openDetail(Screen.PlaylistDetail.route(id, name, artworkUrl ?: ""))
                    },
                    onArtistClick = { returnState.openDetail(Screen.ArtistDetail.route(it)) },
                )
            }
            returningComposable(Screen.Search.route) {
                RootPageBackHandler(
                    enabled = !showExitDialog && currentRoute == Screen.Search.route,
                    isHome = false,
                    navBarHasFocus = navBarHasFocus,
                    onFocusTab = { requestTabFocus(destinationTab) },
                    onReturnHome = returnToListenNow,
                    onRequestExit = { showExitDialog = true },
                )
                SearchScreen(
                    vm = requireNotNull(searchVm),
                    onReturnToTab = { requestTabFocus(destinationTab) },
                    tabBarFocused = navBarHasFocus,
                    playerVm = playerVm,
                    onAlbumClick  = { returnState.openDetail(Screen.AlbumDetail.route(it)) },
                    onArtistClick = { returnState.openDetail(Screen.ArtistDetail.route(it)) },
                    onPlaylistClick = { id, name, artworkUrl ->
                        returnState.openDetail(Screen.PlaylistDetail.route(id, name, artworkUrl))
                    },
                    onCuratorClick = { id, kind ->
                        val prefix = when (kind) {
                            "multiroom"     -> "mr-"
                            "apple-curator" -> "ac-"
                            "room"          -> "room-"
                            "grouping"      -> "grouping-"
                            else            -> "c-"
                        }
                        navController.navigate(Screen.Category.route(prefix + id))
                    },
                )
            }
            returningComposable(Screen.NowPlaying.route) { entry ->
                val playerHandle = remember(entry) { entry.savedStateHandle }
                val wallPlaybackReturn by playerHandle
                    .getStateFlow(WALL_PLAYBACK_RETURN, false).collectAsState()
                BackHandler(enabled = !showExitDialog && isOnNowPlaying && !videoActive,
                    onBack = returnFromPlayer)
                // When a video is active it fills this tab via the AppShell overlay. Don't
                // compose the audio screen behind it — its focusable controls would steal the
                // D-pad (moving the song UI instead of the video).
                if (!videoActive) {
                        NowPlayingScreen(
                        playerVm = playerVm,
                        navVm = navVm,
                        onArtistClick = { returnState.openDetail(Screen.ArtistDetail.route(it), fromPlayer = true) },
                        onAlbumClick  = { returnState.openDetail(Screen.AlbumDetail.route(it), fromPlayer = true) },
                        onPlaylistClick = { id, name, art -> returnState.openDetail(Screen.PlaylistDetail.route(id, name, art ?: ""), fromPlayer = true) },
                        onWallCardClick = { card ->
                            val route = when (card.kind) {
                                com.applemusicktv.ui.wall.WallKind.Album -> Screen.AlbumDetail.route(card.id)
                                com.applemusicktv.ui.wall.WallKind.Playlist -> Screen.PlaylistDetail.route(card.id, card.title, card.artworkUrl)
                            }
                            returnState.openDetail(route, fromWall = true)
                        },
                        wallPlaybackReturn = wallPlaybackReturn,
                        onConsumeWallPlaybackReturn = { playerHandle[WALL_PLAYBACK_RETURN] = false },
                        onWallStateChanged = { playerHandle[PLAYER_IS_WALL] = it },
                    )
                }
            }
            returningComposable(Screen.Radio.route) {
                RadioScreen(playerVm = playerVm, onReturnToTab = { requestTabFocus(destinationTab) })
            }
            returningComposable(Screen.DevMenu.route)    {
                DevMenuScreen(
                    playerVm = playerVm,
                    onDataRefresh = { homeVm.load(); libraryVm.refresh() },
                    onReturnToTab = { requestTabFocus(TopNavTab.Dev) },
                    tabBarFocused = navBarHasFocus,
                )
            }
            returningComposable(
                route     = Screen.AlbumDetail.route,
                arguments = listOf(navArgument("albumId") { type = NavType.StringType }),
            ) {
                AlbumDetailScreen(onReturnToTab = { requestTabFocus(destinationTab) }, tabBarFocused = navBarHasFocus, playerVm = playerVm, onBack = returnState::back,
                    onArtistClick = { returnState.openDetail(Screen.ArtistDetail.route(it)) },
                    onAlbumClick  = { returnState.openDetail(Screen.AlbumDetail.route(it)) })
            }
            returningComposable(
                route     = Screen.ArtistDetail.route,
                arguments = listOf(navArgument("artistId") { type = NavType.StringType }),
            ) {
                ArtistDetailScreen(
                    onReturnToTab = { requestTabFocus(destinationTab) },
                    tabBarFocused = navBarHasFocus,
                    playerVm = playerVm,
                    onAlbumClick  = { returnState.openDetail(Screen.AlbumDetail.route(it)) },
                    onArtistClick = { returnState.openDetail(Screen.ArtistDetail.route(it)) },
                )
            }
            returningComposable(
                route     = Screen.PlaylistDetail.route,
                arguments = listOf(
                    navArgument("playlistId")   { type = NavType.StringType },
                    navArgument("playlistName") { type = NavType.StringType },
                    navArgument("artworkUrl")   { type = NavType.StringType },
                ),
            ) { back ->
                val id         = URLDecoder.decode(back.arguments?.getString("playlistId")   ?: "", "UTF-8")
                val name       = URLDecoder.decode(back.arguments?.getString("playlistName") ?: "", "UTF-8")
                val artworkUrl = URLDecoder.decode(back.arguments?.getString("artworkUrl")   ?: "", "UTF-8")
                PlaylistDetailScreen(
                    onReturnToTab = { requestTabFocus(destinationTab) },
                    tabBarFocused = navBarHasFocus,
                    playlistId    = id,
                    playlistName  = name,
                    artworkUrl    = artworkUrl.ifEmpty { null },
                    playerVm      = playerVm,
                    onBack        = returnState::back,
                    onArtistClick = { returnState.openDetail(Screen.ArtistDetail.route(it)) },
                    onAlbumClick  = { returnState.openDetail(Screen.AlbumDetail.route(it)) },
                    onMusicVideoClick = { s ->
                        mvVm.show(s.id, s.title, s.artistName)
                        selectedTab = TopNavTab.NowPlaying
                        navController.navigate(Screen.NowPlaying.route) { launchSingleTop = true }
                    },
                )
            }
        }
        // MUT expired banner
        if (playerState.mutExpired) {
            Box(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 72.dp).fillMaxWidth(0.5f)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                    .background(androidx.compose.ui.graphics.Color(0xFFB22222))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    androidx.compose.material3.Text(tr(R.string.msg_002), color = androidx.compose.ui.graphics.Color.White, fontSize = 13.sp)
                    com.applemusicktv.ui.components.TouchSurface(
                        onClick = { playerVm.dismissMutExpired() },
                        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(50)),
                        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(containerColor = androidx.compose.ui.graphics.Color(0x33FFFFFF), focusedContainerColor = androidx.compose.ui.graphics.Color(0x55FFFFFF)),
                    ) { androidx.compose.foundation.layout.Box(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                        com.applemusicktv.ui.components.Icon(com.applemusicktv.ui.components.Glyph.CLOSE, size = 13.dp, color = androidx.compose.ui.graphics.Color.White)
                    } }
                }
            }
        }

        // Preview mode banner
        if (!playerState.isFullStream && playerState.currentSong != null) {
            Box(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .background(androidx.compose.ui.graphics.Color(0xFFB22222))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                androidx.compose.material3.Text(
                    tr(R.string.msg_003),
                    color = androidx.compose.ui.graphics.Color.White,
                    fontSize = 11.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                )
            }
        }

        // Exit dialog
        FadeThrough(showExitDialog, contentKey = { if (it) true else null }) { visible ->
            if (visible) {
                com.applemusicktv.ui.components.ExitAppDialog(onDismiss = { showExitDialog = false }) {
                    showExitDialog = false
                    playerVm.stopPlayback()
                    playerVm.clearVideoRequest(); mvVm.close()
                    if (activity?.moveTaskToBack(true) != true) activity?.finish()
                }
            }
        }

        // Nav bar on top layer (drawn after content). Hidden entirely on the
        // fullscreen music-video route so it can't steal D-pad focus.
        // Single persistent video surface. ONE PlayerView node that resizes between fullscreen
        // (Now Playing tab) and an in-app PiP corner — never torn down while a video is active,
        // so the codec never loses its surface (that was the "无法播放此视频" crash).
        // A secure (HDCP) SurfaceView can't be confined to a Compose corner — it draws across
        // whatever screen you're on. So the video surface renders ONLY fullscreen on Now Playing.
        // On other tabs the video KEEPS PLAYING (audio; the picture is simply not drawn) — it is
        // not closed — and reappears when you return to Now Playing. On-screen PiP over the app
        // isn't possible for protected video; the OS system PiP is the "picture while browsing".
        // Secure video surface — the "video in library" bleed, finally understood. A secure (HDCP)
        // SurfaceView is a whole-screen hardware overlay. The bleed was Compose DISPOSING that
        // SurfaceView when we left Now Playing (the old `&& isOnNowPlaying` gate): on Fire TV,
        // destroying a SurfaceView mid-secure-frame ORPHANS its SurfaceFlinger layer, which then
        // lingers as a frozen top overlay over Library/Browse (the black flash on return is a new
        // surface replacing it). The fix: keep the PlayerView COMPOSED the entire time a video is
        // active (gate on videoActive only) and merely toggle VISIBILITY. The view keeps managing
        // its layer, so GONE reaps it cleanly instead of orphaning it. detachVideo() (via the
        // LaunchedEffect above) disables the video track off-screen so the secure decoder stops while
        // audio keeps playing; attachVideo() re-enables it on return.
        if (videoActive) {
            val mvPlayer by mvVm.playerFlow.collectAsState()
            // The library "bleed" is a Fire TV compositor bug: a secure (HDCP) SurfaceView placed on
            // the media-overlay plane (setZOrderMediaOverlay) keeps its LAST protected frame latched in
            // that hardware plane. View flags (GONE) and track-disable don't reap it, so it lingers on
            // top of Library/Browse. The only reliable teardown is to DESTROY the SurfaceView — but that
            // orphans the plane too if a secure frame is still latched at destroy time. So the sequence
            // is: (1) detachVideo() rebuilds the player audio-only SYNCHRONOUSLY, releasing the secure
            // decoder and clearing the surface, THEN (2) unmount the PlayerView so its now content-free
            // SurfaceView is destroyed cleanly and the plane is freed. Audio never stops.
            val lowPower = playerState.lowPowerMode
            // Two strategies, chosen by the Low Power toggle:
            //  • Low Power ON  → FREE the secure decoder off Now Playing (detach video track + unmount
            //    the SurfaceView). Lightest on a starved Fire TV, but re-acquiring the codec blips ~0.5s
            //    on return.
            //  • Low Power OFF (default) → keep the decoder ALIVE: the PlayerView stays mounted the whole
            //    time a video is active, just shrunk to 1px BEHIND the window off Now Playing (default
            //    z-order = behind, so the opaque tab fully covers it — no bleed, no dispose-orphan). The
            //    surface keeps compositing (no latched stale frame), so returning is instant.
            var surfaceMounted by remember { mutableStateOf(false) }
            LaunchedEffect(isOnNowPlaying, lowPower) {
                if (lowPower) {
                    if (isOnNowPlaying) surfaceMounted = true
                    else { mvVm.detachVideo(); surfaceMounted = false }
                } else {
                    surfaceMounted = true                       // keep alive; never free the decoder
                    if (isOnNowPlaying) mvVm.attachVideo()       // re-enable if we came from low-power
                }
            }
            Box(Modifier.fillMaxSize().background(if (isOnNowPlaying) androidx.compose.ui.graphics.Color.Black else androidx.compose.ui.graphics.Color.Transparent)) {
                if (surfaceMounted) {
                    androidx.compose.ui.viewinterop.AndroidView(
                        factory = { ctx ->
                            androidx.media3.ui.PlayerView(ctx).apply {
                                useController = false
                                setBackgroundColor(android.graphics.Color.BLACK)
                                setShutterBackgroundColor(android.graphics.Color.BLACK)
                                setKeepScreenOn(true)
                                // Quality change rebuilds the player (single-variant master). Without this
                                // the view blanks to BLACK the instant the old player detaches and only
                                // repaints when the new decoder emits its first frame — that black flash is
                                // the "480p→1080p glitch". Keeping the last frame up bridges the reload.
                                setKeepContentOnPlayerReset(true)
                                // DO NOT setZOrderMediaOverlay/OnTop(true). That puts the secure SurfaceView
                                // on a hardware plane ABOVE the window, which bleeds over Library and can't be
                                // reaped by any View teardown. DEFAULT z-order sits it BEHIND the window,
                                // punching a hole only where mounted — off Now Playing we either unmount it
                                // (low power) or shrink it to 1px behind the opaque tab (seamless).
                                (videoSurfaceView as? android.view.SurfaceView)?.apply {
                                    setZOrderMediaOverlay(false)
                                    // Mark the surface SECURE so the compositor grants HDCP-protected HD
                                    // output. Without it the OS lets SD through but blocks HD with
                                    // "Required output protections are not active" (Netflix marks its
                                    // surface secure — that's why it does 1080p here and we were stuck at
                                    // 480p). A secure surface also reads back BLACK in screenshots.
                                    setSecure(true)
                                }
                                player = mvPlayer
                            }
                        },
                        update = { pv ->
                            pv.player = mvPlayer
                            pv.visibility = android.view.View.VISIBLE
                            if (lowPower && isOnNowPlaying) mvVm.attachVideo()
                        },
                        onRelease = { it.player = null },
                        // Seamless mode off Now Playing: 1px behind the window (decoder keeps running,
                        // invisible). Everywhere else: fullscreen.
                        modifier = if (!isOnNowPlaying && !lowPower) Modifier.size(1.dp)
                                   else Modifier.fillMaxSize(),
                    )
                }
                if (isOnNowPlaying) {
                    CompositionLocalProvider(com.applemusicktv.ui.navigation.LocalReturnPage provides returnState.pages[currentEntry?.id]) {
                    MusicVideoScreen(
                        vm = mvVm,
                        // Audio and video share the exact source entry on fullscreen exit.
                        onExit = returnFromPlayer,
                        onFocusUp = { /* The full-screen player has no navigation overlay. */ },
                        onArtistClick = { returnState.openDetail(Screen.ArtistDetail.route(it)) },
                        // Google TV remotes lack media keys → draw prev/play/next on screen.
                        showOnScreenControls = com.applemusicktv.util.TvDevice.needsOnScreenMenuToggle(appContext, playerVm.remoteOverride()),
                        queue = playerState.queue,
                        queueIndex = playerState.queueIndex,
                        onPickQueueItem = { playerVm.playFromQueue(it) },
                        focusRequester = videoFocus,
                        navVm = navVm,
                    )
                    }
                }
            }
        }

        // Keep pixels for the short exit, but disable focus/touch as soon as the player opens.
        AnimatedVisibility(visible = !isOnNowPlaying,
            modifier = Modifier.align(Alignment.TopCenter).motionInput(!isOnNowPlaying),
            enter = fadeIn(tween(
                com.applemusicktv.ui.navigation.PlayerEntryMotion.duration(uiMotion),
                easing = com.applemusicktv.ui.navigation.PlayerEntryMotion.easing,
            )),
            exit = fadeOut(tween(
                com.applemusicktv.ui.navigation.PlayerEntryMotion.duration(uiMotion),
                easing = com.applemusicktv.ui.navigation.PlayerEntryMotion.easing,
            ))) {
        Box(
            modifier = Modifier
                .onFocusChanged {
                    val hasFocus = it.hasFocus && !isOnNowPlaying && !contentFocusHandoff
                    if (hasFocus && !navBarHasFocus && inputModeManager.inputMode == androidx.compose.ui.input.InputMode.Keyboard)
                        tabFocusEntryId++
                    navBarHasFocus = hasFocus
                }
                .focusProperties {
                    onEnter = { if (!contentFocusHandoff && inputModeManager.inputMode == androidx.compose.ui.input.InputMode.Keyboard) tabFocus.getValue(destinationTab).requestFocus() }
                }.focusGroup(),
        ) {
            TopNavBar(
                selected = destinationTab,
                focusEntryId = tabFocusEntryId,
                navigationHasFocus = navBarHasFocus && !contentFocusHandoff,
                focusEnabled = !contentFocusHandoff,
                avatarUrl = avatarUrl,
                onAvatarLoadError = profileVm::onAvatarDisplayError,
                tabFocusRequesters = tabFocus,
                onTabFocusChanged = { tab, focused ->
                    if (focused) android.util.Log.i("AppleMusicFocus", "header focused ${tab.name}")
                    if (focused && !isOnNowPlaying) focusedTopTab = tab else if (focusedTopTab == tab) focusedTopTab = null
                },
                contentFocusRequester = if (currentRoute == Screen.Library.route) libraryContentFocus else contentFocus,
                // Restore the original V12 beat-reactive marker for music and video.
                isPlaying = playerState.isPlaying || (videoActive && videoPlaying),
                beatAnalyzer = playerVm.beatAnalyzer,
                onSelect = { tab ->
                    // Focus can move again before the next composition. Read the live
                    // destination so rapid arrows never select against a stale route.
                    val activeRoute = navController.currentDestination?.route
                    if (activeRoute == Screen.NowPlaying.route) return@TopNavBar
                    if (returnState.contentFocusHandoff) return@TopNavBar
                    pendingTabFocus = null
                    selectedTab = tab
                    returnState.selectTab(tab)
                },
            )
        }
        }

    }
    } // CompositionLocalProvider(LocalMotionArtworkEnabled)
    } // PageLayout
    }
    }
}
