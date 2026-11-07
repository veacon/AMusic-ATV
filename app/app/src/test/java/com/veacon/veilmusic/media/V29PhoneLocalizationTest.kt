package com.veacon.veilmusic.media

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 手机配置页的语言协商。
 *
 * 2.0 起页面不再有令牌登录区块，原来那几条围绕「登录你的音乐 / Copy Token / bookmark.js」
 * 的用例已随功能一起下线；这里保留仍决定用户体验的部分：浏览器语言 + 手动切换的优先级。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class V29PhoneLocalizationTest {
    @Test fun browserLanguageNegotiationHonorsSavedChoiceAndQuality() {
        val page = com.veacon.veilmusic.i18n.PhonePage
        assertEquals("en", page.resolveLanguage(null, null, "en-US,en;q=0.9"))
        assertEquals("zh", page.resolveLanguage(null, null, "zh-TW,en;q=0.8"))
        assertEquals("en", page.resolveLanguage(null, null, "fr-FR,zh;q=0.8"))
        assertEquals("en", page.resolveLanguage(null, null, "zh;q=0,en;q=0.8"))
        assertEquals("zh", page.resolveLanguage(null, "amtv_lang=zh", "en-US"))
        assertEquals("en", page.resolveLanguage("en", "amtv_lang=zh", "zh-CN"))
    }
}
