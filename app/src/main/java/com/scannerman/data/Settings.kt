package com.scannerman.data

import android.content.Context
import com.scannerman.net.Ports
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class V6PortScan(val label: String) {
    OFF("Off"),
    V6_ONLY_HOSTS("IPv6-only hosts"),
    ALL("All IPv6 hosts"),
}

data class Settings(
    /** Port spec, e.g. "22,80,443,8000-8100". */
    val ports: String = Ports.DEFAULT_SPEC,
    val discoveryTimeoutMs: Int = 700,
    val portTimeoutMs: Int = 400,
    val parallelHosts: Int = 48,
    /** Largest subnet that is swept completely; bigger ones fall back to the local /24. */
    val maxSubnetPrefix: Int = 22,
    val ipv6Enabled: Boolean = true,
    val v6PortScan: V6PortScan = V6PortScan.V6_ONLY_HOSTS,
    val showUnmatchedV6: Boolean = true,
    val resolveNames: Boolean = true,
    val autoScanOnStart: Boolean = false,
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _flow = MutableStateFlow(load())
    val flow: StateFlow<Settings> = _flow
    val value: Settings get() = _flow.value

    private fun load(): Settings {
        val d = Settings()
        return Settings(
            ports = prefs.getString("ports", d.ports) ?: d.ports,
            discoveryTimeoutMs = prefs.getInt("discoveryTimeoutMs", d.discoveryTimeoutMs),
            portTimeoutMs = prefs.getInt("portTimeoutMs", d.portTimeoutMs),
            parallelHosts = prefs.getInt("parallelHosts", d.parallelHosts),
            maxSubnetPrefix = prefs.getInt("maxSubnetPrefix", d.maxSubnetPrefix),
            ipv6Enabled = prefs.getBoolean("ipv6Enabled", d.ipv6Enabled),
            v6PortScan = runCatching { V6PortScan.valueOf(prefs.getString("v6PortScan", null)!!) }
                .getOrDefault(d.v6PortScan),
            showUnmatchedV6 = prefs.getBoolean("showUnmatchedV6", d.showUnmatchedV6),
            resolveNames = prefs.getBoolean("resolveNames", d.resolveNames),
            autoScanOnStart = prefs.getBoolean("autoScanOnStart", d.autoScanOnStart),
        )
    }

    fun update(transform: (Settings) -> Settings) {
        val s = transform(_flow.value)
        prefs.edit()
            .putString("ports", s.ports)
            .putInt("discoveryTimeoutMs", s.discoveryTimeoutMs)
            .putInt("portTimeoutMs", s.portTimeoutMs)
            .putInt("parallelHosts", s.parallelHosts)
            .putInt("maxSubnetPrefix", s.maxSubnetPrefix)
            .putBoolean("ipv6Enabled", s.ipv6Enabled)
            .putString("v6PortScan", s.v6PortScan.name)
            .putBoolean("showUnmatchedV6", s.showUnmatchedV6)
            .putBoolean("resolveNames", s.resolveNames)
            .putBoolean("autoScanOnStart", s.autoScanOnStart)
            .apply()
        _flow.value = s
    }
}
