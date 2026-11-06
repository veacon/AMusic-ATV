package com.applemusicktv.ui.navigation

/** Lineage belongs to detail entries, never to the global playback navigation event. */
private const val PLAYER_ORIGIN = "player.detail.origin"
private const val PLAYER_ORIGIN_WALL = "player.detail.origin.wall"
internal const val PLAYER_IS_WALL = "player.presentation.wall"
internal const val WALL_PLAYBACK_RETURN = "wall.playback.return"
private val playerDetailRoutes = setOf(Screen.AlbumDetail.route, Screen.PlaylistDetail.route, Screen.ArtistDetail.route)

/** Audio-player menus and wall cards establish a return source; related details inherit it. */
internal fun NavigationReturnState.openDetail(route: String, fromWall: Boolean = false, fromPlayer: Boolean = false) {
    val source = nav.currentBackStackEntry
    val startsAtPlayer = (fromWall || fromPlayer) && source?.destination?.route == Screen.NowPlaying.route
    val origin = when {
        startsAtPlayer -> source?.id
        source?.destination?.route in playerDetailRoutes -> source?.savedStateHandle?.get<String>(PLAYER_ORIGIN)
        else -> null
    }
    val wall = if (startsAtPlayer) fromWall || source?.savedStateHandle?.get<Boolean>(PLAYER_IS_WALL) == true
        else source?.savedStateHandle?.get<Boolean>(PLAYER_ORIGIN_WALL) == true
    nav.navigate(route)
    val detail = nav.currentBackStackEntry
    if (origin != null && detail?.destination?.route in playerDetailRoutes) {
        detail?.savedStateHandle?.set(PLAYER_ORIGIN, origin)
        detail?.savedStateHandle?.set(PLAYER_ORIGIN_WALL, wall)
    }
}

/** Explicit playback ends this detail branch and reuses the exact player that opened it. */
internal fun NavigationReturnState.returnToPlayerAfterPlayback(): Boolean {
    val detail = nav.currentBackStackEntry ?: return false
    if (detail.destination.route !in playerDetailRoutes) return false
    val originId = detail.savedStateHandle.get<String>(PLAYER_ORIGIN) ?: return false
    val wall = detail.savedStateHandle.get<Boolean>(PLAYER_ORIGIN_WALL) == true
    val origin = runCatching { nav.getBackStackEntry(Screen.NowPlaying.route) }.getOrNull() ?: return false
    if (origin.id != originId) return false
    if (!nav.popBackStack(Screen.NowPlaying.route, inclusive = false)) return false
    cancel()
    origin.savedStateHandle[WALL_PLAYBACK_RETURN] = wall
    if (wall) {
        // A playback return shows the normal wall, so the old grid must not reacquire focus.
        origin.savedStateHandle.remove<String>("return.address")
        origin.savedStateHandle.remove<String>("return.tabFocus")
    } else request(origin.id)
    return true
}
