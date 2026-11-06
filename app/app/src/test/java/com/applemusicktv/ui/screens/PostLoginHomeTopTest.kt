package com.applemusicktv.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercise the production effect only after the real LazyColumn mounts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w960dp-h540dp-land-mdpi")
class PostLoginHomeTopTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var list: LazyListState
    private lateinit var scope: CoroutineScope
    private var contentReady by mutableStateOf(false)
    private var pending by mutableStateOf(true)
    private var consumed = 0
    private var itemCount by mutableIntStateOf(30)

    private fun mount() {
        compose.setContent {
            list = rememberLazyListState(12, 18)
            scope = rememberCoroutineScope()
            if (contentReady) {
                RestorePostLoginHomeTop(list, pending) { consumed++; pending = false }
                LazyColumn(state = list, modifier = Modifier.height(240.dp), contentPadding = PaddingValues(top = 100.dp)) {
                    items(itemCount) { Text("Shelf $it", Modifier.height(80.dp)) }
                }
            } else Text("Loading after login")
        }
        compose.waitForIdle()
    }

    @Test fun waitsForContentThenRestoresFirstItemAndItsFullTopPadding() {
        mount()
        compose.runOnIdle { assertEquals(0, consumed); assertTrue(pending) }
        compose.runOnIdle { contentReady = true }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(0, list.firstVisibleItemIndex)
            assertEquals(0, list.firstVisibleItemScrollOffset)
            assertEquals(100, list.layoutInfo.beforeContentPadding)
            assertEquals(1, consumed)
            assertFalse(pending)
        }
    }

    @Test fun laterRefreshAndContentRemountDoNotResetTheUsersBrowsingPosition() {
        contentReady = true; mount()
        compose.runOnIdle { scope.launch { list.scrollToItem(7, 8) } }
        compose.waitForIdle()
        compose.runOnIdle { itemCount = 40 }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(7, list.firstVisibleItemIndex); assertEquals(8, list.firstVisibleItemScrollOffset) }
        compose.runOnIdle { contentReady = false }
        compose.runOnIdle { contentReady = true }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(7, list.firstVisibleItemIndex); assertEquals(1, consumed) }
    }

    @Test fun ordinaryLaunchWithoutLoginKeepsItsSavedPosition() {
        pending = false; contentReady = true; mount()
        compose.runOnIdle {
            assertEquals(12, list.firstVisibleItemIndex)
            assertEquals(18, list.firstVisibleItemScrollOffset)
            assertEquals(0, consumed)
        }
    }
}
