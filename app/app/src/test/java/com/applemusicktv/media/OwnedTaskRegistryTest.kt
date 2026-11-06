package com.applemusicktv.media

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test

class OwnedTaskRegistryTest {
    @Test fun jobSupertypeCleanupDoesNotRemoveAReplacementForTheSameSong() {
        val registry = OwnedTaskRegistry<String, CompletableDeferred<Int>>()
        val old = CompletableDeferred<Int>(); val replacement = CompletableDeferred<Int>()
        registry["song"] = old
        registry["song"] = replacement
        val oldOwner: Job = old
        assertFalse(registry.remove("song", oldOwner))
        assertSame(replacement, registry["song"])
        assertFalse(registry.remove("song", null))
        assertTrue(registry.remove("song", replacement as Job))
        assertNull(registry["song"])
    }
}
