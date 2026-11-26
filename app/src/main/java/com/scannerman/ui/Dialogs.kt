package com.scannerman.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.scannerman.data.DeviceType
import com.scannerman.data.KnownDevice
import com.scannerman.data.TypeGuess

@Composable
fun KnownDeviceDialog(
    title: String,
    initialName: String,
    initialNotes: String,
    initialType: DeviceType?,
    autoGuess: TypeGuess?,
    onDismiss: () -> Unit,
    onSave: (name: String, notes: String, type: DeviceType?) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var notes by remember { mutableStateOf(initialNotes) }
    var type by remember { mutableStateOf(initialType) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(notes, { notes = it }, label = { Text("Notes") }, minLines = 2)
                Text("Device type", style = MaterialTheme.typography.labelLarge)
                autoGuess?.let {
                    Text("Auto-guess: ${it.type.label} – ${it.reason}", style = MaterialTheme.typography.bodySmall)
                }
                DeviceTypePicker(type, autoGuess?.type) { type = it }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, notes, type) }, enabled = name.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Choose an existing known device to attach a host's addresses to. */
@Composable
fun PickDeviceDialog(devices: List<KnownDevice>, onDismiss: () -> Unit, onPick: (KnownDevice) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Link to known device") },
        text = {
            Column {
                Text(
                    "The host's MAC / IPv4 / IPv6 addresses are added to the device, so both records are shown as one.",
                    style = MaterialTheme.typography.bodySmall,
                )
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(devices, key = { it.id }) { d ->
                        Column(Modifier.fillMaxWidth().clickable { onPick(d) }.padding(vertical = 10.dp)) {
                            Text(d.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                listOfNotNull(d.ip4, d.mac).joinToString(" · ").ifEmpty { "${d.ip6.size} IPv6 addresses" },
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Full editor used on the known-devices screen, including match identifiers. */
@Composable
fun KnownDeviceEditor(
    device: KnownDevice,
    autoType: DeviceType?,
    onDismiss: () -> Unit,
    onSave: (KnownDevice) -> Unit,
    onDelete: () -> Unit,
) {
    var type by remember { mutableStateOf(device.type) }
    var name by remember { mutableStateOf(device.name) }
    var notes by remember { mutableStateOf(device.notes) }
    var mac by remember { mutableStateOf(device.mac.orEmpty()) }
    var ip4 by remember { mutableStateOf(device.ip4.orEmpty()) }
    var ip6 by remember { mutableStateOf(device.ip6.joinToString("\n")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit known device") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(notes, { notes = it }, label = { Text("Notes") }, minLines = 2)
                Text("Device type", style = MaterialTheme.typography.labelLarge)
                DeviceTypePicker(type, autoType) { type = it }
                OutlinedTextField(mac, { mac = it }, label = { Text("MAC (match)") }, singleLine = true)
                OutlinedTextField(ip4, { ip4 = it }, label = { Text("IPv4 (match)") }, singleLine = true)
                OutlinedTextField(ip6, { ip6 = it }, label = { Text("IPv6 (one per line)") }, minLines = 2)
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onSave(
                        device.copy(
                            name = name.trim(),
                            notes = notes.trim(),
                            mac = mac.trim().lowercase().ifEmpty { null },
                            ip4 = ip4.trim().ifEmpty { null },
                            ip6 = ip6.lines().map { it.trim().lowercase() }.filter { it.isNotEmpty() },
                            type = type,
                        )
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
