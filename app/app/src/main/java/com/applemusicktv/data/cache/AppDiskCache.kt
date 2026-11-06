package com.applemusicktv.data.cache

import android.content.Context
import coil.imageLoader
import android.os.FileObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest

internal object CacheLimits {
    const val GIGABYTE = 1_000_000_000L
    const val TOTAL_BYTES = GIGABYTE
    const val CLEANUP_BYTES = 500_000_000L
    const val IMAGE_BYTES = 150_000_000L
    const val MEDIA_HEADROOM_BYTES = 8_000_000L
    // Up to four current/outgoing/prepared items can be protected without filling the total budget.
    const val MAX_MEDIA_ENTRY_BYTES = (TOTAL_BYTES - IMAGE_BYTES - MEDIA_HEADROOM_BYTES) / 4
}

internal class AppDiskCache(private val context: Context,
    private val imageCache: () -> coil.disk.DiskCache? = { context.imageLoader.diskCache },
) {
    private val settings = context.getSharedPreferences("cache_settings", Context.MODE_PRIVATE)
    val capacityGb: Int get() = settings.getInt("capacity_gb", 1).coerceIn(1, 10)
    private val root = context.cacheDir
    private val imageDirectory = File(root, "image_cache")
    private val definitions: Map<String, (String) -> Boolean> = mapOf(
        "home_cache" to { it == "sections" || it == "sections_audio_v75" },
        "browse_cache" to { it.startsWith("shelves_v1_") || it.startsWith("shelves_audio_v75_") },
        "library_cache" to { it in setOf("playlists", "albums", "artists", "songs") },
        "album_detail_cache" to { it.startsWith("album_") || it.startsWith("tracks_") || it.startsWith("related_") },
        "playlist_detail_cache" to { it.startsWith("tracks_") || it.startsWith("art_") || it.startsWith("curator_") },
    )
    private val preferences = definitions.keys.associateWith { context.getSharedPreferences(it, Context.MODE_PRIVATE) }
    val store = DiskBudgetStore(root, capacityGb * CacheLimits.GIGABYTE, CacheLimits.CLEANUP_BYTES,
        reservedBytes = { imageDirectory.walkTopDown().filter { it.isFile }.sumOf { it.length() } },
        externalEntries = { legacyEntries() })
    private val namespaces = definitions.keys.associateWith { JsonCache(it) }
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private var observer: FileObserver? = null
    private val maintenanceGate = CacheMaintenanceGate()
    private var started = false

    fun setCapacityGb(value: Int): DiskBudgetStore.TrimResult {
        val gb = value.coerceIn(1, 10)
        settings.edit().putInt("capacity_gb", gb).commit()
        return store.resize(gb * CacheLimits.GIGABYTE)
    }

    fun sizeBytes(): Long = store.sizeBytes()

    /** Coil owns its journal: use its API instead of deleting open image files ourselves. */
    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    fun clear(): DiskBudgetStore.TrimResult {
        val before = sizeBytes()
        imageCache()?.clear()
        val result = store.clear()
        val after = sizeBytes()
        return result.copy(beforeBytes = before, afterBytes = after, removedBytes = (before - after).coerceAtLeast(0))
    }

    fun content(namespace: String): JsonCache = namespaces.getValue(namespace)
    fun mediaFile(name: String): File = store.file(name)
    fun cachedMedia(name: String): File? = store.existing(name, CacheLimits.MAX_MEDIA_ENTRY_BYTES)
    fun putMedia(name: String, bytes: ByteArray): File? = if (bytes.size > CacheLimits.MAX_MEDIA_ENTRY_BYTES) null
        else store.put(name, bytes, headroomBytes = CacheLimits.MEDIA_HEADROOM_BYTES)

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun mediaDataSourceFactory(): androidx.media3.datasource.DataSource.Factory =
        CacheFileDataSource.factory(root, store, androidx.media3.datasource.DefaultDataSource.Factory(context))

    fun protect(owner: String, names: () -> Set<String>) = store.protect(owner, names)
    fun unprotect(owner: String) = store.unprotect(owner)

    /** Only these regenerable string values are legacy cache; sort, pins, account and player prefs are excluded. */
    private fun legacyEntries(): List<DiskBudgetStore.ExternalEntry> = definitions.mapNotNull { (name, accepts) ->
        val prefs = preferences.getValue(name)
        val cached = prefs.all.filter { (key, value) -> accepts(key) && value is String }
        if (cached.isEmpty()) null else {
            val xml = File(context.applicationInfo.dataDir, "shared_prefs/$name.xml")
            val bytes = if (xml.isFile) xml.length() + File(xml.path + ".bak").length()
                else cached.values.sumOf { (it as String).toByteArray().size.toLong() }
            DiskBudgetStore.ExternalEntry("legacy:$name", bytes, xml.lastModified()) {
                val edit = prefs.edit()
                cached.forEach { (key, value) -> if (prefs.getString(key, null) == value) edit.remove(key) }
                edit.commit()
            }
        }
    }

    inner class JsonCache internal constructor(private val namespace: String) {
        private val lock = Any()
        private val prefs get() = preferences.getValue(namespace)
        private fun path(key: String): String {
            require(definitions.getValue(namespace)(key))
            val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            return "content/$namespace/$hash.json"
        }
        fun getString(key: String): String? = synchronized(lock) {
            store.readText(path(key))?.let { return@synchronized it }
            val old = prefs.getString(key, null) ?: return@synchronized null
            if (store.put(path(key), old.toByteArray(Charsets.UTF_8)) != null) prefs.edit().remove(key).commit()
            old
        }
        fun putString(key: String, value: String?): Boolean = synchronized(lock) {
            val saved = if (value.isNullOrEmpty()) store.remove(path(key))
                else store.put(path(key), value.toByteArray(Charsets.UTF_8)) != null
            if (saved) prefs.edit().remove(key).commit()
            saved
        }
        fun migrate() = synchronized(lock) {
            val source = File(context.applicationInfo.dataDir, "shared_prefs/$namespace.xml").lastModified()
            // Snapshot first: quota eviction is allowed to remove legacy records during migration.
            val old = prefs.all.filter { (key, value) -> definitions.getValue(namespace)(key) && value is String }
            old.forEach { (key, value) ->
                if (store.existing(path(key)) != null || store.put(path(key), (value as String).toByteArray(Charsets.UTF_8), source) != null) {
                    prefs.edit().remove(key).commit()
                }
            }
        }
    }

    /** Off-main-thread startup migration and one global quota, including actual Coil directory bytes. */
    fun initialize() {
        namespaces.values.forEach { it.migrate() }
        root.listFiles()?.filter { it.isFile && it.name.endsWith(".tmp") }?.forEach { store.remove(it.name) }
        maintain()
    }
    fun maintain(): DiskBudgetStore.TrimResult = store.trim().also {
        if (it.removedEntries > 0) com.applemusicktv.data.NetworkLog.line("Cache", "removed=${it.removedBytes} remaining=${it.afterBytes} limit=${store.limitBytes}")
    }
    @Synchronized fun start(scope: CoroutineScope) {
        if (started) return
        started = true
        scope.launch { runCatching { initialize() } }
        imageDirectory.mkdirs()
        observer = object : FileObserver(imageDirectory.absolutePath, FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO) {
            override fun onEvent(event: Int, path: String?) {
                maintenanceGate.markDirty()
                requests.trySend(Unit)
            }
        }.also { it.startWatching() }
        scope.launch {
            for (request in requests) {
                delay(CacheMaintenanceGate.BATCH_DELAY_MS)
                if (maintenanceGate.claim()) {
                    runCatching { maintain() }.onFailure {
                        maintenanceGate.markDirty() // A later idle check retries a failed scan.
                    }
                }
            }
        }
        scope.launch {
            while (isActive) {
                delay(CacheMaintenanceGate.IDLE_CHECK_MS)
                requests.trySend(Unit) // Clean caches do not trigger any directory traversal.
            }
        }
    }

    companion object {
        @Volatile private var instance: AppDiskCache? = null
        fun get(context: Context): AppDiskCache = instance ?: synchronized(this) {
            instance ?: AppDiskCache(context.applicationContext).also { instance = it }
        }
        fun audioNames(ids: Collection<String>): Set<String> = ids.filter { it.isNotBlank() }.flatMap {
            val id = it.replace(Regex("[^A-Za-z0-9._-]"), "_")
            listOf("clear_$id.mp4", "standalone_$id.m3u8", "encrypted_$id.cache")
        }.toSet()
    }
}
