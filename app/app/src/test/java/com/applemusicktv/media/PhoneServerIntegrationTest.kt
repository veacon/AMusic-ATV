package com.applemusicktv.media

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import android.net.ConnectivityManager
import com.applemusicktv.R
import com.applemusicktv.data.*
import com.applemusicktv.data.auth.TokenVerifier
import com.applemusicktv.data.auth.TokenVerification
import com.applemusicktv.data.repository.MusicRepository
import com.applemusicktv.ui.viewmodel.OnboardingViewModel
import kotlinx.coroutines.*
import java.net.BindException
import java.net.ServerSocket
import java.net.Socket
import java.net.InetAddress
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28], application = Application::class)
class PhoneServerIntegrationTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val verifier = TokenVerifier { TokenVerification.Valid }

    private fun server(): InAppWebServer {
        clearPhoneNetworks(context)
        addPhoneNetwork(context, false, "192.168.1.4")
        val type = Class.forName("sun.misc.Unsafe")
        val unsafe = type.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val repo = type.getMethod("allocateInstance", Class::class.java).invoke(unsafe, MusicRepository::class.java) as MusicRepository
        return InAppWebServer(context, MutPreferences(context), repo, LyricsOffsetPreferences(context),
            CrossfadePreferences(context), StandalonePreferences(context), ServerPreferences(context), BeatAnalyzer(), verifier)
    }

    private fun awaitReady(server: InAppWebServer) = runBlocking {
        withTimeout(5000) { while (!server.running) delay(10) }
    }

    private fun get(port: Int, path: String): String = Socket("127.0.0.1", port).use { client ->
        client.soTimeout = 5000
        client.getOutputStream().write("GET $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: close\r\n\r\n".toByteArray())
        client.getInputStream().bufferedReader().readText()
    }

    @Test fun conflictServesPhonePageAndStatusOnAdvertisedPortDespiteBusyEventPort() {
        val blockers = listOf(8080, 8081).mapNotNull { try { ServerSocket(it) } catch (_: BindException) { null } }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = server()
        try {
            assertEquals("", server.serverUrl())
            server.boot(scope)
            awaitReady(server)
            assertNotEquals(8080, server.port)
            assertNotEquals(8081, server.port)
            assertEquals("http://192.168.1.4:${server.port}", server.serverUrl())
            val page = get(server.port, "/")
            assertTrue(page.startsWith("HTTP/1.1 200 OK"))
            assertTrue(page.contains("Apple Music"))
            val status = get(server.port, "/status").substringAfter("\r\n\r\n")
            assertEquals(server.serverUrl(), JSONObject(status).getString("url"))
            assertFalse(JSONObject(status).getBoolean("hasMUT"))
        } finally { server.stop(); scope.cancel(); blockers.forEach { it.close() } }
    }

    @Test fun stopHidesAddressAndRestartRebindsWithoutStaleCleanupClosingNewListener() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = server()
        try {
            repeat(4) {
                server.boot(scope)
                awaitReady(server)
                assertTrue(get(server.port, "/status").startsWith("HTTP/1.1 200 OK"))
                server.stop()
                assertFalse(server.running)
                assertEquals(0, server.port)
                assertEquals("", server.serverUrl())
            }
        } finally { server.stop(); scope.cancel() }
    }

    @Test fun onboardingTracksBoundAddressAndStopWithoutWaitingForTokenVerification() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = server()
        val store = ViewModelStore()
        val vm = OnboardingViewModel(OnboardingPreferences(context), MutPreferences(context), server, verifier)
        store.put("login", vm)
        try {
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("", vm.state.value.phoneUrl)
            assertTrue(vm.state.value.phoneServerUnavailable)
            server.boot(scope)
            awaitReady(server)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(server.serverUrl(), vm.state.value.phoneUrl)
            assertFalse(vm.state.value.phoneServerUnavailable)
            server.stop()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("", vm.state.value.phoneUrl)
            assertTrue(vm.state.value.phoneServerUnavailable)
        } finally { server.stop(); scope.cancel(); store.clear() }
    }

    @Test fun ethernetBootPublishesRealWiredAddressWhenWifiHasNoIp() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = server()
        clearPhoneNetworks(context)
        addPhoneNetwork(context, true, "192.168.1.44")
        try {
            server.boot(scope)
            awaitReady(server)
            assertEquals("http://192.168.1.44:${server.port}", server.serverUrl())
            val status = JSONObject(get(server.port, "/status").substringAfter("\r\n\r\n"))
            assertEquals(server.serverUrl(), status.getString("url"))
        } finally { server.stop(); scope.cancel() }
    }

    @Test fun waitingHotspotAndDisconnectUpdateLoginImmediatelyAndRetainListeningPort() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = server()
        clearPhoneNetworks(context)
        val network = addPhoneNetwork(context, false, null)
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val store = ViewModelStore()
        val vm = OnboardingViewModel(OnboardingPreferences(context), MutPreferences(context), server, verifier)
        store.put("login", vm)
        try {
            server.boot(scope)
            awaitReady(server)
            shadowOf(Looper.getMainLooper()).idle()
            val port = server.port
            assertEquals("", vm.state.value.phoneUrl)
            assertEquals(R.string.phone_network_waiting, vm.state.value.phoneSetupMessage)
            val callbacks = shadowOf(cm).networkCallbacks.toList()
            callbacks.forEach { it.onLinkPropertiesChanged(network, phoneLinks("192.168.43.20")) }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("http://192.168.43.20:$port", vm.state.value.phoneUrl)
            assertNull(vm.state.value.phoneSetupMessage)
            callbacks.forEach { it.onLinkPropertiesChanged(network, phoneLinks("192.168.99.20")) }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("http://192.168.99.20:$port", vm.state.value.phoneUrl)
            assertEquals(port, server.port)
            assertTrue(get(port, "/status").contains("http://192.168.99.20:$port"))
            callbacks.forEach { it.onLost(network) }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("", vm.state.value.phoneUrl)
            assertEquals(R.string.msg_172, vm.state.value.phoneSetupMessage)
            assertEquals(port, server.port)
        } finally { server.stop(); scope.cancel(); store.clear() }
    }

    @Test fun disabledServerDoesNotPublishANonfunctionalQrAddress() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = server()
        try {
            server.setEnabled(false)
            server.boot(scope)
            assertFalse(server.running)
            assertEquals("", server.serverUrl())
        } finally { server.stop(); scope.cancel() }
    }
}
