package com.applemusicktv.data.updates

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28], manifest = Config.NONE)
class AppUpdatesTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private fun updater() = AppUpdates(ctx, "https://update-test.applemusictv.pages.dev/update.json")
    private fun valid() = JSONObject().put("channel", "user").put("packageName", ctx.packageName)
        .put("versionName", "3.0-V51").put("versionCode", 51).put("sizeBytes", 7_000_000)
        .put("minSdk", 23).put("sha256", "a".repeat(64)).put("notes", "Test")
        .put("apkUrl", "https://update-test.applemusictv.pages.dev/downloads/Apple%20Music%20TV%20V51.apk")
    @Test fun validManifestParsesAcrossAndroidVersions() {
        val i = updater().parse(valid().toString())
        assertEquals(51, i.code); assertEquals("3.0-V51", i.version); assertEquals(7_000_000L, i.size)
    }
    @Test fun publicVersionAndUpgradeCodeAreIndependent() {
        val manifest = valid().put("versionName", "3.0-V72").put("versionCode", 89)
            .put("apkUrl", "https://update-test.applemusictv.pages.dev/downloads/Apple%20Music%20TV%20V72.apk")
        val info = updater().parse(manifest.toString())
        assertEquals("3.0-V72", info.version)
        assertEquals(89, info.code)
    }
    @Test fun rejectsDifferentChannelsPackagesAndInvalidChecksums() {
        for ((key, value) in listOf("channel" to "experimental", "packageName" to "other.app", "sha256" to "bad", "sizeBytes" to 0, "versionCode" to -1)) {
            try { updater().parse(valid().put(key, value).toString()); fail(key) } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun rejectsUntrustedUrlsAndUnexpectedQuery() {
        for (url in listOf("http://update-test.applemusictv.pages.dev/downloads/a.apk", "https://update-test.applemusictv.pages.dev.evil.test/downloads/a.apk", "https://update-test.applemusictv.pages.dev/other/a.apk", "https://update-test.applemusictv.pages.dev/downloads/a.apk?token=secret", "https://update-test.applemusictv.pages.dev:444/downloads/a.apk")) {
            try { updater().parse(valid().put("apkUrl", url).toString()); fail(url) } catch (_: IllegalArgumentException) { }
        }
    }
}
