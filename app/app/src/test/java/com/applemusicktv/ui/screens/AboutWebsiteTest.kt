package com.applemusicktv.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28], manifest = Config.NONE)
class AboutWebsiteTest {
    @Test fun updateFailureGivesManualWebsiteGuidanceInBothLanguages() {
        assertTrue(updateFailureMessage(false).contains("请检查网络或稍后重试。部分电视品牌限制应用自升级，请前往官网手动下载安装包以更新。"))
        assertTrue(updateFailureMessage(true).contains("Some TV brands restrict app self-updates"))
    }
    @Test fun websiteLaunchUsesBrowsableHttpsAndHandlesMissingBrowser() {
        val intent = officialWebsiteIntent()
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://applemusictv.pages.dev", intent.data.toString())
        assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
        val base = ApplicationProvider.getApplicationContext<Context>()
        var called = false
        val context = object : ContextWrapper(base) {
            override fun startActivity(intent: Intent) { called = true; throw ActivityNotFoundException() }
        }
        openOfficialWebsite(context, false)
        assertTrue(called)
    }
}
