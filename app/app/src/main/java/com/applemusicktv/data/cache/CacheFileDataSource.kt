package com.applemusicktv.data.cache

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.File

/** Hold a read lease for the complete ExoPlayer file-read lifetime, including outgoing crossfades. */
@OptIn(UnstableApi::class)
internal class CacheFileDataSource(
    private val root: File,
    private val store: DiskBudgetStore,
    private val delegate: DataSource,
) : DataSource by delegate {
    private var lease: DiskBudgetStore.Lease? = null
    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        if (uri.scheme == "file") {
            val file = File(uri.path ?: "").canonicalFile
            if (file.path.startsWith(root.canonicalPath + File.separator)) {
                lease = store.acquire(file.relativeTo(root.canonicalFile).invariantSeparatorsPath, Long.MAX_VALUE)
            }
        }
        return try { delegate.open(dataSpec) } catch (e: Exception) {
            lease?.close(); lease = null
            throw e
        }
    }
    override fun close() {
        try { delegate.close() } finally { lease?.close(); lease = null }
    }
    companion object {
        fun factory(root: File, store: DiskBudgetStore, delegate: DataSource.Factory): DataSource.Factory =
            DataSource.Factory { CacheFileDataSource(root, store, delegate.createDataSource()) }
    }
}
