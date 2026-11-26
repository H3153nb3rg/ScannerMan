package com.scannerman.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** A device the user has named. Matched to scan results by MAC, IPv4 or any IPv6 address. */
data class KnownDevice(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val notes: String = "",
    val mac: String? = null,
    val ip4: String? = null,
    val ip6: List<String> = emptyList(),
    /** Manual type override; null = use the auto-guess. */
    val type: DeviceType? = null,
) {
    fun matches(h: Host): Boolean =
        (mac != null && h.mac.equals(mac, ignoreCase = true)) ||
            (ip4 != null && h.ip4 == ip4 && (mac == null || h.mac == null)) ||
            ip6.any { a -> h.ip6.any { it.equals(a, ignoreCase = true) } }

    /** Adds the identifiers of [h] so that it will match in future scans. */
    fun learn(h: Host) = copy(
        mac = mac ?: h.mac,
        ip4 = ip4 ?: h.ip4,
        ip6 = (ip6 + h.ip6).distinct(),
    )
}

class KnownDeviceStore(context: Context) {
    private val file = File(context.filesDir, "known_devices.json")
    private val _flow = MutableStateFlow(load())
    val flow: StateFlow<List<KnownDevice>> = _flow
    val value: List<KnownDevice> get() = _flow.value

    fun find(h: Host): KnownDevice? = _flow.value.firstOrNull { it.matches(h) }

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
        val byDevice = LinkedHashMap<String, Host>()
        val rest = ArrayList<Host>()
        for (h in hosts) {
            val d = known.firstOrNull { it.matches(h) }
            if (d == null) { rest += h; continue }
            val existing = byDevice[d.id]
            // Prefer the record that has an IPv4 address as the base.
            byDevice[d.id] = when {
                existing == null -> h
                existing.ip4 == null && h.ip4 != null -> h.absorb(existing)
                else -> existing.absorb(h)
            }
        }
        return byDevice.values.toList() + rest
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
            )
        }
        file.writeText(arr.toString())
        _flow.value = list
    }

    private fun load(): List<KnownDevice> = runCatching {
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val v6 = o.optJSONArray("ip6")
            KnownDevice(
                id = o.getString("id"),
                name = o.getString("name"),
                notes = o.optString("notes"),
                mac = o.optNullableString("mac"),
                ip4 = o.optNullableString("ip4"),
                ip6 = v6?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
                type = o.optNullableString("type")?.let { t -> runCatching { DeviceType.valueOf(t) }.getOrNull() },
            )
        }
    }.getOrDefault(emptyList())
}

internal fun JSONObject.optNullableString(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }
