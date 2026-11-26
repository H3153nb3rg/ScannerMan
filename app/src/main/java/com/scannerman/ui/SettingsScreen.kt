@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.scannerman.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scannerman.data.V6PortScan
import com.scannerman.net.Ports
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val ui by vm.state.collectAsStateWithLifecycle()
    var portsText by remember { mutableStateOf(s.ports) }
    var confirmClear by remember { mutableStateOf(false) }
    val parsed = Ports.parseSpec(portsText)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (parsed.isNotEmpty()) vm.updateSettings { it.copy(ports = portsText) }
                        onBack()
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Section("Ports")
            OutlinedTextField(
                value = portsText,
                onValueChange = {
                    portsText = it
                    if (Ports.parseSpec(it).isNotEmpty()) vm.updateSettings { st -> st.copy(ports = it) }
                },
                label = { Text("Ports to scan (e.g. 22,80,443,8000-8100)") },
                minLines = 2,
                isError = parsed.isEmpty(),
                supportingText = {
                    Text(if (parsed.isEmpty()) "No valid ports" else "${parsed.size} ports (max ${Ports.MAX_PORTS})")
                },
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = {
                portsText = Ports.DEFAULT_SPEC
                vm.updateSettings { it.copy(ports = Ports.DEFAULT_SPEC) }
            }) { Text("Reset to default ports") }

            Section("Speed")
            SliderSetting("Host discovery timeout", s.discoveryTimeoutMs, 200f..3000f, "ms", steps = 27) {
                vm.updateSettings { st -> st.copy(discoveryTimeoutMs = it) }
            }
            SliderSetting("Port timeout", s.portTimeoutMs, 100f..3000f, "ms", steps = 28) {
                vm.updateSettings { st -> st.copy(portTimeoutMs = it) }
            }
            SliderSetting("Hosts in parallel", s.parallelHosts, 8f..128f, "", steps = 14) {
                vm.updateSettings { st -> st.copy(parallelHosts = it) }
            }

            Section("Range")
            Text("Largest subnet to sweep completely (bigger ones fall back to your /24)", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(24, 23, 22, 21, 20).forEach { p ->
                    FilterChip(
                        selected = s.maxSubnetPrefix == p,
                        onClick = { vm.updateSettings { it.copy(maxSubnetPrefix = p) } },
                        label = { Text("/$p · ${(1 shl (32 - p)) - 2}") },
                    )
                }
            }

            Section("IPv6")
            SwitchSetting("IPv6 discovery", "Ping all IPv6 nodes and probe known IPv6 addresses", s.ipv6Enabled) {
                vm.updateSettings { st -> st.copy(ipv6Enabled = it) }
            }
            Text("Port scan over IPv6", style = MaterialTheme.typography.bodyLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                V6PortScan.entries.forEach { m ->
                    FilterChip(
                        selected = s.v6PortScan == m,
                        enabled = s.ipv6Enabled,
                        onClick = { vm.updateSettings { it.copy(v6PortScan = m) } },
                        label = { Text(m.label) },
                    )
                }
            }
            SwitchSetting(
                "Show unmatched IPv6-only hosts",
                "IPv6 addresses that couldn't be tied to an IPv4 host (often the same device with a privacy address). Link them to a known device to merge.",
                s.showUnmatchedV6,
            ) { vm.updateSettings { st -> st.copy(showUnmatchedV6 = it) } }

            Section("General")
            SwitchSetting("Resolve hostnames", "Reverse DNS lookup for each host", s.resolveNames) {
                vm.updateSettings { st -> st.copy(resolveNames = it) }
            }
            SwitchSetting("Scan on app start", null, s.autoScanOnStart) {
                vm.updateSettings { st -> st.copy(autoScanOnStart = it) }
            }

            Section("History")
            OutlinedButton(onClick = { confirmClear = true }, enabled = ui.network != null && !ui.scanning) {
                Text("Clear history for ${ui.network?.cidr ?: "this network"}")
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear history?") },
            text = { Text("All remembered hosts for ${ui.network?.cidr} are removed. Known devices are kept.") },
            confirmButton = { TextButton(onClick = { vm.clearHistory(); confirmClear = false }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(top = 12.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun SliderSetting(
    title: String,
    value: Int,
    range: ClosedFloatingPointRange<Float>,
    unit: String,
    steps: Int,
    onChange: (Int) -> Unit,
) {
    var v by remember(value) { mutableStateOf(value.toFloat()) }
    Column {
        Row {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text("${v.roundToInt()} $unit", style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = v,
            onValueChange = { v = it },
            onValueChangeFinished = { onChange(v.roundToInt()) },
            valueRange = range,
            steps = steps,
        )
    }
}

@Composable
private fun SwitchSetting(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
