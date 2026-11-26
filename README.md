# ScannerMan

A subnet scanner for Android. It lists the hosts on your local network with their IPv4/IPv6 addresses, MAC, hostname, device type and open ports. It also remembers earlier scans, so you can see which devices are new and which have gone offline.

Written in Kotlin with Jetpack Compose. Needs no root and has no external dependencies at runtime.

## Features

- **Host discovery:** an IPv4 sweep using ICMP plus TCP probes. A refused connection also counts as "alive". Devices that block probes are still found through mDNS / Bonjour and NetBIOS.
- **Separate IPv4 and IPv6 availability:** each host shows its own v4 and v6 state, with last-seen times kept per protocol. IPv6 addresses come from mDNS (A and AAAA records that tie v4 and v6 to the same host) and are probed actively. IPv6-only hosts are listed too.
- **Port scan:** about 40 common ports by default, or your own list (`22,80,443,8000-8100`). Ports are scanned over IPv4, and optionally over IPv6.
- **"Open with":** tap an open port to launch an app for it: `http(s)://`, `ssh://`, `smb://`, `rdp://`, `vnc://`, `rtsp://`, `ftp://`, `telnet://`, `mqtt://`, and more.
- **History:** each network remembers its hosts. Every scan marks them **Online / New / Offline**, and offline hosts show when they were last seen.
- **Follows devices across DHCP changes:** devices are recognised by a weighted score, not just their IPv4. The evidence is MAC, shared IPv6 address, hostname (DHCP/DNS, NetBIOS, mDNS, friendly name), IPv4, model, mDNS services and open ports. A device that got a new address keeps its name and history, and its card shows *"IP changed 2 h ago · was 192.168.0.23"*. The detail view lists the address changes and what the device was recognised by. If another device takes over an old address, conflicting evidence (such as a different name or services) keeps the two apart.
- **Network profiles:** a network is named after its Wi-Fi SSID and can be renamed. Several SSIDs or access points can share one subnet. When you connect through a new SSID on a subnet the app already knows, it asks whether to treat it as the same network. The app lists every access point (BSSID and band) it has been seen through.
- **Known devices:** name a device, add notes and set its type. Devices are recognised again by MAC, IPv4 or IPv6. Devices that aren't on your list are flagged **unknown**. You can link an unmatched IPv6-only entry to an existing device so the two show as one.
- **Device-type icons, guessed automatically:** router, phone, tablet, computer, laptop, server, NAS, printer, TV, media/cast, speaker, camera, game console or IoT. The guess combines the gateway role, hostname keywords, open ports, mDNS services and model strings (`md=`, `model=`, `ty=` …), MAC vendor prefixes and randomized MACs. The app shows the reason for each guess, and you can override it.
- **Sharing:** share the whole list (or the current filter) as text or CSV, or share a single host's details.
- **Settings:** port list, timeouts, parallelism, largest subnet to sweep (/24 … /20), IPv6 options, hostname lookup, scan on start, clear history.

## Requirements

- Android 8.0 (API 26) or newer.
- To build: Android SDK with platform 36, and JDK 17 or newer (JDK 21 works).

## Build & install

```sh
# Windows: set JAVA_HOME to a JDK 17+ first
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease        # minified, ~1.3 MB, signed with the debug key
./gradlew installDebug           # build + install on a connected device
```

Create a `local.properties` file pointing to your SDK if `ANDROID_HOME` isn't set:

```properties
sdk.dir=C\:\\path\\to\\Android\\Sdk
```

The release build is signed with the debug key, which is fine for sideloading. Configure your own signing key in `app/build.gradle.kts` before distributing it.

## Permissions

| Permission | Why |
|---|---|
| `INTERNET` | Probing hosts and ports |
| `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` | Detecting the subnet, gateway and interface |
| `CHANGE_WIFI_MULTICAST_STATE` | Receiving mDNS / Bonjour replies |
| `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` | Android only reveals the Wi-Fi **SSID/BSSID** to apps with location permission. It's used only to name the network; no location is recorded. Optional: without it, networks are named by subnet. |

## How discovery works on modern Android

Android restricts what unprivileged apps can see. On current versions (tested on a Samsung with Android 17):

| Method | Status | Used for |
|---|---|---|
| Neighbour table (`ip neigh`, `/proc/net/arp`) | ❌ blocked (Android 10+/11+) | MAC addresses. Still tried, in case a device allows it. |
| IPv6 all-nodes ping (`ff02::1`) | ❌ blocked (unprivileged ping can't send to multicast) | Still tried |
| ICMP + TCP connect probes | ✅ | Host discovery and port scan |
| mDNS / DNS-SD queries (`224.0.0.251`, `ff02::fb`) | ✅ | Names, IPv4↔IPv6 matching, model, services, some MACs |
| NetBIOS node status (UDP 137) | ✅ | Windows/Samba names, real MAC from Windows hosts |
| EUI-64 IPv6 addresses | ✅ | Recovering the MAC from IPv6 addresses built from it |
| Reverse DNS | ✅ | Hostnames (often from the router's DHCP table) |

As a result, **MAC addresses are best-effort**. Phones and laptops that use randomized MACs and announce nothing over mDNS may show no MAC. Without root, there's no way around this.

## Project structure

```
app/src/main/java/com/scannerman/
├── MainActivity.kt          screen switching, theme
├── net/
│   ├── Scanner.kt           scan pipeline (sweep → mDNS/neighbours → IPv6 → ports → names)
│   ├── Mdns.kt              DNS-SD queries and DNS response parser
│   ├── NetBios.kt           NBSTAT query/parser
│   ├── Neighbors.kt         ARP/NDP table, IPv6 ping, EUI-64 helpers
│   ├── LocalNetwork.kt      subnet/gateway/interface detection
│   ├── WifiDetails.kt       SSID/BSSID/band (location-gated)
│   └── Ports.kt             port list, port spec parser, "open with" URIs
├── data/
│   ├── Host.kt              host model, per-protocol availability
│   ├── HistoryStore.kt      per-network JSON history + merge logic
│   ├── NetworkProfiles.kt   SSID ↔ subnet profiles, access points
│   ├── KnownDevices.kt      user-named devices + record combining
│   ├── DeviceType.kt        device types + auto-guess heuristics
│   ├── Settings.kt          preferences
│   └── Export.kt            text / CSV export
└── ui/                      Compose screens, dialogs, ViewModel
```

All data is stored in the app's private storage as JSON files (`files/history/`, `networks.json`, `known_devices.json`). Nothing leaves the device unless you share it.

## Use responsibly

Only scan networks you own or are allowed to test.
