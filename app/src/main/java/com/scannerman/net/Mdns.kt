package com.scannerman.net

import android.content.Context
import android.net.wifi.WifiManager
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * mDNS / DNS-SD (Bonjour) discovery. Works for unprivileged apps (unlike the neighbour table
 * or multicast ping) and is the main source of IPv6 addresses, since responders publish
 * A and AAAA records for the same hostname together. Also yields model names and services.
 */
object Mdns {

    class Device(val hostname: String?) {
        val v4 = linkedSetOf<String>()
        val v6 = linkedSetOf<String>()
        /** Addresses we actually received packets from (proves reachability). */
        val sources = linkedSetOf<String>()
        val services = sortedSetOf<String>()
        val txt = LinkedHashMap<String, String>()
        var mac: String? = null

        val model: String?
            get() = listOf("md", "model", "am", "rpmd", "ty", "usb_mdl", "product")
                .firstNotNullOfOrNull { k -> txt[k]?.takeIf { it.isNotBlank() } }
        val friendlyName: String? get() = txt["fn"]?.takeIf { it.isNotBlank() }
    }

    private val SERVICE_TYPES = listOf(
        "_services._dns-sd._udp", "_workstation._tcp", "_device-info._tcp", "_http._tcp", "_https._tcp",
        "_ssh._tcp", "_sftp-ssh._tcp", "_smb._tcp", "_afpovertcp._tcp", "_adisk._tcp", "_rfb._tcp",
        "_ipp._tcp", "_ipps._tcp", "_printer._tcp", "_pdl-datastream._tcp", "_scanner._tcp", "_uscan._tcp",
        "_airplay._tcp", "_raop._tcp", "_googlecast._tcp", "_spotify-connect._tcp", "_sonos._tcp",
        "_companion-link._tcp", "_apple-mobdev2._tcp", "_homekit._tcp", "_hap._tcp", "_matter._tcp",
        "_matterc._udp", "_esphomelib._tcp", "_hue._tcp", "_shelly._tcp", "_androidtvremote2._tcp",
        "_amzn-wplay._tcp", "_home-assistant._tcp", "_mqtt._tcp", "_plexmediasvr._tcp", "_daap._tcp",
        "_touch-able._tcp", "_sleep-proxy._udp", "_nvstream._tcp", "_ewelink._tcp", "_miio._udp",
    ).map { "$it.local" }

    private const val T_A = 1
    private const val T_PTR = 12
    private const val T_TXT = 16
    private const val T_AAAA = 28
    private const val T_SRV = 33
    private const val T_ANY = 255

    private val MAC_RE = Regex("""\b([0-9a-fA-F]{2}[:-]){5}[0-9a-fA-F]{2}\b""")

