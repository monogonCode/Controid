package com.controid.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.controid.clipboard.ClipboardEntry
import com.controid.clipboard.ControidTileService
import com.controid.protocol.LinkStatus
import com.controid.service.EcosystemForegroundService
import com.controid.service.EcosystemSnapshot
import com.controid.service.EcosystemStateHub
import com.controid.service.ShizukuState
import com.controid.ui.dock.DockScreen
import com.controid.ui.dock.SekaiBackdrop
import com.controid.ui.dock.SekaiDockPrefs
import com.controid.ui.dock.SekaiPalettes
import com.controid.ui.dock.SekaiPlate
import com.controid.ui.dock.SekaiResolvedTheme
import com.controid.ui.dock.SekaiThemePickerDialog

@Composable
fun ControidApp() {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val systemDark = isSystemInDarkTheme()

    val snapshot by EcosystemStateHub.snapshot.collectAsState()
    val clipboardEntries by EcosystemStateHub.clipboardHistory.collectAsState()

    LaunchedEffect(Unit) {
        if (!snapshot.serviceRunning) {
            ensureServiceStarted(context)
        }
    }

    val uiState = remember(snapshot, clipboardEntries) {
        snapshot.toUiState(clipboardEntries)
    }

    var currentTab by rememberSaveable { mutableStateOf(ControidTab.DOCK) }
    var dockImmersive by rememberSaveable { mutableStateOf(false) }
    var trackpadImmersive by rememberSaveable { mutableStateOf(false) }

    // Shared SekaiPop appearance & dock behavior preferences across ALL tabs
    var paletteId by remember { mutableStateOf(SekaiDockPrefs.loadPaletteId(context)) }
    var darkMode by remember { mutableStateOf(SekaiDockPrefs.loadDarkMode(context, systemDark)) }
    var tapAlwaysNew by remember { mutableStateOf(SekaiDockPrefs.loadTapAlwaysNew(context)) }
    var showThemeModal by remember { mutableStateOf(false) }

    val sekaiTheme = remember(paletteId, darkMode) {
        SekaiPalettes.resolve(SekaiPalettes.byId(paletteId), darkMode)
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            view.context.findActivity()?.window?.let { window ->
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !sekaiTheme.isDark
                controller.isAppearanceLightNavigationBars = !sekaiTheme.isDark
            }
        }
    }

    ControidTheme(darkTheme = darkMode) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = sekaiTheme.backdropWash
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                SekaiBackdrop(theme = sekaiTheme)

                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE ||
                        maxWidth > maxHeight
                    val isFullscreenMode = (currentTab == ControidTab.DOCK && dockImmersive) ||
                        (currentTab == ControidTab.TRACKPAD && trackpadImmersive)

                    val rootModifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)

                    if (isLandscape) {
                        Row(modifier = rootModifier) {
                            if (!isFullscreenMode) {
                                SekaiSideNavRail(
                                    theme = sekaiTheme,
                                    uiState = uiState,
                                    currentTab = currentTab,
                                    onSelectTab = { currentTab = it }
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            ) {
                                ActiveTabContent(
                                    currentTab = currentTab,
                                    uiState = uiState,
                                    snapshot = snapshot,
                                    sekaiTheme = sekaiTheme,
                                    tapAlwaysNew = tapAlwaysNew,
                                    dockImmersive = dockImmersive,
                                    onToggleImmersive = { dockImmersive = !dockImmersive },
                                    trackpadImmersive = trackpadImmersive,
                                    onToggleTrackpadImmersive = { trackpadImmersive = !trackpadImmersive },
                                    onOpenTheme = { showThemeModal = true },
                                    onSelectTab = {
                                        dockImmersive = false
                                        trackpadImmersive = false
                                        currentTab = it
                                    }
                                )
                            }
                        }
                    } else {
                        Column(modifier = rootModifier) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                            ) {
                                ActiveTabContent(
                                    currentTab = currentTab,
                                    uiState = uiState,
                                    snapshot = snapshot,
                                    sekaiTheme = sekaiTheme,
                                    tapAlwaysNew = tapAlwaysNew,
                                    dockImmersive = dockImmersive,
                                    onToggleImmersive = { dockImmersive = !dockImmersive },
                                    trackpadImmersive = trackpadImmersive,
                                    onToggleTrackpadImmersive = { trackpadImmersive = !trackpadImmersive },
                                    onOpenTheme = { showThemeModal = true },
                                    onSelectTab = {
                                        dockImmersive = false
                                        trackpadImmersive = false
                                        currentTab = it
                                    }
                                )
                            }

                            if (!isFullscreenMode) {
                                SekaiBottomNavBar(
                                    theme = sekaiTheme,
                                    uiState = uiState,
                                    currentTab = currentTab,
                                    onSelectTab = { currentTab = it }
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showThemeModal) {
            SekaiThemePickerDialog(
                theme = sekaiTheme,
                selectedPaletteId = paletteId,
                darkMode = darkMode,
                tapAlwaysNew = tapAlwaysNew,
                onSelectPalette = { newId ->
                    paletteId = newId
                    SekaiDockPrefs.savePaletteId(context, newId)
                },
                onToggleDark = { newDark ->
                    darkMode = newDark
                    SekaiDockPrefs.saveDarkMode(context, newDark)
                },
                onToggleTapNew = { newVal ->
                    tapAlwaysNew = newVal
                    SekaiDockPrefs.saveTapAlwaysNew(context, newVal)
                },
                onRefreshCatalog = {
                    EcosystemStateHub.refreshDockApps(force = true)
                },
                onDismiss = { showThemeModal = false }
            )
        }
    }
}

