package com.applemusicktv.data.cache

import java.io.File
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiskBudgetStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun root() = temporary.newFolder()
    private fun seed(root: File, name: String, bytes: Int, time: Long): File = File(root, name).apply {
        parentFile?.mkdirs(); writeBytes(ByteArray(bytes) { 7 }); setLastModified(time)
    }

    @Test fun noCleanupBelowTheLimit() {
        val root = root(); seed(root, "old", 649, 1000)
        val store = DiskBudgetStore(root, 800, 400, reservedBytes = { 150 })
        assertEquals(0, store.trim().removedEntries)
        assertTrue(File(root, "old").exists())
    }
    @Test fun crossingLimitFreesAWhole400BatchOldestFirst() {
        val root = root()
        (1..5).forEach { seed(root, "song$it", 150, it * 1000L) }
        val result = DiskBudgetStore(root, 800, 400, reservedBytes = { 150 }).trim()
        assertEquals(900L, result.beforeBytes)
        assertEquals(450L, result.removedBytes)
        assertEquals(450L, result.afterBytes)
        assertEquals(listOf("song4", "song5"), root.listFiles()!!.map { it.name }.sorted())
    }
    @Test fun lastReadRefreshesRecencySoHotFilesSurvive() {
        val root = root(); (1..5).forEach { seed(root, "song$it", 180, it * 1000L) }
        val store = DiskBudgetStore(root, 800, 400, now = { 9000 })
        assertNotNull(store.readText("song1"))
        store.trim()
        assertTrue(File(root, "song1").exists())
        assertFalse(File(root, "song2").exists())
    }
    @Test fun activeFilesAndReadersAreProtectedUntilReleased() {
        val root = root(); (1..5).forEach { seed(root, "song$it", 180, it * 1000L) }
        val store = DiskBudgetStore(root, 800, 400)
        store.protect("player") { setOf("song1", "song2") }
        val lease = store.acquire("song3")!!
        val result = store.trim()
        assertEquals(360L, result.removedBytes)
        assertTrue(File(root, "song1").exists()); assertTrue(File(root, "song3").exists())
        store.unprotect("player"); lease.close(); lease.close()
        seed(root, "overflow", 400, 10000)
        assertTrue(store.trim().removedBytes >= 400)
    }
    @Test fun writerReservesFullStagingAndLeavesOldValueIfProtectedCapacityIsFull() {
        val root = root(); seed(root, "current", 600, 1000); seed(root, "record", 100, 2000)
        val store = DiskBudgetStore(root, 800, 400)
        store.protect("player") { setOf("current") }
        val old = File(root, "record").readBytes()
        assertNull(store.put("record", ByteArray(200)))
        assertArrayEquals(old, File(root, "record").readBytes())
        assertFalse(File(root, "record.pending").exists())
    }
    @Test fun crossingLimitOnWriteAlsoFreesAFullBatch() {
        val root = root(); (1..7).forEach { seed(root, "old$it", 100, it * 1000L) }
        val store = DiskBudgetStore(root, 800, 400)
        assertNotNull(store.put("new", ByteArray(150)))
        assertEquals(450L, store.sizeBytes())
        assertFalse(File(root, "old4").exists()); assertTrue(File(root, "old5").exists())
    }
    @Test fun oversizedWriteDoesNotDestroyExistingCache() {
        val root = root(); seed(root, "history", 400, 1000)
        val store = DiskBudgetStore(root, 800, 400, reservedBytes = { 150 })
        assertNull(store.put("too-large", ByteArray(651)))
        assertEquals(400L, File(root, "history").length())
    }
    @Test fun actualImageBytesAreCountedButNativeImageJournalIsNeverDeleted() {
        val root = root(); seed(root, "image_cache/journal", 150, 1)
        (1..5).forEach { seed(root, "song$it", 150, it * 1000L) }
        val store = DiskBudgetStore(root, 800, 400, reservedBytes = { 150 })
        assertEquals(900L, store.sizeBytes())
        store.trim(); assertEquals(150L, File(root, "image_cache/journal").length())
    }
    @Test fun legacyEntriesShareTheBudgetAndEvictionOrder() {
        val root = root(); seed(root, "newer", 450, 3000)
        var legacy = true
        val store = DiskBudgetStore(root, 800, 400, externalEntries = {
            if (legacy) listOf(DiskBudgetStore.ExternalEntry("legacy", 450, 1000) { legacy = false; true }) else emptyList()
        })
        assertEquals(450L, store.trim().removedBytes)
        assertFalse(legacy); assertTrue(File(root, "newer").exists())
    }
    @Test fun failedDeletionDoesNotPreventOtherCandidatesFromBeingCleaned() {
        val root = root(); (1..4).forEach { seed(root, "file$it", 200, it * 1000L) }
        val store = DiskBudgetStore(root, 800, 400, externalEntries = {
            listOf(DiskBudgetStore.ExternalEntry("locked", 100, 1) { false })
        })
        assertEquals(400L, store.trim().removedBytes)
        assertTrue(File(root, "file3").exists())
    }
    @Test fun simultaneousWritesStayWithinBudgetAndLeaveNoPartialFiles() {
        val root = root(); val store = DiskBudgetStore(root, 800, 400)
        val executor = Executors.newFixedThreadPool(4)
        try {
            val futures = (1..30).map { id -> executor.submit<Boolean> { store.put("entry$id", ByteArray(70)) != null } }
            assertTrue(futures.all { it.get() })
            assertTrue(store.sizeBytes() <= 800)
            assertFalse(root.walkTopDown().any { it.name.endsWith(".pending") })
        } finally { executor.shutdownNow() }
    }
    @Test fun replacementIsAtomicAndReadableAfterRecreation() {
        val root = root(); val store = DiskBudgetStore(root, 800, 400)
        store.put("history/feed", "old".toByteArray()); store.put("history/feed", "new".toByteArray())
        assertEquals("new", DiskBudgetStore(root, 800, 400).readText("history/feed"))
    }
    @Test fun invalidPathsCannotEscapeBudgetRootOrNativeImageOwnership() {
        val store = DiskBudgetStore(root(), 800, 400)
        listOf("../account", "/tmp/account", "image_cache/journal").forEach { name ->
            try { store.file(name); fail("Accepted $name") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun reachingLimitFrees500BatchAndLoweringLimitAppliesImmediately() {
        val root = root()
        (1..10).forEach { seed(root, "entry$it", 100, it * 1000L) }
        val store = DiskBudgetStore(root, 1000, 500)
        assertEquals(500L, store.trim().removedBytes)
        assertEquals(500L, store.sizeBytes())
        assertEquals(0L, store.resize(6000).removedBytes)
        (11..24).forEach { seed(root, "entry$it", 100, it * 1000L) }
        val shrink = store.resize(1000)
        assertTrue(shrink.afterBytes < 1000)
        assertTrue(shrink.removedBytes >= 500)
        assertEquals(1000L, store.limitBytes)
    }
    @Test fun manualCleanupClearsBelowLimitButKeepsCurrentAndOpenReaders() {
        val root = root()
        (1..3).forEach { seed(root, "song$it", 100, it * 1000L) }
        val store = DiskBudgetStore(root, 1000, 500)
        store.protect("playing") { setOf("song1") }
        val reader = store.acquire("song2")!!
        assertEquals(100L, store.clear().removedBytes)
        assertEquals(200L, store.sizeBytes())
        store.unprotect("playing"); reader.close()
        assertEquals(200L, store.clear().removedBytes)
        assertEquals(0L, store.sizeBytes())
    }
}
