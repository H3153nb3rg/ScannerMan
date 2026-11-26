package com.scannerman.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Persists every host ever seen on a network (one JSON file per subnet), so the next
 * scan can tell which hosts are new and which have disappeared, per IPv4 and IPv6.
 */
class HistoryStore(context: Context) {

    data class Snapshot(val lastScan: Long?, val hosts: List<Host>)

    private val dir = File(context.filesDir, "history").apply { mkdirs() }

    /** [key] is a network profile id ("192.168.0.0/24" for the first profile of a subnet). */
    private fun file(key: String) = File(dir, key.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json")

    fun load(cidr: String): Snapshot {
        val f = file(cidr)
        if (!f.exists()) return Snapshot(null, emptyList())
        return runCatching {
            val root = JSONObject(f.readText())
            val arr = root.getJSONArray("hosts")
            Snapshot(
                lastScan = root.optLong("lastScan").takeIf { it > 0 },
                hosts = (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) },
            )
        }.getOrDefault(Snapshot(null, emptyList()))
    }

    fun save(cidr: String, lastScan: Long, hosts: List<Host>) {
        val root = JSONObject()
            .put("lastScan", lastScan)
            .put("hosts", JSONArray().apply { hosts.forEach { put(toJson(it)) } })
        val tmp = File(dir, "${file(cidr).name}.tmp")
        tmp.writeText(root.toString())
        tmp.renameTo(file(cidr))
    }

    fun clear(cidr: String) {
        file(cidr).delete()
    }

    private fun toJson(h: Host) = JSONObject()
        .put("ip4", h.ip4 ?: JSONObject.NULL)
        .put("mac", h.mac ?: JSONObject.NULL)
        .put("ip6", JSONArray(h.ip6))
        .put("hostname", h.hostname ?: JSONObject.NULL)
        .put("model", h.model ?: JSONObject.NULL)
        .put("services", JSONArray(h.services))
        .put("ports", JSONArray(h.openPorts))
        .put("ports6", JSONArray(h.openPorts6))
        .put("v4", h.v4.name)
        .put("v6", h.v6.name)
        .put("firstSeen", h.firstSeen)
        .put("lastSeen", h.lastSeen)
        .put("lastSeen4", h.lastSeen4 ?: JSONObject.NULL)
        .put("lastSeen6", h.lastSeen6 ?: JSONObject.NULL)
        .put("self", h.isSelf)
        .put("gateway", h.isGateway)
        .put("status", h.status.name)
        .put("uid", h.uid)
        .put("names", JSONArray(h.names))
        .put("ipChanges", JSONArray().apply {
            h.ipChanges.forEach { put(JSONObject().put("from", it.from).put("to", it.to).put("at", it.at).put("via", it.via)) }
        })

    private fun fromJson(o: JSONObject): Host {
        fun ints(name: String) = o.optJSONArray(name)?.let { a -> (0 until a.length()).map { a.getInt(it) } }.orEmpty()
        fun avail(name: String) = runCatching { Avail.valueOf(o.getString(name)) }.getOrDefault(Avail.UNKNOWN)
        fun long(name: String) = if (o.isNull(name) || !o.has(name)) null else o.optLong(name)
        fun strings(name: String) = o.optJSONArray(name)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        val ip6 = o.optJSONArray("ip6")
        return Host(
            ip4 = o.optNullableString("ip4"),
            mac = o.optNullableString("mac"),
            ip6 = ip6?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
            hostname = o.optNullableString("hostname"),
            model = o.optNullableString("model"),
            services = o.optJSONArray("services")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
            openPorts = ints("ports"),
            openPorts6 = ints("ports6"),
            v4 = avail("v4"),
            v6 = avail("v6"),
            firstSeen = o.optLong("firstSeen"),
            lastSeen = o.optLong("lastSeen"),
            lastSeen4 = long("lastSeen4"),
            lastSeen6 = long("lastSeen6"),
            isSelf = o.optBoolean("self"),
            isGateway = o.optBoolean("gateway"),
            names = strings("names"),
            uid = o.optNullableString("uid") ?: UUID.randomUUID().toString(),
            ipChanges = o.optJSONArray("ipChanges")?.let { a ->
                (0 until a.length()).map { a.getJSONObject(it) }.map {
                    IpChange(it.getString("from"), it.getString("to"), it.getLong("at"), it.optString("via"))
                }
            }.orEmpty(),
            // Status as of the last run; recomputed by merge() on the next scan.
            status = runCatching { HostStatus.valueOf(o.optString("status")) }
                .getOrDefault(HostStatus.ONLINE),
        )
    }

