package com.controid.ui.dock

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.VolumeDown
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.AddBox
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.controid.dock.DockAppItem
import com.controid.dock.DockCategoryFilter
import com.controid.dock.DockLaunchFeedback
import com.controid.dock.DockSnapshotState
import com.controid.dock.DockWorkspace
import com.controid.service.EcosystemStateHub
import com.controid.ui.EcosystemUiState
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DockScreen(
    uiState: EcosystemUiState,
    theme: SekaiResolvedTheme,
    tapAlwaysNew: Boolean,
    immersiveMode: Boolean,
    onToggleImmersive: () -> Unit,
    onOpenTheme: () -> Unit,
    onOpenDashboard: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current

    val dockSnapshot by EcosystemStateHub.dockSnapshot.collectAsState()
    val dockIcons by EcosystemStateHub.dockIcons.collectAsState()
    val dockFeedback by EcosystemStateHub.dockFeedback.collectAsState()

    var deckMode by remember { mutableStateOf(SekaiDockPrefs.loadDeckMode(context)) }
    var immersiveSearchOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(immersiveMode) {
        if (!immersiveMode) {
            immersiveSearchOpen = false
        }
    }

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedCategory by rememberSaveable { mutableStateOf(DockCategoryFilter.ALL) }
    var actionTargetApp by remember { mutableStateOf<DockAppItem?>(null) }
    var visibleFeedback by remember { mutableStateOf<DockLaunchFeedback?>(null) }

    // Live clock ticker for the SekaiPop header pill
    var nowTime by remember { mutableStateOf(currentClockPair()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowTime = currentClockPair()
            delay(15_000L)
        }
    }

    // Auto-fetch dock apps whenever authenticated and cache is empty
    LaunchedEffect(uiState.tcpAuthenticated) {
        if (uiState.tcpAuthenticated && dockSnapshot.apps.isEmpty()) {
            EcosystemStateHub.refreshDockApps(force = false)
        }
    }

    // Show launch feedback pill briefly (only once per unique timestamp)
    var lastShownFeedbackTs by rememberSaveable { mutableStateOf(0L) }
    LaunchedEffect(dockFeedback?.timestampMs) {
        val fb = dockFeedback ?: return@LaunchedEffect
        if (fb.timestampMs != lastShownFeedbackTs &&
            System.currentTimeMillis() - fb.timestampMs < 3500L
        ) {
            lastShownFeedbackTs = fb.timestampMs
            visibleFeedback = fb
            delay(2000L)
            if (visibleFeedback?.timestampMs == fb.timestampMs) {
                visibleFeedback = null
            }
        }
    }

    val pinnedSet = remember(dockSnapshot.pinnedIds) { dockSnapshot.pinnedIds.toSet() }
    val frequentSet = remember(dockSnapshot.frequentIds) { dockSnapshot.frequentIds.toSet() }

    val filteredApps = remember(dockSnapshot.apps, searchQuery, selectedCategory, pinnedSet, frequentSet) {
        val query = searchQuery.trim()
        dockSnapshot.apps
            .asSequence()
            .filter { app -> selectedCategory.matches(app, pinnedSet, frequentSet) }
            .filter { app ->
                if (query.isEmpty()) {
                    true
                } else {
                    app.name.contains(query, ignoreCase = true) ||
                        (app.genericName?.contains(query, ignoreCase = true) == true) ||
                        app.id.contains(query, ignoreCase = true) ||
                        app.categories.any { it.contains(query, ignoreCase = true) }
                }
            }
            .sortedWith(
                compareByDescending<DockAppItem> { it.focused }
                    .thenByDescending { it.runningCount > 0 }
                    .thenByDescending { it.id in pinnedSet }
                    .thenByDescending { it.launchCount }
                    .thenBy { it.name.lowercase(Locale.ROOT) }
            )
            .toList()
    }

    // Sticky Quick Dock apps: pinned in user order + running unpinned apps
    val quickDockApps = remember(dockSnapshot.apps, dockSnapshot.pinnedIds) {
        val byId = dockSnapshot.apps.associateBy { it.id }
        val pinnedOrdered = dockSnapshot.pinnedIds.mapNotNull { byId[it] }
        val pinnedLookup = dockSnapshot.pinnedIds.toSet()
        val runningUnpinned = dockSnapshot.apps
            .filter { it.runningCount > 0 && it.id !in pinnedLookup }
            .sortedWith(compareByDescending<DockAppItem> { it.focused }.thenBy { it.name.lowercase(Locale.ROOT) })
        val combined = pinnedOrdered + runningUnpinned
        if (combined.isNotEmpty()) combined else dockSnapshot.apps.take(8)
    }

    val launchAppAction: (DockAppItem, String?) -> Unit = { app, explicitAction ->
        val action = explicitAction ?: if (tapAlwaysNew) "new_window" else "activate"
        EcosystemStateHub.launchDockApp(app.id, action)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .then(if (immersiveMode) Modifier.windowInsetsPadding(WindowInsets.safeDrawing) else Modifier)
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE ||
                maxWidth > maxHeight

            if (isLandscape) {
                LandscapeDockLayout(
                    theme = theme,
                    uiState = uiState,
                    snapshot = dockSnapshot,
                    filteredApps = filteredApps,
                    quickDockApps = quickDockApps,
                    pinnedSet = pinnedSet,
                    frequentSet = frequentSet,
                    icons = dockIcons,
                    clockPair = nowTime,
                    searchQuery = searchQuery,
                    onSearchChange = { searchQuery = it },
                    selectedCategory = selectedCategory,
                    onCategoryChange = { selectedCategory = it },
                    deckMode = deckMode,
                    onToggleDeckMode = {
                        deckMode = !deckMode
                        SekaiDockPrefs.saveDeckMode(context, deckMode)
                    },
                    immersiveMode = immersiveMode,
                    immersiveSearchOpen = immersiveSearchOpen,
                    onToggleImmersiveSearch = { immersiveSearchOpen = !immersiveSearchOpen },
                    onToggleImmersive = onToggleImmersive,
                    onOpenTheme = onOpenTheme,
                    onOpenDashboard = onOpenDashboard,
                    onTapApp = { app -> launchAppAction(app, null) },
                    onLongPressApp = { app -> actionTargetApp = app }
                )
            } else {
                PortraitDockLayout(
                    theme = theme,
                    uiState = uiState,
                    snapshot = dockSnapshot,
                    filteredApps = filteredApps,
                    pinnedSet = pinnedSet,
                    frequentSet = frequentSet,
                    icons = dockIcons,
                    clockPair = nowTime,
                    searchQuery = searchQuery,
                    onSearchChange = { searchQuery = it },
                    selectedCategory = selectedCategory,
                    onCategoryChange = { selectedCategory = it },
                    deckMode = deckMode,
                    onToggleDeckMode = {
                        deckMode = !deckMode
                        SekaiDockPrefs.saveDeckMode(context, deckMode)
                    },
                    immersiveMode = immersiveMode,
                    immersiveSearchOpen = immersiveSearchOpen,
                    onToggleImmersiveSearch = { immersiveSearchOpen = !immersiveSearchOpen },
                    onToggleImmersive = onToggleImmersive,
                    onOpenTheme = onOpenTheme,
                    onOpenDashboard = onOpenDashboard,
                    onTapApp = { app -> launchAppAction(app, null) },
                    onLongPressApp = { app -> actionTargetApp = app }
                )
            }
        }

        // Launch toast feedback pill
        val rememberedFeedback = remember { mutableStateOf(visibleFeedback) }
        if (visibleFeedback != null) {
            rememberedFeedback.value = visibleFeedback
        }
        AnimatedVisibility(
            visible = visibleFeedback != null,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 6.dp, start = 16.dp, end = 16.dp)
        ) {
            rememberedFeedback.value?.let { fb ->
                val appName = dockSnapshot.apps.firstOrNull { it.id == fb.desktopId }?.name
                    ?: fb.desktopId
                val msg = if (fb.ok) {
                    if (fb.desktopId.isBlank()) {
                        when {
                            fb.action.startsWith("workspace") -> "Espacio de trabajo"
                            fb.action.startsWith("media") -> "Multimedia"
                            fb.action.startsWith("volume") -> "Volumen"
                            fb.action == "lock" -> "Bloqueando PC"
                            fb.action == "screenshot" -> "Captura de pantalla"
                            else -> "Acción aplicada"
                        }
                    } else {
                        when (fb.action) {
                            "close" -> "Cerrando $appName"
                            "new_window" -> "Nueva ventana: $appName"
                            else -> "Abriendo $appName"
                        }
                    }
                } else {
                    fb.error ?: if (appName.isNotBlank()) "Error ejecutando $appName" else "Error en acción"
                }
                SekaiPlate(
                    theme = theme,
                    cornerRadius = 999.dp,
                    shadowOffset = 3.dp,
                    fillColor = if (fb.ok) theme.accentA else theme.pinkAccent
                ) {
                    Text(
                        text = msg,
                        color = Color(0xFF1A1110),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }

    // Long-press app actions dialog
    actionTargetApp?.let { target ->
        val isPinned = target.id in pinnedSet
        DockAppActionDialog(
            app = target,
            icon = dockIcons[target.id],
            theme = theme,
            isPinned = isPinned,
            onDismiss = { actionTargetApp = null },
            onActivate = {
                actionTargetApp = null
                launchAppAction(target, "activate")
            },
            onNewWindow = {
                actionTargetApp = null
                launchAppAction(target, "new_window")
            },
            onTogglePin = {
                actionTargetApp = null
                EcosystemStateHub.setDockPinned(target.id, !isPinned)
            },
            onCloseApp = {
                actionTargetApp = null
                launchAppAction(target, "close")
            }
        )
    }
}

@Composable
private fun PortraitDockLayout(
    theme: SekaiResolvedTheme,
    uiState: EcosystemUiState,
    snapshot: DockSnapshotState,
    filteredApps: List<DockAppItem>,
    pinnedSet: Set<String>,
    frequentSet: Set<String>,
    icons: Map<String, ImageBitmap>,
    clockPair: Pair<String, String>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    selectedCategory: DockCategoryFilter,
    onCategoryChange: (DockCategoryFilter) -> Unit,
    deckMode: Boolean,
    onToggleDeckMode: () -> Unit,
    immersiveMode: Boolean,
    immersiveSearchOpen: Boolean,
    onToggleImmersiveSearch: () -> Unit,
    onToggleImmersive: () -> Unit,
    onOpenTheme: () -> Unit,
    onOpenDashboard: () -> Unit,
    onTapApp: (DockAppItem) -> Unit,
    onLongPressApp: (DockAppItem) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                horizontal = if (immersiveMode) 8.dp else 12.dp,
                vertical = if (immersiveMode) 6.dp else 10.dp
            )
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(if (immersiveMode) 6.dp else 8.dp)
    ) {
        if (!immersiveMode) {
            // Header row: Clock + DE badge + quick control pills
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SekaiPlate(
                        theme = theme,
                        cornerRadius = 14.dp,
                        shadowOffset = 2.5.dp,
                        fillColor = theme.surface
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
                            Text(
                                text = clockPair.first,
                                color = theme.ink,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = clockPair.second,
                                color = theme.inkSub,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    DeStatusChip(
                        theme = theme,
                        uiState = uiState,
                        snapshot = snapshot,
                        onClick = onOpenDashboard
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HeaderIconKey(
                        theme = theme,
                        icon = if (deckMode) Icons.Outlined.GridView else Icons.Outlined.ViewAgenda,
                        contentDescription = "Cambiar vista",
                        active = deckMode,
                        onClick = onToggleDeckMode
                    )
                    HeaderIconKey(
                        theme = theme,
                        icon = Icons.Outlined.ColorLens,
                        contentDescription = "Estilo SekaiPop",
                        onClick = onOpenTheme
                    )
                    HeaderIconKey(
                        theme = theme,
                        icon = Icons.Outlined.Fullscreen,
                        contentDescription = "Modo pantalla completa",
                        active = false,
                        onClick = onToggleImmersive
                    )
                }
            }

            // Workspaces strip (media controls live cleanly in SekaiCavaMediaBar below)
            if (snapshot.workspaces.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    WorkspacesRow(theme = theme, workspaces = snapshot.workspaces)
                }
            }

            // Search pill
            SekaiSearchPill(
                theme = theme,
                query = searchQuery,
                onQueryChange = onSearchChange,
                placeholder = "Buscar app o abrir rápido..."
            )

            // Categories strip
            CategoryChipsStrip(
                theme = theme,
                apps = snapshot.apps,
                pinnedSet = pinnedSet,
                frequentSet = frequentSet,
                selected = selectedCategory,
                onSelect = onCategoryChange
            )
        } else if (immersiveSearchOpen) {
            ImmersiveToolsBar(
                theme = theme,
                snapshot = snapshot,
                pinnedSet = pinnedSet,
                frequentSet = frequentSet,
                searchQuery = searchQuery,
                onSearchChange = onSearchChange,
                selectedCategory = selectedCategory,
                onCategoryChange = onCategoryChange,
                deckMode = deckMode,
                onToggleDeckMode = onToggleDeckMode,
                onCloseTools = onToggleImmersiveSearch,
                onExitImmersive = onToggleImmersive
            )
        }

        // Connection warning banner if disconnected
        if (!uiState.tcpAuthenticated && !immersiveMode) {
            DisconnectedBanner(
                theme = theme,
                hasCachedApps = snapshot.apps.isNotEmpty(),
                onConnectClick = onOpenDashboard
            )
        }

        // Main App Plate (Grid or Deck) + floating mini-pill in fullscreen
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            MainCatalogSurface(
                theme = theme,
                snapshot = snapshot,
                filteredApps = filteredApps,
                pinnedSet = pinnedSet,
                icons = icons,
                deckMode = deckMode,
                isLandscape = false,
                extraTopPadding = if (immersiveMode && !immersiveSearchOpen) 50.dp else 0.dp,
                onTapApp = onTapApp,
                onLongPressApp = onLongPressApp,
                onRefresh = { EcosystemStateHub.refreshDockApps(force = true) }
            )

            if (immersiveMode && !immersiveSearchOpen) {
                SekaiImmersiveFloatingPill(
                    theme = theme,
                    deckMode = deckMode,
                    searchActive = searchQuery.isNotBlank() || selectedCategory != DockCategoryFilter.ALL,
                    onToggleDeckMode = onToggleDeckMode,
                    onOpenTools = onToggleImmersiveSearch,
                    onExitImmersive = onToggleImmersive,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 8.dp, end = 8.dp)
                )
            }
        }

        // Cava Dynamic Music Bar + Quick Actions Footer (replaces duplicate bottom pinned dock)
        SekaiCavaMediaBar(
            theme = theme,
            media = snapshot.media,
            onAction = { action -> EcosystemStateHub.sendDockSystemAction(action) }
        )
    }
}

