package com.scannerman.data

/**
 * Recognises the same physical device across scans even when DHCP hands it a new IPv4
 * address and no MAC is visible (the normal case on modern Android).
 *
 * Every pair of records gets a score from weighted evidence:
 *
 * | Evidence                                  | Score |
 * |-------------------------------------------|-------|
 * | same MAC                                  | +100  |
 * | both gateway / both "this device"         | +100  |
 * | shared IPv6 address                       |  +80  |
 * | same unique name (DHCP/DNS, NetBIOS, mDNS) |  +70  |
 * | same IPv4                                 |  +30  |
 * | same model / same services / same ports   | +20 / +15 / +15 |
 * | different (non-random) MAC                | -100  |
 * | different name from the same source       |  -50  |
 * | different model                           |  -40  |
 * | disjoint services / disjoint ports (≥2)   | -20 / -10 |
 *
 * A pair is the same device at [MATCH] or more. Matches that rest only on the
 * fingerprint (model, services, ports), with no identifier and no shared address, are
 * accepted only when they're unambiguous on both sides. See [HistoryStore.merge].
 */
object Identity {
    const val MATCH = 30
    const val STRONG = 60

    data class Match(val score: Int, val via: String, val strong: Boolean, val sameIp4: Boolean) {
        val ok get() = score >= MATCH
        /** Only the fingerprint matched: no MAC/IPv6/name/IPv4 in common. */
        val fingerprintOnly get() = !strong && !sameIp4
    }

    /** What identifies a host record or a known device. */
    data class Print(
        val mac: String?,
        val ip4: String?,
        val ip6: Collection<String>,
        val names: Collection<String>,
        val model: String?,
        val services: Collection<String>,
        val ports: Collection<Int>,
        val isSelf: Boolean = false,
        val isGateway: Boolean = false,
    )

    fun print(h: Host) = Print(
        mac = h.mac, ip4 = h.ip4, ip6 = h.ip6, names = h.names, model = h.model,
        services = h.services, ports = (h.openPorts + h.openPorts6).toSet(),
        isSelf = h.isSelf, isGateway = h.isGateway,
    )

    fun print(d: KnownDevice) = Print(
        mac = d.mac, ip4 = d.ip4, ip6 = d.ip6, names = d.names, model = d.model,
        services = emptyList(), ports = emptyList(),
    )

    fun score(a: Print, b: Print, ambiguous: Set<String> = emptySet()): Match {
        var score = 0
        var strong = false
        val reasons = ArrayList<Pair<Int, String>>()
        fun add(points: Int, why: String) {
            score += points
            if (points > 0) reasons += points to why
        }

        if (a.isSelf && b.isSelf) { add(100, "this device"); strong = true }
        if (a.isGateway && b.isGateway) { add(100, "gateway"); strong = true }

        if (a.mac != null && b.mac != null) {
            if (a.mac.equals(b.mac, ignoreCase = true)) { add(100, "MAC"); strong = true }
            // Randomised MACs can differ per SSID for the same phone.
            else add(if (isRandomMac(a.mac) || isRandomMac(b.mac)) -30 else -100, "")
        }

        val v6a = a.ip6.map { it.lowercase() }.toSet()
        if (b.ip6.any { it.lowercase() in v6a }) { add(80, "IPv6 address"); strong = true }

        val na = a.names.filter { value(it) !in ambiguous }
        val nb = b.names.filter { value(it) !in ambiguous }
        val shared = na.map(::value).toSet().intersect(nb.map(::value).toSet())
        if (shared.isNotEmpty()) {
            add(70, "name “${shared.first()}”")
            strong = true
        } else {
            val sourcesA = na.map(::source).toSet()
            if (nb.any { source(it) in sourcesA }) add(-50, "")
        }

        val sameIp4 = a.ip4 != null && a.ip4 == b.ip4
        if (sameIp4) add(30, "same IPv4")

        if (a.model != null && b.model != null) {
            if (a.model.equals(b.model, ignoreCase = true)) add(20, "model") else add(-40, "")
        }
        if (a.services.isNotEmpty() && b.services.isNotEmpty()) {
            val sa = a.services.toSet()
            when {
                sa == b.services.toSet() -> add(15, "mDNS services")
                b.services.none { it in sa } -> add(-20, "")
            }
        }
        if (a.ports.isNotEmpty() && b.ports.isNotEmpty()) {
            val pa = a.ports.toSet()
            when {
                pa == b.ports.toSet() -> add(if (pa.size >= 2) 15 else 5, "open ports")
                pa.size >= 2 && b.ports.size >= 2 && b.ports.none { it in pa } -> add(-10, "")
            }
        }

        val via = reasons.sortedByDescending { it.first }.take(if (strong) 1 else 3)
            .joinToString(" + ") { it.second }
            .let { if (!strong && !sameIp4 && it.isNotEmpty()) "probably: same $it" else it }
        return Match(score, via, strong, sameIp4)
    }

    // ---- Names ------------------------------------------------------------------------

    /** Name sources, stored as "<source>:<normalised name>". */
    const val DNS = "dns"      // reverse DNS, usually the router's DHCP lease name
    const val NETBIOS = "nb"
    const val MDNS = "mdns"    // <host>.local
    const val FRIENDLY = "fn"  // mDNS TXT fn= ("Living Room TV")

    fun nameId(source: String, raw: String?): String? = normalize(raw)?.let { "$source:$it" }

    fun source(id: String) = id.substringBefore(':')
    fun value(id: String) = id.substringAfter(':')

    private val GENERIC = setOf(
        "localhost", "android", "unknown", "host", "device", "client", "dhcp", "default",
        "none", "espressif", "esp32", "esp8266", "esp", "linux", "workgroup", "local",
    )
    /** Names derived from the address (e.g. "192-168-0-23", "dhcp-0-23", "ip-c0a80017") identify nothing. */
    private val ADDRESS_LIKE = Regex("""\d{1,3}[-_.]\d{1,3}[-_.]\d{1,3}|(^|[-_])ip[-_]?[0-9a-f]{8}$|([0-9a-f]{1,4}-){3}[0-9a-f]{1,4}""")

    fun normalize(raw: String?): String? {
        var s = raw?.trim()?.lowercase()?.removeSuffix(".") ?: return null
        if (s.isEmpty() || s.contains(':')) return null
        // DNS-style names: keep only the host label ("pixel-7.fritz.box" -> "pixel-7").
        if (' ' !in s) s = s.substringBefore('.')
        if (s.length < 2 || s in GENERIC || ADDRESS_LIKE.containsMatchIn(s)) return null
        return s
    }

    /** Values shared by more than one record in [hosts] can't tell devices apart. */
    fun ambiguousNames(hosts: List<Host>): Set<String> =
        hosts.flatMap { h -> h.names.map(::value).distinct() }
            .groupingBy { it }.eachCount().filterValues { it > 1 }.keys

    /** Current names first; old names are kept only for sources that gave nothing now. */
    fun mergeNames(current: List<String>, old: List<String>): List<String> {
        val sources = current.map(::source).toSet()
        return (current + old.filter { source(it) !in sources }).distinct().take(12)
    }

    /** Locally administered bit set: Android/iOS/Windows private addresses. */
    fun isRandomMac(mac: String): Boolean =
        mac.take(2).toIntOrNull(16)?.let { it and 0x02 != 0 } ?: false
}
