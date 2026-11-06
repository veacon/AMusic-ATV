package com.applemusicktv.media

import com.applemusicktv.R
import java.net.BindException
import java.net.ServerSocket

internal enum class PhoneServerPhase { STOPPED, STARTING, READY, FAILED }

internal data class PhoneServerEndpoint(
    val phase: PhoneServerPhase = PhoneServerPhase.STOPPED,
    val port: Int = 0,
    val address: String = "",
    val networkConnected: Boolean = false,
)

internal fun phoneSetupHintId(endpoint: PhoneServerEndpoint): Int? = when {
    endpoint.phase == PhoneServerPhase.STOPPED || endpoint.phase == PhoneServerPhase.FAILED -> R.string.phone_setup_unavailable
    endpoint.phase == PhoneServerPhase.STARTING -> R.string.phone_setup_starting
    endpoint.address.isNotEmpty() -> null
    endpoint.networkConnected -> R.string.phone_network_waiting
    else -> R.string.msg_172
}

/** Bind and retain the listener; a separate availability probe would race other apps. */
internal object PhoneServerSocket {
    // 8081 remains reserved for the optional diagnostic stream.
    private val candidates = listOf(8080) + (8082..8090) + 0

    fun bind(open: (Int) -> ServerSocket = { ServerSocket(it) }): ServerSocket {
        for (port in candidates) {
            try {
                return open(port)
            } catch (e: BindException) {
                if (port == 0) throw e
            }
        }
        error("No phone configuration port available")
    }
}
