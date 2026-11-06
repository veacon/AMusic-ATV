package com.applemusicktv.data.cache

import org.junit.Assert.*
import org.junit.Test

class CacheMaintenanceGateTest {
    @Test fun idleChecksDoNotScanAnUnchangedCache() {
        val gate=CacheMaintenanceGate();repeat(100){assertFalse(gate.claim())}
        assertEquals(300000L,CacheMaintenanceGate.IDLE_CHECK_MS)
    }
    @Test fun hundredsOfImageWritesCoalesceIntoOneMaintenancePass() {
        val gate=CacheMaintenanceGate();repeat(500){gate.markDirty()}
        assertTrue(gate.claim());assertFalse(gate.claim())
        assertEquals(30000L,CacheMaintenanceGate.BATCH_DELAY_MS)
    }
    @Test fun writesDuringAScanAndFailedScansRemainPendingForTheNextBatch() {
        val gate=CacheMaintenanceGate();gate.markDirty();assertTrue(gate.claim())
        gate.markDirty();assertTrue(gate.claim())
        gate.markDirty();assertTrue(gate.claim());assertFalse(gate.claim())
    }
}