@Composable
private fun LandscapeDockLayout(
    theme: SekaiResolvedTheme,
    uiState: EcosystemUiState,
    snapshot: DockSnapshotState,
    filteredApps: List<DockAppItem>,
    quickDockApps: List<DockAppItem>,
    pinnedSet: Set<String>,
    frequentSet: Set<String>,
    icons: Map<String, ImageBitmap>,
    clockPair: Pair<String, String>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    selectedCategory: DockCategoryFilter,
    onCategoryChange: (DockCategoryFilter) -> Unit,
    deckMode: Boolean,
    onToggleDeckMode: () -> Unit,
    immersiveMode: Boolean,
    immersiveSearchOpen: Boolean,
    onToggleImmersiveSearch: () -> Unit,
    onToggleImmersive: () -> Unit,
    onOpenTheme: () -> Unit,
    onOpenDashboard: () -> Unit,
    onTapApp: (DockAppItem) -> Unit,
    onLongPressApp: (DockAppItem) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                horizontal = if (immersiveMode) 8.dp else 10.dp,
                vertical = if (immersiveMode) 5.dp else 6.dp
            )
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(if (immersiveMode) 5.dp else 6.dp)
    ) {
        if (!immersiveMode) {
            // Ultra-compact Landscape Header Row leaving maximum vertical height for app icons
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                val runningTotal = snapshot.apps.sumOf { it.runningCount }
                val deLabel = if (uiState.tcpAuthenticated) {
                    snapshot.desktopEnv.ifBlank { "Linux" }
                } else {
                    "Sin PC"
                }

                SekaiKeyPill(
                    theme = theme,
                    active = uiState.tcpAuthenticated,
                    fillColor = if (uiState.tcpAuthenticated) theme.accentB else theme.pinkAccent,
                    shadowOffset = 2.dp,
                    onClick = if (uiState.tcpAuthenticated) {
                        { EcosystemStateHub.refreshDockApps(force = true) }
                    } else {
                        onOpenDashboard
                    }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = if (uiState.tcpAuthenticated) {
                                Icons.Outlined.DesktopWindows
                            } else {
                                Icons.Outlined.WifiOff
                            },
                            contentDescription = null,
                            tint = Color(0xFF1A1110),
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = if (uiState.tcpAuthenticated) "$deLabel • $runningTotal" else deLabel,
                            color = Color(0xFF1A1110),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Black,
                            maxLines = 1
                        )
                    }
                }

                SekaiSearchPill(
                    theme = theme,
                    query = searchQuery,
                    onQueryChange = onSearchChange,
                    placeholder = "Buscar app (${snapshot.apps.size})...",
                    compact = true,
                    modifier = Modifier.weight(1f)
                )

                // Single-line compact clock pill
                SekaiPlate(
                    theme = theme,
                    cornerRadius = 999.dp,
                    shadowOffset = 2.dp,
                    fillColor = theme.surface
                ) {
                    Text(
                        text = clockPair.first,
                        color = theme.ink,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    SekaiIconButton(
                        theme = theme,
                        icon = Icons.Outlined.Refresh,
                        contentDescription = "Actualizar catálogo",
                        size = 34.dp,
                        onClick = { EcosystemStateHub.refreshDockApps(force = true) }
                    )
                    SekaiIconButton(
                        theme = theme,
                        icon = if (deckMode) Icons.Outlined.GridView else Icons.Outlined.ViewAgenda,
                        contentDescription = "Deck / Rejilla",
                        active = deckMode,
                        size = 34.dp,
                        onClick = onToggleDeckMode
                    )
                    SekaiIconButton(
                        theme = theme,
                        icon = Icons.Outlined.ColorLens,
                        contentDescription = "Tema",
                        size = 34.dp,
                        onClick = onOpenTheme
                    )
                    SekaiIconButton(
                        theme = theme,
                        icon = Icons.Outlined.Fullscreen,
                        contentDescription = "Pantalla completa",
                        active = false,
                        size = 34.dp,
                        onClick = onToggleImmersive
                    )
                }
            }

            // Single slim combined strip: Workspaces + Categories
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (snapshot.workspaces.isNotEmpty()) {
                    WorkspacesRow(theme = theme, workspaces = snapshot.workspaces)
                    Box(
                        modifier = Modifier
                            .width(2.dp)
                            .height(18.dp)
                            .background(theme.line.copy(alpha = 0.25f))
                    )
                }
                DockCategoryFilter.entries.forEach { cat ->
                    val active = selectedCategory == cat
                    SekaiKeyPill(
                        theme = theme,
                        active = active,
                        shadowOffset = 2.dp,
                        onClick = { onCategoryChange(cat) }
                    ) {
                        Text(
                            text = cat.label,
                            color = if (active) Color(0xFF1A1110) else theme.ink,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }
        } else if (immersiveSearchOpen) {
            ImmersiveToolsBar(
                theme = theme,
                snapshot = snapshot,
                pinnedSet = pinnedSet,
                frequentSet = frequentSet,
                searchQuery = searchQuery,
                onSearchChange = onSearchChange,
                selectedCategory = selectedCategory,
                onCategoryChange = onCategoryChange,
                deckMode = deckMode,
                onToggleDeckMode = onToggleDeckMode,
                onCloseTools = onToggleImmersiveSearch,
                onExitImmersive = onToggleImmersive,
                compactLandscape = true
            )
        }

        // Body: Deck Mode (with slim Cava footer) OR Split Mode (Left Cava Media/Actions Rail + Right Catalog Grid)
        if (deckMode) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    MainCatalogSurface(
                        theme = theme,
                        snapshot = snapshot,
                        filteredApps = filteredApps,
                        pinnedSet = pinnedSet,
                        icons = icons,
                        deckMode = true,
                        isLandscape = true,
                        extraTopPadding = if (immersiveMode && !immersiveSearchOpen) 48.dp else 0.dp,
                        onTapApp = onTapApp,
                        onLongPressApp = onLongPressApp,
                        onRefresh = { EcosystemStateHub.refreshDockApps(force = true) }
                    )

                    if (immersiveMode && !immersiveSearchOpen) {
                        SekaiImmersiveFloatingPill(
                            theme = theme,
                            deckMode = true,
                            searchActive = searchQuery.isNotBlank() || selectedCategory != DockCategoryFilter.ALL,
                            onToggleDeckMode = onToggleDeckMode,
                            onOpenTools = onToggleImmersiveSearch,
                            onExitImmersive = onToggleImmersive,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 6.dp, end = 6.dp)
                        )
                    }
                }

                SekaiCavaMediaBar(
                    theme = theme,
                    media = snapshot.media,
                    compactLandscape = true,
                    onAction = { action -> EcosystemStateHub.sendDockSystemAction(action) }
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Streamlined Left Rail: Cava Visualizer + Quick Media + Quick System Actions + Compact Pinned Strip
                LandscapeMediaAndPinsRail(
                    theme = theme,
                    snapshot = snapshot,
                    quickDockApps = quickDockApps,
                    pinnedSet = pinnedSet,
                    icons = icons,
                    onTapApp = onTapApp,
                    onLongPressApp = onLongPressApp
                )

                // Right Main Catalog Grid + floating mini-pill in fullscreen
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    MainCatalogSurface(
                        theme = theme,
                        snapshot = snapshot,
                        filteredApps = filteredApps,
                        pinnedSet = pinnedSet,
                        icons = icons,
                        deckMode = false,
                        isLandscape = true,
                        extraTopPadding = if (immersiveMode && !immersiveSearchOpen) 48.dp else 0.dp,
                        onTapApp = onTapApp,
                        onLongPressApp = onLongPressApp,
                        onRefresh = { EcosystemStateHub.refreshDockApps(force = true) }
                    )

                    if (immersiveMode && !immersiveSearchOpen) {
                        SekaiImmersiveFloatingPill(
                            theme = theme,
                            deckMode = false,
                            searchActive = searchQuery.isNotBlank() || selectedCategory != DockCategoryFilter.ALL,
                            onToggleDeckMode = onToggleDeckMode,
                            onOpenTools = onToggleImmersiveSearch,
                            onExitImmersive = onToggleImmersive,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 6.dp, end = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LandscapeMediaAndPinsRail(
    theme: SekaiResolvedTheme,
    snapshot: DockSnapshotState,
    quickDockApps: List<DockAppItem>,
    pinnedSet: Set<String>,
    icons: Map<String, ImageBitmap>,
    onTapApp: (DockAppItem) -> Unit,
    onLongPressApp: (DockAppItem) -> Unit
) {
    val media = snapshot.media
    val isPlaying = media?.playing == true
    val isMuted = media?.muted == true
    val titleText = media?.title?.takeIf { it.isNotBlank() }
    val artistText = media?.artist?.takeIf { it.isNotBlank() }
    val volumeLabel = when {
        isMuted -> "MUTE"
        media?.volume != null -> "${media.volume}%"
        else -> null
    }

    SekaiPlate(
        theme = theme,
        cornerRadius = 20.dp,
        shadowOffset = 3.5.dp,
        fillColor = theme.surface.copy(alpha = 0.96f),
        modifier = Modifier
            .width(192.dp)
            .fillMaxHeight()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            // Header row: Media emblem + Volume badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.MusicNote,
                        contentDescription = null,
                        tint = if (isPlaying) theme.pinkAccent else theme.inkSub,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = if (isPlaying) "SONANDO" else "MEDIA",
                        color = theme.inkSub,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.7.sp
                    )
                }
                if (volumeLabel != null) {
                    Text(
                        text = volumeLabel,
                        color = Color(0xFF1A1110),
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (isMuted) theme.pinkAccent else theme.accentB)
                            .border(1.2.dp, theme.line, CircleShape)
                            .clickable { EcosystemStateHub.sendDockSystemAction("volume_mute") }
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    )
                }
            }

            // Live 16-bar Cava Visualizer (expands when playing, minimal baseline dots when idle)
            SekaiCavaEqualizerCanvas(
                theme = theme,
                isPlaying = isPlaying,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (isPlaying) 30.dp else 14.dp)
            )

            // Track title & artist pill
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(theme.surfaceHover.copy(alpha = 0.8f))
                    .border(1.4.dp, theme.line.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                    .clickable { EcosystemStateHub.sendDockSystemAction("media_play_pause") }
                    .padding(horizontal = 7.dp, vertical = 4.dp)
            ) {
                Text(
                    text = titleText ?: if (isPlaying) "Audio en vivo" else "Sin reproducción",
                    color = if (titleText != null || isPlaying) theme.ink else theme.inkSub,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (isPlaying && titleText != null) Modifier.basicMarquee() else Modifier
                )
                if (isPlaying && artistText != null) {
                    Text(
                        text = artistText,
                        color = theme.inkSub,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Media Transport Row: Prev, Play/Pause, Next
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SekaiIconButton(
                    theme = theme,
                    icon = Icons.Outlined.SkipPrevious,
                    contentDescription = "Anterior",
                    size = 32.dp,
                    cornerRadius = 10.dp,
                    onClick = { EcosystemStateHub.sendDockSystemAction("media_prev") }
                )
                SekaiIconButton(
                    theme = theme,
                    icon = if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    contentDescription = "Play/Pausa",
                    active = isPlaying,
                    size = 36.dp,
                    cornerRadius = 11.dp,
                    onClick = { EcosystemStateHub.sendDockSystemAction("media_play_pause") }
                )
                SekaiIconButton(
                    theme = theme,
                    icon = Icons.Outlined.SkipNext,
                    contentDescription = "Siguiente",
                    size = 32.dp,
                    cornerRadius = 10.dp,
                    onClick = { EcosystemStateHub.sendDockSystemAction("media_next") }
                )
            }

            // System & Volume Quick Actions Row: Vol-, Mute, Vol+, Lock, Screenshot
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SekaiIconButton(
                    theme = theme,
                    icon = Icons.AutoMirrored.Outlined.VolumeDown,
                    contentDescription = "Bajar volumen",
                    size = 29.dp,
                    cornerRadius = 9.dp,
                    onClick = { EcosystemStateHub.sendDockSystemAction("volume_down") }
                )
                SekaiIconButton(
                    theme = theme,
                    icon = Icons.AutoMirrored.Outlined.VolumeOff,
                    contentDescription = "Silenciar",
                    active = isMuted,
                    fillColor = if (isMuted) theme.pinkAccent else theme.surface,
                    size = 29.dp,
                    cornerRadius = 9.dp,
                    onClick = { EcosystemStateHub.sendDockSystemAction("volume_mute") }
                )
                SekaiIconButton(
                    theme = theme,
                    icon = Icons.AutoMirrored.Outlined.VolumeUp,
                    contentDescription = "Subir volumen",
                    size = 29.dp,
                    cornerRadius = 9.dp,
                    onClick = { EcosystemStateHub.sendDockSystemAction("volume_up") }
                )
                SekaiIconButton(
                    theme = theme,
                    icon = Icons.Outlined.Lock,
                    contentDescription = "Bloquear PC",
                    size = 29.dp,
                    cornerRadius = 9.dp,
                    onClick = { EcosystemStateHub.sendDockSystemAction("lock") }
                )
                SekaiIconButton(
                    theme = theme,
                    icon = Icons.Outlined.PhotoCamera,
                    contentDescription = "Captura de pantalla",
                    size = 29.dp,
                    cornerRadius = 9.dp,
                    onClick = { EcosystemStateHub.sendDockSystemAction("screenshot") }
                )
            }

            // Compact Pinned Quick Apps (uses remaining vertical room cleanly without cramping media)
            if (quickDockApps.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.5.dp)
                        .background(theme.line.copy(alpha = 0.18f))
                )
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(top = 1.dp, bottom = 1.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    items(quickDockApps.take(9), key = { "rail_${it.id}" }) { app ->
                        SekaiAppTile(
                            app = app,
                            icon = icons[app.id],
                            theme = theme,
                            isPinned = app.id in pinnedSet,
                            compact = true,
                            showLabel = false,
                            iconSize = 26.dp,
                            onTap = { onTapApp(app) },
                            onLongPress = { onLongPressApp(app) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SekaiImmersiveFloatingPill(
    theme: SekaiResolvedTheme,
    deckMode: Boolean,
    searchActive: Boolean,
    onToggleDeckMode: () -> Unit,
    onOpenTools: () -> Unit,
    onExitImmersive: () -> Unit,
    modifier: Modifier = Modifier
) {
    SekaiPlate(
        theme = theme,
        cornerRadius = 999.dp,
        borderWidth = 2.2.dp,
        shadowOffset = 3.dp,
        fillColor = theme.surface.copy(alpha = 0.96f),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SekaiIconButton(
                theme = theme,
                icon = if (deckMode) Icons.Outlined.GridView else Icons.Outlined.ViewAgenda,
                contentDescription = "Deck / Rejilla",
                active = deckMode,
                size = 28.dp,
                cornerRadius = 9.dp,
                onClick = onToggleDeckMode
            )
            SekaiIconButton(
                theme = theme,
                icon = Icons.Outlined.Search,
                contentDescription = "Buscar y filtrar",
                active = searchActive,
                size = 28.dp,
                cornerRadius = 9.dp,
                onClick = onOpenTools
            )
            SekaiIconButton(
                theme = theme,
                icon = Icons.Outlined.FullscreenExit,
                contentDescription = "Salir de pantalla completa",
                active = true,
                fillColor = theme.pinkAccent,
                size = 28.dp,
                cornerRadius = 9.dp,
                onClick = onExitImmersive
            )
        }
    }
}

@Composable
private fun ImmersiveToolsBar(
    theme: SekaiResolvedTheme,
    snapshot: DockSnapshotState,
    pinnedSet: Set<String>,
    frequentSet: Set<String>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    selectedCategory: DockCategoryFilter,
    onCategoryChange: (DockCategoryFilter) -> Unit,
    deckMode: Boolean,
    onToggleDeckMode: () -> Unit,
    onCloseTools: () -> Unit,
    onExitImmersive: () -> Unit,
    compactLandscape: Boolean = false
) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SekaiSearchPill(
                theme = theme,
                query = searchQuery,
                onQueryChange = onSearchChange,
                placeholder = "Buscar app...",
                compact = true,
                modifier = Modifier.weight(1f)
            )
            SekaiIconButton(
                theme = theme,
                icon = if (deckMode) Icons.Outlined.GridView else Icons.Outlined.ViewAgenda,
                contentDescription = "Deck / Rejilla",
                active = deckMode,
                size = if (compactLandscape) 34.dp else 36.dp,
                onClick = onToggleDeckMode
            )
            SekaiIconButton(
                theme = theme,
                icon = Icons.Outlined.Close,
                contentDescription = "Ocultar barra",
                size = if (compactLandscape) 34.dp else 36.dp,
                onClick = onCloseTools
            )
            SekaiIconButton(
                theme = theme,
                icon = Icons.Outlined.FullscreenExit,
                contentDescription = "Salir de pantalla completa",
                active = true,
                fillColor = theme.pinkAccent,
                size = if (compactLandscape) 34.dp else 36.dp,
                onClick = onExitImmersive
            )
        }

        if (snapshot.workspaces.isNotEmpty() || compactLandscape) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (snapshot.workspaces.isNotEmpty()) {
                    WorkspacesRow(theme = theme, workspaces = snapshot.workspaces)
                    Box(
                        modifier = Modifier
                            .width(2.dp)
                            .height(18.dp)
                            .background(theme.line.copy(alpha = 0.25f))
                    )
                }
                DockCategoryFilter.entries.forEach { category ->
                    val active = selectedCategory == category
                    SekaiKeyPill(
                        theme = theme,
                        active = active,
                        shadowOffset = 2.dp,
                        onClick = { onCategoryChange(category) }
                    ) {
                        Text(
                            text = category.label,
                            color = if (active) Color(0xFF1A1110) else theme.ink,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }
            }
        } else {
            CategoryChipsStrip(
                theme = theme,
                apps = snapshot.apps,
                pinnedSet = pinnedSet,
                frequentSet = frequentSet,
                selected = selectedCategory,
                onSelect = onCategoryChange
            )
        }
    }
}

