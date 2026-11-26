package com.scannerman.ui

import android.Manifest
import android.content.Intent
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.scannerman.data.NetworkProfile
import com.scannerman.data.Resolution
import com.scannerman.net.WifiDetails

/** Explains why the Wi-Fi name is missing and offers the fix (location permission / switch). */
@Composable
fun WifiNameBanner(wifi: WifiDetails.Info?, onGranted: () -> Unit) {
    val ctx = LocalContext.current
    var dismissed by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onGranted() }
    val missing = wifi?.missing ?: return
    if (dismissed || (missing != WifiDetails.Missing.NO_PERMISSION && missing != WifiDetails.Missing.LOCATION_OFF)) return

    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Wifi, null)
                Spacer(Modifier.width(8.dp))
                Text("Show the Wi-Fi name", fontWeight = FontWeight.Bold)
            }
            Text(
                if (missing == WifiDetails.Missing.NO_PERMISSION)
                    "Android only tells apps the Wi-Fi name (SSID) and access point if they have location " +
                        "permission. ScannerMan uses it only to name this network – no location is recorded."
                else "Location is switched off, so Android hides the Wi-Fi name. Turn it on to name this network.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 6.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (missing == WifiDetails.Missing.NO_PERMISSION) {
                    OutlinedButton(onClick = {
                        launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }) { Text("Allow") }
                } else {
                    OutlinedButton(onClick = {
                        ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    }) { Text("Location settings") }
                    TextButton(onClick = onGranted) { Text("Retry") }
                }
                TextButton(onClick = { dismissed = true }) { Text("Not now") }
            }
        }
    }
}

/** "WAP-B5 uses the same subnet as Meganto – same network?" */
@Composable
fun MergeNetworkDialog(ask: Resolution.Ask, onAnswer: (NetworkProfile?) -> Unit) {
    AlertDialog(
        onDismissRequest = {}, // must be answered: history depends on it
        icon = { Icon(Icons.Outlined.Wifi, null) },
        title = { Text("Same network?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val sameGw = ask.candidates.filter { it.gateway != null && it.gateway == ask.gateway }
                Text(
                    "You're connected to “${ask.ssid}”" +
                        (ask.bssid?.let { " ($it)" } ?: "") +
                        ", which uses subnet ${ask.cidr}" +
                        (if (sameGw.isNotEmpty()) " and gateway ${ask.gateway}" else "") +
                        " – just like the network(s) below.",
                )
                Text(
                    "Treat it as the same network? Then hosts, history and known devices are shared, " +
                        "and only the access point differs.",
                    style = MaterialTheme.typography.bodySmall,
                )
                ask.candidates.forEach { p ->
                    OutlinedButton(onClick = { onAnswer(p) }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "Same as “${p.name}”" +
                                (p.ssids.filter { it != p.name }.takeIf { it.isNotEmpty() }
                                    ?.let { " (${it.joinToString()})" } ?: ""),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { onAnswer(null) }) { Text("No, separate network") } },
    )
}

/** Rename the network, see its SSIDs and every access point it was seen through. */
@Composable
fun NetworkSheet(
    profile: NetworkProfile,
    currentBssid: String?,
    onRename: (String) -> Unit,
    onDetach: (String) -> Unit,
) {
    var name by remember(profile.id) { mutableStateOf(profile.name) }
    Column(
        Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Network", style = MaterialTheme.typography.headlineSmall)
        Text(
            "${profile.cidr}" + (profile.gateway?.let { " · gateway $it" } ?: ""),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.weight(1f))
            TextButton(onClick = { onRename(name) }, enabled = name.isNotBlank() && name != profile.name) { Text("Save") }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("Wi-Fi names (SSIDs) in this network", style = MaterialTheme.typography.titleSmall)
        if (profile.ssids.isEmpty()) Text("None known (Wi-Fi name not readable or Ethernet)", style = MaterialTheme.typography.bodySmall)
        profile.ssids.forEach { ssid ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ssid, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                if (profile.ssids.size > 1) {
                    TextButton(onClick = { onDetach(ssid) }) { Text("Split off") }
                }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("Access points", style = MaterialTheme.typography.titleSmall)
        if (profile.aps.isEmpty()) Text("No access point info yet", style = MaterialTheme.typography.bodySmall)
        profile.aps.forEach { ap ->
            val current = ap.bssid == currentBssid
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                Icon(
                    Icons.Outlined.Wifi, null, Modifier.size(18.dp),
                    tint = if (current) statusColor(com.scannerman.data.HostStatus.ONLINE) else MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        ap.ssid + (WifiDetails.Info(null, null, ap.frequency, WifiDetails.Missing.NONE).band?.let { " · $it" } ?: "") +
                            if (current) " · connected" else "",
                        fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                    )
                    Text(
                        ap.bssid + " · " + DateUtils.getRelativeTimeSpanString(ap.lastSeen),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
