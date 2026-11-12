package com.controid.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.controid.protocol.LinkStatus
import com.controid.service.ShizukuState

@Composable
fun DashboardScreen(
    state: EcosystemUiState,
    onRequestShizuku: () -> Unit,
    onStartHotspot: () -> Unit,
    onStopHotspot: () -> Unit,
    onClearPairing: () -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val wideLayout = maxWidth >= 700.dp
        val scrollState = rememberScrollState()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!state.statusDetail.isNullOrBlank()) {
                ElevatedCard(
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = state.statusDetail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
            }

            if (wideLayout) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ShizukuCard(
                        state = state,
                        onRequestShizuku = onRequestShizuku,
                        modifier = Modifier.weight(1f)
                    )
                    HotspotBleCard(
                        state = state,
                        onStartHotspot = onStartHotspot,
                        onStopHotspot = onStopHotspot,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TcpPairingCard(
                        state = state,
                        onClearPairing = onClearPairing,
                        modifier = Modifier.weight(1f)
                    )
                    ScreenStreamCard(
                        state = state,
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                ShizukuCard(
                    state = state,
                    onRequestShizuku = onRequestShizuku,
                    modifier = Modifier.fillMaxWidth()
                )
                HotspotBleCard(
                    state = state,
                    onStartHotspot = onStartHotspot,
                    onStopHotspot = onStopHotspot,
                    modifier = Modifier.fillMaxWidth()
                )
                TcpPairingCard(
                    state = state,
                    onClearPairing = onClearPairing,
                    modifier = Modifier.fillMaxWidth()
                )
                ScreenStreamCard(
                    state = state,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun ShizukuCard(
    state: EcosystemUiState,
    onRequestShizuku: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showGuide by rememberSaveable { mutableStateOf(!state.shizukuGranted) }

    val statusLabel = when (state.shizukuState) {
        ShizukuState.READY -> "UID ${state.shizukuUid ?: 2000} Authorized"
        ShizukuState.PERMISSION_REQUIRED -> "Grant Required"
        ShizukuState.DAEMON_UNAVAILABLE -> "Daemon Lost"
        ShizukuState.UNAVAILABLE -> if (state.shizukuInstalled) "Service Stopped" else "Shizuku Offline"
    }
    val statusColor = when (state.shizukuState) {
        ShizukuState.READY -> MaterialTheme.colorScheme.primary
        ShizukuState.PERMISSION_REQUIRED -> MaterialTheme.colorScheme.tertiary
        ShizukuState.DAEMON_UNAVAILABLE, ShizukuState.UNAVAILABLE -> MaterialTheme.colorScheme.error
    }

    ElevatedCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SectionHeader(
                icon = Icons.Outlined.AdminPanelSettings,
                title = "Shizuku Privileged Bridge",
                badgeText = statusLabel,
                badgeColor = statusColor
            )

            Text(
                text = "Required for UID 2000 shell VirtualDisplay capture, 5 GHz SoftAP tethering, and background clipboard access.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalButton(
                    onClick = onRequestShizuku,
                    enabled = !state.shizukuGranted
                ) {
                    Text(if (state.shizukuGranted) "Authorized" else "Request Permission")
                }

                TextButton(onClick = { showGuide = !showGuide }) {
                    Text(if (showGuide) "Hide Guide" else "Reconnect Guide")
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = if (showGuide) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            AnimatedVisibility(visible = showGuide) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Shizuku Reconnect Steps",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "1. Open Shizuku and start via Wireless Debugging (or USB: adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh).",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "2. Confirm Shizuku reports UID 2000 (adb/shell).",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "3. Tap Request Permission above and allow Controid.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HotspotBleCard(
    state: EcosystemUiState,
    onStartHotspot: () -> Unit,
    onStopHotspot: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isActive = state.hotspotActive || state.linkStatus == LinkStatus.ACTIVE
    val isBusy = state.linkStatus == LinkStatus.STARTING
    val statusColor = when (state.linkStatus) {
        LinkStatus.ACTIVE -> MaterialTheme.colorScheme.primary
        LinkStatus.STARTING -> MaterialTheme.colorScheme.tertiary
        LinkStatus.IDLE -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.error
    }

    ElevatedCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SectionHeader(
                icon = Icons.Outlined.WifiTethering,
                title = "BLE GATT & 5 GHz Hotspot",
                badgeText = state.linkStatus.name,
                badgeColor = statusColor
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = {
                        Text(if (state.hotspotBand5Ghz) "5 GHz Band" else "2.4 GHz Band")
                    }
                )
                AssistChip(
                    onClick = {},
                    enabled = false,
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Bluetooth,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    label = {
                        Text(if (state.bleAdvertising) "BLE GATT Ready" else "BLE Idle")
                    }
                )
                if (state.connectedClients > 0) {
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text("${state.connectedClients} Client(s)") }
                    )
                }
            }

            if (!state.hotspotSsid.isNullOrBlank()) {
                Text(
                    text = "SSID: ${state.hotspotSsid}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isActive) "Hotspot Operational" else "Hotspot Standby",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Avoids 2.4 GHz BLE HID RF contention",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = isActive || isBusy,
                    onCheckedChange = { enable ->
                        if (enable) onStartHotspot() else onStopHotspot()
                    }
                )
            }
        }
    }
}

@Composable
private fun TcpPairingCard(
    state: EcosystemUiState,
    onClearPairing: () -> Unit,
    modifier: Modifier = Modifier
) {
    val statusText = when {
        state.tcpAuthenticated -> "Authenticated"
        state.tcpConnected -> "Handshake Pending"
        else -> "Listening :55777"
    }
    val statusColor = when {
        state.tcpAuthenticated -> MaterialTheme.colorScheme.primary
        state.tcpConnected -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.outline
    }

    ElevatedCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SectionHeader(
                icon = Icons.Outlined.Key,
                title = "TCP L7 Ed25519 Pairing",
                badgeText = statusText,
                badgeColor = statusColor
            )

            if (!state.peerAddress.isNullOrBlank()) {
                Text(
                    text = "Peer: ${state.peerAddress}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Paired Peer Fingerprint",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = state.pairedFingerprint ?: "No peer paired (Trust-On-First-Use enabled)",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(
                    onClick = onClearPairing,
                    enabled = state.isPaired || !state.pairedFingerprint.isNullOrBlank(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Revoke Pairing")
                }
            }
        }
    }
}

@Composable
private fun ScreenStreamCard(
    state: EcosystemUiState,
    modifier: Modifier = Modifier
) {
    val badgeColor = if (state.streamActive) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline
    }

    ElevatedCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SectionHeader(
                icon = Icons.Outlined.Cast,
                title = "Live Screen Stream",
                badgeText = if (state.streamActive) "Streaming" else "Standby",
                badgeColor = badgeColor
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                MetricColumn(
                    label = "Codec",
                    value = state.streamCodec.uppercase(),
                    modifier = Modifier.weight(1f)
                )
                MetricColumn(
                    label = "Resolution",
                    value = if (state.streamWidth > 0 && state.streamHeight > 0) {
                        "${state.streamWidth}x${state.streamHeight}"
                    } else {
                        "Auto"
                    },
                    modifier = Modifier.weight(1f)
                )
                MetricColumn(
                    label = "Rate",
                    value = if (state.streamFps > 0) {
                        "${state.streamFps}fps / ${state.streamBitrateMbps}M"
                    } else {
                        "On Demand"
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            HorizontalDivider()

            Text(
                text = "Zero-copy SurfaceControl + MediaCodec Annex-B pipeline protected by strict 5s heartbeat watchdog.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SectionHeader(
    icon: ImageVector,
    title: String,
    badgeText: String,
    badgeColor: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        AssistChip(
            onClick = {},
            enabled = false,
            leadingIcon = {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(badgeColor)
                )
            },
            label = {
                Text(
                    text = badgeText,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1
                )
            },
            colors = AssistChipDefaults.assistChipColors(
                disabledContainerColor = badgeColor.copy(alpha = 0.12f),
                disabledLabelColor = MaterialTheme.colorScheme.onSurface
            )
        )
    }
}

@Composable
private fun MetricColumn(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace
        )
    }
}
