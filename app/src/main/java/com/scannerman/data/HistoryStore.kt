package com.scannerman.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

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

    /**
     * Merge a fresh scan with what we knew before:
     *  - seen now & before -> ONLINE (keeps firstSeen)
     *  - seen now only     -> NEW (or ONLINE on the very first scan)
     *  - seen before only  -> OFFLINE (keeps last known data)
     * Per-protocol last-seen times are kept separately.
     */
    fun merge(previous: List<Host>, current: List<Host>, now: Long): List<Host> {
        val unmatched = previous.toMutableList()
        val firstRun = previous.isEmpty()
        val merged = current.map { cur ->
            val matches = unmatched.filter { it.sameDeviceAs(cur) }
            unmatched -= matches.toSet()
            val prev = matches.firstOrNull()
            cur.copy(
                ip4 = cur.ip4 ?: matches.firstNotNullOfOrNull { it.ip4 },
                mac = cur.mac ?: matches.firstNotNullOfOrNull { it.mac },
                hostname = cur.hostname ?: matches.firstNotNullOfOrNull { it.hostname },
                model = cur.model ?: matches.firstNotNullOfOrNull { it.model },
                services = cur.services.ifEmpty { matches.flatMap { it.services }.distinct() },
                ip6 = cur.ip6.ifEmpty { matches.flatMap { it.ip6 }.distinct() },
                firstSeen = matches.minOfOrNull { it.firstSeen } ?: now,
                lastSeen = now,
                lastSeen4 = if (cur.v4 == Avail.UP) now else matches.mapNotNull { it.lastSeen4 }.maxOrNull(),
                lastSeen6 = if (cur.v6 == Avail.UP) now else matches.mapNotNull { it.lastSeen6 }.maxOrNull(),
                status = if (prev != null || firstRun) HostStatus.ONLINE else HostStatus.NEW,
            )
        }
        val offline = unmatched.filter { !it.isSelf }.map {
            it.copy(
                status = HostStatus.OFFLINE,
                v4 = if (it.ip4 != null) Avail.DOWN else Avail.UNKNOWN,
                v6 = if (it.ip6.isNotEmpty()) Avail.DOWN else Avail.UNKNOWN,
            )
        }
        return merged + offline
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

    private fun fromJson(o: JSONObject): Host {
        fun ints(name: String) = o.optJSONArray(name)?.let { a -> (0 until a.length()).map { a.getInt(it) } }.orEmpty()
        fun avail(name: String) = runCatching { Avail.valueOf(o.getString(name)) }.getOrDefault(Avail.UNKNOWN)
        fun long(name: String) = if (o.isNull(name) || !o.has(name)) null else o.optLong(name)
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
            // Status as of the last run; recomputed by merge() on the next scan.
            status = runCatching { HostStatus.valueOf(o.optString("status")) }
                .getOrDefault(HostStatus.ONLINE),
        )
    }
}
