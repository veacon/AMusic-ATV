package com.applemusicktv

import com.applemusicktv.data.cache.AppDiskCache
import com.applemusicktv.data.cache.CacheLimits
import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.applemusicktv.data.MutPreferences
import com.applemusicktv.data.repository.MusicRepository
import com.applemusicktv.media.InAppWebServer
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class AppleMusicApp : Application(), ImageLoaderFactory {

    /**
     * Coil's default disk cache grows to ~2% of the whole partition — unbounded on a big
     * Fire TV. Artwork uses a 150 MB native LRU within the shared configurable disk budget.
     * Memory remains capped at 64 MiB — 15% of RAM was
     * ~225 MB on this box, far too much to hold in a memory-starved foreground app.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .memoryCache { MemoryCache.Builder(this).maxSizeBytes(64 * 1024 * 1024).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(CacheLimits.IMAGE_BYTES)
                    .build()
            }
            // Fire TV menu smoothness: RGB_565 halves bitmap memory for opaque artwork (no alpha
            // needed) → far less GC churn while flinging shelves. respectCacheHeaders(false) keeps
            // decoded art in cache so re-focusing a shelf never re-fetches/re-decodes.
            .allowRgb565(true)
            .respectCacheHeaders(false)
            .crossfade(false)
            .eventListener(object : coil.EventListener {
                override fun onSuccess(request: coil.request.ImageRequest, result: coil.request.SuccessResult) {
                    com.applemusicktv.ui.background.IdleArtworkPool.record(request.data)
                }
            })
            .build()

    @Inject lateinit var webServer: InAppWebServer
    @Inject lateinit var repo: MusicRepository
    @Inject lateinit var mutPrefs: MutPreferences

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        com.applemusicktv.i18n.AppLanguage.initialize(this)
        com.applemusicktv.util.CrashReporter.install(this)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        com.applemusicktv.i18n.AppLanguage.configurationChanged(newConfig)
    }

    /** Start main-app work only after the current window passes the size gate. */
    private var servicesStarted = false
    @Synchronized fun startMainServices() {
        if (servicesStarted) { webServer.boot(appScope); return }
        servicesStarted = true
        // Starts before login and survives page changes. Persistent font data is not content cache.
        com.applemusicktv.data.fonts.AppFonts.get(this).start(appScope)
        AppDiskCache.get(this).start(appScope)
        webServer.boot(appScope)   // starts only if the Dev toggle is on
        // Route volume-leveling diagnostics into the APP log (so they show under App Log, not Network,
        // and stream live on the :8081 event port).
        com.applemusicktv.media.GainProcessor.logger = { tag, msg -> webServer.addLog(tag, msg) }
        // Sync locally-stored MUT to proxy server on startup so ExoPlayer stream requests work
        val mut = mutPrefs.getMUT()
        if (mut.isNotEmpty()) {
            appScope.launch {
                runCatching { repo.syncMUTToServer(mut) }
            }
        }
    }

}