    companion object {
        /**
         * Merge a fresh scan with what we knew before:
         *  - seen now & before -> ONLINE (keeps uid, firstSeen; logs an IPv4 change)
         *  - seen now only     -> NEW (or ONLINE on the very first scan)
         *  - seen before only  -> OFFLINE (keeps last known data)
         * Devices are recognised with [Identity] scoring, so a new DHCP address doesn't turn a
         * device into "offline + new". Per-protocol last-seen times are kept separately.
         */
        fun merge(previous: List<Host>, scanned: List<Host>, now: Long): List<Host> {
            val current = collapse(scanned)
            val firstRun = previous.isEmpty()
            val prev = previous.filter { !it.isSelf } + previous.filter { it.isSelf }.take(1)
            val ambiguous = Identity.ambiguousNames(current) + Identity.ambiguousNames(prev)

            val curPrints = current.map(Identity::print)
            val prevPrints = prev.map(Identity::print)
            data class Pair(val c: Int, val p: Int, val m: Identity.Match)
            val pairs = ArrayList<Pair>()
            for (c in current.indices) for (p in prev.indices) {
                val m = Identity.score(curPrints[c], prevPrints[p], ambiguous)
                if (m.ok) pairs += Pair(c, p, m)
            }

            // 1) Greedy one-to-one on identifier / same-address matches, best first.
            val primary = HashMap<Int, Pair>()
            val usedPrev = HashSet<Int>()
            pairs.filter { !it.m.fingerprintOnly }.sortedByDescending { it.m.score }.forEach { x ->
                if (x.c !in primary && x.p !in usedPrev) { primary[x.c] = x; usedPrev += x.p }
            }
            // 2) Fingerprint-only (new IP, nothing else in common): only if unambiguous both ways.
            val fp = pairs.filter { it.m.fingerprintOnly && it.c !in primary && it.p !in usedPrev }
            fp.forEach { x ->
                if (fp.count { it.c == x.c } == 1 && fp.count { it.p == x.p } == 1) {
                    primary[x.c] = x; usedPrev += x.p
                }
            }
            // 3) Leftover old records that strongly belong to a matched device (e.g. a former
            //    IPv6-only record of it) are folded in instead of showing up as offline.
            val extras = HashMap<Int, MutableList<Int>>()
            pairs.filter { it.m.strong && it.c in primary && it.p !in usedPrev }
                .sortedByDescending { it.m.score }
                .forEach { x -> if (x.p !in usedPrev) { extras.getOrPut(x.c) { ArrayList() } += x.p; usedPrev += x.p } }

            val merged = current.mapIndexed { i, cur ->
                val match = primary[i]
                val old = listOfNotNull(match?.p) + extras[i].orEmpty()
                val matches = old.map { prev[it] }
                val p = matches.firstOrNull()
                val change = if (p != null && match != null && cur.v4 == Avail.UP && cur.ip4 != null &&
                    p.ip4 != null && p.ip4 != cur.ip4
                ) IpChange(p.ip4, cur.ip4, now, match.m.via) else null
                cur.copy(
                    uid = p?.uid ?: cur.uid,
                    ip4 = cur.ip4 ?: matches.firstNotNullOfOrNull { it.ip4 },
                    mac = cur.mac ?: matches.firstNotNullOfOrNull { it.mac },
                    hostname = cur.hostname ?: matches.firstNotNullOfOrNull { it.hostname },
                    model = cur.model ?: matches.firstNotNullOfOrNull { it.model },
                    services = cur.services.ifEmpty { matches.flatMap { it.services }.distinct() },
                    ip6 = cur.ip6.ifEmpty { matches.flatMap { it.ip6 }.distinct() },
                    names = Identity.mergeNames(cur.names, matches.flatMap { it.names }),
                    ipChanges = (listOfNotNull(change) + matches.flatMap { it.ipChanges })
                        .distinct().sortedByDescending { it.at }.take(Host.MAX_IP_CHANGES),
                    firstSeen = matches.minOfOrNull { it.firstSeen } ?: now,
                    lastSeen = now,
                    lastSeen4 = if (cur.v4 == Avail.UP) now else matches.mapNotNull { it.lastSeen4 }.maxOrNull(),
                    lastSeen6 = if (cur.v6 == Avail.UP) now else matches.mapNotNull { it.lastSeen6 }.maxOrNull(),
                    status = if (p != null || firstRun) HostStatus.ONLINE else HostStatus.NEW,
                )
            }
            val offline = prev.indices.filter { it !in usedPrev }.map { prev[it] }.filter { !it.isSelf }.map {
                it.copy(
                    status = HostStatus.OFFLINE,
                    v4 = if (it.ip4 != null) Avail.DOWN else Avail.UNKNOWN,
                    v6 = if (it.ip6.isNotEmpty()) Avail.DOWN else Avail.UNKNOWN,
                )
            }
            return merged + offline
        }

        /**
         * Within one scan, records that share a MAC or IPv6 address are the same device
         * (e.g. an IPv6-only record of a host whose IPv4 changed). Two records that are both
         * reachable over IPv4 at different addresses are never combined.
         */
        private fun collapse(hosts: List<Host>): List<Host> {
            val out = ArrayList<Host>()
            for (h in hosts) {
                val i = out.indexOfFirst { o ->
                    !(o.v4 == Avail.UP && h.v4 == Avail.UP && o.ip4 != h.ip4) &&
                        ((o.mac != null && o.mac.equals(h.mac, ignoreCase = true)) ||
                            o.ip6.any { a -> h.ip6.any { it.equals(a, ignoreCase = true) } })
                }
                if (i < 0) { out += h; continue }
                val o = out[i]
                out[i] = if (o.v4 != Avail.UP && h.v4 == Avail.UP) h.absorb(o) else o.absorb(h)
            }
            return out
        }
    }
}
