package com.scannerman.data

enum class DeviceType(val label: String) {
    ROUTER("Router / AP"),
    PHONE("Phone"),
    TABLET("Tablet"),
    COMPUTER("Computer"),
    LAPTOP("Laptop"),
    SERVER("Server"),
    NAS("NAS / Storage"),
    PRINTER("Printer"),
    TV("TV"),
    MEDIA("Media / Cast"),
    SPEAKER("Speaker"),
    CAMERA("Camera"),
    CONSOLE("Game console"),
    IOT("Smart home / IoT"),
    UNKNOWN("Unknown"),
}

data class TypeGuess(val type: DeviceType, val reason: String)

/**
 * Heuristic device classification. Each signal adds a weighted vote; the highest total wins.
 * Signals: gateway/self flags, hostname keywords, open ports, MAC vendor prefix (OUI),
 * and randomized ("locally administered") MACs, which modern phones use per Wi-Fi network.
 */
object DeviceGuesser {

    private class Votes {
        val score = HashMap<DeviceType, Int>()
        val why = HashMap<DeviceType, MutableList<String>>()
        fun add(t: DeviceType, w: Int, reason: String) {
            score[t] = (score[t] ?: 0) + w
            why.getOrPut(t) { mutableListOf() } += reason
        }
    }

    /** Hostname keywords. Short ones (≤4 chars) must match a whole token, longer ones any substring. */
    private val NAME_RULES: List<Pair<DeviceType, List<String>>> = listOf(
        DeviceType.PHONE to listOf("iphone", "android", "galaxy", "pixel", "oneplus", "xiaomi", "redmi", "huawei", "phone", "smartphone", "moto", "nokia"),
        DeviceType.TABLET to listOf("ipad", "tablet", "tab", "kindle", "fire"),
        DeviceType.LAPTOP to listOf("macbook", "laptop", "notebook", "thinkpad", "xps", "surface", "chromebook", "zenbook", "yoga"),
        DeviceType.COMPUTER to listOf("desktop", "imac", "macmini", "mac", "pc", "workstation", "win", "windows", "ubuntu", "debian", "fedora"),
        DeviceType.SERVER to listOf("server", "srv", "proxmox", "pve", "esxi", "raspberrypi", "raspberry", "rpi", "homeassistant", "hassio", "docker", "unraid", "pihole"),
        DeviceType.NAS to listOf("nas", "synology", "diskstation", "qnap", "truenas", "freenas", "readynas", "mycloud"),
        DeviceType.PRINTER to listOf("printer", "print", "epson", "brother", "canon", "laserjet", "officejet", "deskjet", "envy", "hp", "kyocera", "lexmark", "xerox"),
        DeviceType.TV to listOf("tv", "bravia", "lgwebos", "webos", "tizen", "roku", "firetv", "aftv", "shield", "appletv", "vizio", "hisense", "philipstv"),
        DeviceType.MEDIA to listOf("chromecast", "googletv", "cast", "plex", "kodi", "osmc", "libreelec", "mediaserver"),
        DeviceType.SPEAKER to listOf("sonos", "echo", "alexa", "homepod", "speaker", "googlehome", "nesthub", "nestmini", "bose", "denon", "yamaha", "marantz"),
        DeviceType.CAMERA to listOf("cam", "camera", "ipcam", "hikvision", "reolink", "dahua", "axis", "doorbell", "ring", "eufy", "arlo", "wyze", "nvr", "dvr"),
        DeviceType.CONSOLE to listOf("playstation", "ps4", "ps5", "xbox", "nintendo", "switch", "steamdeck"),
        DeviceType.IOT to listOf("esp", "esp32", "esp8266", "shelly", "tasmota", "sonoff", "tuya", "hue", "lifx", "nest", "ecobee", "tado", "netatmo", "wled", "zigbee", "smartplug", "plug", "bulb", "thermostat", "vacuum", "roborock", "roomba"),
        DeviceType.ROUTER to listOf("router", "gateway", "fritz", "fritzbox", "openwrt", "ddwrt", "unifi", "ubnt", "mikrotik", "tplink", "archer", "deco", "eero", "orbi", "netgear", "linksys", "asus", "rt", "ap", "repeater", "mesh", "speedport", "livebox", "edgerouter"),
    )

