package org.novabird.feature.sync.limit

import android.net.ConnectivityManager

fun interface NetworkState {
    /**
     * Returns `true` if the active network is metered or if there is no active network.
     */
    fun isActiveNetworkMetered(): Boolean
}

internal class AndroidNetworkState(
    private val connectivityManager: ConnectivityManager,
) : NetworkState {
    override fun isActiveNetworkMetered(): Boolean = connectivityManager.isActiveNetworkMetered
}
