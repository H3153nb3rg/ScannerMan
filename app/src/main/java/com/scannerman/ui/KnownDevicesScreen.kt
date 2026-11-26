@file:OptIn(ExperimentalMaterial3Api::class)

package com.scannerman.ui

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scannerman.data.Avail
import com.scannerman.data.DeviceGuesser
import com.scannerman.data.DeviceType
import com.scannerman.data.HostStatus
import com.scannerman.data.KnownDevice

@Composable
fun KnownDevicesScreen(vm: MainViewModel, onBack: () -> Unit) {
    val known by vm.known.collectAsStateWithLifecycle()
    val s by vm.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<KnownDevice?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Known devices") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { pad ->
        if (known.isEmpty()) {
            Box(Modifier.padding(pad).fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "No known devices yet.\nOpen a host from the scan list and tap “Add to known”.",
                    textAlign = TextAlign.Center,
                )
            }
            return@Scaffold
        }
        LazyColumn(
            Modifier.padding(pad),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(known, key = { it.id }) { d ->
                val host = s.hosts.firstOrNull { d.matches(it) }
                val type = d.type ?: host?.let { DeviceGuesser.guess(it).type } ?: DeviceType.UNKNOWN
                Card(Modifier.fillMaxWidth().clickable { editing = d }) {
                  Row(Modifier.padding(12.dp)) {
                    DeviceAvatar(
                        type,
                        if (host?.isOnline == true) statusColor(HostStatus.ONLINE) else MaterialTheme.colorScheme.outline,
                        dimmed = host?.isOnline != true,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(d.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            if (host != null) {
                                AvailBadge("v4", host.v4)
                                Spacer(Modifier.width(4.dp))
                                AvailBadge("v6", host.v6)
                            }
                        }
                        Text(
                            when {
                                host == null -> "Not seen on this network"
                                host.status == HostStatus.OFFLINE ->
                                    "Offline · last seen " + DateUtils.getRelativeTimeSpanString(host.lastSeen)
                                host.v4 == Avail.UP || host.v6 == Avail.UP -> "Online at ${host.address}"
                                else -> "Status unknown"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (host?.isOnline == true) statusColor(HostStatus.ONLINE) else MaterialTheme.colorScheme.outline,
                        )
                        Text(
                            listOfNotNull(d.ip4, d.mac, d.ip6.takeIf { it.isNotEmpty() }?.let { "${it.size}× IPv6" })
                                .joinToString(" · "),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (d.notes.isNotBlank()) Text(d.notes, style = MaterialTheme.typography.bodySmall)
                    }
                  }
                }
            }
        }
    }

    editing?.let { d ->
        KnownDeviceEditor(
            device = d,
            autoType = s.hosts.firstOrNull { d.matches(it) }?.let { DeviceGuesser.guess(it).type },
            onDismiss = { editing = null },
            onSave = { vm.updateKnown(it); editing = null },
            onDelete = { vm.deleteKnown(d.id); editing = null },
        )
    }
}
