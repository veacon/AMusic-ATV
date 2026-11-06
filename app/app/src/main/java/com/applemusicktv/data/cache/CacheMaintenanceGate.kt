package com.applemusicktv.data.cache

/** Image writes only dirty the budget. A single worker batches automatic full scans. */
internal class CacheMaintenanceGate {
    private var dirty = false
    @Synchronized fun markDirty() { dirty = true }
    @Synchronized fun claim(): Boolean {
        if (!dirty) return false
        dirty = false
        return true
    }
    companion object {
        const val BATCH_DELAY_MS = 30_000L
        const val IDLE_CHECK_MS = 300_000L
    }
}
