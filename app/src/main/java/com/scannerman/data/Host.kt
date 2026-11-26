package com.scannerman.data

import java.util.UUID

/** Status of a host relative to the previous scan of the same network. */
enum class HostStatus {
    /** Seen in this scan and in the previous one. */
    ONLINE,
    /** Seen in this scan, never seen before. */
    NEW,
    /** Known from history but not answering in this scan. */
    OFFLINE,
}

/** Per-protocol reachability in the latest scan. */
enum class Avail { UP, DOWN, UNKNOWN }

/** The device moved from [from] to [to] (detected at [at]); [via] says how it was recognised. */
data class IpChange(val from: String, val to: String, val at: Long, val via: String)

data class Host(
    /** Null for hosts only seen over IPv6. */
    val ip4: String?,
    val mac: String? = null,
    val ip6: List<String> = emptyList(),
    val hostname: String? = null,
    /** Model reported via mDNS TXT records (e.g. "Chromecast", "MacBookPro18,1"). */
    val model: String? = null,
    /** DNS-SD service types announced by the device (e.g. "_googlecast", "_ipp"). */
    val services: List<String> = emptyList(),
    /** Open ports found over IPv4. */
    val openPorts: List<Int> = emptyList(),
    /** Open ports found over IPv6. */
    val openPorts6: List<Int> = emptyList(),
    val v4: Avail = if (ip4 != null) Avail.UP else Avail.UNKNOWN,
    val v6: Avail = Avail.UNKNOWN,
    val firstSeen: Long = System.currentTimeMillis(),
    val lastSeen: Long = System.currentTimeMillis(),
    val lastSeen4: Long? = null,
    val lastSeen6: Long? = null,
    val status: HostStatus = HostStatus.ONLINE,
    val isSelf: Boolean = false,
    val isGateway: Boolean = false,
    /** Identity names as "<source>:<name>" (see [Identity]); survive IP changes. */
    val names: List<String> = emptyList(),
    /** Stable id of this device in the history; kept as long as the device is recognised. */
    val uid: String = UUID.randomUUID().toString(),
    /** IPv4 changes, newest first. */
    val ipChanges: List<IpChange> = emptyList(),
) {
    /** Stable identity across scans. */
    val key: String get() = uid

    val lastIpChange: IpChange? get() = ipChanges.firstOrNull()

    /** Address used for display and "open with". */
    val address: String get() = ip4 ?: ip6.firstOrNull() ?: "?"

    val isOnline: Boolean get() = v4 == Avail.UP || v6 == Avail.UP

    /** Combine two records of the same device, preferring this one. */
    fun absorb(o: Host): Host = copy(
        ip4 = ip4 ?: o.ip4,
        mac = mac ?: o.mac,
        ip6 = (ip6 + o.ip6).distinct(),
        hostname = hostname ?: o.hostname,
        model = model ?: o.model,
        services = (services + o.services).distinct().sorted(),
        openPorts = (openPorts + o.openPorts).distinct().sorted(),
        openPorts6 = (openPorts6 + o.openPorts6).distinct().sorted(),
        v4 = best(v4, o.v4),
        v6 = best(v6, o.v6),
        firstSeen = minOf(firstSeen, o.firstSeen),
        lastSeen = maxOf(lastSeen, o.lastSeen),
        lastSeen4 = maxOfNullable(lastSeen4, o.lastSeen4),
        lastSeen6 = maxOfNullable(lastSeen6, o.lastSeen6),
        status = when {
            status != HostStatus.OFFLINE && o.status != HostStatus.OFFLINE ->
                if (status == HostStatus.ONLINE || o.status == HostStatus.ONLINE) HostStatus.ONLINE else HostStatus.NEW
            status != HostStatus.OFFLINE -> status
            else -> o.status
        },
        isSelf = isSelf || o.isSelf,
        isGateway = isGateway || o.isGateway,
        names = Identity.mergeNames(names, o.names),
        ipChanges = (ipChanges + o.ipChanges).distinct().sortedByDescending { it.at }.take(MAX_IP_CHANGES),
    )

    private fun best(a: Avail, b: Avail) = when {
        a == Avail.UP || b == Avail.UP -> Avail.UP
        a == Avail.DOWN || b == Avail.DOWN -> Avail.DOWN
        else -> Avail.UNKNOWN
    }

    companion object {
        const val MAX_IP_CHANGES = 10
    }

    private fun maxOfNullable(a: Long?, b: Long?) = if (a == null) b else if (b == null) a else maxOf(a, b)
}

/** Sort key: IPv4 hosts numerically first, IPv6-only hosts after. */
fun hostSortKey(h: Host): Long =
    h.ip4?.split('.')?.fold(0L) { acc, part -> (acc shl 8) or (part.toLongOrNull() ?: 0L) }
        ?: Long.MAX_VALUE
