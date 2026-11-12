package com.controid.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.controid.R
import com.controid.clipboard.ClipboardEntry
import com.controid.clipboard.ControidTileService
import com.controid.protocol.LinkStatus
import com.controid.service.EcosystemForegroundService
import com.controid.service.EcosystemSnapshot
import com.controid.service.EcosystemStateHub
import com.controid.service.ShizukuState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControidApp() {
    val context = LocalContext.current
    val snapshot by EcosystemStateHub.snapshot.collectAsState()
    val clipboardEntries by EcosystemStateHub.clipboardHistory.collectAsState()
    val appIcon = remember(context) {
        runCatching {
            val sizePx = (24 * context.resources.displayMetrics.density).toInt().coerceAtLeast(48)
            ContextCompat.getDrawable(context, R.mipmap.ic_launcher)
                ?.toBitmap(width = sizePx, height = sizePx)
                ?.asImageBitmap()
        }.getOrNull()
    }

    LaunchedEffect(Unit) {
        if (!snapshot.serviceRunning) {
            ensureServiceStarted(context)
        }
    }

    val uiState = remember(snapshot, clipboardEntries) {
        snapshot.toUiState(clipboardEntries)
    }

    var currentTab by rememberSaveable { mutableStateOf(ControidTab.DASHBOARD) }

    ControidTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val useRail = maxWidth >= 600.dp

                Scaffold(
                    topBar = {
                        CenterAlignedTopAppBar(
                            title = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (appIcon != null) {
                                        Image(
                                            bitmap = appIcon,
                                            contentDescription = null,
                                            contentScale = ContentScale.Fit,
                                            modifier = Modifier
                                                .size(24.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                        )
                                    }
                                    Text(
                                        text = "Controid",
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    ConnectionDot(uiState)
                                }
                            }
                        )
                    },
                    bottomBar = {
                        if (!useRail) {
                            NavigationBar {
                                ControidTab.entries.forEach { tab ->
                                    NavigationBarItem(
                                        selected = currentTab == tab,
                                        onClick = { currentTab = tab },
                                        icon = { Icon(tabIcon(tab), contentDescription = tab.title) },
                                        label = { Text(tab.title) }
                                    )
                                }
                            }
                        }
                    }
                ) { innerPadding ->
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        if (useRail) {
                            NavigationRail {
                                ControidTab.entries.forEach { tab ->
                                    NavigationRailItem(
                                        selected = currentTab == tab,
                                        onClick = { currentTab = tab },
                                        icon = { Icon(tabIcon(tab), contentDescription = tab.title) },
                                        label = { Text(tab.title) }
                                    )
                                }
                            }
                        }

                        Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                            when (currentTab) {
                                ControidTab.DASHBOARD -> DashboardScreen(
                                    state = uiState,
                                    onRequestShizuku = {
                                        if (!snapshot.serviceRunning) ensureServiceStarted(context)
                                        EcosystemStateHub.requestShizukuPermission()
                                    },
                                    onStartHotspot = {
                                        if (!snapshot.serviceRunning) ensureServiceStarted(context)
                                        EcosystemStateHub.startHotspot(prefer5Ghz = true)
                                    },
                                    onStopHotspot = {
                                        EcosystemStateHub.stopHotspot()
                                    },
                                    onClearPairing = {
                                        EcosystemStateHub.clearPairing()
                                    }
                                )

                                ControidTab.TRACKPAD -> TrackpadScreen(
                                    state = uiState,
                                    onMouseMove = { dx, dy, wheel ->
                                        EcosystemStateHub.sendMouseMove(dx, dy, wheel)
                                    },
                                    onMouseButtons = { mask ->
                                        EcosystemStateHub.sendMouseButtons(mask)
                                    },
                                    onSendKey = { modifiers, hidUsage, pressed, linuxKeycode ->
                                        EcosystemStateHub.sendKey(
                                            modifiers = modifiers,
                                            hidUsage = hidUsage,
                                            pressed = pressed,
                                            linuxKeycode = linuxKeycode
                                        )
                                    },
                                    onSendConsumer = { usage ->
                                        EcosystemStateHub.sendConsumer(usage)
                                    },
                                    onReconnectHid = {
                                        EcosystemStateHub.reconnectHid()
                                    }
                                )

                                ControidTab.CLIPBOARD -> ClipboardScreen(
                                    state = uiState,
                                    onSendClipboardNow = {
                                        if (!snapshot.serviceRunning) {
                                            ensureServiceStarted(context, ControidTileService.ACTION_SYNC_CLIPBOARD)
                                        }
                                        EcosystemStateHub.sendClipboardNow()
                                    },
                                    onCopyTextToClipboard = { text ->
                                        if (snapshot.serviceRunning) {
                                            EcosystemStateHub.copyTextToClipboard(text)
                                        } else {
                                            copyTextToSystemClipboard(context, text)
                                        }
                                    },
                                    onCopyEntryToClipboard = { entry ->
                                        if (snapshot.serviceRunning) {
                                            EcosystemStateHub.copyClipboardEntry(entry)
                                        } else {
                                            copyEntryLocally(context, entry)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun EcosystemSnapshot.toUiState(
    clipboardEntries: List<ClipboardEntry>
): EcosystemUiState = EcosystemUiState(
    serviceRunning = serviceRunning,
    shizukuState = shizukuState,
    shizukuInstalled = shizukuInstalled,
    shizukuRunning = shizukuState == ShizukuState.READY || shizukuState == ShizukuState.PERMISSION_REQUIRED,
    shizukuGranted = shizukuState == ShizukuState.READY,
    shizukuUid = shizukuUid,
    linkStatus = tetheringStatus,
    hotspotActive = tetheringStatus == LinkStatus.ACTIVE,
    hotspotSsid = hotspotSsid,
    hotspotBand5Ghz = hotspotBand5Ghz,
    bleAdvertising = bleActive,
    connectedClients = connectedClients,
    tcpConnected = tcpConnected,
    tcpAuthenticated = tcpAuthenticated,
    peerAddress = peerAddress,
    pairedFingerprint = pairedKeyFingerprint,
    isPaired = !pairedKeyFingerprint.isNullOrBlank(),
    streamActive = isStreaming,
    streamWidth = streamWidth,
    streamHeight = streamHeight,
    streamFps = streamFps,
    streamBitrateMbps = streamBitrateMbps,
    streamCodec = streamCodec,
    hidConnected = hidConnected,
    fallbackInputActive = hidFallbackActive,
    clipboardBackgroundGranted = clipboardBackgroundGranted,
    clipboardEntries = clipboardEntries,
    statusDetail = lastError
)

private fun copyEntryLocally(context: Context, entry: ClipboardEntry) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    when {
        entry.textPreview != null -> {
            runCatching { cm.setPrimaryClip(ClipData.newPlainText("Controid", entry.textPreview)) }
        }

        entry.fileUri != null -> {
            runCatching {
                cm.setPrimaryClip(ClipData.newUri(context.contentResolver, "Controid", entry.fileUri))
            }
        }
    }
}

private fun ensureServiceStarted(context: Context, action: String? = null) {
    val intent = Intent(context, EcosystemForegroundService::class.java).apply {
        if (action != null) this.action = action
    }
    runCatching { ContextCompat.startForegroundService(context, intent) }
        .onFailure { runCatching { context.startService(intent) } }
}

@Composable
private fun ConnectionDot(state: EcosystemUiState) {
    val color = when {
        state.tcpAuthenticated -> MaterialTheme.colorScheme.primary
        state.hotspotActive -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.outline
    }
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color)
    )
}

private fun tabIcon(tab: ControidTab) = when (tab) {
    ControidTab.DASHBOARD -> Icons.Outlined.Dashboard
    ControidTab.TRACKPAD -> Icons.Outlined.TouchApp
    ControidTab.CLIPBOARD -> Icons.Outlined.ContentPaste
}

@Composable
private fun ControidTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
