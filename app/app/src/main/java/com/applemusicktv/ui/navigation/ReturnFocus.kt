package com.applemusicktv.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.lifecycle.Lifecycle
import androidx.navigation.*
import androidx.navigation.compose.composable
import com.applemusicktv.ui.components.LocalMotionInteractive
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

internal data class ReturnStep(val list: Int, val key: String, val index: Int)
internal data class ReturnAddress(val target: Int, val path: List<ReturnStep>) {
    fun encode(): String = JSONObject().put("target", target).put("path", JSONArray().apply {
        path.forEach { put(JSONObject().put("list",it.list).put("key",it.key).put("index",it.index)) }
    }).toString()
    companion object {
        fun decode(value: String?): ReturnAddress? = runCatching {
            val o=JSONObject(value ?: return null); val a=o.getJSONArray("path")
            ReturnAddress(o.getInt("target"), (0 until a.length()).map { i -> a.getJSONObject(i).let {
                ReturnStep(it.getInt("list"),it.getString("key"),it.getInt("index"))
            } })
        }.getOrNull()
    }
}
internal data class ReturnTarget(val address: ReturnAddress, val requester: FocusRequester, val enabled: Boolean, val isFocused: () -> Boolean)
internal class ReturnList(val keys: List<String>, val visible: () -> Set<Int>, val scroll: suspend (Int) -> Unit)
internal class ReturnPageState(val entry: NavBackStackEntry) {
    // Capture while CREATED: the entry getter rejects access after a pop destroys its lifecycle.
    val handle = entry.savedStateHandle
    var last: ReturnAddress?
        get() = ReturnAddress.decode(handle.get<String>("return.address"))
        set(value) { handle["return.address"] = value?.encode() }
    val targets = linkedMapOf<Int,ReturnTarget>()
    val lists = mutableMapOf<Int,ReturnList>()
}
internal class NavigationReturnState(val nav: NavHostController) {
    val pages = mutableStateMapOf<String,ReturnPageState>()
    var pending by mutableStateOf<String?>(null)
    var pendingDetailFocus by mutableStateOf<String?>(null)
    val contentFocusHandoff get() = pending != null || pendingDetailFocus != null
    fun finishDetailFocus(id: String?) { if (pendingDetailFocus == id) pendingDetailFocus = null }
    var generation by mutableIntStateOf(0)
    var returnToTab by mutableStateOf<TopNavTab?>(null)
    var active: String? = null
    // Any explicit key/touch input cancels a delayed first-entry focus request.
    var inputSerial: Long = 0L
        private set
    val restoring get() = pending != null
    fun request(id: String) { pending=id; generation++ }
    fun cancel() { inputSerial++; pendingDetailFocus=null; if(pending != null || returnToTab != null) { pending=null; returnToTab=null; generation++ } }
    fun back() {
        pendingDetailFocus=null
        val previous=nav.previousBackStackEntry
        if (previous != null) {
            request(previous.id)
            if (!nav.popBackStack()) cancel()
        } else {
            nav.navigate(Screen.Home.route) { launchSingleTop=true }
            returnToTab=TopNavTab.ListenNow
        }
    }
    fun owner(entry: NavBackStackEntry?): TopNavTab {
        if (entry == null) return TopNavTab.ListenNow
        val own=when(entry.destination.route) {
            Screen.Home.route -> TopNavTab.ListenNow
            Screen.Browse.route -> TopNavTab.Browse
            Screen.Library.route -> TopNavTab.Library
            Screen.Search.route -> TopNavTab.Search
            Screen.NowPlaying.route -> TopNavTab.NowPlaying
            Screen.DevMenu.route -> TopNavTab.Dev
            else -> null
        }
        return own ?: entry.savedStateHandle.get<String>("return.owner")?.let {
            runCatching { TopNavTab.valueOf(it) }.getOrNull()
        } ?: nav.previousBackStackEntry?.takeIf { it.id != entry.id }?.let { owner(it) }
            ?: TopNavTab.ListenNow
    }
}
internal val LocalNavigationReturn = staticCompositionLocalOf<NavigationReturnState?> { null }
internal val LocalReturnPage = staticCompositionLocalOf<ReturnPageState?> { null }
internal val LocalReturnPath = compositionLocalOf { emptyList<ReturnStep>() }

/** Stable Compose item keys supply identity; the path also reveals items outside the viewport. */
@Composable
internal fun rememberReturnTarget(enabled: Boolean = true): Pair<Modifier, () -> Unit> {
    val page=LocalReturnPage.current
    val coordinator=LocalNavigationReturn.current
    val path=LocalReturnPath.current
    val id=currentCompositeKeyHash
    val requester=remember { FocusRequester() }
    val focus=remember { booleanArrayOf(false) }
    val interactive=enabled && LocalMotionInteractive.current
    val address=ReturnAddress(id,path)
    val mark: () -> Unit = { if (interactive && page != null && coordinator?.restoring != true) { page.last=address; page.handle.remove<String>("return.tabFocus") } }
    DisposableEffect(page,id) { onDispose { page?.targets?.remove(id) } }
    SideEffect { page?.targets?.set(id,ReturnTarget(address,requester,interactive) { focus[0] }) }
    if (page == null) return Modifier to {}
    return Modifier.focusRequester(requester).onFocusChanged { focus[0]=it.isFocused; if(it.isFocused) mark() } to mark
}

