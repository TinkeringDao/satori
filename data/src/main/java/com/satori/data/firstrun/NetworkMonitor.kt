package com.satori.data.firstrun

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.satori.domain.firstrun.NetworkFacts
import com.satori.domain.firstrun.NetworkWatch
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The default network as Android reports it, through the default network's callbacks: whether
 * there is one, whether Android validated it, whether it is metered. Read from its capabilities
 * (NOT_METERED, VALIDATED), never from its transport: behind an always-on VPN the default network
 * is the VPN, whatever carries it, and a check for Wi-Fi would misread it.
 */
@Singleton
class NetworkMonitor @Inject constructor(@ApplicationContext context: Context) : NetworkWatch {

    private val connectivity: ConnectivityManager? = context.getSystemService(ConnectivityManager::class.java)

    private val _facts = MutableStateFlow(current())
    override val facts: StateFlow<NetworkFacts> = _facts.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = changed(of(capabilities))
        override fun onLost(network: Network) = changed(NetworkFacts.NONE)
    }

    init {
        try {
            connectivity?.registerDefaultNetworkCallback(callback)
        } catch (e: SecurityException) {
            // Without ACCESS_NETWORK_STATE: the downloads then go by what they find.
            Log.w(TAG, "Network not watched: ${e.javaClass.simpleName}")
        }
    }

    private fun changed(facts: NetworkFacts) {
        if (_facts.value == facts) return
        _facts.value = facts
        Log.i(TAG, "network connected=${facts.connected} validated=${facts.validated} metered=${facts.metered}")
    }

    private fun current(): NetworkFacts = try {
        val manager = connectivity
        val network = manager?.activeNetwork
        val capabilities = network?.let { manager.getNetworkCapabilities(it) }
        capabilities?.let(::of) ?: NetworkFacts.NONE
    } catch (e: SecurityException) {
        NetworkFacts.NONE
    }

    companion object {
        private const val TAG = "SatoriFirstRun"

        private fun of(capabilities: NetworkCapabilities): NetworkFacts = facts(
            internet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            notMetered = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED)
        )

        /**
         * The facts of a default network from its capabilities: one without internet is none,
         * and only a network that is there can be validated or metered.
         */
        internal fun facts(internet: Boolean, validated: Boolean, notMetered: Boolean): NetworkFacts =
            if (!internet) NetworkFacts.NONE else NetworkFacts(connected = true, validated = validated, metered = !notMetered)
    }
}
