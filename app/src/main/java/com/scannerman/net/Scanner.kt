package com.scannerman.net

import android.content.Context
import com.scannerman.data.Avail
import com.scannerman.data.Host
import com.scannerman.data.Settings
import com.scannerman.data.V6PortScan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

enum class Phase(val label: String) {
    DISCOVERY("Discovering IPv4 hosts"),
    NEIGHBORS("Collecting mDNS / neighbour info"),
    IPV6("Probing IPv6 addresses"),
    PORTS("Scanning ports"),
    NAMES("Resolving hostnames"),
}

class Scanner(private val settings: Settings, private val context: Context) {

    /** Blocking socket work runs here; elastic beyond Dispatchers.IO's default 64 threads. */
    private val io = Dispatchers.IO.limitedParallelism(192)

    private enum class Probe { OPEN, REFUSED, NO_ANSWER }

    /** One device being assembled during a scan. */
    private class Work(
        val ip4: String?,
        @Volatile var mac: String?,
        val ip6: MutableSet<String>,
        val alive4: Boolean,
        val mdns: Mdns.Device? = null,
    ) {
        val ports4: MutableSet<Int> = ConcurrentHashMap.newKeySet()
        val ports6: MutableSet<Int> = ConcurrentHashMap.newKeySet()
        @Volatile var name: String? = null
    }

