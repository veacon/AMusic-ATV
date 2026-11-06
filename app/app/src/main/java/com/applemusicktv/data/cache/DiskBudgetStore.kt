package com.applemusicktv.data.cache

import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Coordinates all non-Coil files. Coil retains ownership of its journal and its own LRU. */
internal class DiskBudgetStore(
    private val root: File,
    limitBytes: Long,
    private val batchBytes: Long,
    private val reservedBytes: () -> Long = { 0L },
    private val externalEntries: () -> List<ExternalEntry> = { emptyList() },
    private val now: () -> Long = System::currentTimeMillis,
) {
    data class ExternalEntry(val id: String, val bytes: Long, val usedAt: Long, val evict: () -> Boolean)
    data class TrimResult(val beforeBytes: Long, val afterBytes: Long, val removedBytes: Long, val removedEntries: Int)
    class Lease internal constructor(val file: File, private val release: () -> Unit) : Closeable {
        private val closed = AtomicBoolean()
        override fun close() { if (closed.compareAndSet(false, true)) release() }
    }
    @Volatile var limitBytes: Long = limitBytes
        private set
    private val lock = Any()
    private val owners = mutableMapOf<String, () -> Set<String>>()
    private val readers = mutableMapOf<String, Int>()
    private val rootPath = root.canonicalPath

    init { require(limitBytes > 0 && batchBytes > 0); root.mkdirs() }

    fun protect(owner: String, files: () -> Set<String>) = synchronized(lock) { owners[owner] = files }
    fun unprotect(owner: String) = synchronized(lock) { owners.remove(owner); Unit }

    fun file(relative: String): File {
        require(relative.isNotBlank() && !File(relative).isAbsolute && relative.split('/').none { it == ".." })
        val file = File(root, relative)
        require(file.canonicalPath.startsWith(rootPath + File.separator))
        require(relative.substringBefore('/') != "image_cache")
        return file
    }

    private fun files(): List<File> = root.walkTopDown().onEnter {
        val path = it.canonicalPath
        (path == rootPath || path.startsWith(rootPath + File.separator)) &&
            (it == root || it.relativeTo(root).invariantSeparatorsPath.substringBefore('/') != "image_cache")
    }.filter { it.isFile && it.canonicalPath.startsWith(rootPath + File.separator) }.toList()

    private fun pinned(): Set<String> = owners.values.flatMap {
        runCatching { it().toList() }.getOrDefault(emptyList())
    }.toSet() + readers.filterValues { it > 0 }.keys

    fun acquire(relative: String, maxEntryBytes: Long = limitBytes): Lease? = synchronized(lock) {
        val file = file(relative)
        if (!file.isFile || file.length() <= 0 || file.length() > maxEntryBytes) return@synchronized null
        readers[relative] = (readers[relative] ?: 0) + 1
        file.setLastModified(now())
        Lease(file) { synchronized(lock) {
            val count = (readers[relative] ?: 1) - 1
            if (count == 0) readers.remove(relative) else readers[relative] = count
        } }
    }

    fun readText(relative: String): String? {
        val lease = acquire(relative) ?: return null
        return lease.use { runCatching { it.file.readText(Charsets.UTF_8) }.getOrNull() }
    }

    /** Caller must protect returned playback files for their full playback/preparation lifetime. */
    fun existing(relative: String, maxEntryBytes: Long = limitBytes): File? =
        acquire(relative, maxEntryBytes)?.use { it.file }

    fun remove(relative: String): Boolean = synchronized(lock) {
        if (relative in pinned()) false else file(relative).let { !it.exists() || it.delete() }
    }

    /** Reserve complete staging bytes before writing. A refused/failed write leaves old data intact. */
    fun put(relative: String, bytes: ByteArray, usedAt: Long = now(), headroomBytes: Long = 0): File? = synchronized(lock) {
        if (bytes.isEmpty() || bytes.size.toLong() + headroomBytes + reservedBytes() > limitBytes) return@synchronized null
        val target = file(relative)
        val pending = file("$relative.pending")
        if (pending.exists()) pending.delete()
        val result = trimLocked(bytes.size.toLong() + headroomBytes, setOf(relative))
        if (result.afterBytes + bytes.size.toLong() + headroomBytes > limitBytes) return@synchronized null
        try {
            target.parentFile?.mkdirs()
            pending.writeBytes(bytes)
            if (!pending.renameTo(target)) throw java.io.IOException("Cache atomic rename failed")
            target.setLastModified(usedAt)
            target
        } catch (_: Exception) {
            pending.delete()
            null
        }
    }

    fun resize(bytes: Long): TrimResult = synchronized(lock) {
        require(bytes > 0)
        limitBytes = bytes
        trimLocked(0, emptySet())
    }

    /** Keep active readers and playback/preparation owners alive during manual cleanup. */
    fun clear(): TrimResult = synchronized(lock) { trimLocked(0, emptySet(), clearAll = true) }

    fun trim(): TrimResult = synchronized(lock) { trimLocked(0, emptySet()) }
    fun sizeBytes(): Long = synchronized(lock) {
        files().sumOf { it.length() } + externalEntries().sumOf { it.bytes } + reservedBytes()
    }

    private fun trimLocked(incoming: Long, exclude: Set<String>, clearAll: Boolean = false): TrimResult {
        val disk = files()
        val external = externalEntries()
        val before = disk.sumOf { it.length() } + external.sumOf { it.bytes } + reservedBytes()
        if (!clearAll && before + incoming < limitBytes) return TrimResult(before, before, 0, 0)
        // Once over budget, free a full batch (not just the few bytes over the high-water mark).
        val target = if (clearAll) 0L else minOf((limitBytes - incoming - 1).coerceAtLeast(0), (before - batchBytes).coerceAtLeast(0))
        val protected = pinned() + exclude
        data class Candidate(val id: String, val bytes: Long, val usedAt: Long, val evict: () -> Boolean)
        val candidates = disk.filter { it.relativeTo(root).invariantSeparatorsPath !in protected }.map { f ->
            Candidate(f.relativeTo(root).invariantSeparatorsPath, f.length(), f.lastModified(), { f.delete() })
        } + external.map { Candidate(it.id, it.bytes, it.usedAt, it.evict) }
        var after = before
        var count = 0
        for (entry in candidates.sortedWith(compareBy<Candidate> { it.usedAt }.thenBy { it.id })) {
            if (after <= target) break
            if (runCatching(entry.evict).getOrDefault(false)) { after -= entry.bytes; count++ }
        }
        return TrimResult(before, after, before - after, count)
    }
}
