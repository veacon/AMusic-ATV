package com.applemusicktv.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.applemusicktv.data.fonts.FontDownloadPhase
import com.applemusicktv.data.fonts.FontDownloadState
import com.applemusicktv.ui.theme.LocalAppFontState
import com.applemusicktv.ui.theme.ProvideAppFontState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], application=Application::class)
class FontGatedLyricsTest {
    private var previousLanguage = com.applemusicktv.i18n.LanguageChoice.System
    @org.junit.Before fun useChineseFixtureLanguage() {
        previousLanguage = com.applemusicktv.i18n.AppLanguage.choice
        com.applemusicktv.i18n.AppLanguage.select(com.applemusicktv.i18n.LanguageChoice.Chinese)
    }
    @org.junit.After fun restoreFixtureLanguage() {
        com.applemusicktv.i18n.AppLanguage.select(previousLanguage)
    }

    @get:Rule val compose = createComposeRule()

    @Test fun bothLayoutsHideLyricsUntilEveryFontIsReadyAndShowFailureVerbatim() {
        var state by mutableStateOf(FontDownloadState(25,100))
        compose.setContent {
            Column {
                FontGatedLyrics(state) { Text("普通布局歌词") }
                FontGatedLyrics(state) { Text("全屏布局歌词") }
            }
        }
        compose.onAllNodesWithText("歌词字体加载中，25%").assertCountEquals(2)
        compose.onNodeWithText("普通布局歌词").assertDoesNotExist()
        compose.onNodeWithText("全屏布局歌词").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(phase=FontDownloadPhase.Failed) }
        compose.onAllNodesWithText("字体下载失败，稍后将自动重试").assertCountEquals(2)
        compose.runOnIdle { state = FontDownloadState(100,100,FontDownloadPhase.Ready) }
        compose.onNodeWithText("普通布局歌词").assertExists()
        compose.onNodeWithText("全屏布局歌词").assertExists()
        compose.onAllNodesWithText("字体下载失败，稍后将自动重试").assertCountEquals(0)
    }

    @Test fun fontRefreshUpdatesBothTextSystemsWithoutRemountingAppState() {
        var state by mutableStateOf(FontDownloadState())
        var mounts = 0
        var disposals = 0
        var materialFamily: FontFamily? = null
        var tvFamily: FontFamily? = null
        var position = -1
        compose.setContent {
            ProvideAppFontState(state, if (state.ready) FontFamily.Serif else null) {
                var scroll by remember { mutableIntStateOf(37) }
                DisposableEffect(Unit) { mounts++; onDispose { disposals++ } }
                val normal = androidx.compose.material3.LocalTextStyle.current.fontFamily
                val television = androidx.tv.material3.LocalTextStyle.current.fontFamily
                val current = LocalAppFontState.current
                SideEffect { materialFamily=normal; tvFamily=television; position=scroll }
                Text("${current.percent}:$scroll")
            }
        }
        compose.runOnIdle { state=FontDownloadState(20,100) }
        compose.runOnIdle { state=FontDownloadState(100,100,FontDownloadPhase.Ready) }
        compose.runOnIdle {
            assertEquals(1,mounts); assertEquals(0,disposals); assertEquals(37,position)
            assertEquals(FontFamily.Serif,materialFamily); assertEquals(FontFamily.Serif,tvFamily)
        }
    }
}