/** Dialog/Popup focus must never replace the control that opened it. */
@Composable
internal fun ReturnFocusLayer(content: @Composable () -> Unit) {
    val page=LocalReturnPage.current
    val coordinator=LocalNavigationReturn.current
    val origin=remember { page?.last }
    DisposableEffect(page) {
        onDispose {
            if (page != null && origin != null) {
                page.last=origin
                if (coordinator?.nav?.currentBackStackEntry?.id == page.entry.id) coordinator.request(page.entry.id)
            }
        }
    }
    CompositionLocalProvider(LocalReturnPage provides null, content=content)
}

internal fun NavGraphBuilder.returningComposable(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    content: @Composable (NavBackStackEntry) -> Unit,
) {
    composable(route,arguments=arguments) { entry ->
        val coordinator=LocalNavigationReturn.current
        if (coordinator == null) { content(entry); return@composable }
        val page=remember(entry) { ReturnPageState(entry) }
        val mode=LocalInputModeManager.current
        val lifecycle by entry.lifecycle.currentStateFlow.collectAsState()
        DisposableEffect(entry) {
            coordinator.pages[entry.id]=page
            if (entry.savedStateHandle.get<String>("return.owner") == null) {
                entry.savedStateHandle["return.owner"]=coordinator.owner(coordinator.nav.previousBackStackEntry).name
            }
            onDispose { if(coordinator.pages[entry.id] === page) coordinator.pages.remove(entry.id) }
        }
        LaunchedEffect(lifecycle) {
            if(lifecycle==Lifecycle.State.RESUMED) coordinator.active=entry.id
        }
        // Register restoration before disposing the outgoing focused control.
        BackHandler { coordinator.back() }
        LaunchedEffect(coordinator.pending,coordinator.generation,lifecycle) {
            if(coordinator.pending != entry.id || lifecycle != Lifecycle.State.RESUMED) return@LaunchedEffect
            val tabOrigin=entry.savedStateHandle.remove<String>("return.tabFocus")
            if(tabOrigin != null) {
                coordinator.pending=null
                if(mode.inputMode != InputMode.Touch) coordinator.returnToTab=runCatching { TopNavTab.valueOf(tabOrigin) }.getOrNull()
                return@LaunchedEffect
            }
            val address=page.last
            // Touch users restore the viewport/identity without forcing keyboard mode or remote focus.
            val touchReturn=mode.inputMode == InputMode.Touch
            if(touchReturn && address == null) { coordinator.pending=null; return@LaunchedEffect }
            val token=coordinator.generation
            var fallback: ReturnTarget?=null
            repeat(300) { frame ->
                withFrameNanos { }
                if(coordinator.generation != token || coordinator.pending != entry.id) return@LaunchedEffect
                val target=address?.let { page.targets[it.target] }?.takeIf { it.enabled }
                if(target != null && (touchReturn || runCatching { target.requester.requestFocus(); target.isFocused() }.getOrDefault(false))) {
                    if (!touchReturn) android.util.Log.i("AppleMusicFocus", "return content acquired focus")
                    page.last=target.address; coordinator.pending=null; return@LaunchedEffect
                }
                var pathReady=true
                address?.path?.forEach { step ->
                    if(pathReady) {
                        val list=page.lists[step.list]
                        if(list == null || list.keys.isEmpty()) pathReady=false
                        else {
                            val index=list.keys.indexOf(step.key).takeIf { it>=0 } ?: step.index.coerceIn(list.keys.indices)
                            if(index !in list.visible()) { list.scroll(index); pathReady=false }
                        }
                    }
                }
                fallback=page.targets.values.filter { it.enabled }.minByOrNull { candidate ->
                    val desired=address?.path.orEmpty(); val actual=candidate.address.path
                    desired.zip(actual).sumOf { (a,b) -> if(a.list != b.list) 100_000 else kotlin.math.abs(a.index-b.index) } +
                        kotlin.math.abs(desired.size-actual.size)*100_000
                }
                // Permit asynchronous data/transition mounting. Missing identities use a nearby item.
                if(frame>=60 && pathReady && fallback != null) {
                    if(touchReturn || runCatching { fallback!!.requester.requestFocus(); fallback!!.isFocused() }.getOrDefault(false)) {
                        page.last=fallback!!.address; coordinator.pending=null; return@LaunchedEffect
                    }
                }
            }
            if(coordinator.pending==entry.id) {
                // A whole shelf may have disappeared, including its nested list. Prefer
                // any nearby usable content over Tab even when the old path no longer mounts.
                val nearest=fallback
                val restored=nearest != null && (touchReturn || runCatching { nearest.requester.requestFocus(); nearest.isFocused() }.getOrDefault(false))
                if(restored) page.last=nearest!!.address
                coordinator.pending=null
                if(!restored && !touchReturn) coordinator.returnToTab=coordinator.owner(entry)
            }
        }
        CompositionLocalProvider(LocalReturnPage provides page,LocalReturnPath provides emptyList()) { content(entry) }
    }
}