    /**
     * Full scan of [net]. [previous] (history) supplies IPv6 addresses to re-probe.
     * [onProgress] gets (phase, done, total) from worker threads; [onHostFound] is called
     * for every live host as soon as it is detected.
     */
    suspend fun scan(
        net: LocalNetwork,
        previous: List<Host>,
        onProgress: (Phase, Int, Int) -> Unit,
        onHostFound: (Host) -> Unit,
    ): List<Host> = withContext(io) {
        val ports = Ports.parseSpec(settings.ports)
        val ownV6 = net.ip6.map(Neighbors::normalize6).toSet()

        // mDNS listens in the background while the sweep runs.
        val mdnsJob = async { Mdns.discover(context, net.iface) }

        // ---- 1. IPv4 discovery sweep ------------------------------------------------
        val targets = net.hostAddresses()
        val alive4 = ConcurrentHashMap.newKeySet<String>()
        run {
            val done = AtomicInteger()
            val gate = Semaphore(settings.parallelHosts)
            targets.map { ip ->
                async {
                    gate.withPermit {
                        if (isAlive(ip)) {
                            alive4 += ip
                            onHostFound(Host(ip4 = ip, isGateway = ip == net.gateway))
                        }
                    }
                    onProgress(Phase.DISCOVERY, done.incrementAndGet(), targets.size)
                }
            }.awaitAll()
        }

        // ---- 2. Neighbour table (MACs) + IPv6 all-nodes ping -----------------------
        onProgress(Phase.NEIGHBORS, 0, 1)
        val responders = if (settings.ipv6Enabled)
            Neighbors.pingAllNodesV6(net.iface).map(Neighbors::normalize6).toSet() - ownV6
        else emptySet()
        val neigh = Neighbors.read()
        val macByIp4 = neigh.filter { !it.isV6 }.associate { it.ip to it.mac }
        // Hosts that answered ARP but blocked all our probes still count as alive.
        macByIp4.keys.filter { net.contains(it) && it != net.ip4 && alive4.add(it) }
            .forEach { onHostFound(Host(ip4 = it, isGateway = it == net.gateway)) }

        // mDNS: devices that announced themselves are alive; they also tie v4 <-> v6.
        val mdns = mdnsJob.await().filter { d -> net.ip4 !in d.v4 && d.v6.none { it in ownV6 } }
        val mdnsByIp4 = HashMap<String, Mdns.Device>()
        mdns.forEach { d ->
            d.v4.filter { net.contains(it) }.forEach { ip ->
                mdnsByIp4[ip] = d
                if (alive4.add(ip)) onHostFound(Host(ip4 = ip, isGateway = ip == net.gateway))
            }
        }
        val mdnsV6Only = mdns.filter { d -> d.v4.none { net.contains(it) } && d.v6.isNotEmpty() }
        val proven6 = responders + mdns.flatMap { d -> d.sources.filter { ':' in it } }

        val macByIp6 = HashMap<String, String>()
        neigh.filter { it.isV6 }.forEach { macByIp6[Neighbors.normalize6(it.ip)] = it.mac }
        mdns.forEach { d -> d.mac?.let { m -> d.v6.forEach { macByIp6.putIfAbsent(it, m) } } }
        (responders + mdns.flatMap { it.v6 }).forEach { a -> Neighbors.macFromEui64(a)?.let { macByIp6.putIfAbsent(a, it) } }
        val ip6ByMac = macByIp6.entries.groupBy({ it.value }, { it.key })
        onProgress(Phase.NEIGHBORS, 1, 1)

        // ---- 3. Build device list, attach known IPv6 addresses ----------------------
        val usedPrev = HashSet<Host>()
        fun prevFor(ip4: String?, mac: String?): Host? = previous.firstOrNull { p ->
            !p.isSelf && p !in usedPrev &&
                ((mac != null && p.mac == mac) || (ip4 != null && p.ip4 == ip4 && (p.mac == null || mac == null)))
        }?.also { usedPrev += it }

        val works = alive4.map { ip ->
            val md = mdnsByIp4[ip]
            val prev = prevFor(ip, macByIp4[ip] ?: md?.mac)
            val mac = macByIp4[ip] ?: md?.mac ?: prev?.mac
            val v6 = linkedSetOf<String>()
            if (settings.ipv6Enabled) {
                mac?.let { v6 += ip6ByMac[it].orEmpty() }
                md?.let { v6 += it.v6 }
                prev?.let { v6 += it.ip6.map(Neighbors::normalize6) }
            }
            Work(ip, mac, v6, alive4 = true, mdns = md)
        }.toMutableList()

        if (settings.ipv6Enabled) {
            // ---- 4. Probe every candidate IPv6 address -----------------------------
            val remembered = previous.filter { !it.isSelf && it !in usedPrev && it.ip6.isNotEmpty() }
            val candidates = (works.flatMap { it.ip6 } +
                remembered.flatMap { p -> p.ip6.map(Neighbors::normalize6) } +
                mdnsV6Only.flatMap { it.v6 } +
                macByIp6.keys).toSet() - ownV6 - proven6
            val alive6 = ConcurrentHashMap.newKeySet<String>().apply { addAll(proven6) }
            val done = AtomicInteger()
            val gate = Semaphore(settings.parallelHosts)
            candidates.map { a ->
                async {
                    gate.withPermit { if (isAlive(net.scoped(a))) alive6 += a }
                    onProgress(Phase.IPV6, done.incrementAndGet(), candidates.size)
                }
            }.awaitAll()

            // Remembered devices that are now reachable only over IPv6.
            remembered.filter { p -> p.ip6.any { Neighbors.normalize6(it) in alive6 } }.forEach { p ->
                works += Work(p.ip4, p.mac, p.ip6.map(Neighbors::normalize6).toMutableSet(), alive4 = false)
            }
            // Devices that announced themselves over mDNS with IPv6 only.
            mdnsV6Only.forEach { d ->
                val known = works.flatMap { it.ip6 }.toSet()
                if (d.v6.none { it in known }) {
                    works += Work(null, d.mac ?: d.v6.firstNotNullOfOrNull { macByIp6[it] }, d.v6.toMutableSet(), alive4 = false, mdns = d)
                }
            }
            // Live IPv6 addresses no device claimed yet -> IPv6-only hosts (grouped by MAC).
            val claimed = works.flatMap { it.ip6 }.toSet()
            (alive6 - claimed).groupBy { macByIp6[it] ?: it }.values.forEach { addrs ->
                val mac = macByIp6[addrs.first()]
                works += Work(null, mac, addrs.toMutableSet(), alive4 = false)
            }
            // Keep only live addresses on devices where at least one answered.
            works.forEach { w ->
                if (w.ip6.any { it in alive6 }) w.ip6.retainAll(alive6.toSet())
            }
            works.forEach { w -> if (!w.alive4) onHostFound(w.toHost(net, alive6)) }

            // ---- 5. Port scan --------------------------------------------------------
            portScan(net, works, ports, onProgress) { w ->
                val v6Up = w.ip6.any { it in alive6 }
                v6Up && when (settings.v6PortScan) {
                    V6PortScan.OFF -> false
                    V6PortScan.V6_ONLY_HOSTS -> !w.alive4
                    V6PortScan.ALL -> true
                }
            }
            resolveNames(net, works, onProgress)
            works.map { it.toHost(net, alive6) } + selfHost(net)
        } else {
            portScan(net, works, ports, onProgress) { false }
            resolveNames(net, works, onProgress)
            works.map { it.toHost(net, emptySet()) } + selfHost(net)
        }
    }