@Composable
private fun MainCatalogSurface(
    theme: SekaiResolvedTheme,
    snapshot: DockSnapshotState,
    filteredApps: List<DockAppItem>,
    pinnedSet: Set<String>,
    icons: Map<String, ImageBitmap>,
    deckMode: Boolean,
    isLandscape: Boolean,
    extraTopPadding: Dp = 0.dp,
    onTapApp: (DockAppItem) -> Unit,
    onLongPressApp: (DockAppItem) -> Unit,
    onRefresh: () -> Unit
) {
    val gridState = rememberLazyGridState()
    LaunchedEffect(extraTopPadding) {
        if (gridState.firstVisibleItemIndex <= 1) {
            gridState.scrollToItem(0)
        }
    }
    if (deckMode) {
        if (filteredApps.isEmpty()) {
            EmptyCatalogCard(
                theme = theme,
                loading = snapshot.loading,
                syncError = snapshot.syncError,
                onRefresh = onRefresh
            )
        } else {
            LazyVerticalGrid(
                state = gridState,
                columns = if (isLandscape) GridCells.Adaptive(minSize = 205.dp) else GridCells.Fixed(2),
                contentPadding = PaddingValues(top = extraTopPadding, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(filteredApps, key = { _, app -> "deck_${app.id}" }) { index, app ->
                    SekaiDeckCard(
                        indexNumber = index + 1,
                        app = app,
                        icon = icons[app.id],
                        theme = theme,
                        onTap = { onTapApp(app) },
                        onLongPress = { onLongPressApp(app) }
                    )
                }
            }
        }
    } else {
        SekaiPlate(
            theme = theme,
            cornerRadius = 22.dp,
            shadowOffset = 4.dp,
            fillColor = theme.surface.copy(alpha = 0.94f),
            modifier = Modifier.fillMaxSize()
        ) {
            if (filteredApps.isEmpty()) {
                EmptyCatalogCard(
                    theme = theme,
                    loading = snapshot.loading,
                    syncError = snapshot.syncError,
                    onRefresh = onRefresh
                )
            } else {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(minSize = if (isLandscape) 86.dp else 78.dp),
                    contentPadding = PaddingValues(
                        top = 10.dp + extraTopPadding,
                        start = 10.dp,
                        end = 10.dp,
                        bottom = 10.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(filteredApps, key = { "grid_${it.id}" }) { app ->
                        SekaiAppTile(
                            app = app,
                            icon = icons[app.id],
                            theme = theme,
                            isPinned = app.id in pinnedSet,
                            compact = false,
                            iconSize = if (isLandscape) 46.dp else 48.dp,
                            onTap = { onTapApp(app) },
                            onLongPress = { onLongPressApp(app) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryChipsStrip(
    theme: SekaiResolvedTheme,
    apps: List<DockAppItem>,
    pinnedSet: Set<String>,
    frequentSet: Set<String>,
    selected: DockCategoryFilter,
    onSelect: (DockCategoryFilter) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        DockCategoryFilter.entries.forEach { category ->
            val count = remember(apps, pinnedSet, frequentSet, category) {
                if (category == DockCategoryFilter.ALL) {
                    apps.size
                } else {
                    apps.count { category.matches(it, pinnedSet, frequentSet) }
                }
            }
            val active = selected == category
            SekaiKeyPill(
                theme = theme,
                active = active,
                shadowOffset = 2.5.dp,
                onClick = { onSelect(category) }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        text = category.label,
                        color = if (active) Color(0xFF1A1110) else theme.ink,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black
                    )
                    if (count > 0) {
                        Text(
                            text = count.toString(),
                            color = if (active) Color(0xFF1A1110) else theme.inkSub,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspacesRow(
    theme: SekaiResolvedTheme,
    workspaces: List<DockWorkspace>
) {
    val haptic = LocalHapticFeedback.current
    var optimisticWorkspaceId by remember(workspaces) { mutableStateOf<String?>(null) }

    LaunchedEffect(optimisticWorkspaceId) {
        if (optimisticWorkspaceId != null) {
            delay(1_500L)
            optimisticWorkspaceId = null
        }
    }

    workspaces.forEach { ws ->
        val isActive = if (optimisticWorkspaceId != null) {
            ws.id == optimisticWorkspaceId
        } else {
            ws.active
        }
        SekaiKeyPill(
            theme = theme,
            active = isActive,
            fillColor = if (isActive) theme.accentB else theme.surface,
            shadowOffset = 2.dp,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                optimisticWorkspaceId = ws.id
                EcosystemStateHub.sendDockSystemAction("workspace_switch", ws.id)
            }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "WS ${ws.name}",
                    color = if (isActive) Color(0xFF1A1110) else theme.ink,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black
                )
                if (ws.windows > 0 || isActive) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (isActive) theme.line else theme.pinkAccent)
                    )
                }
            }
        }
    }
}

@Composable
private fun DeStatusChip(
    theme: SekaiResolvedTheme,
    uiState: EcosystemUiState,
    snapshot: DockSnapshotState,
    onClick: () -> Unit
) {
    val label = if (uiState.tcpAuthenticated) {
        snapshot.desktopEnv.ifBlank { "Linux" }
    } else {
        "Sin PC"
    }
    SekaiKeyPill(
        theme = theme,
        active = uiState.tcpAuthenticated,
        fillColor = if (uiState.tcpAuthenticated) theme.accentB else theme.pinkAccent,
        shadowOffset = 2.5.dp,
        onClick = onClick
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                imageVector = if (uiState.tcpAuthenticated) {
                    Icons.Outlined.DesktopWindows
                } else {
                    Icons.Outlined.WifiOff
                },
                contentDescription = null,
                tint = Color(0xFF1A1110),
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = label,
                color = Color(0xFF1A1110),
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun HeaderIconKey(
    theme: SekaiResolvedTheme,
    icon: ImageVector,
    contentDescription: String,
    active: Boolean = false,
    onClick: () -> Unit
) {
    SekaiIconButton(
        theme = theme,
        icon = icon,
        contentDescription = contentDescription,
        active = active,
        size = 38.dp,
        onClick = onClick
    )
}

@Composable
private fun DisconnectedBanner(
    theme: SekaiResolvedTheme,
    hasCachedApps: Boolean,
    onConnectClick: () -> Unit
) {
    SekaiPlate(
        theme = theme,
        cornerRadius = 16.dp,
        shadowOffset = 3.dp,
        fillColor = theme.surfaceHover,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "PC desconectado",
                    color = theme.ink,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black
                )
                Text(
                    text = if (hasCachedApps) {
                        "Mostrando apps en caché. Conecta el daemon para abrir apps en vivo."
                    } else {
                        "Abre Dashboard o Hotspot para enlazar tu Linux."
                    },
                    color = theme.inkSub,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.width(8.dp))
            SekaiKeyPill(
                theme = theme,
                active = true,
                onClick = onConnectClick
            ) {
                Text(
                    text = "Conectar",
                    color = Color(0xFF1A1110),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }
    }
}

@Composable
private fun EmptyCatalogCard(
    theme: SekaiResolvedTheme,
    loading: Boolean,
    syncError: String?,
    onRefresh: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (loading) {
                CircularProgressIndicator(color = theme.accentA)
                Text(
                    text = "Sincronizando aplicaciones del escritorio...",
                    color = theme.ink,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.Apps,
                    contentDescription = null,
                    tint = theme.inkSub,
                    modifier = Modifier.size(36.dp)
                )
                Text(
                    text = syncError ?: "No hay aplicaciones en esta vista",
                    color = theme.ink,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Black
                )
                SekaiKeyPill(
                    theme = theme,
                    active = true,
                    onClick = onRefresh
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = null,
                            tint = Color(0xFF1A1110),
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = if (syncError != null) "Reintentar" else "Recargar catálogo",
                            color = Color(0xFF1A1110),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DockAppActionDialog(
    app: DockAppItem,
    icon: ImageBitmap?,
    theme: SekaiResolvedTheme,
    isPinned: Boolean,
    onDismiss: () -> Unit,
    onActivate: () -> Unit,
    onNewWindow: () -> Unit,
    onTogglePin: () -> Unit,
    onCloseApp: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        SekaiPlate(
            theme = theme,
            cornerRadius = 24.dp,
            shadowOffset = 5.dp,
            fillColor = theme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SekaiAppIconBox(
                        app = app,
                        icon = icon,
                        theme = theme,
                        size = 52.dp
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = app.name,
                            color = theme.ink,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Black,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (app.runningCount > 0) {
                                "${app.runningCount} ventana(s) activa(s)"
                            } else {
                                app.genericName ?: app.id
                            },
                            color = theme.inkSub,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                ActionSheetRowButton(
                    theme = theme,
                    icon = Icons.AutoMirrored.Outlined.OpenInNew,
                    label = if (app.runningCount > 0) "Enfocar ventana" else "Abrir aplicación",
                    fill = theme.accentA,
                    onClick = onActivate
                )
                ActionSheetRowButton(
                    theme = theme,
                    icon = Icons.Outlined.AddBox,
                    label = "Abrir nueva ventana",
                    fill = theme.surfaceHover,
                    onClick = onNewWindow
                )
                ActionSheetRowButton(
                    theme = theme,
                    icon = Icons.Outlined.PushPin,
                    label = if (isPinned) "Quitar de Fijadas" else "Fijar en Acceso Rápido",
                    fill = if (isPinned) theme.pinkAccent else theme.accentB,
                    onClick = onTogglePin
                )
                if (app.runningCount > 0) {
                    ActionSheetRowButton(
                        theme = theme,
                        icon = Icons.Outlined.Close,
                        label = "Cerrar aplicación",
                        fill = theme.pinkAccent.copy(alpha = 0.78f),
                        onClick = onCloseApp
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionSheetRowButton(
    theme: SekaiResolvedTheme,
    icon: ImageVector,
    label: String,
    fill: Color,
    onClick: () -> Unit
) {
    SekaiKeyPill(
        theme = theme,
        fillColor = fill,
        shadowOffset = 3.dp,
        cornerRadius = 14.dp,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color(0xFF1A1110),
                modifier = Modifier.size(19.dp)
            )
            Text(
                text = label,
                color = Color(0xFF1A1110),
                fontSize = 14.sp,
                fontWeight = FontWeight.Black
            )
        }
    }
}

private fun currentClockPair(): Pair<String, String> {
    val now = Date()
    val timeFmt = SimpleDateFormat("hh:mma", Locale.US)
    val dateFmt = SimpleDateFormat("EEE, dd MMM", Locale.getDefault())
    return timeFmt.format(now).uppercase(Locale.US) to dateFmt.format(now)
}
