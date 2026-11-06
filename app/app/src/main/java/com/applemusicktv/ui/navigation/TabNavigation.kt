package com.applemusicktv.ui.navigation

import androidx.navigation.NavGraph.Companion.findStartDestination

/** Settings/player are temporary visits with Back origins, never a browsing Tab's saved top. */
internal fun NavigationReturnState.selectTab(tab: TopNavTab) {
    // Passive focus on the already selected tab must not cancel a content handoff.
    if(tab==owner(nav.currentBackStackEntry)) return
    cancel()
    val route=when(tab) {
        TopNavTab.ListenNow -> Screen.Home.route
        TopNavTab.Browse -> Screen.Browse.route
        TopNavTab.Library -> Screen.Library.route
        TopNavTab.Search -> Screen.Search.route
        TopNavTab.NowPlaying -> Screen.NowPlaying.route
        TopNavTab.Dev -> Screen.DevMenu.route
    }
    if(tab in setOf(TopNavTab.NowPlaying,TopNavTab.Dev)) {
        nav.currentBackStackEntry?.savedStateHandle?.set("return.tabFocus",owner(nav.currentBackStackEntry).name)
        nav.navigate(route) {launchSingleTop=true}
        return
    }
    // Drop transient visits BEFORE saving a browsing stack, retaining the true source detail.
    discardTransientVisits()
    nav.navigate(route) {
        popUpTo(nav.graph.findStartDestination().id) {saveState=true}
        launchSingleTop=true
        restoreState=true
    }
    // Repair a stack saved by V6 as well, without deleting its ordinary detail-page history.
    discardTransientVisits()
}

private fun NavigationReturnState.discardTransientVisits() {
    while(true) {
        val route=listOf(Screen.DevMenu.route,Screen.NowPlaying.route).firstOrNull {
            runCatching {nav.getBackStackEntry(it)}.isSuccess
        } ?: return
        val before=nav.currentBackStackEntry?.id
        nav.popBackStack(route,inclusive=true)
        if(nav.currentBackStackEntry?.id==before) return
        nav.currentBackStackEntry?.savedStateHandle?.remove<String>("return.tabFocus")
    }
}
