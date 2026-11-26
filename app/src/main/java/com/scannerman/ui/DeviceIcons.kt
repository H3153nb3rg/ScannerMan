package com.scannerman.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.DevicesOther
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Laptop
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Speaker
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.TabletAndroid
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.scannerman.data.DeviceType

val DeviceType.icon: ImageVector
    get() = when (this) {
        DeviceType.ROUTER -> Icons.Outlined.Router
        DeviceType.PHONE -> Icons.Outlined.PhoneAndroid
        DeviceType.TABLET -> Icons.Outlined.TabletAndroid
        DeviceType.COMPUTER -> Icons.Outlined.Computer
        DeviceType.LAPTOP -> Icons.Outlined.Laptop
        DeviceType.SERVER -> Icons.Outlined.Dns
        DeviceType.NAS -> Icons.Outlined.Storage
        DeviceType.PRINTER -> Icons.Outlined.Print
        DeviceType.TV -> Icons.Outlined.Tv
        DeviceType.MEDIA -> Icons.Outlined.Cast
        DeviceType.SPEAKER -> Icons.Outlined.Speaker
        DeviceType.CAMERA -> Icons.Outlined.Videocam
        DeviceType.CONSOLE -> Icons.Outlined.SportsEsports
        DeviceType.IOT -> Icons.Outlined.Lightbulb
        DeviceType.UNKNOWN -> Icons.Outlined.DevicesOther
    }

/** Device-type icon in a tinted circle with a status dot in the corner. */
@Composable
fun DeviceAvatar(type: DeviceType, statusColor: Color, dimmed: Boolean, size: Dp = 40.dp) {
    Box(Modifier.size(size)) {
        Box(
            Modifier
                .size(size)
                .background(
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (dimmed) 0.4f else 1f),
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                type.icon,
                contentDescription = type.label,
                modifier = Modifier.size(size * 0.58f),
                tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = if (dimmed) 0.5f else 1f),
            )
        }
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .size(size * 0.3f)
                .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                .background(statusColor, CircleShape)
        )
    }
}

/** Grid of type icons for manual override; null selection = "Auto". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeviceTypePicker(selected: DeviceType?, autoType: DeviceType?, onSelect: (DeviceType?) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TypeCell(
            icon = Icons.Filled.AutoAwesome,
            label = "Auto" + (autoType?.let { "\n(${it.label})" } ?: ""),
            selected = selected == null,
        ) { onSelect(null) }
        DeviceType.entries.filter { it != DeviceType.UNKNOWN }.forEach { t ->
            TypeCell(t.icon, t.label, selected == t) { onSelect(t) }
        }
    }
}

@Composable
private fun TypeCell(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier
            .width(72.dp)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, shape,
            )
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape,
            )
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.size(22.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}