    /** Sends DNS-SD queries on [iface] over IPv4 and IPv6 and collects answers for [durationMs]. */
    fun discover(context: Context, iface: String, durationMs: Long = 4000): List<Device> {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi?.createMulticastLock("scannerman-mdns")?.apply { setReferenceCounted(false) }
        val state = State()
        try {
            lock?.acquire()
            val nif = runCatching { NetworkInterface.getByName(iface) }.getOrNull()
            MulticastSocket(0).use { sock ->
                if (nif != null) runCatching { sock.networkInterface = nif }
                sock.timeToLive = 255
                sock.soTimeout = 200
                val targets = buildList {
                    add(InetSocketAddress(InetAddress.getByName("224.0.0.251"), 5353))
                    if (nif != null) runCatching {
                        add(InetSocketAddress(Inet6Address.getByAddress(null, InetAddress.getByName("ff02::fb").address, nif), 5353))
                    }
                }
                fun send(questions: List<Pair<String, Int>>) {
                    questions.chunked(18).forEach { chunk ->
                        val bytes = buildQuery(chunk)
                        targets.forEach { t -> runCatching { sock.send(DatagramPacket(bytes, bytes.size, t)) } }
                    }
                }

                val start = System.currentTimeMillis()
                send(SERVICE_TYPES.map { it to T_PTR })
                var followUpSent = false
                var resent = false
                val buf = ByteArray(9000)
                while (System.currentTimeMillis() - start < durationMs) {
                    val elapsed = System.currentTimeMillis() - start
                    if (!resent && elapsed > 900) {
                        // Second round: newly enumerated service types + repeat (UDP can drop).
                        send((SERVICE_TYPES + state.serviceTypes).distinct().map { it to T_PTR })
                        resent = true
                    }
                    if (!followUpSent && elapsed > durationMs / 2) {
                        // Ask for addresses / SRV of anything we heard about but lack details for.
                        val q = state.missingHosts().flatMap { listOf(it to T_A, it to T_AAAA) } +
                            state.missingSrv().map { it to T_ANY }
                        if (q.isNotEmpty()) send(q.take(60))
                        followUpSent = true
                    }
                    val p = DatagramPacket(buf, buf.size)
                    try {
                        sock.receive(p)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    runCatching { state.ingest(buf.copyOf(p.length), p.address) }
                }
            }
        } catch (_: Exception) {
            // Multicast not available on this network: return whatever we have.
        } finally {
            runCatching { lock?.release() }
        }
        return state.devices()
    }

    // ---- Response aggregation ------------------------------------------------------------

    private class State {
        val a = HashMap<String, MutableSet<String>>()        // host -> v4
        val aaaa = HashMap<String, MutableSet<String>>()     // host -> v6
        val srv = HashMap<String, String>()                  // instance -> host
        val ptr = HashMap<String, MutableSet<String>>()      // service type -> instances
        val txt = HashMap<String, MutableMap<String, String>>() // instance -> kv
        val serviceTypes = linkedSetOf<String>()
        val hostSources = HashMap<String, MutableSet<String>>()
        val anonymousSources = linkedSetOf<String>()

        fun ingest(pkt: ByteArray, src: InetAddress) {
            val r = Reader(pkt)
            r.u16(); val flags = r.u16()
            if (flags and 0x8000 == 0) return // a query, not a response
            val qd = r.u16(); val an = r.u16(); val ns = r.u16(); val ar = r.u16()
            repeat(qd) { r.name(); r.u16(); r.u16() }
            val hostsInPacket = linkedSetOf<String>()
            repeat(an + ns + ar) {
                val name = r.name().lowercase()
                val type = r.u16(); r.u16(); r.u32()
                val len = r.u16()
                val end = r.pos + len
                when (type) {
                    T_A -> if (len == 4) {
                        a.getOrPut(name) { linkedSetOf() } += InetAddress.getByAddress(r.bytes(4)).hostAddress!!
                        hostsInPacket += name
                    }
                    T_AAAA -> if (len == 16) {
                        aaaa.getOrPut(name) { linkedSetOf() } += Neighbors.normalize6(InetAddress.getByAddress(r.bytes(16)).hostAddress!!)
                        hostsInPacket += name
                    }
                    T_PTR -> {
                        val target = r.name().lowercase()
                        if (name == "_services._dns-sd._udp.local") serviceTypes += target
                        else ptr.getOrPut(name) { linkedSetOf() } += target
                    }
                    T_SRV -> {
                        r.u16(); r.u16(); r.u16()
                        val host = r.name().lowercase()
                        srv[name] = host
                        hostsInPacket += host
                    }
                    T_TXT -> {
                        val kv = txt.getOrPut(name) { LinkedHashMap() }
                        while (r.pos < end) {
                            val l = r.u8()
                            val s = String(r.bytes(l), Charsets.UTF_8)
                            val i = s.indexOf('=')
                            if (i > 0) kv[s.substring(0, i).lowercase()] = s.substring(i + 1)
                        }
                    }
                }
                r.pos = end
            }
            val s = if (src is Inet6Address) Neighbors.normalize6(src.hostAddress!!) else src.hostAddress!!
            if (hostsInPacket.size == 1) hostSources.getOrPut(hostsInPacket.first()) { linkedSetOf() } += s
            else if (hostsInPacket.isEmpty()) anonymousSources += s
        }

        fun missingHosts() = srv.values.filter { it !in a && it !in aaaa }.distinct()
        fun missingSrv() = ptr.values.flatten().filter { it !in srv }.distinct()

        fun devices(): List<Device> {
            val byHost = LinkedHashMap<String, Device>()
            fun dev(host: String) = byHost.getOrPut(host) { Device(host.removeSuffix(".local").removeSuffix(".")) }
            (a.keys + aaaa.keys + srv.values + hostSources.keys).forEach { h ->
                val d = dev(h)
                a[h]?.let { d.v4 += it }
                aaaa[h]?.let { d.v6 += it }
                hostSources[h]?.forEach { s -> d.sources += s; if (':' in s) d.v6 += s else d.v4 += s }
            }
            // Attach services + TXT to the host that serves them.
            for ((type, instances) in ptr) for (inst in instances) {
                val host = srv[inst] ?: continue
                val d = dev(host)
                d.services += type.removeSuffix(".local").removeSuffix("._tcp").removeSuffix("._udp")
                txt[inst]?.let { d.txt.putAll(it) }
                if (d.mac == null) d.mac = MAC_RE.find(inst)?.value?.replace('-', ':')?.lowercase()
            }
            byHost.values.forEach { d ->
                if (d.mac == null) {
                    d.mac = d.txt.values.firstNotNullOfOrNull { MAC_RE.find(it)?.value }?.replace('-', ':')?.lowercase()
                        ?: d.v6.firstNotNullOfOrNull { Neighbors.macFromEui64(it) }
                }
            }
            // Responders whose packets didn't name a host – keep their address anyway.
            val known = byHost.values.flatMap { it.v4 + it.v6 }.toSet()
            val anon = (anonymousSources - known).map { s ->
                Device(null).apply { sources += s; if (':' in s) v6 += s else v4 += s }
            }
            return byHost.values.filter { it.v4.isNotEmpty() || it.v6.isNotEmpty() } + anon
        }
    }

    // ---- DNS wire format -----------------------------------------------------------------

    private fun buildQuery(questions: List<Pair<String, Int>>): ByteArray {
        val out = ByteArrayOutputStream()
        fun u16(v: Int) { out.write(v shr 8 and 0xff); out.write(v and 0xff) }
        u16(0); u16(0); u16(questions.size); u16(0); u16(0); u16(0)
        for ((name, type) in questions) {
            name.trimEnd('.').split('.').forEach { label ->
                val b = label.toByteArray(Charsets.UTF_8)
                out.write(b.size); out.write(b)
            }
            out.write(0)
            u16(type)
            u16(0x8001) // class IN + "unicast response" bit
        }
        return out.toByteArray()
    }

    private class Reader(val b: ByteArray) {
        var pos = 0
        fun u8(): Int = b[pos++].toInt() and 0xff
        fun u16(): Int = (u8() shl 8) or u8()
        fun u32(): Long = (u16().toLong() shl 16) or u16().toLong()
        fun bytes(n: Int): ByteArray = b.copyOfRange(pos, pos + n).also { pos += n }

        fun name(): String {
            val labels = ArrayList<String>()
            var p = pos
            var jumped = false
            var hops = 0
            while (true) {
                val len = b[p].toInt() and 0xff
                when {
                    len == 0 -> { p++; break }
                    len and 0xc0 == 0xc0 -> {
                        val target = ((len and 0x3f) shl 8) or (b[p + 1].toInt() and 0xff)
                        if (!jumped) pos = p + 2
                        jumped = true
                        p = target
                        if (++hops > 32) break
                    }
                    else -> {
                        labels += String(b, p + 1, len, Charsets.UTF_8)
                        p += len + 1
                    }
                }
            }
            if (!jumped) pos = p
            return labels.joinToString(".")
        }
    }
}
