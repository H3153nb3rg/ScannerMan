@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.scannerman.ui

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scannerman.Screen
import com.scannerman.data.Avail
import com.scannerman.data.DeviceGuesser
import com.scannerman.data.DeviceType
import com.scannerman.data.Export
import com.scannerman.data.Host
import com.scannerman.data.HostStatus
import com.scannerman.data.KnownDevice
import com.scannerman.net.Ports

private enum class Filter(val label: String) {
    ALL("All"), ONLINE("Online"), NEW("New"), OFFLINE("Offline"), UNKNOWN("Unknown"), V6ONLY("IPv6-only")
}

@Composable
fun MainScreen(vm: MainViewModel, navigate: (Screen) -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val known by vm.known.collectAsStateWithLifecycle()
    val knownFor: (Host) -> KnownDevice? = { h -> known.firstOrNull { it.matches(h) } }
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var shareMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }

    LaunchedEffect(s.message) {
        s.message?.let { snackbar.showSnackbar(it); vm.consumeMessage() }
    }

    // Unmatched IPv6-only hosts can be hidden in settings (unless they're named devices).
    val visible = s.hosts.filter { settings.showUnmatchedV6 || it.ip4 != null || knownFor(it) != null }
    fun matches(f: Filter, h: Host) = when (f) {
        Filter.ALL -> true
        Filter.ONLINE -> h.status != HostStatus.OFFLINE
        Filter.NEW -> h.status == HostStatus.NEW
        Filter.OFFLINE -> h.status == HostStatus.OFFLINE
        Filter.UNKNOWN -> !h.isSelf && knownFor(h) == null
        Filter.V6ONLY -> h.ip4 == null
    }
    val shown = visible.filter { matches(filter, it) }
    val cidr = s.network?.cidr ?: "no network"
    val netLabel = s.profile?.let { "${it.name} ($cidr)" } ?: cidr
    var networkSheet by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column(Modifier.clickable(enabled = s.profile != null) { networkSheet = true }) {
                        Text(
                            s.profile?.name ?: s.pendingMerge?.ssid ?: "ScannerMan",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            listOfNotNull(
                                cidr,
                                s.wifi?.ssid?.takeIf { it != s.profile?.name }?.let { "via $it" },
                                s.wifi?.band,
                                s.profile?.aps?.size?.takeIf { it > 1 }?.let { "$it APs" },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    if (s.scanning) {
                        IconButton(onClick = vm::stopScan) { Icon(Icons.Default.Close, "Stop scan") }
                    } else {
                        IconButton(onClick = vm::startScan, enabled = s.network != null) {
                            Icon(Icons.Default.Refresh, "Scan")
                        }
                    }
                    Box {
                        IconButton(onClick = { shareMenu = true }, enabled = shown.isNotEmpty()) {
                            Icon(Icons.Default.Share, "Share")
                        }
                        DropdownMenu(shareMenu, onDismissRequest = { shareMenu = false }) {
                            DropdownMenuItem(text = { Text("Share list as text") }, onClick = {
                                shareMenu = false
                                Actions.shareText(ctx, "Network scan $cidr", Export.listText(netLabel, s.lastScan, shown, knownFor))
                            })
                            DropdownMenuItem(text = { Text("Share list as CSV") }, onClick = {
                                shareMenu = false
                                Actions.shareCsv(ctx, "scan_${cidr.replace('/', '_')}.csv", Export.csv(shown, knownFor))
                            })
                        }
                    }
                    Box {
                        IconButton(onClick = { moreMenu = true }) { Icon(Icons.Default.MoreVert, "More") }
                        DropdownMenu(moreMenu, onDismissRequest = { moreMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Known devices (${known.size})") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, null) },
                                onClick = { moreMenu = false; navigate(Screen.KNOWN) },
                            )
                            DropdownMenuItem(
                                text = { Text("Settings") },
                                leadingIcon = { Icon(Icons.Default.Settings, null) },
                                onClick = { moreMenu = false; navigate(Screen.SETTINGS) },
                            )
                            DropdownMenuItem(
                                text = { Text("Re-detect network") },
                                leadingIcon = { Icon(Icons.Default.Refresh, null) },
                                enabled = !s.scanning,
                                onClick = { moreMenu = false; vm.refreshNetwork() },
                            )
                            DropdownMenuItem(
                                text = { Text("Forget offline hosts") },
                                leadingIcon = { Icon(Icons.Default.Delete, null) },
                                enabled = !s.scanning && s.hosts.any { it.status == HostStatus.OFFLINE },
                                onClick = { moreMenu = false; vm.forgetOffline() },
                            )
                        }
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            WifiNameBanner(s.wifi, onGranted = { vm.refreshNetwork() })
            if (s.scanning) {
                LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                Text(
                    "${s.phase?.label ?: "Starting"}… ${(s.progress * 100).toInt()}%  ·  ${s.hosts.size} hosts",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            } else if (s.lastScan != null) {
                Text(
                    (if (s.fromHistory) "Last run " else "Scanned ") +
                        DateUtils.getRelativeTimeSpanString(s.lastScan!!) +
                        (if (s.fromHistory) " – tap ⟳ to rescan" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Filter.entries.forEach { f ->
                    val count = visible.count { matches(f, it) }
                    if (f == Filter.ALL || count > 0 || filter == f) {
                        FilterChip(
                            selected = filter == f,
                            onClick = { filter = f },
                            label = { Text("${f.label} $count") },
                        )
                    }
                }
            }

            if (s.hosts.isEmpty() && !s.scanning) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (s.network == null) "Connect to Wi-Fi / Ethernet first" else "No scan yet – tap ⟳",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(shown, key = { it.address + "|" + it.key }) { h ->
                        val k = knownFor(h)
                        val type = k?.type ?: remember(h) { DeviceGuesser.guess(h).type }
                        HostCard(h, k, type, showUnknownTag = known.isNotEmpty(), finalStatus = !s.scanning) {
                            selectedKey = h.address + "|" + h.key
                        }
                    }
                }
            }
        }
    }

    s.pendingMerge?.let { ask -> MergeNetworkDialog(ask, onAnswer = vm::answerMerge) }
    if (networkSheet) {
        s.profile?.let { p ->
            ModalBottomSheet(onDismissRequest = { networkSheet = false }) {
                NetworkSheet(
                    profile = p,
                    currentBssid = s.wifi?.bssid,
                    onRename = vm::renameNetwork,
                    onDetach = { ssid -> vm.detachSsid(ssid); networkSheet = false },
                )
            }
        }
    }

    val selected = s.hosts.firstOrNull { it.address + "|" + it.key == selectedKey }
    if (selected != null) {
        ModalBottomSheet(onDismissRequest = { selectedKey = null }) {
            HostDetail(selected, knownFor(selected), known, vm, editable = !s.scanning)
        }
    }
}

// ---- Shared bits ----------------------------------------------------------------------

@Composable
fun statusColor(status: HostStatus): Color = when (status) {
    HostStatus.ONLINE -> Color(0xFF2ECC71)
    HostStatus.NEW -> Color(0xFF3498DB)
    HostStatus.OFFLINE -> MaterialTheme.colorScheme.outline
}

@Composable
fun availColor(a: Avail): Color = when (a) {
    Avail.UP -> Color(0xFF2ECC71)
    Avail.DOWN -> Color(0xFFE67E22)
    Avail.UNKNOWN -> MaterialTheme.colorScheme.outlineVariant
}

/** Small "v4 ●" / "v6 ●" pill showing per-protocol availability. */
@Composable
fun AvailBadge(label: String, a: Avail) {
    Row(
        Modifier
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(50))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).background(availColor(a), CircleShape))
        Spacer(Modifier.width(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (a == Avail.UNKNOWN) MaterialTheme.colorScheme.outline else Color.Unspecified,
        )
    }
}

