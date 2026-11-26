package com.scannerman.net

import android.net.Uri

object Ports {

    /** Ports probed on every live host, with a short service label. */
    val COMMON: Map<Int, String> = linkedMapOf(
        21 to "FTP", 22 to "SSH", 23 to "Telnet", 25 to "SMTP", 53 to "DNS",
        80 to "HTTP", 81 to "HTTP-alt", 110 to "POP3", 135 to "MS-RPC", 139 to "NetBIOS",
        143 to "IMAP", 443 to "HTTPS", 445 to "SMB", 515 to "LPD", 548 to "AFP",
        554 to "RTSP", 631 to "IPP/CUPS", 993 to "IMAPS", 995 to "POP3S", 1883 to "MQTT",
        3000 to "HTTP-dev", 3306 to "MySQL", 3389 to "RDP", 5000 to "HTTP/UPnP",
        5432 to "PostgreSQL", 5900 to "VNC", 6379 to "Redis", 8000 to "HTTP-alt",
        8008 to "HTTP-alt", 8009 to "Chromecast", 8080 to "HTTP-proxy", 8081 to "HTTP-alt",
        8123 to "Home Assistant", 8443 to "HTTPS-alt", 8883 to "MQTTS", 8888 to "HTTP-alt",
        9000 to "HTTP-alt", 9100 to "Printer RAW", 32400 to "Plex", 62078 to "iOS sync",
    )

    /** Small set used during host discovery (an open OR refused connection = host alive). */
    val DISCOVERY = listOf(80, 443, 22, 445, 139, 53, 8080, 62078, 5000, 7000)

    val DEFAULT_SPEC: String = COMMON.keys.joinToString(",")

    /** Upper bound so a typo like "1-65535" doesn't take forever. */
    const val MAX_PORTS = 2048

    /** Parses "22, 80,443, 8000-8100" into a sorted port list. Invalid tokens are ignored. */
    fun parseSpec(spec: String): List<Int> {
        val out = sortedSetOf<Int>()
        for (raw in spec.split(',', ';', ' ', '\n')) {
            val t = raw.trim()
            if (t.isEmpty()) continue
            val range = t.split('-').mapNotNull { it.trim().toIntOrNull() }
            when {
                range.size == 1 -> out += range[0]
                range.size == 2 && range[0] <= range[1] -> (range[0]..range[1]).forEach { out += it }
            }
            if (out.size > MAX_PORTS) break
        }
        return out.filter { it in 1..65535 }.take(MAX_PORTS)
    }

    fun name(port: Int): String = COMMON[port] ?: ""

    fun label(port: Int): String = name(port).let { if (it.isEmpty()) "$port" else "$port/$it" }

    /**
     * URI that other apps can handle for a well-known port, or null if there is no
     * sensible "open with" target. Launched via ACTION_VIEW + chooser.
     * IPv6 addresses are bracketed; link-local ones can't be opened (no zone in URIs).
     */
    fun openUri(address: String, port: Int): Uri? {
        if (address.startsWith("fe80", ignoreCase = true)) return null
        val ip = if (':' in address) "[$address]" else address
        val s = when (port) {
            80 -> "http://$ip/"
            81, 3000, 5000, 8000, 8008, 8080, 8081, 8123, 8888, 9000 -> "http://$ip:$port/"
            631 -> "http://$ip:631/"
            32400 -> "http://$ip:32400/web"
            443 -> "https://$ip/"
            8443 -> "https://$ip:8443/"
            21 -> "ftp://$ip:$port/"
            22 -> "ssh://$ip:$port"
            23 -> "telnet://$ip:$port"
            139, 445 -> "smb://$ip/"
            548 -> "afp://$ip/"
            554 -> "rtsp://$ip:$port/"
            3389 -> "rdp://full%20address=s:$ip:$port"
            5900 -> "vnc://$ip:$port"
            1883 -> "mqtt://$ip:$port"
            8883 -> "mqtts://$ip:$port"
            3306 -> "mysql://$ip:$port"
            5432 -> "postgresql://$ip:$port"
            6379 -> "redis://$ip:$port"
            else -> null
        }
        return s?.let(Uri::parse)
    }
}
