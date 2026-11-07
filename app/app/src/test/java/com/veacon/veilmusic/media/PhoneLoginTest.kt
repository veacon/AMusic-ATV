package com.veacon.veilmusic.media

import android.app.Application
import com.veacon.veilmusic.data.*
import com.veacon.veilmusic.data.repository.MusicRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 手机配置页：2.0 起不再有令牌登录表单（免登录），页面只保留
 * 「资料库 · 导入歌单」与折叠起来的「播放与音源设置」。
 * 令牌接口本身仍在（本地接口/历史调用），这里只守住它不会被无效令牌覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PhoneLoginTest {
    private fun server(): InAppWebServer {
        val context = RuntimeEnvironment.getApplication()
        // The HTML renderer doesn't call the repository. Avoid creating networking clients.
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val repo = unsafeClass.getMethod("allocateInstance", Class::class.java)
            .invoke(field.get(null), MusicRepository::class.java) as MusicRepository
        val lx = com.veacon.veilmusic.luoxue.LxPlayback(context, com.veacon.veilmusic.data.AudioQualityPreferences(context))
        val importer = com.veacon.veilmusic.data.local.PlaylistImporter(okhttp3.OkHttpClient(),
            com.veacon.veilmusic.data.local.LocalLibraryStore(context, com.veacon.veilmusic.data.di.NetworkModule.provideMoshi()))
        return InAppWebServer(context, MutPreferences(context), repo, LyricsOffsetPreferences(context),
            CrossfadePreferences(context), StandalonePreferences(context), ServerPreferences(context), BeatAnalyzer(), com.veacon.veilmusic.data.auth.TokenVerifier { token ->
                when {
                    token.startsWith("unavailable-") -> com.veacon.veilmusic.data.auth.TokenVerification.Unavailable
                    token.startsWith("replacement-") || token.startsWith("phone-") -> com.veacon.veilmusic.data.auth.TokenVerification.Valid
                    else -> com.veacon.veilmusic.data.auth.TokenVerification.Invalid
                }
            }, lx, importer)
    }

    @Test fun pageRendersPlaylistImportAndCollapsedSourceSettings() {
        val html = server().html()
        // 导入歌单是首屏主操作
        assertTrue(html.contains("action=/playlist-import"))
        assertTrue(html.contains("导入到资料库"))
        // 播放/音源设置收在折叠里，默认不展开
        assertTrue(html.contains("<details class=more>"))
        assertFalse(html.contains("<details class=more open"))
        val advanced = html.substringAfter("<details class=more>")
        listOf("歌词偏移", "节拍延迟", "本机模式", "音量均衡", "后台播放").forEach {
            assertTrue("$it should be inside the disclosure", advanced.contains(it))
        }
        // 免登录：页面上不再有令牌输入框
        assertFalse(html.contains("for=mut"))
    }

    @Test fun invalidTokenCannotReplaceASavedToken() {
        val server = server()
        val mut = MutPreferences(RuntimeEnvironment.getApplication())
        mut.setMUT("existing-test-token-1234567890")
        val apply = InAppWebServer::class.java.getDeclaredMethod("applyToken", String::class.java).apply { isAccessible = true }
        assertEquals(com.veacon.veilmusic.data.auth.TokenVerification.Invalid, apply.invoke(server, "short"))
        assertEquals("existing-test-token-1234567890", mut.getMUT())
        assertEquals(com.veacon.veilmusic.data.auth.TokenVerification.Invalid, apply.invoke(server, "arbitrary-incorrect-token-1234567890"))
        assertEquals(com.veacon.veilmusic.data.auth.TokenVerification.Unavailable, apply.invoke(server, "unavailable-test-token-1234567890"))
        assertEquals("existing-test-token-1234567890", mut.getMUT())
        assertEquals(com.veacon.veilmusic.data.auth.TokenVerification.Valid, apply.invoke(server, "replacement-test-token-1234567890"))
        assertEquals("replacement-test-token-1234567890", mut.getMUT())
    }
}
