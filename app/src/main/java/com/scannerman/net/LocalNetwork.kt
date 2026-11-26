package com.scannerman.net

import android.content.Context
import android.net.ConnectivityManager
import java.net.Inet4Address
import java.net.Inet6Address

/** The network the phone is currently attached to. */
data class LocalNetwork(
    val iface: String,
    val ip4: String,
    val prefix: Int,
    /** Prefix actually scanned (large subnets are clamped to the /24 around us). */
    val scanPrefix: Int,
    val ip6: List<String>,
    val gateway: String?,
) {
    private val ownInt = toLong(ip4)
    private val mask = (0xffffffffL shl (32 - scanPrefix)) and 0xffffffffL
    val networkAddress: String = fromLong(ownInt and mask)
    val cidr: String get() = "$networkAddress/$scanPrefix"

    fun contains(ip: String): Boolean =
        ip.count { it == '.' } == 3 && (toLong(ip) and mask) == (ownInt and mask)

    /** All usable host addresses in the scan range, excluding our own. */
    fun hostAddresses(): List<String> {
        val base = ownInt and mask
        val size = 1L shl (32 - scanPrefix)
        return (1 until size - 1).map { fromLong(base + it) }.filter { it != ip4 }
    }

    /** IPv6 link-local addresses need the interface as zone for sockets. */
    fun scoped(ip6: String): String =
        if (ip6.startsWith("fe80", ignoreCase = true) && '%' !in ip6) "$ip6%$iface" else ip6

    companion object {
        /**
         * @param minPrefix largest range swept completely (e.g. 22 = 1022 hosts);
         *        bigger subnets fall back to the /24 around our own address.
         */
        fun detect(context: Context, minPrefix: Int = 22): LocalNetwork? {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
            val net = cm.activeNetwork ?: return null
            val lp = cm.getLinkProperties(net) ?: return null
            val v4 = lp.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return null
            val prefix = v4.prefixLength
            val scanPrefix = if (prefix in minPrefix..30) prefix else 24
            val v6 = lp.linkAddresses
                .map { it.address }
                .filterIsInstance<Inet6Address>()
                .map { it.hostAddress!!.substringBefore('%') }
            val gw = lp.routes
                .firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
                ?.gateway?.hostAddress
            return LocalNetwork(
                iface = lp.interfaceName ?: "wlan0",
                ip4 = v4.address.hostAddress!!,
                prefix = prefix,
                scanPrefix = scanPrefix,
                ip6 = v6,
                gateway = gw,
            )
        }

        fun toLong(ip: String): Long =
            ip.split('.').fold(0L) { acc, p -> (acc shl 8) or (p.toLong() and 0xff) }

        fun fromLong(v: Long): String =
            "${(v shr 24) and 0xff}.${(v shr 16) and 0xff}.${(v shr 8) and 0xff}.${v and 0xff}"
    }
}
