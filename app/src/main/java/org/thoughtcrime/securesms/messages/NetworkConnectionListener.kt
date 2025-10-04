/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.messages

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.ProxyInfo
import android.os.Build
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint
import org.thoughtcrime.securesms.util.ServiceUtil

/**
 * Backcompat listener for determining when the network connection is lost.
 * On API 28+, [onNetworkLost] is invoked when the system notifies the app that the network is lost.
 * On earlier versions, [onNetworkLost] is invoked on any network change (gained, lost, losing, etc)
 * Therefore, [onNetworkLost] is a higher-order function, which takes a function to determine conditionally if it should run.
 * API 28+ only runs on lost networks, so it provides a conditional that's always true because that is guaranteed by the call site.
 * Earlier versions use [NetworkConstraint.isMet] to query the current network state upon receiving the broadcast.
 */
class NetworkConnectionListener(private val context: Context, private val onNetworkLost: (() -> Boolean) -> Unit, private val onProxySettingsChanged: ((ProxyInfo?) -> Unit)) {
  companion object {
    private val TAG = Log.tag(NetworkConnectionListener::class.java)
  }

  private val connectivityManager = ServiceUtil.getConnectivityManager(context)
  private val lastNetworkCapabilities = mutableMapOf<Network, String>()
  private val lastValidatedNetworkCapabilities = mutableMapOf<Network, String>()

  // onCapabilitiesChanged gets called every ~30 seconds or so in my testing, as the network estimator runs
  // and updates the bandwidth estimated capabilities. This is thus essential for preventing log spam.
  private fun logCapabilitiesIfChanged(
    network: Network,
    networkCapabilities: NetworkCapabilities,
    callbackType: String,
    lastLogs: MutableMap<Network, String>
  ) {
    val currentLog = buildString {
      append(callbackType)
      append(" onCapabilitiesChanged($network, ")
      append("hasInternet=${networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)}, ")
      append("validated=${networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}, ")
      append("captivePortal=${networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)})")
    }

    if (lastLogs[network] != currentLog) {
      Log.d(TAG, currentLog)
      lastLogs[network] = currentLog
    }
  }

  private val networkChangedCallback: ConnectivityManager.NetworkCallback = object : ConnectivityManager.NetworkCallback() {
    override fun onUnavailable() {
      super.onUnavailable()
      Log.d(TAG, "ConnectivityManager.NetworkCallback onUnavailable()")
      onNetworkLost { true }
    }

    override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
      super.onBlockedStatusChanged(network, blocked)
      Log.d(TAG, "ConnectivityManager.NetworkCallback onBlockedStatusChanged($network, $blocked)")
      onNetworkLost { blocked }
    }

    override fun onAvailable(network: Network) {
      super.onAvailable(network)
      Log.d(TAG, "ConnectivityManager.NetworkCallback onAvailable($network)")
      onNetworkLost { false }
    }

    override fun onLost(network: Network) {
      super.onLost(network)
      Log.d(TAG, "ConnectivityManager.NetworkCallback onLost($network)")
      onNetworkLost { true }
    }

    override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
      super.onLinkPropertiesChanged(network, linkProperties)
      Log.d(TAG, "ConnectivityManager.NetworkCallback onLinkPropertiesChanged($network)")
      onProxySettingsChanged(linkProperties.httpProxy)
    }

    override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
      super.onCapabilitiesChanged(network, networkCapabilities)
      logCapabilitiesIfChanged(network, networkCapabilities, "ConnectivityManager.NetworkCallback", lastNetworkCapabilities)
    }
  }

  private val connectionReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      Log.d(TAG, "BroadcastReceiver onReceive().")
      onNetworkLost { !NetworkConstraint.isMet(context) }
    }
  }

  private val logOnlyValidatedNetworkCallback: ConnectivityManager.NetworkCallback = object : ConnectivityManager.NetworkCallback() {
    override fun onUnavailable() {
      Log.d(TAG, "ValidatedNetworkCallback onUnavailable()")
      super.onUnavailable()
    }

    override fun onAvailable(network: Network) {
      super.onAvailable(network)
      Log.d(TAG, "ValidatedNetworkCallback onAvailable($network)")
    }

    override fun onLost(network: Network) {
      super.onLost(network)
      Log.d(TAG, "ValidatedNetworkCallback onLost($network)")
    }

    override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
      super.onBlockedStatusChanged(network, blocked)
      Log.d(TAG, "ValidatedNetworkCallback onBlockedStatusChanged($network, $blocked)")
    }

    // This should not be strictly necessary, but seeing as what we're doing here is trying to get more
    // insight into cases where Android's ConnectivityManager is behaving unexpectedly, I tend towards
    // logging more rather than less.
    override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
      super.onCapabilitiesChanged(network, networkCapabilities)
      logCapabilitiesIfChanged(network, networkCapabilities, "ValidatedNetworkCallback", lastValidatedNetworkCapabilities)
    }
  }

  fun register() {
    if (Build.VERSION.SDK_INT >= 28) {
      connectivityManager.registerDefaultNetworkCallback(networkChangedCallback)
      val validatedNetworkRequest = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED).build()
      connectivityManager.registerNetworkCallback(validatedNetworkRequest, logOnlyValidatedNetworkCallback)
    } else {
      context.registerReceiver(connectionReceiver, IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION))
    }
  }

  fun unregister() {
    if (Build.VERSION.SDK_INT >= 28) {
      connectivityManager.unregisterNetworkCallback(networkChangedCallback)
      connectivityManager.unregisterNetworkCallback(logOnlyValidatedNetworkCallback)
    } else {
      context.unregisterReceiver(connectionReceiver)
    }
  }
}