    /** Small built-in MAC vendor table (OUI prefix → type). Extend as needed. */
    private val OUI: Map<String, Pair<DeviceType, String>> = buildMap {
        listOf("b8:27:eb", "dc:a6:32", "e4:5f:01", "d8:3a:dd", "2c:cf:67", "28:cd:c1")
            .forEach { put(it, DeviceType.SERVER to "Raspberry Pi") }
        listOf("24:0a:c4", "30:ae:a4", "24:6f:28", "a4:cf:12", "ec:fa:bc", "84:f3:eb", "bc:dd:c2", "5c:cf:7f", "60:01:94", "18:fe:34", "cc:50:e3", "8c:aa:b5")
            .forEach { put(it, DeviceType.IOT to "Espressif (ESP8266/ESP32)") }
        put("00:11:32", DeviceType.NAS to "Synology")
        put("00:08:9b", DeviceType.NAS to "QNAP")
        listOf("24:a4:3c", "80:2a:a8", "f0:9f:c2", "78:8a:20", "fc:ec:da", "74:83:c2", "b4:fb:e4", "e0:63:da")
            .forEach { put(it, DeviceType.ROUTER to "Ubiquiti") }
        listOf("00:04:0e", "c8:0e:14", "3c:a6:2f", "dc:39:6f")
            .forEach { put(it, DeviceType.ROUTER to "AVM (FRITZ!Box)") }
        listOf("00:0e:58", "5c:aa:fd", "94:9f:3e", "b8:e9:37", "78:28:ca", "48:a6:b8")
            .forEach { put(it, DeviceType.SPEAKER to "Sonos") }
        put("00:17:88", DeviceType.IOT to "Philips Hue")
    }