    private suspend fun portScan(
        net: LocalNetwork,
        works: List<Work>,
        ports: List<Int>,
        onProgress: (Phase, Int, Int) -> Unit,
        scanV6: (Work) -> Boolean,
    ): Unit = coroutineScope {
        data class Job(val w: Work, val addr: String, val v6: Boolean)
        val jobs = buildList {
            works.forEach { w ->
                if (w.alive4 && w.ip4 != null) add(Job(w, w.ip4, false))
                if (scanV6(w)) {
                    // Prefer a global/ULA address over link-local.
                    val a = w.ip6.firstOrNull { !it.startsWith("fe80") } ?: w.ip6.first()
                    add(Job(w, net.scoped(a), true))
                }
            }
        }
        val total = jobs.size * ports.size
        val done = AtomicInteger()
        val gate = Semaphore(settings.parallelHosts * 3)
        jobs.flatMap { j ->
            ports.map { port ->
                async {
                    gate.withPermit {
                        if (probe(j.addr, port, settings.portTimeoutMs) == Probe.OPEN) {
                            (if (j.v6) j.w.ports6 else j.w.ports4) += port
                        }
                    }
                    onProgress(Phase.PORTS, done.incrementAndGet(), total)
                }
            }
        }.awaitAll()
    }

    private suspend fun resolveNames(
        net: LocalNetwork,
        works: List<Work>,
        onProgress: (Phase, Int, Int) -> Unit,
    ): Unit = coroutineScope {
        val done = AtomicInteger()
        works.map { w ->
            async {
                // NetBIOS: Windows/Samba name and (Windows) real MAC.
                val nb = if (w.alive4 && w.ip4 != null) NetBios.query(w.ip4) else null
                if (w.mac == null) w.mac = nb?.mac
                val addr = w.ip4 ?: w.ip6.firstOrNull { !it.startsWith("fe80") } ?: w.ip6.firstOrNull()?.let(net::scoped)
                val rdns = if (settings.resolveNames && addr != null) withTimeoutOrNull(2500) { reverseLookup(addr) } else null
                w.name = rdns ?: nb?.name ?: w.mdns?.friendlyName ?: w.mdns?.hostname
                onProgress(Phase.NAMES, done.incrementAndGet(), works.size)
            }
        }.awaitAll()
    }

    private fun Work.toHost(net: LocalNetwork, alive6: Set<String>) = Host(
        ip4 = ip4,
        mac = mac,
        ip6 = ip6.toList(),
        hostname = name,
        model = mdns?.model,
        services = mdns?.services?.toList().orEmpty(),
        openPorts = ports4.sorted(),
        openPorts6 = ports6.sorted(),
        v4 = when {
            alive4 -> Avail.UP
            ip4 != null -> Avail.DOWN
            else -> Avail.UNKNOWN
        },
        v6 = when {
            !settings.ipv6Enabled -> Avail.UNKNOWN
            ip6.any { it in alive6 } -> Avail.UP
            ip6.isNotEmpty() -> Avail.DOWN
            else -> Avail.UNKNOWN
        },
        isGateway = ip4 != null && ip4 == net.gateway,
    )

    private fun selfHost(net: LocalNetwork) = Host(
        ip4 = net.ip4,
        ip6 = net.ip6.map(Neighbors::normalize6),
        hostname = runCatching { InetAddress.getLocalHost().hostName }.getOrNull()
            ?.takeIf { it != "localhost" },
        v4 = Avail.UP,
        v6 = if (net.ip6.isNotEmpty()) Avail.UP else Avail.UNKNOWN,
        isSelf = true,
    )

    /** Alive = answers ping, or any discovery port is open or actively refused (RST). */
    private suspend fun isAlive(addr: String): Boolean = coroutineScope {
        val t = settings.discoveryTimeoutMs
        val icmp = async {
            runCatching { InetAddress.getByName(addr).isReachable(t) }.getOrDefault(false)
        }
        val tcp = Ports.DISCOVERY.map { p -> async { probe(addr, p, t) != Probe.NO_ANSWER } }
        (tcp + icmp).awaitAll().any { it }
    }

    private suspend fun probe(addr: String, port: Int, timeoutMs: Int): Probe {
        coroutineContext.ensureActive()
        return try {
            Socket().use { it.connect(InetSocketAddress(addr, port), timeoutMs) }
            Probe.OPEN
        } catch (e: ConnectException) {
            // RST from the target => it exists, the port is just closed.
            if (e.message?.contains("refused", ignoreCase = true) == true) Probe.REFUSED
            else Probe.NO_ANSWER
        } catch (_: IOException) {
            Probe.NO_ANSWER
        } catch (_: IllegalArgumentException) {
            Probe.NO_ANSWER
        }
    }

    private fun reverseLookup(addr: String): String? = runCatching {
        InetAddress.getByName(addr).canonicalHostName
            .takeIf { it != addr && it != addr.substringBefore('%') && ':' !in it }
    }.getOrNull()
}
