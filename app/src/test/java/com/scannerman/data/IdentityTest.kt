package com.scannerman.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityTest {
    private val t0 = 1_000L
    private val now = 2_000L

    private fun host(
        ip4: String?, names: List<String> = emptyList(), mac: String? = null, ip6: List<String> = emptyList(),
        model: String? = null, services: List<String> = emptyList(), ports: List<Int> = emptyList(), uid: String? = null,
    ) = Host(
        ip4 = ip4, mac = mac, ip6 = ip6, model = model, services = services, openPorts = ports,
        names = names, firstSeen = t0, lastSeen = t0,
    ).let { if (uid != null) it.copy(uid = uid) else it }

    private fun merge(prev: List<Host>, cur: List<Host>) = HistoryStore.merge(prev, cur, now)

    @Test fun nameFollowsDhcpChange() {
        val old = host("192.168.0.23", listOf("mdns:pixel-7"), uid = "A")
        val r = merge(listOf(old), listOf(host("192.168.0.40", listOf("mdns:pixel-7"))))
        assertEquals(1, r.size)
        val h = r.single()
        assertEquals("A", h.uid)
        assertEquals(HostStatus.ONLINE, h.status)
        assertEquals("192.168.0.23", h.lastIpChange?.from)
        assertEquals("192.168.0.40", h.lastIpChange?.to)
        assertEquals(t0, h.firstSeen)
    }

    @Test fun nameMatchesAcrossSources() {
        // Router DNS "pixel-7.fritz.box" before, mDNS "Pixel-7.local" now.
        val old = host("192.168.0.23", listOfNotNull(Identity.nameId(Identity.DNS, "pixel-7.fritz.box")), uid = "A")
        val cur = host("192.168.0.41", listOfNotNull(Identity.nameId(Identity.MDNS, "Pixel-7.local")))
        assertEquals("A", merge(listOf(old), listOf(cur)).single { it.status != HostStatus.OFFLINE }.uid)
    }

    @Test fun otherDeviceOnOldIpIsNotConfused() {
        val phone = host("192.168.0.23", listOf("dns:pixel-7"), uid = "A")
        val r = merge(listOf(phone), listOf(host("192.168.0.23", listOf("dns:ipad"))))
        val new = r.single { it.status == HostStatus.NEW }
        assertTrue(new.uid != "A")
        assertEquals("A", r.single { it.status == HostStatus.OFFLINE }.uid)
    }

    @Test fun swappedAddressesAreFollowed() {
        val a = host("192.168.0.10", listOf("dns:laptop"), uid = "A")
        val b = host("192.168.0.11", listOf("dns:tablet"), uid = "B")
        val r = merge(listOf(a, b), listOf(host("192.168.0.11", listOf("dns:laptop")), host("192.168.0.10", listOf("dns:tablet"))))
        assertEquals("A", r.single { it.ip4 == "192.168.0.11" }.uid)
        assertEquals("B", r.single { it.ip4 == "192.168.0.10" }.uid)
        assertTrue(r.all { it.status == HostStatus.ONLINE })
    }

    @Test fun sameIpWithoutInfoStillMatches() {
        val r = merge(listOf(host("192.168.0.5", uid = "A")), listOf(host("192.168.0.5")))
        assertEquals("A", r.single().uid)
        assertNull(r.single().lastIpChange)
    }

    @Test fun sameIpButDifferentServicesIsNewDevice() {
        val printer = host("192.168.0.5", services = listOf("_ipp"), uid = "A")
        val r = merge(listOf(printer), listOf(host("192.168.0.5", services = listOf("_googlecast"))))
        assertEquals(2, r.size)
    }

    @Test fun uniqueFingerprintFollowsIpChange() {
        val cast = host("192.168.0.30", model = "Chromecast", services = listOf("_googlecast"), ports = listOf(8008, 8009), uid = "A")
        val r = merge(listOf(cast), listOf(host("192.168.0.31", model = "Chromecast", services = listOf("_googlecast"), ports = listOf(8008, 8009))))
        val h = r.single()
        assertEquals("A", h.uid)
        assertTrue(h.lastIpChange!!.via.startsWith("probably"))
    }

    @Test fun ambiguousFingerprintIsNotGuessed() {
        fun cast(ip: String, uid: String? = null) =
            host(ip, model = "Chromecast", services = listOf("_googlecast"), ports = listOf(8008, 8009), uid = uid)
        val r = merge(listOf(cast("192.168.0.30", "A"), cast("192.168.0.31", "B")),
            listOf(cast("192.168.0.40"), cast("192.168.0.41")))
        assertEquals(2, r.count { it.status == HostStatus.NEW })
        assertEquals(2, r.count { it.status == HostStatus.OFFLINE })
    }

    @Test fun duplicateGenericNamesDoNotMatch() {
        // Two ESP boards both called "esp-device" swap addresses: the name proves nothing.
        val r = merge(
            listOf(host("192.168.0.50", listOf("dns:esp-device"), uid = "A"), host("192.168.0.51", listOf("dns:esp-device"), uid = "B")),
            listOf(host("192.168.0.50", listOf("dns:esp-device")), host("192.168.0.51", listOf("dns:esp-device"))),
        )
        assertEquals("A", r.single { it.ip4 == "192.168.0.50" }.uid)
        assertEquals("B", r.single { it.ip4 == "192.168.0.51" }.uid)
    }

    @Test fun addressLikeNamesAreIgnored() {
        assertNull(Identity.normalize("192-168-0-23.local"))
        assertNull(Identity.normalize("android.local"))
        assertEquals("pixel-7", Identity.normalize("Pixel-7.fritz.box."))
        assertEquals("living room tv", Identity.normalize("Living Room TV"))
    }

    @Test fun knownDeviceFollowsUidAndIgnoresStaleIp() {
        val d = KnownDevice(name = "Phone", ip4 = "192.168.0.23", uids = listOf("A"))
        val moved = host("192.168.0.40", uid = "A")
        val stranger = host("192.168.0.23")
        val owners = KnownDeviceStore.assign(listOf(d), listOf(moved, stranger))
        assertEquals(d.id, owners["A"]?.id)
        assertNull(owners[stranger.uid])
        assertEquals("192.168.0.40", d.learn(moved).ip4)
    }

    @Test fun knownDeviceByIpOnlyWhenNothingBetter() {
        val d = KnownDevice(name = "NAS", ip4 = "192.168.0.2")
        val h = host("192.168.0.2")
        assertEquals(d.id, KnownDeviceStore.assign(listOf(d), listOf(h))[h.uid]?.id)
        // ... but not if the names contradict.
        val d2 = d.copy(names = listOf("dns:nas"))
        val h2 = host("192.168.0.2", listOf("dns:printer"))
        assertNull(KnownDeviceStore.assign(listOf(d2), listOf(h2))[h2.uid])
    }
}
