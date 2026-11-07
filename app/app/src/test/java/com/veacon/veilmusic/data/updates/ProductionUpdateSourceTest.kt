package com.veacon.veilmusic.data.updates

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.veacon.veilmusic.BuildConfig
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28], manifest = Config.NONE)
class ProductionUpdateSourceTest {
    @Test fun productionSourceAcceptsItsOwnDownloadAndRejectsForeignPaths() {
        assertEquals("https://github.com/veacon/AMusic-ATV", BuildConfig.UPDATE_BASE_URL)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val updater = AppUpdates(context)
        fun manifest(apkUrl: String) = JSONObject().put("channel", "user").put("packageName", context.packageName)
            .put("versionName", "3.0-V56").put("versionCode", 56).put("sizeBytes", 7000000)
            .put("minSdk", 23).put("sha256", "a".repeat(64)).put("notes", "")
            .put("apkUrl", apkUrl)

        // 同仓库的 release 资产：接受。
        assertEquals(56, updater.parse(manifest(
            "https://github.com/veacon/AMusic-ATV/releases/download/v3.0/Apple%20Music%20TV%20V56.apk").toString()).code)

        // 别的仓库 / 别的路径 / 非 https / 带 query：一律拒绝。
        val rejected = listOf(
            "https://github.com/someone/else/releases/download/v3.0/x.apk",
            "https://github.com/veacon/AMusic-ATV/releases/latest/update.apk",
            "https://github.com/veacon/AMusic-ATV/raw/main/x.apk",
            "https://update-test.example.com/releases/download/v3.0/x.apk",
            "http://github.com/veacon/AMusic-ATV/releases/download/v3.0/x.apk",
            "https://github.com/veacon/AMusic-ATV/releases/download/v3.0/x.apk?x=1",
        )
        for (url in rejected) {
            try {
                updater.parse(manifest(url).toString()); fail("must be rejected: $url")
            } catch (_: IllegalArgumentException) { }
        }
    }
}