@Composable
private fun HostCard(
    h: Host,
    known: KnownDevice?,
    type: DeviceType,
    showUnknownTag: Boolean,
    finalStatus: Boolean,
    onClick: () -> Unit,
) {
    val offline = h.status == HostStatus.OFFLINE
    val dim = if (offline) MaterialTheme.colorScheme.outline else Color.Unspecified
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (offline) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(Modifier.padding(12.dp)) {
          DeviceAvatar(type, statusColor(h.status), dimmed = offline)
          Spacer(Modifier.width(12.dp))
          Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (known != null) {
                    Icon(Icons.Default.Star, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    known?.name ?: h.hostname ?: h.address,
                    fontWeight = FontWeight.Bold,
                    color = dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                val tag = when {
                    h.isSelf -> "this device"
                    h.isGateway -> "gateway"
                    finalStatus && h.status == HostStatus.NEW -> "NEW"
                    offline -> "offline"
                    showUnknownTag && known == null -> "unknown"
                    else -> null
                }
                tag?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (it == "unknown") MaterialTheme.colorScheme.error else statusColor(h.status),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Text(
                    h.ip4 ?: h.ip6.firstOrNull() ?: "",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                    color = dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                AvailBadge("v4", h.v4)
                Spacer(Modifier.width(4.dp))
                AvailBadge(if (h.ip6.size > 1) "v6 ×${h.ip6.size}" else "v6", h.v6)
            }
            Text(
                "MAC ${h.mac ?: "—"}" +
                    (h.model?.let { "  ·  $it" } ?: "") +
                    (if (known != null && h.hostname != null) "  ·  ${h.hostname}" else ""),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (offline) {
                Text("Last seen " + DateUtils.getRelativeTimeSpanString(h.lastSeen), style = MaterialTheme.typography.bodySmall)
            }
            val ports = (h.openPorts + h.openPorts6).distinct().sorted()
            if (ports.isNotEmpty()) {
                Text(
                    ports.joinToString("  ") { Ports.label(it) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                )
            }
          }
        }
    }
}

@Composable
private fun HostDetail(h: Host, known: KnownDevice?, allKnown: List<KnownDevice>, vm: MainViewModel, editable: Boolean) {
    val ctx = LocalContext.current
    var editDialog by remember { mutableStateOf(false) }
    var linkDialog by remember { mutableStateOf(false) }
    val guess = remember(h) { DeviceGuesser.guess(h) }
    val type = known?.type ?: guess.type

    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.clickable(enabled = editable && !h.isSelf) { editDialog = true }) {
                DeviceAvatar(type, statusColor(h.status), dimmed = h.status == HostStatus.OFFLINE, size = 48.dp)
            }
            Spacer(Modifier.width(12.dp))
            Text(
                known?.name ?: h.hostname ?: h.address,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(h.status.name, color = statusColor(h.status), style = MaterialTheme.typography.labelLarge)
        }
        if (known != null && h.hostname != null) Text(h.hostname, style = MaterialTheme.typography.titleSmall)
        known?.notes?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Text(
            if (known?.type != null) "${type.label} · set manually"
            else "${type.label} · auto: ${guess.reason}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        h.model?.let { Text("Model: $it", style = MaterialTheme.typography.bodySmall) }
        if (h.services.isNotEmpty()) {
            Text(
                "mDNS: " + h.services.joinToString(" ") { it.removePrefix("_") },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        // Known-device actions
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            if (known != null) {
                OutlinedButton(onClick = { editDialog = true }, enabled = editable) {
                    Icon(Icons.Default.Edit, null); Spacer(Modifier.width(6.dp)); Text("Edit name")
                }
            } else if (!h.isSelf) {
                OutlinedButton(onClick = { editDialog = true }, enabled = editable) {
                    Icon(Icons.Default.Star, null); Spacer(Modifier.width(6.dp)); Text("Add to known")
                }
                if (allKnown.isNotEmpty()) {
                    TextButton(onClick = { linkDialog = true }, enabled = editable) { Text("Link to device…") }
                }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("IPv4", style = MaterialTheme.typography.titleSmall)
        AvailRow(h.v4, h.lastSeen4)
        h.ip4?.let { ip -> InfoRow("Address", ip) { Actions.copy(ctx, "IPv4", ip) } }
        InfoRow("MAC", h.mac ?: "unavailable") { h.mac?.let { Actions.copy(ctx, "MAC", it) } }
        PortChips(h.ip4, h.openPorts, emptyText = if (h.v4 == Avail.UP) "No scanned ports open" else null)

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("IPv6", style = MaterialTheme.typography.titleSmall)
        AvailRow(h.v6, h.lastSeen6)
        if (h.ip6.isEmpty()) InfoRow("Address", "none found") {}
        h.ip6.forEach { a -> InfoRow(if (a.startsWith("fe80")) "Link-local" else "Address", a) { Actions.copy(ctx, "IPv6", a) } }
        val openable6 = h.ip6.firstOrNull { !it.startsWith("fe80") }
        PortChips(openable6, h.openPorts6, emptyText = null)

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        InfoRow("First seen", DateUtils.getRelativeTimeSpanString(h.firstSeen).toString()) {}
        InfoRow("Last seen", DateUtils.getRelativeTimeSpanString(h.lastSeen).toString()) {}

        Spacer(Modifier.size(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { Actions.shareText(ctx, "Host ${h.address}", Export.hostText(h, known)) }) {
                Icon(Icons.Default.Share, null); Spacer(Modifier.width(6.dp)); Text("Share info")
            }
            TextButton(onClick = { Actions.copy(ctx, "Host", Export.hostText(h, known)) }) { Text("Copy all") }
        }
    }

    if (editDialog) {
        KnownDeviceDialog(
            title = if (known == null) "Add known device" else "Edit known device",
            initialName = known?.name ?: h.hostname.orEmpty(),
            initialNotes = known?.notes.orEmpty(),
            initialType = known?.type,
            autoGuess = guess,
            onDismiss = { editDialog = false },
            onSave = { name, notes, t -> vm.saveKnown(h, known, name, notes, t); editDialog = false },
        )
    }
    if (linkDialog) {
        PickDeviceDialog(
            devices = allKnown,
            onDismiss = { linkDialog = false },
            onPick = { d -> vm.saveKnown(h, d, d.name, d.notes, d.type); linkDialog = false },
        )
    }
}

@Composable
private fun AvailRow(a: Avail, lastSeen: Long?) {
    val text = when (a) {
        Avail.UP -> "Reachable"
        Avail.DOWN -> "Not reachable" + (lastSeen?.let { " · last seen " + DateUtils.getRelativeTimeSpanString(it) } ?: " · never seen")
        Avail.UNKNOWN -> "No address known"
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Box(Modifier.size(8.dp).background(availColor(a), CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PortChips(address: String?, ports: List<Int>, emptyText: String?) {
    if (ports.isEmpty()) {
        emptyText?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        return
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ports.forEach { p ->
            val ctx = LocalContext.current
            if (address != null && Ports.openUri(address, p) != null) {
                AssistChip(onClick = { Actions.openPort(ctx, address, p) }, label = { Text("${Ports.label(p)} ↗") })
            } else {
                SuggestionChip(onClick = {}, label = { Text(Ports.label(p)) })
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp)) {
        Text(label, Modifier.width(96.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
        Text(value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
    }
}
