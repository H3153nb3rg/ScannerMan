package com.scannerman.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** One physical access point (radio) seen while connected to a network. */
data class AccessPoint(
    val ssid: String,
    val bssid: String,
    val frequency: Int?,
    val lastSeen: Long,
)

/**
 * A named network = subnet + the Wi-Fi SSIDs that lead into it. Host history is stored
 * per profile [id]. The first profile of a subnet uses the subnet itself as id, so history
 * from older app versions (keyed by subnet) is picked up automatically.
 */
data class NetworkProfile(
    val id: String,
    val name: String,
    val cidr: String,
    val gateway: String?,
    val ssids: List<String> = emptyList(),
    val aps: List<AccessPoint> = emptyList(),
)

sealed interface Resolution {
    data class Ok(val profile: NetworkProfile) : Resolution

    /** [ssid] is new on a subnet that already has profiles – the user decides. */
    data class Ask(
        val ssid: String,
        val bssid: String?,
        val frequency: Int?,
        val cidr: String,
        val gateway: String?,
        val candidates: List<NetworkProfile>,
    ) : Resolution
}

class NetworkProfileStore(context: Context) {
    private val file = File(context.filesDir, "networks.json")
    private val _flow = MutableStateFlow(load())
    val flow: StateFlow<List<NetworkProfile>> = _flow

    fun get(id: String) = _flow.value.firstOrNull { it.id == id }

    fun resolve(cidr: String, gateway: String?, ssid: String?, bssid: String?, freq: Int?): Resolution {
        val now = System.currentTimeMillis()
        val onSubnet = _flow.value.filter { it.cidr == cidr }

        if (ssid == null) {
            // Ethernet, or Wi-Fi name not readable: fall back to the subnet alone.
            val p = onSubnet.firstOrNull { it.ssids.isEmpty() } ?: onSubnet.firstOrNull()
                ?: save(NetworkProfile(id = cidr, name = cidr, cidr = cidr, gateway = gateway))
            return Resolution.Ok(p)
        }
        onSubnet.firstOrNull { ssid in it.ssids }?.let {
            return Resolution.Ok(save(it.withAp(ssid, bssid, freq, now).copy(gateway = gateway ?: it.gateway)))
        }
        // A profile created before the Wi-Fi name was known: adopt it silently.
        onSubnet.firstOrNull { it.ssids.isEmpty() }?.let {
            val named = it.copy(name = if (it.name == it.cidr) ssid else it.name, ssids = listOf(ssid))
            return Resolution.Ok(save(named.withAp(ssid, bssid, freq, now)))
        }
        if (onSubnet.isEmpty()) {
            val id = if (_flow.value.none { it.id == cidr }) cidr else newId(cidr)
            return Resolution.Ok(
                save(NetworkProfile(id, ssid, cidr, gateway, listOf(ssid)).withAp(ssid, bssid, freq, now))
            )
        }
        return Resolution.Ask(ssid, bssid, freq, cidr, gateway, onSubnet)
    }

    /** User said: [ask].ssid belongs to [into]. */
    fun merge(ask: Resolution.Ask, into: NetworkProfile): NetworkProfile =
        save(
            into.copy(ssids = (into.ssids + ask.ssid).distinct())
                .withAp(ask.ssid, ask.bssid, ask.frequency, System.currentTimeMillis())
        )

    /** User said: [ask].ssid is a separate network that just happens to use the same subnet. */
    fun separate(ask: Resolution.Ask): NetworkProfile =
        save(
            NetworkProfile(newId(ask.cidr), ask.ssid, ask.cidr, ask.gateway, listOf(ask.ssid))
                .withAp(ask.ssid, ask.bssid, ask.frequency, System.currentTimeMillis())
        )

    fun rename(id: String, name: String) = get(id)?.let { save(it.copy(name = name.trim().ifEmpty { it.name })) }

    /** Splits an SSID off; next time it's used, the app asks again. */
    fun detachSsid(id: String, ssid: String) = get(id)?.let { p ->
        save(p.copy(ssids = p.ssids - ssid, aps = p.aps.filter { it.ssid != ssid }))
    }

    private fun newId(cidr: String) = "$cidr@" + UUID.randomUUID().toString().take(8)

    private fun NetworkProfile.withAp(ssid: String, bssid: String?, freq: Int?, now: Long): NetworkProfile {
        if (bssid == null) return this
        val others = aps.filter { it.bssid != bssid }
        return copy(aps = (others + AccessPoint(ssid, bssid, freq, now)).sortedWith(compareBy({ it.ssid }, { it.bssid })))
    }

    private fun save(p: NetworkProfile): NetworkProfile {
        val list = _flow.value.filter { it.id != p.id } + p
        val arr = JSONArray()
        list.forEach { n ->
            arr.put(
                JSONObject()
                    .put("id", n.id).put("name", n.name).put("cidr", n.cidr)
                    .put("gateway", n.gateway ?: JSONObject.NULL)
                    .put("ssids", JSONArray(n.ssids))
                    .put("aps", JSONArray().apply {
                        n.aps.forEach { a ->
                            put(JSONObject().put("ssid", a.ssid).put("bssid", a.bssid)
                                .put("freq", a.frequency ?: JSONObject.NULL).put("lastSeen", a.lastSeen))
                        }
                    })
            )
        }
        file.writeText(arr.toString())
        _flow.value = list
        return p
    }

    private fun load(): List<NetworkProfile> = runCatching {
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val ssids = o.optJSONArray("ssids")
            val aps = o.optJSONArray("aps")
            NetworkProfile(
                id = o.getString("id"),
                name = o.getString("name"),
                cidr = o.getString("cidr"),
                gateway = o.optNullableString("gateway"),
                ssids = ssids?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
                aps = aps?.let { a ->
                    (0 until a.length()).map { j ->
                        val ap = a.getJSONObject(j)
                        AccessPoint(
                            ap.getString("ssid"), ap.getString("bssid"),
                            if (ap.isNull("freq")) null else ap.optInt("freq"),
                            ap.optLong("lastSeen"),
                        )
                    }
                }.orEmpty(),
            )
        }
    }.getOrDefault(emptyList())
}