@Composable
private fun ActiveTabContent(
    currentTab: ControidTab,
    uiState: EcosystemUiState,
    snapshot: EcosystemSnapshot,
    sekaiTheme: SekaiResolvedTheme,
    tapAlwaysNew: Boolean,
    dockImmersive: Boolean,
    onToggleImmersive: () -> Unit,
    trackpadImmersive: Boolean,
    onToggleTrackpadImmersive: () -> Unit,
    onOpenTheme: () -> Unit,
    onSelectTab: (ControidTab) -> Unit
) {
    val context = LocalContext.current
    when (currentTab) {
        ControidTab.DOCK -> DockScreen(
            uiState = uiState,
            theme = sekaiTheme,
            tapAlwaysNew = tapAlwaysNew,
            immersiveMode = dockImmersive,
            onToggleImmersive = onToggleImmersive,
            onOpenTheme = onOpenTheme,
            onOpenDashboard = { onSelectTab(ControidTab.DASHBOARD) }
        )

        ControidTab.DASHBOARD -> DashboardScreen(
            state = uiState,
            theme = sekaiTheme,
            onOpenTheme = onOpenTheme,
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
            theme = sekaiTheme,
            immersiveMode = trackpadImmersive,
            onToggleImmersive = onToggleTrackpadImmersive,
            onOpenTheme = onOpenTheme,
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
            theme = sekaiTheme,
            onOpenTheme = onOpenTheme,
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

@Composable
private fun SekaiBottomNavBar(
    theme: SekaiResolvedTheme,
    uiState: EcosystemUiState,
    currentTab: ControidTab,
    onSelectTab: (ControidTab) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        SekaiPlate(
            theme = theme,
            cornerRadius = 22.dp,
            borderWidth = 2.4.dp,
            shadowOffset = 3.5.dp,
            fillColor = theme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ControidTab.entries.forEach { tab ->
                    val selected = currentTab == tab
                    SekaiNavTabItem(
                        theme = theme,
                        tab = tab,
                        selected = selected,
                        showStatusDot = tab == ControidTab.DASHBOARD,
                        statusDotColor = connectionDotColor(theme, uiState),
                        onClick = { onSelectTab(tab) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun SekaiSideNavRail(
    theme: SekaiResolvedTheme,
    uiState: EcosystemUiState,
    currentTab: ControidTab,
    onSelectTab: (ControidTab) -> Unit
) {
    Box(
        modifier = Modifier
            .width(72.dp)
            .fillMaxHeight()
            .padding(start = 8.dp, top = 6.dp, bottom = 6.dp)
    ) {
        SekaiPlate(
            theme = theme,
            cornerRadius = 20.dp,
            borderWidth = 2.4.dp,
            shadowOffset = 3.5.dp,
            fillColor = theme.surface,
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                ControidTab.entries.forEach { tab ->
                    val selected = currentTab == tab
                    SekaiNavTabItem(
                        theme = theme,
                        tab = tab,
                        selected = selected,
                        showStatusDot = tab == ControidTab.DASHBOARD,
                        statusDotColor = connectionDotColor(theme, uiState),
                        onClick = { onSelectTab(tab) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun SekaiNavTabItem(
    theme: SekaiResolvedTheme,
    tab: ControidTab,
    selected: Boolean,
    showStatusDot: Boolean,
    statusDotColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(14.dp)
    val bg = if (selected) theme.accentA else Color.Transparent
    val fg = if (selected) Color(0xFF1A1110) else theme.inkSub

    Column(
        modifier = modifier
            .clip(shape)
            .background(bg)
            .then(if (selected) Modifier.border(2.dp, theme.line, shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            Icon(
                imageVector = tabIcon(tab),
                contentDescription = tab.title,
                tint = fg,
                modifier = Modifier.size(19.dp)
            )
            if (showStatusDot) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .rotate(45f)
                        .background(statusDotColor, RoundedCornerShape(1.5.dp))
                        .border(1.2.dp, theme.line, RoundedCornerShape(1.5.dp))
                )
            }
        }
        Text(
            text = shortTabLabel(tab),
            color = if (selected) Color(0xFF1A1110) else theme.ink,
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.Black else FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun connectionDotColor(theme: SekaiResolvedTheme, state: EcosystemUiState): Color = when {
    state.tcpAuthenticated -> theme.accentB
    state.hotspotActive -> theme.accentA
    else -> theme.pinkAccent
}

private fun shortTabLabel(tab: ControidTab): String = when (tab) {
    ControidTab.DOCK -> "Dock"
    ControidTab.DASHBOARD -> "Panel"
    ControidTab.TRACKPAD -> "Pad"
    ControidTab.CLIPBOARD -> "Clip"
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

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun tabIcon(tab: ControidTab) = when (tab) {
    ControidTab.DOCK -> Icons.Outlined.Apps
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
