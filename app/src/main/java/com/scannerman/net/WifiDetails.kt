package com.scannerman.net

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * SSID / BSSID of the current Wi-Fi connection. Android only reveals these to apps holding
 * location permission with Location switched on; otherwise [Info.ssid] is null and
 * [Info.missing] says why.
 */
object WifiDetails {

    enum class Missing { NONE, NOT_WIFI, NO_PERMISSION, LOCATION_OFF, UNKNOWN }

    data class Info(
        val ssid: String?,
        val bssid: String?,
        val frequency: Int?,
        val missing: Missing,
    ) {
        val band: String?
            get() = frequency?.let {
                when {
                    it < 3000 -> "2.4 GHz"
                    it < 5925 -> "5 GHz"
                    else -> "6 GHz"
                }
            }
    }

    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun locationOn(ctx: Context) =
        ctx.getSystemService(LocationManager::class.java)?.isLocationEnabled ?: true

    suspend fun current(ctx: Context): Info {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val net = cm?.activeNetwork
        val caps = net?.let { cm.getNetworkCapabilities(it) }
        if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return Info(null, null, null, Missing.NOT_WIFI)
        }

        var wi: WifiInfo? = null
        if (Build.VERSION.SDK_INT >= 31 && hasPermission(ctx)) {
            // Documented way on Android 12+: a callback that opts in to location info.
            var cb: ConnectivityManager.NetworkCallback? = null
            try {
                wi = withTimeoutOrNull(1500) {
                    suspendCancellableCoroutine { cont ->
                        cb = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                            override fun onCapabilitiesChanged(n: android.net.Network, c: NetworkCapabilities) {
                                val info = c.transportInfo as? WifiInfo ?: return
                                if (n == net && cont.isActive) cont.resume(info)
                            }
                        }.also {
                            cm.registerNetworkCallback(
                                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), it,
                            )
                        }
                    }
                }
            } finally {
                cb?.let { runCatching { cm.unregisterNetworkCallback(it) } }
            }
        }
        if (wi == null || wi.ssid.isUnknownSsid()) {
            @Suppress("DEPRECATION")
            wi = ctx.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo ?: wi
        }

        val ssid = wi?.ssid?.takeUnless { it.isUnknownSsid() }?.removeSurrounding("\"")
        val bssid = wi?.bssid?.takeUnless { it == "02:00:00:00:00:00" || it.isBlank() }?.lowercase()
        val freq = wi?.frequency?.takeIf { it > 0 }
        val missing = when {
            ssid != null -> Missing.NONE
            !hasPermission(ctx) -> Missing.NO_PERMISSION
            !locationOn(ctx) -> Missing.LOCATION_OFF
            else -> Missing.UNKNOWN
        }
        return Info(ssid, bssid, freq, missing)
    }

    private fun String?.isUnknownSsid() =
        this == null || this == WifiManager.UNKNOWN_SSID || this == "<unknown ssid>" || this.isBlank()
}
