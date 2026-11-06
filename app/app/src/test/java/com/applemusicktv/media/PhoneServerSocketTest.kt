package com.applemusicktv.media

import java.net.BindException
import java.net.ServerSocket
import java.net.SocketException
import org.junit.Assert.*
import org.junit.Test

class PhoneServerSocketTest {
    @Test fun preferredListenerRemainsBoundInsteadOfBeingProbedAndClosed() {
        val attempted = mutableListOf<Int>()
        PhoneServerSocket.bind { attempted += it; ServerSocket(0) }.use { listener ->
            assertEquals(listOf(8080), attempted)
            try {
                ServerSocket(listener.localPort).use { fail("Listener must remain reserved") }
            } catch (_: BindException) { }
        }
    }

    private fun occupy(port: Int): ServerSocket? = try { ServerSocket(port) } catch (_: BindException) { null }

    @Test fun occupied8080UsesAnotherPortAndNeverTakesEventPort() {
        val blocker = occupy(8080)
        try {
            PhoneServerSocket.bind().use { listener ->
                assertTrue(listener.localPort > 0)
                assertNotEquals(8080, listener.localPort)
                assertNotEquals(8081, listener.localPort)
            }
        } finally { blocker?.close() }
    }

    @Test fun allFixedPortsBusyUsesAnOsAllocatedPort() {
        val fixed = listOf(8080) + (8082..8090)
        val blockers = fixed.mapNotNull(::occupy)
        try {
            PhoneServerSocket.bind().use { listener ->
                assertTrue(listener.localPort > 0)
                assertFalse(listener.localPort in fixed)
                assertNotEquals(8081, listener.localPort)
            }
        } finally { blockers.forEach { it.close() } }
    }

    @Test fun nonBindingErrorsAreReportedWithoutTryingUnrelatedPorts() {
        val attempted = mutableListOf<Int>()
        try {
            PhoneServerSocket.bind { attempted += it; throw SocketException("permission denied") }
            fail("Failure must reach service status")
        } catch (_: SocketException) { }
        assertEquals(listOf(8080), attempted)
    }

    @Test fun exhaustionIncludesOsFallbackAndPropagatesFailure() {
        val attempted = mutableListOf<Int>()
        try {
            PhoneServerSocket.bind { attempted += it; throw BindException("occupied") }
            fail("Must fail without publishing a URL")
        } catch (_: BindException) { }
        assertEquals(listOf(8080) + (8082..8090) + 0, attempted)
    }
}