    fun guess(h: Host): TypeGuess {
        if (h.isSelf) return TypeGuess(DeviceType.PHONE, "this device")
        val v = Votes()

        if (h.isGateway) v.add(DeviceType.ROUTER, 100, "default gateway")

        // --- Hostname (first DNS label only, so ".fritz.box" suffixes don't count) ---
        h.hostname?.substringBefore('.')?.lowercase()?.takeIf { it.isNotBlank() }?.let { label ->
            val compact = label.replace(Regex("[^a-z0-9]"), "")
            val tokens = label.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.toSet()
            for ((type, words) in NAME_RULES) {
                val hit = words.firstOrNull { w -> if (w.length <= 4) w in tokens else w in compact }
                if (hit != null) v.add(type, if (hit.length <= 4) 25 else 40, "name “$hit”")
            }
        }

        // --- Open ports (v4 + v6) ---
        val p = (h.openPorts + h.openPorts6).toSet()
        fun has(vararg ports: Int) = ports.any { it in p }
        if (has(9100)) v.add(DeviceType.PRINTER, 50, "port 9100 (RAW print)")
        if (has(515)) v.add(DeviceType.PRINTER, 30, "port 515 (LPD)")
        if (has(631)) v.add(DeviceType.PRINTER, 25, "port 631 (IPP)")
        if (has(62078)) v.add(DeviceType.PHONE, 45, "port 62078 (iOS sync)")
        if (has(8009)) v.add(DeviceType.MEDIA, 45, "port 8009 (Google Cast)")
        if (has(7000) && !has(445, 22)) v.add(DeviceType.TV, 20, "port 7000 (AirPlay)")
        if (has(554) && !has(22, 445)) v.add(DeviceType.CAMERA, 35, "port 554 (RTSP)")
        if (has(3389)) v.add(DeviceType.COMPUTER, 45, "port 3389 (RDP)")
        if (has(135) && has(445)) v.add(DeviceType.COMPUTER, 35, "ports 135+445 (Windows)")
        if (has(5900) && has(548, 445)) v.add(DeviceType.COMPUTER, 20, "VNC + file sharing")
        if (has(445, 548) && has(5000, 5001)) v.add(DeviceType.NAS, 45, "file sharing + admin UI 5000")
        if (has(32400)) v.add(DeviceType.SERVER, 35, "port 32400 (Plex)")
        if (has(8123)) v.add(DeviceType.SERVER, 40, "port 8123 (Home Assistant)")
        if (has(1883, 8883)) v.add(DeviceType.SERVER, 20, "MQTT broker")
        if (has(3306, 5432, 6379)) v.add(DeviceType.SERVER, 30, "database port")
        if (has(22) && !has(3389, 62078, 9100)) v.add(DeviceType.SERVER, 15, "SSH")
        if (has(53) && has(80, 443)) v.add(DeviceType.ROUTER, 35, "DNS + web UI")

        // --- mDNS / Bonjour services (devices describe themselves – strong signal) ---
        val svc = h.services.toSet()
        fun svc(vararg names: String) = names.firstOrNull { it in svc }
        svc("_ipp", "_ipps", "_printer", "_pdl-datastream", "_uscan", "_scanner")
            ?.let { v.add(DeviceType.PRINTER, 60, "mDNS $it") }
        svc("_googlecast")?.let { v.add(DeviceType.MEDIA, 50, "mDNS $it") }
        svc("_androidtvremote2", "_amzn-wplay", "_nvstream")?.let { v.add(DeviceType.TV, 60, "mDNS $it") }
        svc("_spotify-connect", "_sonos")?.let { v.add(DeviceType.SPEAKER, 35, "mDNS $it") }
        if ("_raop" in svc && "_airplay" !in svc) v.add(DeviceType.SPEAKER, 35, "mDNS _raop (AirPlay audio)")
        svc("_airplay")?.let { v.add(DeviceType.MEDIA, 25, "mDNS $it") }
        svc("_companion-link", "_apple-mobdev2")?.let { v.add(DeviceType.PHONE, 35, "mDNS $it (Apple)") }
        svc("_hap", "_homekit", "_matter", "_matterc", "_esphomelib", "_hue", "_shelly", "_ewelink", "_miio")
            ?.let { v.add(DeviceType.IOT, 50, "mDNS $it") }
        svc("_home-assistant", "_plexmediasvr", "_mqtt")?.let { v.add(DeviceType.SERVER, 45, "mDNS $it") }
        if ("_adisk" in svc) v.add(DeviceType.NAS, 45, "mDNS _adisk (Time Machine)")
        if ("_workstation" in svc) v.add(DeviceType.SERVER, 25, "mDNS _workstation (Linux)")
        if ("_rfb" in svc) v.add(DeviceType.COMPUTER, 20, "mDNS _rfb (screen sharing)")
        if (svc.containsAll(listOf("_smb", "_afpovertcp"))) v.add(DeviceType.NAS, 20, "SMB + AFP shares")

        // --- Model string from mDNS TXT (md=, model=, am=, ty=) ---
        h.model?.lowercase()?.let { m ->
            val t = when {
                "macbook" in m -> DeviceType.LAPTOP
                m.startsWith("imac") || m.startsWith("macmini") || m.startsWith("macpro") || m.startsWith("mac1") -> DeviceType.COMPUTER
                m.startsWith("iphone") -> DeviceType.PHONE
                m.startsWith("ipad") -> DeviceType.TABLET
                m.startsWith("appletv") || "tv" in m.split(Regex("[^a-z]+")) || "television" in m -> DeviceType.TV
                m.startsWith("audioaccessory") || "homepod" in m || "nest mini" in m || "nest audio" in m ||
                    "home mini" in m || "google home" in m || "speaker" in m -> DeviceType.SPEAKER
                "nest hub" in m -> DeviceType.SPEAKER
                "chromecast" in m -> DeviceType.MEDIA
                "laserjet" in m || "officejet" in m || "deskjet" in m || "printer" in m || "epson" in m ||
                    "brother" in m || "canon" in m -> DeviceType.PRINTER
                "ds" == m.take(2) && m.drop(2).firstOrNull()?.isDigit() == true -> DeviceType.NAS // Synology DS920+
                else -> null
            }
            t?.let { v.add(it, 70, "model “${h.model}”") }
        }

        // --- MAC vendor / randomized MAC ---
        h.mac?.lowercase()?.let { mac ->
            OUI[mac.take(8)]?.let { (t, vendor) -> v.add(t, 50, vendor) }
            val first = mac.take(2).toIntOrNull(16)
            if (first != null && first and 0x02 != 0 && !h.isGateway) {
                v.add(DeviceType.PHONE, 20, "randomized MAC")
            }
        }

        // No ports open at all + randomized/unknown → still likely a phone (they block everything).
        if (p.isEmpty() && h.ip4 != null && !h.isGateway && (v.score[DeviceType.PHONE] ?: 0) > 0) {
            v.add(DeviceType.PHONE, 10, "no open ports")
        }

        val best = v.score.maxByOrNull { it.value } ?: return TypeGuess(DeviceType.UNKNOWN, "no clues")
        return TypeGuess(best.key, v.why[best.key]!!.joinToString(", "))
    }
}
