package com.scannerman.net

import java.io.File
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Best-effort access to the kernel neighbour (ARP / NDP) table and IPv6 all-nodes ping.
 *
 * NOTE: Android increasingly locks this down. /proc/net/arp is unreadable for apps on
 * Android 10+, and `ip neigh` is blocked for apps targeting newer SDKs on many builds.
 * Everything here therefore fails soft and returns empty results; MAC/IPv6 columns are
 * filled in whenever the device allows it.
 */
object Neighbors {

    data class Entry(val ip: String, val mac: String, val isV6: Boolean)

    private val NEIGH_RE =
        Regex("""^(\S+)\s+dev\s+(\S+)\s+lladdr\s+([0-9a-fA-F:]{17})\b(.*)$""")
    private val PING_FROM_RE = Regex("""from ([0-9a-fA-F:]+?)(?:%\S+?)?:\s""")

    fun read(): List<Entry> =
        (readIpNeigh() + readProcArp())
            .filter { it.mac != "00:00:00:00:00:00" }
            .distinctBy { it.ip }

    private fun readIpNeigh(): List<Entry> =
        run("ip", "neigh", "show").mapNotNull { line ->
            val m = NEIGH_RE.find(line.trim()) ?: return@mapNotNull null
            val state = m.groupValues[4]
            if ("FAILED" in state || "INCOMPLETE" in state) return@mapNotNull null
            val ip = m.groupValues[1]
            Entry(ip, m.groupValues[3].lowercase(), ':' in ip)
        }

    private fun readProcArp(): List<Entry> = runCatching {
        File("/proc/net/arp").readLines().drop(1).mapNotNull { line ->
            val c = line.trim().split(Regex("\\s+"))
            if (c.size < 4 || c[2] == "0x0") null else Entry(c[0], c[3].lowercase(), false)
        }
    }.getOrDefault(emptyList())

    /**
     * Pings ff02::1 (all IPv6 nodes on the link). Responders populate the neighbour
     * cache and are returned as link-local addresses.
     */
    fun pingAllNodesV6(iface: String): Set<String> {
        val cmds = listOf(
            arrayOf("ping6", "-c", "2", "-w", "3", "-I", iface, "ff02::1"),
            arrayOf("ping", "-6", "-c", "2", "-w", "3", "-I", iface, "ff02::1"),
            arrayOf("ping6", "-c", "2", "-w", "3", "ff02::1%$iface"),
        )
        for (cmd in cmds) {
            val found = run(*cmd).mapNotNull { PING_FROM_RE.find(it)?.groupValues?.get(1) }.toSet()
            if (found.isNotEmpty()) return found
        }
        return emptySet()
    }

    /** Canonical lower-case compressed form without zone, so addresses compare reliably. */
    fun normalize6(addr: String): String {
        val bare = addr.substringBefore('%')
        return runCatching { InetAddress.getByName(bare).hostAddress!!.substringBefore('%').lowercase() }
            .getOrDefault(bare.lowercase())
    }

    /** Recover a MAC from an EUI-64 style IPv6 interface ID (xx:xx:xx:ff:fe:xx:xx:xx). */
    fun macFromEui64(addr: String): String? {
        val b = runCatching { InetAddress.getByName(addr.substringBefore('%')).address }
            .getOrNull() ?: return null
        if (b.size != 16 || b[11] != 0xff.toByte() || b[12] != 0xfe.toByte()) return null
        val mac = byteArrayOf((b[8].toInt() xor 0x02).toByte(), b[9], b[10], b[13], b[14], b[15])
        return mac.joinToString(":") { "%02x".format(it) }
    }

    private fun run(vararg cmd: String): List<String> = runCatching {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readLines()
        if (!p.waitFor(6, TimeUnit.SECONDS)) p.destroy()
        out
    }.getOrDefault(emptyList())
}
