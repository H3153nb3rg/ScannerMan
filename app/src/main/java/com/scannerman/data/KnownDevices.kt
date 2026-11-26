package com.scannerman.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * A device the user has named. Linked to scan results by the history ids of the hosts it
 * was saved from ([uids], which follow the device across IP changes), and otherwise by
 * [Identity] scoring of MAC, IPv4, IPv6, names and model.
 */
data class KnownDevice(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val notes: String = "",
    val mac: String? = null,
    /** Last IPv4 address it was seen at (kept up to date after every scan). */
    val ip4: String? = null,
    val ip6: List<String> = emptyList(),
    /** Manual type override; null = use the auto-guess. */
    val type: DeviceType? = null,
    val uids: List<String> = emptyList(),
    val names: List<String> = emptyList(),
    val model: String? = null,
) {
    fun matchOf(h: Host, ambiguous: Set<String> = emptySet()): Identity.Match? = when {
        h.uid in uids -> Identity.Match(1000, "linked", strong = true, sameIp4 = h.ip4 == ip4)
        else -> Identity.score(Identity.print(this), Identity.print(h), ambiguous)
            .takeIf { it.ok && !it.fingerprintOnly }
    }

    /** Adds / refreshes the identifiers of [h] so that it keeps matching in future scans. */
    fun learn(h: Host) = copy(
        mac = h.mac ?: mac,
        ip4 = if (h.v4 == Avail.UP || ip4 == null) h.ip4 ?: ip4 else ip4,
        ip6 = (h.ip6 + ip6).distinct().take(MAX_IP6),
        uids = (listOf(h.uid) + uids).distinct().take(MAX_UIDS),
        names = Identity.mergeNames(h.names, names),
        model = h.model ?: model,
    )

    companion object {
        private const val MAX_IP6 = 16
        private const val MAX_UIDS = 8
    }
}

class KnownDeviceStore(context: Context) {
    private val file = File(context.filesDir, "known_devices.json")
    private val _flow = MutableStateFlow(load())
    val flow: StateFlow<List<KnownDevice>> = _flow
    val value: List<KnownDevice> get() = _flow.value

    fun upsert(d: KnownDevice) {
        val list = _flow.value.filter { it.id != d.id } + d
        save(list.sortedBy { it.name.lowercase() })
    }

    fun delete(id: String) = save(_flow.value.filter { it.id != id })

    /**
     * Merges hosts that belong to the same known device (e.g. a phone's IPv4 entry and
     * an unmatched IPv6-only entry that the user linked to it).
     */
    fun combine(hosts: List<Host>): List<Host> {
        val known = _flow.value
        if (known.isEmpty()) return hosts
        val owner = assign(known, hosts)
        val byDevice = LinkedHashMap<String, Host>()
        val rest = ArrayList<Host>()
        for (h in hosts) {
            val d = owner[h.uid]
            if (d == null) { rest += h; continue }
            val existing = byDevice[d.id]
            // Prefer the record that is reachable over IPv4 / has an IPv4 address as the base.
            byDevice[d.id] = when {
                existing == null -> h
                existing.v4 != Avail.UP && h.v4 == Avail.UP -> h.absorb(existing)
                existing.ip4 == null && h.ip4 != null -> h.absorb(existing)
                else -> existing.absorb(h)
            }
        }
        return byDevice.values.toList() + rest
    }

    /**
     * After a scan: refresh the stored identifiers (current IPv4, MAC, names, history id)
     * of every known device that was found by a reliable match, so a stale IPv4 address
     * can't later be mistaken for the device.
     */
    fun refresh(hosts: List<Host>) {
        val known = _flow.value
        if (known.isEmpty()) return
        val amb = Identity.ambiguousNames(hosts)
        var changed = false
        val updated = known.map { d ->
            val h = hosts.filter { it.isOnline }
                .mapNotNull { h -> d.matchOf(h, amb)?.takeIf { it.strong }?.let { h to it } }
                .maxByOrNull { it.second.score }?.first
            val n = h?.let(d::learn) ?: d
            if (n != d) changed = true
            n
        }
        if (changed) save(updated)
    }

    companion object {
        /**
         * Which known device each host (by uid) belongs to. Strong matches (link, MAC,
         * IPv6, name) win; a device matched only by its last IPv4 address takes a host only
         * if it wasn't found elsewhere, and each such address goes to one device.
         */
        fun assign(known: List<KnownDevice>, hosts: List<Host>): Map<String, KnownDevice> {
            if (known.isEmpty() || hosts.isEmpty()) return emptyMap()
            val amb = Identity.ambiguousNames(hosts)
            val result = HashMap<String, KnownDevice>()
            val weak = ArrayList<Triple<Host, KnownDevice, Int>>()
            for (h in hosts) {
                val best = known.mapNotNull { d -> d.matchOf(h, amb)?.let { d to it } }.maxByOrNull { it.second.score }
                    ?: continue
                if (best.second.strong) result[h.uid] = best.first else weak += Triple(h, best.first, best.second.score)
            }
            val found = result.values.map { it.id }.toMutableSet()
            weak.sortedWith(compareByDescending<Triple<Host, KnownDevice, Int>> { it.third }
                .thenByDescending { it.first.isOnline })
                .forEach { (h, d, _) ->
                    if (d.id !in found) { result[h.uid] = d; found += d.id }
                }
            return result
        }
    }

    private fun save(list: List<KnownDevice>) {
        val arr = JSONArray()
        list.forEach { d ->
            arr.put(
                JSONObject()
                    .put("id", d.id).put("name", d.name).put("notes", d.notes)
                    .put("mac", d.mac ?: JSONObject.NULL)
                    .put("ip4", d.ip4 ?: JSONObject.NULL)
                    .put("ip6", JSONArray(d.ip6))
                    .put("type", d.type?.name ?: JSONObject.NULL)
                    .put("uids", JSONArray(d.uids))
                    .put("names", JSONArray(d.names))
                    .put("model", d.model ?: JSONObject.NULL)
            )
        }
        file.writeText(arr.toString())
        _flow.value = list
    }

    private fun load(): List<KnownDevice> = runCatching {
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            KnownDevice(
                id = o.getString("id"),
                name = o.getString("name"),
                notes = o.optString("notes"),
                mac = o.optNullableString("mac"),
                ip4 = o.optNullableString("ip4"),
                ip6 = o.strings("ip6"),
                type = o.optNullableString("type")?.let { t -> runCatching { DeviceType.valueOf(t) }.getOrNull() },
                uids = o.strings("uids"),
                names = o.strings("names"),
                model = o.optNullableString("model"),
            )
        }
    }.getOrDefault(emptyList())
}

internal fun JSONObject.optNullableString(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }

internal fun JSONObject.strings(name: String): List<String> =
    optJSONArray(name)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
