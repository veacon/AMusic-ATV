package com.applemusicktv.ui.navigation

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.*
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import com.applemusicktv.ui.components.PageLayout
import com.applemusicktv.ui.screens.SearchScreen
import com.applemusicktv.ui.viewmodel.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,qualifiers="w960dp-h540dp-land-mdpi")
class SearchEditorReturnTest {
    @org.junit.Before fun useChinese() { com.applemusicktv.i18n.AppLanguage.select(com.applemusicktv.i18n.LanguageChoice.Chinese) }
    @get:Rule val compose=createComposeRule()
    private fun <T> blank(c:Class<T>):T {
        val k=Class.forName("sun.misc.Unsafe");val u=k.getDeclaredField("theUnsafe").apply {isAccessible=true}.get(null)
        @Suppress("UNCHECKED_CAST") return k.getMethod("allocateInstance",Class::class.java).invoke(u,c) as T
    }
    @Test fun realSearchEditorSystemBackClosesEditorThenRestoresSearchButton() {
        val vm=blank(SearchViewModel::class.java)
        val flow=MutableStateFlow(SearchUiState())
        fun field(name:String,value:Any) {vm.javaClass.getDeclaredField(name).apply {isAccessible=true}.set(vm,value)}
        field("_state",flow);field("state",flow);field("recentSearches",MutableStateFlow(emptyList<String>()))
        val player=blank(PlayerViewModel::class.java)
        lateinit var nav:NavHostController;lateinit var dispatcher:OnBackPressedDispatcher
        val mode=object:InputModeManager {
            override var inputMode by mutableStateOf(InputMode.Keyboard)
            override fun requestInputMode(inputMode:InputMode):Boolean {this.inputMode=inputMode;return true}
        }
        compose.setContent {
            nav=rememberNavController();val returns=remember(nav) {NavigationReturnState(nav)}
            dispatcher=LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            CompositionLocalProvider(LocalNavigationReturn provides returns,LocalInputModeManager provides mode) {
                PageLayout {NavHost(nav,startDestination=Screen.Search.route) {
                    returningComposable(Screen.Search.route) {SearchScreen(playerVm=player,vm=vm)}
                }}
            }
        }
        compose.onNodeWithText("搜索 · 点击输入…").performSemanticsAction(SemanticsActions.RequestFocus) {it()}
        compose.onNodeWithText("搜索 · 点击输入…").performClick()
        compose.onNode(hasSetTextAction()).assertExists()
        compose.runOnIdle {dispatcher.onBackPressed()};compose.waitForIdle()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithText("搜索 · 点击输入…").assertIsFocused()
        compose.runOnIdle {assertEquals(Screen.Search.route,nav.currentDestination?.route)}
    }
}
