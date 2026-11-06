package com.applemusicktv.ui.navigation

import android.view.KeyEvent

/** One complete remote press wakes hidden controls, including repeats and key-up. */
internal class PlayerWakeKeyGate {
    private val wakingKeys = mutableSetOf<Int>()

    fun consume(event: KeyEvent, active: Boolean, hidden: Boolean, backReturnsToSplit: Boolean = false, wake: () -> Unit): Boolean {
        if (event.keyCode in wakingKeys) {
            if (event.action == KeyEvent.ACTION_UP) wakingKeys.remove(event.keyCode)
            return true
        }
        if (active && hidden && event.action == KeyEvent.ACTION_DOWN && event.keyCode in wakeKeys &&
            !(backReturnsToSplit && event.keyCode in setOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE))) {
            wakingKeys.add(event.keyCode)
            wake()
            return true
        }
        return false
    }

    companion object {
        private val wakeKeys = setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_MENU)
    }
}

/** Give an item's explicit context menu priority over the original global Menu action. */
internal fun dispatchRemoteMenu(event: KeyEvent, focusedItem: (KeyEvent) -> Boolean, fallback: () -> Unit): Boolean {
    if (event.keyCode != KeyEvent.KEYCODE_MENU) return false
    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && !focusedItem(event)) fallback()
    return true
}
