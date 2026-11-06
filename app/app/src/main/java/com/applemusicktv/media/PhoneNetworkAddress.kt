package com.applemusicktv.media

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import java.net.Inet4Address
import java.net.InetAddress

internal data class PhoneLanAddress(val host: String = "", val connected: Boolean = false)

internal data class PhoneLanCandidate(
    val key: String,
    val wifi: Boolean,
    val ethernet: Boolean,
    val vpn: Boolean,
    val addresses: List<InetAddress>,
)

/** Only addresses assigned to a physical LAN are advertised, never an arbitrary interface. */
internal fun selectPhoneLan(candidates: List<PhoneLanCandidate>, defaultKey: String?): PhoneLanAddress {
    val physical = candidates.filter { (it.wifi || it.ethernet) && !it.vpn }
    val usable = physical.mapNotNull { candidate ->
        val host = candidate.addresses.filterIsInstance<Inet4Address>()
            .filter { !it.isAnyLocalAddress && !it.isLoopbackAddress && !it.isMulticastAddress &&
                !it.isLinkLocalAddress && it.address[0].toInt() != 0 && it.hostAddress != "255.255.255.255" }
            .mapNotNull { it.hostAddress }.sorted().firstOrNull()
        host?.let { candidate to it }
    }
    val chosen = usable.firstOrNull { it.first.key == defaultKey }
        ?: usable.sortedWith(compareBy<Pair<PhoneLanCandidate, String>> { !it.first.ethernet }
            .thenBy { it.first.key }.thenBy { it.second }).firstOrNull()
    return PhoneLanAddress(chosen?.second.orEmpty(), physical.isNotEmpty())
}

/** Observe Wi-Fi and Ethernet on API23+, without requiring public internet validation. */
internal class PhoneNetworkAddress(context: Context, private val changed: (PhoneLanAddress) -> Unit) {
    private val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private data class Snapshot(val capabilities: NetworkCapabilities? = null, val addresses: List<InetAddress> = emptyList())
    private val lock = Any()
    private val networks = mutableMapOf<Network, Snapshot>()
    private var generation = 0
    private var started = false
    private var defaultNetwork: Network? = null
    private var physicalCallback: ConnectivityManager.NetworkCallback? = null
    private var defaultCallback: ConnectivityManager.NetworkCallback? = null

    fun start() = synchronized(lock) {
        if (started) return@synchronized
        val cm = manager ?: return@synchronized
        started = true
        val session = ++generation
        networks.clear()
        defaultNetwork = runCatching { cm.activeNetwork }.getOrNull()
        fun change(action: () -> Unit) = synchronized(lock) {
            if (started && generation == session) {
                action()
                publish()
            }
        }
        val physical = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = change {
                // Newer Android delivers capabilities and link properties separately.
                networks.getOrPut(network) { Snapshot() }
                if (Build.VERSION.SDK_INT < 26) {
                    networks[network] = Snapshot(cm.getNetworkCapabilities(network), cm.getLinkProperties(network)?.linkAddresses.orEmpty().map { it.address })
                }
            }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = change {
                networks[network] = (networks[network] ?: Snapshot()).copy(capabilities = NetworkCapabilities(capabilities))
                if (Build.VERSION.SDK_INT < 24) defaultNetwork = cm.activeNetwork
            }
            override fun onLinkPropertiesChanged(network: Network, links: LinkProperties) = change {
                networks[network] = (networks[network] ?: Snapshot()).copy(addresses = links.linkAddresses.map { it.address })
                if (Build.VERSION.SDK_INT < 24) defaultNetwork = cm.activeNetwork
            }
            override fun onLost(network: Network) = change { networks.remove(network) }
        }
        val preferred = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = change { defaultNetwork = network }
            override fun onLost(network: Network) = change {
                if (defaultNetwork == network) defaultNetwork = null
            }
        }
        try {
            cm.registerNetworkCallback(NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), physical)
            physicalCallback = physical
            if (Build.VERSION.SDK_INT >= 24) {
                // Physical-network observation still works if a vendor rejects this callback.
                runCatching { cm.registerDefaultNetworkCallback(preferred) }
                    .onSuccess { defaultCallback = preferred }
            }
            // Seed networks already connected when the service starts (also needed on API23).
            for (network in cm.allNetworks) {
                networks[network] = Snapshot(cm.getNetworkCapabilities(network), cm.getLinkProperties(network)?.linkAddresses.orEmpty().map { it.address })
            }
            publish()
        } catch (_: Exception) {
            physicalCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
            defaultCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
            physicalCallback = null; defaultCallback = null
            started = false; networks.clear()
            changed(PhoneLanAddress())
        }
    }

    private fun publish() {
        val candidates = networks.mapNotNull { (network, snapshot) ->
            val caps = snapshot.capabilities ?: return@mapNotNull null
            PhoneLanCandidate(network.toString(), caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                snapshot.addresses)
        }
        changed(selectPhoneLan(candidates, defaultNetwork?.toString()))
    }

    fun stop() = synchronized(lock) {
        started = false; generation++
        physicalCallback?.let { callback -> manager?.let { runCatching { it.unregisterNetworkCallback(callback) } } }
        defaultCallback?.let { callback -> manager?.let { runCatching { it.unregisterNetworkCallback(callback) } } }
        physicalCallback = null; defaultCallback = null
        networks.clear(); defaultNetwork = null
        changed(PhoneLanAddress())
    }
}
