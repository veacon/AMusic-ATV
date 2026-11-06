package com.applemusicktv.media

import android.app.Application
import com.applemusicktv.data.*
import com.applemusicktv.data.repository.MusicRepository
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

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
        return InAppWebServer(context, MutPreferences(context), repo, LyricsOffsetPreferences(context),
            CrossfadePreferences(context), StandalonePreferences(context), ServerPreferences(context), BeatAnalyzer(), com.applemusicktv.data.auth.TokenVerifier { token ->
                when {
                    token.startsWith("unavailable-") -> com.applemusicktv.data.auth.TokenVerification.Unavailable
                    token.startsWith("replacement-") || token.startsWith("phone-") -> com.applemusicktv.data.auth.TokenVerification.Valid
                    else -> com.applemusicktv.data.auth.TokenVerification.Invalid
                }
            })
    }

    @Test fun tutorialPrecedesFormAndAllAdvancedControlsAreCollapsed() {
        val html = server().html()
        assertTrue(html.indexOf("如何获取令牌") < html.indexOf("<h2>配置令牌"))
        val advanced = html.substringAfter("<details class=more>")
        assertTrue(advanced.startsWith("\n<summary>更多功能设置</summary>"))
        listOf("歌词偏移", "节拍延迟", "电脑代理服务器", "本机模式", "音量均衡", "后台播放", "交叉渐变", "问题报告", "网络活动", "应用日志").forEach {
            assertTrue("$it should be inside the disclosure", advanced.contains(it))
        }
        assertFalse(html.contains("<details class=more open"))
        assertTrue(html.contains("for=mut"))
        assertTrue(html.contains("<details class=\"card tutorial\">"))
        assertFalse(html.contains("<details class=\"card tutorial\" open>"))
        assertTrue(html.contains(".tutorial a{color:#fa233b}"))
    }

    @Test fun successAndFailureAreDistinctAndEmitReviewableHtml() {
        val server = server()
        val initial = server.html()
        MutPreferences(RuntimeEnvironment.getApplication()).setMUT("preview-test-token-1234567890")
        val saved = server.html(tokenSaved = true)
        val error = server.html(tokenError = true)
        assertFalse(initial.contains("<div class=notice role=status>"))
        assertTrue(saved.contains("请回到电视端，点击“开始聆听”继续操作"))
        assertFalse(error.contains("<strong>令牌已保存</strong>"))
        assertTrue(error.contains("令牌未保存"))
        val root = com.applemusicktv.testing.TestArtifacts.root
        val out = File(root, "artifacts/v3.0-v16-release/preview").apply { mkdirs() }
        File(out, "phone.html").writeText(initial)
        File(out, "phone-saved.html").writeText(saved)
        File(out, "phone-invalid.html").writeText(error)
    }

    @Test fun invalidTokenCannotReplaceASavedToken() {
        val server = server()
        val mut = MutPreferences(RuntimeEnvironment.getApplication())
        mut.setMUT("existing-test-token-1234567890")
        val apply = InAppWebServer::class.java.getDeclaredMethod("applyToken", String::class.java).apply { isAccessible = true }
        assertEquals(com.applemusicktv.data.auth.TokenVerification.Invalid, apply.invoke(server, "short"))
        assertEquals("existing-test-token-1234567890", mut.getMUT())
        assertEquals(com.applemusicktv.data.auth.TokenVerification.Invalid, apply.invoke(server, "arbitrary-incorrect-token-1234567890"))
        assertEquals(com.applemusicktv.data.auth.TokenVerification.Unavailable, apply.invoke(server, "unavailable-test-token-1234567890"))
        assertEquals("existing-test-token-1234567890", mut.getMUT())
        assertEquals(com.applemusicktv.data.auth.TokenVerification.Valid, apply.invoke(server, "replacement-test-token-1234567890"))
        assertEquals("replacement-test-token-1234567890", mut.getMUT())
    }
    @Test fun phonePostRedirectsToHonestSuccessOrFailureMessage() {
        val server = server()
        val handle = InAppWebServer::class.java.getDeclaredMethod("handle", java.net.Socket::class.java).apply { isAccessible = true }
        fun request(method: String, path: String, body: String = ""): String {
            val input = "$method $path HTTP/1.1\r\nAccept-Language: zh-CN\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body".byteInputStream()
            val output = java.io.ByteArrayOutputStream()
            val socket = object : java.net.Socket() {
                override fun getInputStream() = input
                override fun getOutputStream() = output
                override fun close() {}
            }
            handle.invoke(server, socket)
            return output.toString("UTF-8")
        }
        val token = "phone-test-token-1234567890"
        val saved = request("POST", "/set-token", "mut=++$token++")
        assertTrue(saved.contains("303 See Other"))
        assertTrue(saved.contains("Location: /?token=saved"))
        assertEquals(token, MutPreferences(RuntimeEnvironment.getApplication()).getMUT())
        assertTrue(request("GET", "/?token=saved").contains("请回到电视端，点击“开始聆听”继续操作"))
        assertTrue(request("POST", "/set-token", "mut=short").contains("Location: /?token=invalid"))
        assertTrue(request("GET", "/?token=invalid").contains("令牌未保存"))
        assertEquals(token, MutPreferences(RuntimeEnvironment.getApplication()).getMUT())
    }

}
