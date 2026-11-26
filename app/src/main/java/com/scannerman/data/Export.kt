package com.scannerman.data

import com.scannerman.net.Ports
import java.text.DateFormat
import java.util.Date

object Export {

    private fun fmt(t: Long?): String =
        t?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) } ?: "never"

    private fun avail(a: Avail, last: Long?) = when (a) {
        Avail.UP -> "up"
        Avail.DOWN -> "down (last seen ${fmt(last)})"
        Avail.UNKNOWN -> "n/a"
    }

    private fun typeOf(h: Host, known: KnownDevice?) = known?.type ?: DeviceGuesser.guess(h).type

    fun hostText(h: Host, known: KnownDevice? = null): String = buildString {
        append("[${h.status}] ")
        known?.let { append("${it.name} – ") }
        append(h.address)
        h.hostname?.let { append(" ($it)") }
        if (h.isSelf) append(" – this device")
        if (h.isGateway) append(" – gateway")
        appendLine()
        appendLine("  Type: ${typeOf(h, known).label}" + (h.model?.let { " ($it)" } ?: ""))
        if (h.services.isNotEmpty()) appendLine("  Services: ${h.services.joinToString(", ")}")
        appendLine("  IPv4: ${h.ip4 ?: "—"}  [${avail(h.v4, h.lastSeen4)}]")
        appendLine("  IPv6: ${h.ip6.joinToString(", ").ifEmpty { "—" }}  [${avail(h.v6, h.lastSeen6)}]")
        appendLine("  MAC:  ${h.mac ?: "unknown"}")
        h.ipChanges.forEach { appendLine("  IP change: ${it.from} → ${it.to} (${fmt(it.at)}, by ${it.via.ifEmpty { "address" }})") }
        if (h.openPorts.isNotEmpty()) appendLine("  Open v4: " + h.openPorts.joinToString(", ") { Ports.label(it) })
        if (h.openPorts6.isNotEmpty()) appendLine("  Open v6: " + h.openPorts6.joinToString(", ") { Ports.label(it) })
        known?.notes?.takeIf { it.isNotBlank() }?.let { appendLine("  Notes: $it") }
        appendLine("  First seen: ${fmt(h.firstSeen)}, last seen: ${fmt(h.lastSeen)}")
    }

    fun listText(cidr: String, scanTime: Long?, hosts: List<Host>, knownFor: (Host) -> KnownDevice?): String =
        buildString {
            appendLine("ScannerMan – $cidr" + (scanTime?.let { " – ${fmt(it)}" } ?: ""))
            appendLine(
                "${hosts.count { it.status != HostStatus.OFFLINE }} online " +
                    "(v4 ${hosts.count { it.v4 == Avail.UP }}, v6 ${hosts.count { it.v6 == Avail.UP }}), " +
                    "${hosts.count { it.status == HostStatus.OFFLINE }} offline"
            )
            appendLine()
            hosts.forEach { append(hostText(it, knownFor(it))); appendLine() }
        }

    fun csv(hosts: List<Host>, knownFor: (Host) -> KnownDevice?): String = buildString {
        appendLine("status,name,type,ipv4,ipv4_state,ipv4_last_seen,ipv6,ipv6_state,ipv6_last_seen,hostname,mac,ports_v4,ports_v6,first_seen,last_seen,self,gateway")
        hosts.forEach { h ->
            appendLine(
                listOf(
                    h.status.name, knownFor(h)?.name.orEmpty(), typeOf(h, knownFor(h)).label,
                    h.ip4.orEmpty(), h.v4.name, fmt(h.lastSeen4),
                    h.ip6.joinToString(" "), h.v6.name, fmt(h.lastSeen6),
                    h.hostname.orEmpty(), h.mac.orEmpty(),
                    h.openPorts.joinToString(" "), h.openPorts6.joinToString(" "),
                    fmt(h.firstSeen), fmt(h.lastSeen), h.isSelf.toString(), h.isGateway.toString(),
                ).joinToString(",") { "\"" + it.replace("\"", "\"\"") + "\"" }
            )
        }
    }
}
