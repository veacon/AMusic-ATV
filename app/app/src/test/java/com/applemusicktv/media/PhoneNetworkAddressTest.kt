package com.applemusicktv.media

import android.app.Application
import android.content.Context
import android.net.*
import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

internal fun clearPhoneNetworks(context: Context) {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    shadowOf(cm).clearAllNetworks()
    shadowOf(cm).setActiveNetworkInfo(null)
}

internal fun phoneLinks(ip: String?): LinkProperties = LinkProperties().apply {
    interfaceName = "test-lan"
    if (ip != null) {
        val address = ReflectionHelpers.callConstructor(LinkAddress::class.java,
            ClassParameter.from(InetAddress::class.java, InetAddress.getByName(ip)),
            ClassParameter.from(Int::class.javaPrimitiveType!!, if (':' in ip) 64 else 24))
        ReflectionHelpers.callInstanceMethod<Boolean>(this, "addLinkAddress", ClassParameter.from(LinkAddress::class.java, address))
    }
}

internal fun addPhoneNetwork(context: Context, ethernet: Boolean, ip: String?, makeDefault: Boolean = true): Network {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val type = if (ethernet) ConnectivityManager.TYPE_ETHERNET else ConnectivityManager.TYPE_WIFI
    val network = ShadowNetwork.newInstance(type)
    val info = ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, type, 0, true, true)
    shadowOf(cm).addNetwork(network, info)
    shadowOf(cm).setNetworkCapabilities(network, NetworkCapabilities().apply {
        shadowOf(this).addTransportType(if (ethernet) NetworkCapabilities.TRANSPORT_ETHERNET else NetworkCapabilities.TRANSPORT_WIFI)
        shadowOf(this).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    })
    shadowOf(cm).setLinkProperties(network, phoneLinks(ip))
    if (makeDefault) shadowOf(cm).setActiveNetworkInfo(info)
    return network
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28], application = Application::class)
class PhoneNetworkAddressTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val cm: ConnectivityManager get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private fun candidate(key: String, ethernet: Boolean, vararg ips: String) = PhoneLanCandidate(
        key, !ethernet, ethernet, false, ips.map { InetAddress.getByName(it) })

    @Test fun ethernetWorksWithoutWifiOrPublicInternetValidation() {
        clearPhoneNetworks(context)
        addPhoneNetwork(context, true, "192.168.1.44")
        var latest = PhoneLanAddress()
        val monitor = PhoneNetworkAddress(context) { latest = it }
        try {
            monitor.start()
            assertEquals(PhoneLanAddress("192.168.1.44", true), latest)
        } finally { monitor.stop() }
    }

    @Test fun defaultPhysicalNetworkWinsOverTheOtherConnectedInterface() {
        val wifi = candidate("wifi", false, "192.168.2.20")
        val wired = candidate("wired", true, "192.168.1.20")
        assertEquals(wifi.addresses[0].hostAddress, selectPhoneLan(listOf(wired, wifi), "wifi").host)
        assertEquals(wired.addresses[0].hostAddress, selectPhoneLan(listOf(wifi, wired), "wired").host)
        assertEquals("192.168.1.20", selectPhoneLan(listOf(wifi, wired), null).host)
    }

    @Test fun unspecifiedLoopbackMulticastAndLinkLocalNeverBecomeQrHosts() {
        for (ip in listOf("0.0.0.0", "0.1.2.3", "127.0.0.1", "224.0.0.1", "255.255.255.255", "169.254.1.2", "::", "::1")) {
            assertEquals(ip, "", selectPhoneLan(listOf(candidate("wifi", false, ip)), "wifi").host)
        }
    }

    @Test fun vpnAndCellularAddressesAreNotAdvertised() {
        val physical = candidate("wifi", false, "192.168.1.20")
        val vpn = physical.copy(key = "vpn", vpn = true, addresses = listOf(InetAddress.getByName("10.8.0.2")))
        val cellular = physical.copy(key = "cell", wifi = false, addresses = listOf(InetAddress.getByName("10.20.30.40")))
        assertEquals("192.168.1.20", selectPhoneLan(listOf(vpn, cellular, physical), "vpn").host)
        assertEquals(PhoneLanAddress(), selectPhoneLan(listOf(vpn, cellular), "vpn"))
    }

    @Test fun noIpv4IsWaitingAndDoesNotPreventUsingAnotherValidPhysicalNetwork() {
        val wired = candidate("wired", true, "fe80::1")
        assertEquals(PhoneLanAddress("", true), selectPhoneLan(listOf(wired), "wired"))
        assertEquals("192.168.4.20", selectPhoneLan(listOf(wired, candidate("wifi", false, "192.168.4.20")), "wired").host)
    }

    @Test fun addressSelectionIsStableAcrossCandidateOrder() {
        val items = listOf(candidate("b", false, "192.168.2.20"), candidate("a", true, "192.168.1.20"))
        assertEquals(selectPhoneLan(items, null), selectPhoneLan(items.reversed(), null))
    }

    @Test fun callbacksPublishNewIpAndWithdrawLostNetworkWithoutRestarting() {
        clearPhoneNetworks(context)
        val network = addPhoneNetwork(context, false, null)
        var latest = PhoneLanAddress()
        val monitor = PhoneNetworkAddress(context) { latest = it }
        try {
            monitor.start()
            assertEquals(PhoneLanAddress("", true), latest)
            val callbacks = shadowOf(cm).networkCallbacks.toList()
            callbacks.forEach { it.onLinkPropertiesChanged(network, phoneLinks("192.168.43.20")) }
            assertEquals("192.168.43.20", latest.host)
            callbacks.forEach { it.onLinkPropertiesChanged(network, phoneLinks(null)) }
            assertEquals(PhoneLanAddress("", true), latest)
            callbacks.forEach { it.onLost(network) }
            assertEquals(PhoneLanAddress(), latest)
        } finally { monitor.stop() }
    }

    @Test fun stopUnregistersAndOldCallbacksCannotOverwriteRestartedState() {
        clearPhoneNetworks(context)
        val network = addPhoneNetwork(context, true, "192.168.1.44")
        var latest = PhoneLanAddress()
        val monitor = PhoneNetworkAddress(context) { latest = it }
        monitor.start()
        val oldCallbacks = shadowOf(cm).networkCallbacks.toList()
        monitor.stop()
        assertTrue(shadowOf(cm).networkCallbacks.isEmpty())
        assertEquals(PhoneLanAddress(), latest)
        monitor.start()
        try {
            assertEquals("192.168.1.44", latest.host)
            oldCallbacks.forEach { it.onLinkPropertiesChanged(network, phoneLinks("192.168.99.99")); it.onLost(network) }
            assertEquals("192.168.1.44", latest.host)
        } finally { monitor.stop() }
    }
}
