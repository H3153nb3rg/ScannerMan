package com.scannerman.net

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

/**
 * NetBIOS node status (NBSTAT) query on UDP 137. Windows and Samba hosts reply with their
 * machine name, and Windows also with the adapter's real MAC address.
 */
object NetBios {

    data class Info(val name: String?, val mac: String?)

    private val QUERY: ByteArray = buildList<Int> {
        addAll(listOf(0x13, 0x37, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
        add(0x20)
        // "*" padded with NULs to 16 bytes, each nibble encoded as 'A' + nibble.
        val raw = ByteArray(16).also { it[0] = '*'.code.toByte() }
        raw.forEach { b ->
            add('A'.code + ((b.toInt() shr 4) and 0x0f))
            add('A'.code + (b.toInt() and 0x0f))
        }
        add(0x00)
        addAll(listOf(0x00, 0x21, 0x00, 0x01)) // type NBSTAT, class IN
    }.map { it.toByte() }.toByteArray()

    fun query(ip: String, timeoutMs: Int = 700): Info? = runCatching {
        DatagramSocket().use { s ->
            s.soTimeout = timeoutMs
            s.send(DatagramPacket(QUERY, QUERY.size, InetSocketAddress(ip, 137)))
            val buf = ByteArray(1024)
            val p = DatagramPacket(buf, buf.size)
            s.receive(p)
            parse(buf, p.length)
        }
    }.getOrNull()

    private fun parse(b: ByteArray, len: Int): Info? {
        var pos = 12
        // Answer name: either a pointer or the full encoded name.
        pos += if (b[pos].toInt() and 0xc0 == 0xc0) 2 else (b[pos].toInt() and 0xff) + 2
        pos += 2 + 2 + 4 + 2 // type, class, ttl, rdlength
        if (pos >= len) return null
        val count = b[pos++].toInt() and 0xff
        var name: String? = null
        repeat(count) {
            if (pos + 18 > len) return@repeat
            val n = String(b, pos, 15, Charsets.US_ASCII).trim()
            val suffix = b[pos + 15].toInt() and 0xff
            val flags = ((b[pos + 16].toInt() and 0xff) shl 8) or (b[pos + 17].toInt() and 0xff)
            val group = flags and 0x8000 != 0
            if (name == null && suffix == 0x00 && !group && n.isNotEmpty()) name = n
            pos += 18
        }
        val mac = if (pos + 6 <= len) {
            val m = b.copyOfRange(pos, pos + 6)
            if (m.all { it == 0.toByte() }) null else m.joinToString(":") { "%02x".format(it) }
        } else null
        return if (name == null && mac == null) null else Info(name, mac)
    }
}
