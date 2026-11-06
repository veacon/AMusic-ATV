package com.applemusicktv.data.cache

import android.app.Application
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class CacheFileDataSourceTest {
    @get:Rule val temporary = TemporaryFolder()
    private class Source(val failOpen: Boolean = false, val failClose: Boolean = false) : DataSource {
        override fun addTransferListener(transferListener: TransferListener) {}
        override fun open(dataSpec: DataSpec): Long { if (failOpen) throw IOException("open"); return 100 }
        override fun read(buffer: ByteArray, offset: Int, length: Int) = -1
        override fun getUri(): Uri? = null
        override fun close() { if (failClose) throw IOException("close") }
    }
    @Test fun openPlaybackFileSurvivesCleanupUntilDataSourceCloses() {
        val root = temporary.newFolder()
        val current = File(root, "current").apply { writeBytes(ByteArray(100)); setLastModified(1000) }
        (1..4).forEach { File(root, "old$it").apply { writeBytes(ByteArray(200)); setLastModified(it * 2000L) } }
        val store = DiskBudgetStore(root, 800, 400, now = { 1000 })
        val source = CacheFileDataSource(root, store, Source())
        source.open(DataSpec(Uri.fromFile(current))); store.trim()
        assertTrue(current.exists()); source.close()
        File(root, "overflow").apply { writeBytes(ByteArray(500)); setLastModified(9000) }
        store.trim(); assertFalse(current.exists())
    }
    @Test fun failedOpenAndFailedCloseBothReleaseReadProtection() {
        val root = temporary.newFolder(); val store = DiskBudgetStore(root, 800, 400)
        for (failsOpening in listOf(true, false)) {
            val file = File(root, "current").apply { writeBytes(ByteArray(100)) }
            val source = CacheFileDataSource(root, store, Source(failOpen = failsOpening, failClose = !failsOpening))
            try { source.open(DataSpec(Uri.fromFile(file))); source.close(); fail("Expected IOException") } catch (_: IOException) { }
            assertTrue(store.remove("current"))
        }
    }
}
