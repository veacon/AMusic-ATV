package com.applemusicktv.data.fonts

import android.app.Application
import android.content.ContextWrapper
import com.applemusicktv.data.cache.AppDiskCache
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class FontCacheIsolationTest {
    @get:Rule val temporary=TemporaryFolder()
    @Test fun completedAndPartialFontsAreOutsideBudgetAndSurviveCacheCleanup() {
        val root=temporary.newFolder()
        val context=object:ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getCacheDir()=File(root,"cache").apply { mkdirs() }
            override fun getFilesDir()=File(root,"files").apply { mkdirs() }
        }
        val fonts=File(context.filesDir,"fonts/version").apply { mkdirs() }
        val complete=File(fonts,"regular.otf").apply { writeText("persistent font") }
        val partial=File(fonts,"bold.otf.part").apply { writeText("download in progress") }
        val cache=AppDiskCache(context)
        assertEquals(0,cache.store.sizeBytes())
        cache.maintain()
        context.cacheDir.deleteRecursively()
        assertEquals("persistent font",complete.readText())
        assertEquals("download in progress",partial.readText())
    }
}
