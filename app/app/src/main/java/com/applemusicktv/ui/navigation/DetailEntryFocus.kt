package com.applemusicktv.ui.navigation

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.applemusicktv.ui.components.LocalMotionInteractive

/** First remote entry only. Back/overlay restoration continues to own subsequent focus. */
@Composable
internal fun rememberDetailPlayEntryFocus(ready: Boolean, loading: Boolean = true, beforeFocus: suspend () -> Unit = {}): Modifier {
    val page = LocalReturnPage.current
    val coordinator = LocalNavigationReturn.current
    val mode = LocalInputModeManager.current
    val owner = LocalLifecycleOwner.current
    val lifecycle by owner.lifecycle.currentStateFlow.collectAsState()
    val interactive = LocalMotionInteractive.current
    val requester = remember { FocusRequester() }
    val focus = remember { booleanArrayOf(false) }
    val inputAtEntry = remember { coordinator?.inputSerial }
    val remoteEntry = remember { mode.inputMode != InputMode.Touch }
    val prepare by rememberUpdatedState(beforeFocus)
    var handledLocally by rememberSaveable { mutableStateOf(false) }
    val alreadyHandled = handledLocally || page?.handle?.get<Boolean>("detail.playEntryHandled") == true
    LaunchedEffect(page, lifecycle, interactive, ready, loading) {
        if (alreadyHandled) { coordinator?.finishDetailFocus(page?.entry?.id); return@LaunchedEffect }
        if (!ready && !loading) { coordinator?.finishDetailFocus(page?.entry?.id); return@LaunchedEffect }
        if (!ready || !interactive || lifecycle != Lifecycle.State.RESUMED) return@LaunchedEffect
        fun handled() {
            coordinator?.finishDetailFocus(page?.entry?.id)
            handledLocally = true
            page?.handle?.set("detail.playEntryHandled", true)
        }
        if (!remoteEntry || mode.inputMode == InputMode.Touch || coordinator?.restoring == true || coordinator?.inputSerial != inputAtEntry) {
            handled()
            return@LaunchedEffect
        }
        prepare()
        // The play action can mount after an asynchronous load or a region transition.
        repeat(60) {
            withFrameNanos { }
            if (coordinator?.restoring == true || mode.inputMode == InputMode.Touch || coordinator?.inputSerial != inputAtEntry) {
                handled()
                return@LaunchedEffect
            }
            if (runCatching { requester.requestFocus(); focus[0] }.getOrDefault(false)) {
                android.util.Log.i("AppleMusicFocus", "detail Play acquired focus")
                handled()
                return@LaunchedEffect
            }
        }
        handled()
    }
    return Modifier.focusRequester(requester).onFocusChanged { focus[0] = it.isFocused }
}
