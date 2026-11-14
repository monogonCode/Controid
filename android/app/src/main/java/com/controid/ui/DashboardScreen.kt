package com.controid.ui

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.controid.protocol.LinkStatus
import com.controid.service.ShizukuState
import com.controid.ui.dock.SekaiBannerPill
import com.controid.ui.dock.SekaiIconButton
import com.controid.ui.dock.SekaiKeyPill
import com.controid.ui.dock.SekaiMetricBox
import com.controid.ui.dock.SekaiPlate
import com.controid.ui.dock.SekaiResolvedTheme
import com.controid.ui.dock.SekaiSectionHeader
import com.controid.ui.dock.SekaiStatusBadge

@Composable
fun DashboardScreen(
    state: EcosystemUiState,
    theme: SekaiResolvedTheme,
    onOpenTheme: () -> Unit,
    onRequestShizuku: () -> Unit,
    onStartHotspot: () -> Unit,
    onStopHotspot: () -> Unit,
    onClearPairing: () -> Unit,
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val scrollState = rememberScrollState()

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE ||
            maxWidth > maxHeight
        val wideLayout = isLandscape || maxWidth >= 560.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(
                    horizontal = if (isLandscape) 12.dp else 14.dp,
                    vertical = if (isLandscape) 8.dp else 10.dp
                ),
            verticalArrangement = Arrangement.spacedBy(if (isLandscape) 8.dp else 10.dp)
        ) {
            // Compact SekaiPop Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                SekaiBannerPill(
                    theme = theme,
                    title = "Panel Controid",
                    subtitle = if (state.tcpAuthenticated) {
                        "Enlazado • ${state.peerAddress ?: "PC activo"}"
                    } else if (state.hotspotActive) {
                        "Hotspot activo • ${state.hotspotSsid ?: "esperando PC"}"
                    } else {
                        "En espera • :55777"
                    },
                    icon = Icons.Outlined.Dashboard,
                    modifier = Modifier.weight(1f, fill = false)
                )

                Spacer(Modifier.width(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SekaiStatusBadge(
                        theme = theme,
                        text = if (state.tcpAuthenticated) "Conectado" else "Desconectado",
                        dotColor = if (state.tcpAuthenticated) theme.accentB else theme.pinkAccent
                    )
                    SekaiIconButton(
                        theme = theme,
                        icon = Icons.Outlined.ColorLens,
                        contentDescription = "Estética SekaiPop",
                        size = 36.dp,
                        onClick = onOpenTheme
                    )
                }
            }

            if (!state.statusDetail.isNullOrBlank()) {
                SekaiPlate(
                    theme = theme,
                    cornerRadius = 16.dp,
                    shadowOffset = 3.dp,
                    fillColor = theme.pinkAccent.copy(alpha = 0.25f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = state.statusDetail,
                        color = theme.ink,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }

            if (wideLayout) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ShizukuCard(
                        state = state,
                        theme = theme,
                        compact = true,
                        onRequestShizuku = onRequestShizuku,
                        modifier = Modifier.weight(1f)
                    )
                    HotspotBleCard(
                        state = state,
                        theme = theme,
                        compact = true,
                        onStartHotspot = onStartHotspot,
                        onStopHotspot = onStopHotspot,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TcpPairingCard(
                        state = state,
                        theme = theme,
                        compact = true,
                        onClearPairing = onClearPairing,
                        modifier = Modifier.weight(1f)
                    )
                    ScreenStreamCard(
                        state = state,
                        theme = theme,
                        compact = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                ShizukuCard(
                    state = state,
                    theme = theme,
                    compact = false,
                    onRequestShizuku = onRequestShizuku,
                    modifier = Modifier.fillMaxWidth()
                )
                HotspotBleCard(
                    state = state,
                    theme = theme,
                    compact = false,
                    onStartHotspot = onStartHotspot,
                    onStopHotspot = onStopHotspot,
                    modifier = Modifier.fillMaxWidth()
                )
                TcpPairingCard(
                    state = state,
                    theme = theme,
                    compact = false,
                    onClearPairing = onClearPairing,
                    modifier = Modifier.fillMaxWidth()
                )
                ScreenStreamCard(
                    state = state,
                    theme = theme,
                    compact = false,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun ShizukuCard(
    state: EcosystemUiState,
    theme: SekaiResolvedTheme,
    compact: Boolean,
    onRequestShizuku: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showGuide by rememberSaveable { mutableStateOf(false) }

    val statusLabel = when (state.shizukuState) {
        ShizukuState.READY -> "UID ${state.shizukuUid ?: 2000} OK"
        ShizukuState.PERMISSION_REQUIRED -> "Permiso"
        ShizukuState.DAEMON_UNAVAILABLE -> "Daemon Lost"
        ShizukuState.UNAVAILABLE -> if (state.shizukuInstalled) "Detenido" else "Offline"
    }
    val statusColor = when (state.shizukuState) {
        ShizukuState.READY -> theme.accentB
        ShizukuState.PERMISSION_REQUIRED -> theme.accentA
        ShizukuState.DAEMON_UNAVAILABLE, ShizukuState.UNAVAILABLE -> theme.pinkAccent
    }

    SekaiPlate(
        theme = theme,
        cornerRadius = 20.dp,
        shadowOffset = 3.5.dp,
        fillColor = theme.surface,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (compact) 12.dp else 14.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 7.dp else 9.dp)
        ) {
            SekaiSectionHeader(
                theme = theme,
                icon = Icons.Outlined.AdminPanelSettings,
                title = "Shizuku Bridge",
                accentColor = theme.accentB,
                badgeText = statusLabel,
                badgeColor = statusColor
            )

            Text(
                text = "VirtualDisplay UID 2000, hotspot 5 GHz y portapapeles en segundo plano.",
                color = theme.inkSub,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = if (compact) 2 else 3,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SekaiKeyPill(
                    theme = theme,
                    active = !state.shizukuGranted,
                    fillColor = if (state.shizukuGranted) theme.surfaceHover else theme.accentA,
                    shadowOffset = 2.5.dp,
                    onClick = { if (!state.shizukuGranted) onRequestShizuku() }
                ) {
                    Text(
                        text = if (state.shizukuGranted) "Autorizado" else "Dar permiso",
                        color = if (state.shizukuGranted) theme.inkSub else Color(0xFF1A1110),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black
                    )
                }

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .clickable { showGuide = !showGuide }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = if (showGuide) "Ocultar guía" else "Guía",
                        color = theme.ink,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Icon(
                        imageVector = if (showGuide) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = null,
                        tint = theme.ink,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            AnimatedVisibility(visible = showGuide) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(theme.surfaceHover)
                        .border(1.6.dp, theme.line.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Reconexión Shizuku",
                        color = theme.ink,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        text = "1. Abre Shizuku e inicia por Depuración inalámbrica o ADB.",
                        color = theme.inkSub,
                        fontSize = 11.sp
                    )
                    Text(
                        text = "2. Verifica UID 2000 (shell) y pulsa 'Dar permiso'.",
                        color = theme.inkSub,
                        fontSize = 11.sp
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
    theme: SekaiResolvedTheme,
    compact: Boolean,
    onStartHotspot: () -> Unit,
    onStopHotspot: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isActive = state.hotspotActive || state.linkStatus == LinkStatus.ACTIVE
    val isBusy = state.linkStatus == LinkStatus.STARTING
    val statusColor = when (state.linkStatus) {
        LinkStatus.ACTIVE -> theme.accentB
        LinkStatus.STARTING -> theme.accentA
        LinkStatus.IDLE -> theme.inkSub
        else -> theme.pinkAccent
    }

    SekaiPlate(
        theme = theme,
        cornerRadius = 20.dp,
        shadowOffset = 3.5.dp,
        fillColor = theme.surface,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (compact) 12.dp else 14.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 7.dp else 9.dp)
        ) {
            SekaiSectionHeader(
                theme = theme,
                icon = Icons.Outlined.WifiTethering,
                title = "BLE & Hotspot 5 GHz",
                accentColor = theme.accentA,
                badgeText = state.linkStatus.name,
                badgeColor = statusColor
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SekaiStatusBadge(
                    theme = theme,
                    text = if (state.hotspotBand5Ghz) "5 GHz" else "2.4 GHz",
                    dotColor = theme.accentB
                )
                SekaiStatusBadge(
                    theme = theme,
                    text = if (state.bleAdvertising) "BLE GATT Ready" else "BLE Idle",
                    dotColor = if (state.bleAdvertising) theme.accentA else theme.inkSub
                )
                if (state.connectedClients > 0) {
                    SekaiStatusBadge(
                        theme = theme,
                        text = "${state.connectedClients} cliente(s)",
                        dotColor = theme.pinkAccent
                    )
                }
            }

            if (!state.hotspotSsid.isNullOrBlank()) {
                Text(
                    text = "SSID: ${state.hotspotSsid}",
                    color = theme.ink,
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isActive) "Hotspot activo" else "Hotspot en espera",
                        color = theme.ink,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "Evita interferencias RF en 2.4 GHz con BLE HID",
                        color = theme.inkSub,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
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
    theme: SekaiResolvedTheme,
    compact: Boolean,
    onClearPairing: () -> Unit,
    modifier: Modifier = Modifier
) {
    val statusText = when {
        state.tcpAuthenticated -> "Autenticado"
        state.tcpConnected -> "Handshake"
        else -> "Puerto :55777"
    }
    val statusColor = when {
        state.tcpAuthenticated -> theme.accentB
        state.tcpConnected -> theme.accentA
        else -> theme.inkSub
    }

    SekaiPlate(
        theme = theme,
        cornerRadius = 20.dp,
        shadowOffset = 3.5.dp,
        fillColor = theme.surface,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (compact) 12.dp else 14.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 7.dp else 9.dp)
        ) {
            SekaiSectionHeader(
                theme = theme,
                icon = Icons.Outlined.Key,
                title = "Vínculo Ed25519",
                accentColor = theme.pinkAccent,
                badgeText = statusText,
                badgeColor = statusColor
            )

            if (!state.peerAddress.isNullOrBlank()) {
                Text(
                    text = "Peer: ${state.peerAddress}",
                    color = theme.ink,
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(theme.surfaceHover)
                    .border(1.6.dp, theme.line.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "Huella emparejada",
                    color = theme.inkSub,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = state.pairedFingerprint ?: "Sin peer (Trust-On-First-Use activo)",
                    color = theme.ink,
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                val canRevoke = state.isPaired || !state.pairedFingerprint.isNullOrBlank()
                SekaiKeyPill(
                    theme = theme,
                    fillColor = if (canRevoke) theme.pinkAccent.copy(alpha = 0.85f) else theme.surfaceHover,
                    shadowOffset = 2.5.dp,
                    onClick = { if (canRevoke) onClearPairing() }
                ) {
                    Text(
                        text = "Revocar vínculo",
                        color = if (canRevoke) Color(0xFF1A1110) else theme.inkSub,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }
        }
    }
}

@Composable
private fun ScreenStreamCard(
    state: EcosystemUiState,
    theme: SekaiResolvedTheme,
    compact: Boolean,
    modifier: Modifier = Modifier
) {
    SekaiPlate(
        theme = theme,
        cornerRadius = 20.dp,
        shadowOffset = 3.5.dp,
        fillColor = theme.surface,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (compact) 12.dp else 14.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 7.dp else 9.dp)
        ) {
            SekaiSectionHeader(
                theme = theme,
                icon = Icons.Outlined.Cast,
                title = "Live Screen Stream",
                accentColor = theme.accentB,
                badgeText = if (state.streamActive) "Streaming" else "En espera",
                badgeColor = if (state.streamActive) theme.accentB else theme.inkSub
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SekaiMetricBox(
                    theme = theme,
                    label = "Códec",
                    value = state.streamCodec.uppercase(),
                    modifier = Modifier.weight(1f)
                )
                SekaiMetricBox(
                    theme = theme,
                    label = "Resolución",
                    value = if (state.streamWidth > 0 && state.streamHeight > 0) {
                        "${state.streamWidth}x${state.streamHeight}"
                    } else {
                        "Auto"
                    },
                    modifier = Modifier.weight(1f)
                )
                SekaiMetricBox(
                    theme = theme,
                    label = "Tasa",
                    value = if (state.streamFps > 0) {
                        "${state.streamFps}fps/${state.streamBitrateMbps}M"
                    } else {
                        "Demanda"
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            Text(
                text = "SurfaceControl + MediaCodec Annex-B zero-copy con watchdog de heartbeat de 5 s.",
                color = theme.inkSub,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
