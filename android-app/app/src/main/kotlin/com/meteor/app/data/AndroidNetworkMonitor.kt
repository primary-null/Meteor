package com.meteor.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

// --- offline and reconnect ---

/**
 * [NetworkMonitor] over the default-network callback. It is registered only while the app is in the foreground
 * ([start] on ON_START, [stop] on ON_STOP) and needs only ACCESS_NETWORK_STATE.
 *
 * Every start re-reads the current default network, because callbacks are not delivered while stopped. The change
 * counter grows only when the default network is a different one than before (a new Wi-Fi, mobile data, a VPN); the
 * callback's first report of the network that was already known does not count as a change.
 */
class AndroidNetworkMonitor(context: Context) : NetworkMonitor {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val mutable = MutableStateFlow(NetworkStatus())
    override val status: StateFlow<NetworkStatus> = mutable
    @Volatile private var current: Network? = null
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { onDefault(network) }
        override fun onLost(network: Network) {
            // A replaced default network may be reported lost after its successor is already available.
            if (network == current) { current = null; mutable.update { it.copy(online = false) } }
        }
    }

    private fun onDefault(network: Network?) {
        // The first network ever seen is not a change; only replacing a known network is.
        val switched = network != null && current != null && network != current
        current = network
        mutable.update { it.copy(online = network != null, changes = if (switched) it.changes + 1 else it.changes) }
    }

    @Synchronized override fun start() {
        if (registered) return
        // Seed from the present state so a network lost while stopped is noticed, and the callback's first report of the same network is not a change.
        val present = try { manager.activeNetwork } catch (error: SecurityException) { null }
        val switched = present != null && current != null && present != current
        current = present
        mutable.update { it.copy(online = present != null, changes = if (switched) it.changes + 1 else it.changes) }
        try {
            manager.registerDefaultNetworkCallback(callback)
            registered = true
        } catch (error: RuntimeException) {
            // Without a callback the state cannot be kept honest; report online so nothing is blocked on a guess.
            mutable.update { it.copy(online = true) }
        }
    }

    @Synchronized override fun stop() {
        if (!registered) return
        registered = false
        try { manager.unregisterNetworkCallback(callback) } catch (error: IllegalArgumentException) { /* already gone */ }
    }
}
// --- end offline and reconnect ---
