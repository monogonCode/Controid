package com.controid.ui

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.KeyboardReturn
import androidx.compose.material.icons.automirrored.outlined.VolumeDown
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.FastRewind
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.KeyboardHide
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.controid.service.EcosystemStateHub
import com.controid.ui.dock.SekaiBannerPill
import com.controid.ui.dock.SekaiIconButton
import com.controid.ui.dock.SekaiKeyPill
import com.controid.ui.dock.SekaiPlate
import com.controid.ui.dock.SekaiResolvedTheme
import com.controid.ui.dock.SekaiStatusBadge
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class TrackpadMode {
    PAD,
    MEDIA
}

private data class QueuedKeyTap(
    val modifiers: Int,
    val hidUsage: Int,
    val linuxKeycode: Int
)

@Composable
fun TrackpadScreen(
    state: EcosystemUiState,
    theme: SekaiResolvedTheme,
    onOpenTheme: () -> Unit,
    onMouseMove: (dx: Int, dy: Int, wheel: Int) -> Unit,
    onMouseButtons: (mask: Int) -> Unit,
    onSendKey: (modifiers: Int, hidUsage: Int, pressed: Boolean, linuxKeycode: Int?) -> Unit,
    onSendConsumer: (usage: Int) -> Unit,
    onReconnectHid: () -> Unit,
    immersiveMode: Boolean = false,
    onToggleImmersive: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val viewConfig = LocalViewConfiguration.current
    val configuration = LocalConfiguration.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    var trackpadMode by rememberSaveable { mutableStateOf(TrackpadMode.PAD) }
    var directKeyboardOpen by rememberSaveable { mutableStateOf(false) }

    var buttonMask by remember { mutableIntStateOf(0) }
    var activePointers by remember { mutableIntStateOf(0) }
    var edgeScrolling by remember { mutableStateOf(false) }

    var ctrlActive by remember { mutableStateOf(false) }
    var shiftActive by remember { mutableStateOf(false) }
    var altActive by remember { mutableStateOf(false) }
    var superActive by remember { mutableStateOf(false) }

    var directFieldValue by remember {
        mutableStateOf(TextFieldValue(DIRECT_IME_SENTINEL, TextRange(1)))
    }
    val directFocusRequester = remember { FocusRequester() }

    val latestOnSendKey by rememberUpdatedState(onSendKey)
    val keyQueue = remember { Channel<QueuedKeyTap>(Channel.UNLIMITED) }

    LaunchedEffect(keyQueue) {
        for (item in keyQueue) {
            latestOnSendKey(item.modifiers, item.hidUsage, true, item.linuxKeycode)
            delay(10L)
            latestOnSendKey(item.modifiers, item.hidUsage, false, item.linuxKeycode)
            delay(5L)
        }
    }

    LaunchedEffect(directKeyboardOpen, trackpadMode) {
        if (directKeyboardOpen && trackpadMode == TrackpadMode.PAD) {
            delay(50L)
            runCatching { directFocusRequester.requestFocus() }
            keyboardController?.show()
        } else {
            keyboardController?.hide()
            focusManager.clearFocus(force = true)
        }
    }

    fun currentModifierMask(extraShift: Boolean = false): Int {
        var mask = 0
        if (ctrlActive) mask = mask or 0x01
        if (shiftActive || extraShift) mask = mask or 0x02
        if (altActive) mask = mask or 0x04
        if (superActive) mask = mask or 0x08
        return mask
    }

    fun enqueueKeyTap(hidUsage: Int, linuxKeycode: Int, withModifiers: Boolean = true) {
        val modMask = if (withModifiers) currentModifierMask() else 0
        keyQueue.trySend(QueuedKeyTap(modMask, hidUsage, linuxKeycode))
    }

    fun enqueueChar(ch: Char) {
        val map = HidKeyMap.mapChar(ch) ?: return
        val modMask = currentModifierMask(extraShift = map.shift)
        keyQueue.trySend(QueuedKeyTap(modMask, map.hidUsage, map.linuxKeycode))
    }

    fun handleDirectFieldChange(newValue: TextFieldValue) {
        val oldRaw = directFieldValue.text
        val newRaw = newValue.text
        val oldVisible = if (oldRaw.startsWith(DIRECT_IME_SENTINEL)) oldRaw.drop(1) else oldRaw

        if (!newRaw.startsWith(DIRECT_IME_SENTINEL)) {
            val deleteCount = oldVisible.length.coerceAtLeast(1)
            repeat(deleteCount) {
                enqueueKeyTap(HidKeyMap.HID_BACKSPACE, HidKeyMap.KEY_BACKSPACE)
            }
            directFieldValue = TextFieldValue(DIRECT_IME_SENTINEL, TextRange(1))
            return
        }

        val newVisible = newRaw.drop(1)
        var common = 0
        val minLen = minOf(oldVisible.length, newVisible.length)
        while (common < minLen && oldVisible[common] == newVisible[common]) {
            common++
        }
        val deletedCount = oldVisible.length - common
        val insertedText = newVisible.substring(common)

        repeat(deletedCount) {
            enqueueKeyTap(HidKeyMap.HID_BACKSPACE, HidKeyMap.KEY_BACKSPACE)
        }
        for (ch in insertedText) {
            enqueueChar(ch)
        }

        directFieldValue = if (newVisible.length > DIRECT_PREVIEW_MAX_CHARS) {
            val trimmed = DIRECT_IME_SENTINEL + newVisible.takeLast(20)
            TextFieldValue(trimmed, TextRange(trimmed.length))
        } else {
            newValue
        }
    }

    fun clickMouseButton(bit: Int) {
        scope.launch {
            onMouseButtons(buttonMask or bit)
            delay(30L)
            onMouseButtons(buttonMask and bit.inv())
        }
    }

    val directPreviewText = remember(directFieldValue.text) {
        val raw = directFieldValue.text
        if (raw.startsWith(DIRECT_IME_SENTINEL)) raw.drop(1) else raw
    }

    val routeLabel = when {
        state.hidConnected -> "Bluetooth HID"
        state.fallbackInputActive || state.tcpAuthenticated -> "TCP uinput"
        else -> "Desconectado"
    }
    val compactRouteLabel = when {
        state.hidConnected -> "HID"
        state.fallbackInputActive || state.tcpAuthenticated -> "TCP"
        else -> "Reconectar"
    }
    val routeColor = when {
        state.hidConnected -> theme.accentB
        state.fallbackInputActive || state.tcpAuthenticated -> theme.accentA
        else -> theme.pinkAccent
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxSize()
    ) {
        val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE ||
            maxWidth > maxHeight
        val tightPortrait = !isLandscape && maxHeight < 580.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    horizontal = if (immersiveMode || isLandscape) 10.dp else 14.dp,
                    vertical = if (immersiveMode || isLandscape) 6.dp else 10.dp
                ),
            verticalArrangement = Arrangement.spacedBy(
                if (immersiveMode || isLandscape) 6.dp else 9.dp
            )
        ) {
            TrackpadHeader(
                theme = theme,
                mode = trackpadMode,
                isLandscape = isLandscape,
                immersiveMode = immersiveMode,
                hidConnected = state.hidConnected,
                routeLabel = routeLabel,
                compactRouteLabel = compactRouteLabel,
                routeColor = routeColor,
                directKeyboardOpen = directKeyboardOpen,
                onSelectMode = { selected ->
                    trackpadMode = selected
                    if (selected != TrackpadMode.PAD) {
                        directKeyboardOpen = false
                    }
                },
                onToggleDirectKeyboard = {
                    if (trackpadMode != TrackpadMode.PAD) {
                        trackpadMode = TrackpadMode.PAD
                        directKeyboardOpen = true
                    } else {
                        directKeyboardOpen = !directKeyboardOpen
                    }
                },
                onReconnectHid = onReconnectHid,
                onOpenTheme = onOpenTheme,
                onToggleImmersive = onToggleImmersive
            )

            when (trackpadMode) {
                TrackpadMode.PAD -> {
                    PadModeContent(
                        theme = theme,
                        isLandscape = isLandscape,
                        immersiveMode = immersiveMode,
                        directKeyboardOpen = directKeyboardOpen,
                        directFieldValue = directFieldValue,
                        directPreviewText = directPreviewText,
                        directFocusRequester = directFocusRequester,
                        activePointers = activePointers,
                        edgeScrolling = edgeScrolling,
                        touchSlop = viewConfig.touchSlop,
                        buttonMask = buttonMask,
                        ctrlActive = ctrlActive,
                        shiftActive = shiftActive,
                        altActive = altActive,
                        superActive = superActive,
                        onPointersChanged = { activePointers = it },
                        onEdgeScrollingChanged = { edgeScrolling = it },
                        onMouseMove = onMouseMove,
                        onTapPointers = { ptrs ->
                            when (ptrs) {
                                1 -> clickMouseButton(MOUSE_LEFT)
                                2 -> clickMouseButton(MOUSE_RIGHT)
                            }
                        },
                        onMaskChange = { newMask ->
                            buttonMask = newMask
                            onMouseButtons(newMask)
                        },
                        onDirectValueChange = ::handleDirectFieldChange,
                        onOpenDirectKeyboard = {
                            directKeyboardOpen = true
                            runCatching { directFocusRequester.requestFocus() }
                            keyboardController?.show()
                        },
                        onHideDirectKeyboard = {
                            directKeyboardOpen = false
                            keyboardController?.hide()
                            focusManager.clearFocus(force = true)
                        },
                        onClearDirectBuffer = {
                            directFieldValue = TextFieldValue(DIRECT_IME_SENTINEL, TextRange(1))
                        },
                        onDirectBackspace = {
                            val current = directPreviewText
                            if (current.isNotEmpty()) {
                                val next = DIRECT_IME_SENTINEL + current.dropLast(1)
                                directFieldValue = TextFieldValue(next, TextRange(next.length))
                            }
                            enqueueKeyTap(HidKeyMap.HID_BACKSPACE, HidKeyMap.KEY_BACKSPACE)
                        },
                        onToggleCtrl = { ctrlActive = !ctrlActive },
                        onToggleShift = { shiftActive = !shiftActive },
                        onToggleAlt = { altActive = !altActive },
                        onToggleSuper = { superActive = !superActive },
                        onSendKeyTap = { hid, linux -> enqueueKeyTap(hid, linux) },
                        onSendConsumer = onSendConsumer,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )
                }

                TrackpadMode.MEDIA -> {
                    MultimediaModeContent(
                        theme = theme,
                        isLandscape = isLandscape,
                        tightPortrait = tightPortrait,
                        activePointers = activePointers,
                        edgeScrolling = edgeScrolling,
                        touchSlop = viewConfig.touchSlop,
                        onPointersChanged = { activePointers = it },
                        onEdgeScrollingChanged = { edgeScrolling = it },
                        onMouseMove = onMouseMove,
                        onTapPointers = { ptrs ->
                            when (ptrs) {
                                1 -> clickMouseButton(MOUSE_LEFT)
                                2 -> clickMouseButton(MOUSE_RIGHT)
                            }
                        },
                        onSendConsumer = onSendConsumer,
                        onSendKeyTap = { hid, linux ->
                            enqueueKeyTap(hid, linux, withModifiers = false)
                        },
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
private fun TrackpadHeader(
    theme: SekaiResolvedTheme,
    mode: TrackpadMode,
    isLandscape: Boolean,
    immersiveMode: Boolean,
    hidConnected: Boolean,
    routeLabel: String,
    compactRouteLabel: String,
    routeColor: Color,
    directKeyboardOpen: Boolean,
    onSelectMode: (TrackpadMode) -> Unit,
    onToggleDirectKeyboard: () -> Unit,
    onReconnectHid: () -> Unit,
    onOpenTheme: () -> Unit,
    onToggleImmersive: () -> Unit
) {
    if (immersiveMode) {
        // Ultra-compact fullscreen top bar to maximize trackpad / remote surface
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f, fill = false)
            ) {
                SekaiStatusBadge(
                    theme = theme,
                    text = compactRouteLabel,
                    dotColor = routeColor,
                    modifier = if (!hidConnected) {
                        Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .clickable { onReconnectHid() }
                    } else {
                        Modifier
                    }
                )
                ModeSegmentedPill(
                    theme = theme,
                    selectedMode = mode,
                    compact = true,
                    onSelect = onSelectMode
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (mode == TrackpadMode.PAD) {
                    SekaiIconButton(
                        theme = theme,
                        icon = Icons.Outlined.Keyboard,
                        contentDescription = "Teclado directo",
                        active = directKeyboardOpen,
                        size = 34.dp,
                        onClick = onToggleDirectKeyboard
                    )
                }
                SekaiIconButton(
                    theme = theme,
                    icon = Icons.Outlined.ColorLens,
                    contentDescription = "Estética SekaiPop",
                    size = 34.dp,
                    onClick = onOpenTheme
                )
                SekaiIconButton(
                    theme = theme,
                    icon = Icons.Outlined.FullscreenExit,
                    contentDescription = "Salir de pantalla completa",
                    active = true,
                    size = 34.dp,
                    onClick = onToggleImmersive
                )
            }
        }
        return
    }

    if (isLandscape) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SekaiBannerPill(
                theme = theme,
                title = "Trackpad",
                subtitle = if (mode == TrackpadMode.PAD) "Ratón y teclado" else "Control multimedia",
                icon = if (mode == TrackpadMode.PAD) Icons.Outlined.TouchApp else Icons.Outlined.MusicNote,
                modifier = Modifier.weight(1f, fill = false)
            )

            Spacer(Modifier.width(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ModeSegmentedPill(
                    theme = theme,
                    selectedMode = mode,
                    compact = true,
                    onSelect = onSelectMode
                )
                SekaiStatusBadge(
                    theme = theme,
                    text = compactRouteLabel,
                    dotColor = routeColor,
                    modifier = if (!hidConnected) {
                        Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .clickable { onReconnectHid() }
                    } else {
                        Modifier
                    }
                )
                if (mode == TrackpadMode.PAD) {
                    SekaiIconButton(
                        theme = theme,
                        icon = Icons.Outlined.Keyboard,
                        contentDescription = "Teclado directo",
                        active = directKeyboardOpen,
                        size = 34.dp,
                        onClick = onToggleDirectKeyboard
                    )
                }
                SekaiIconButton(
                    theme = theme,
                    icon = Icons.Outlined.ColorLens,
                    contentDescription = "Estética SekaiPop",
                    size = 34.dp,
                    onClick = onOpenTheme
                )
                SekaiIconButton(
                    theme = theme,
                    icon = Icons.Outlined.Fullscreen,
                    contentDescription = "Pantalla completa",
                    size = 34.dp,
                    onClick = onToggleImmersive
                )
            }
        }
    } else {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SekaiBannerPill(
                    theme = theme,
                    title = "Trackpad",
                    subtitle = if (mode == TrackpadMode.PAD) "Ratón y teclado" else "Control multimedia",
                    icon = if (mode == TrackpadMode.PAD) Icons.Outlined.TouchApp else Icons.Outlined.MusicNote,
                    modifier = Modifier.weight(1f, fill = false)
                )

                Spacer(Modifier.width(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SekaiStatusBadge(
                        theme = theme,
                        text = if (hidConnected) routeLabel else compactRouteLabel,
                        dotColor = routeColor,
                        modifier = if (!hidConnected) {
                            Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .clickable { onReconnectHid() }
                        } else {
                            Modifier
                        }
                    )
                    SekaiIconButton(
                        theme = theme,
                        icon = Icons.Outlined.ColorLens,
                        contentDescription = "Estética SekaiPop",
                        size = 35.dp,
                        onClick = onOpenTheme
                    )
                    SekaiIconButton(
                        theme = theme,
                        icon = Icons.Outlined.Fullscreen,
                        contentDescription = "Pantalla completa",
                        size = 35.dp,
                        onClick = onToggleImmersive
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ModeSegmentedPill(
                    theme = theme,
                    selectedMode = mode,
                    compact = false,
                    onSelect = onSelectMode,
                    modifier = Modifier.weight(1f)
                )
                if (mode == TrackpadMode.PAD) {
                    SekaiKeyPill(
                        theme = theme,
                        active = directKeyboardOpen,
                        fillColor = if (directKeyboardOpen) theme.accentB else theme.surface,
                        shadowOffset = 2.5.dp,
                        onClick = onToggleDirectKeyboard
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Keyboard,
                                contentDescription = "Teclado directo",
                                tint = if (directKeyboardOpen) Color(0xFF1A1110) else theme.ink,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Teclado",
                                color = if (directKeyboardOpen) Color(0xFF1A1110) else theme.ink,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Black,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeSegmentedPill(
    theme: SekaiResolvedTheme,
    selectedMode: TrackpadMode,
    compact: Boolean,
    onSelect: (TrackpadMode) -> Unit,
    modifier: Modifier = Modifier
) {
    SekaiPlate(
        theme = theme,
        cornerRadius = 999.dp,
        borderWidth = 2.2.dp,
        shadowOffset = 2.5.dp,
        fillColor = theme.surface,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SegmentTab(
                theme = theme,
                label = if (compact) "Pad" else "Trackpad",
                icon = Icons.Outlined.TouchApp,
                selected = selectedMode == TrackpadMode.PAD,
                compact = compact,
                onClick = { onSelect(TrackpadMode.PAD) },
                modifier = if (compact) Modifier else Modifier.weight(1f)
            )
            SegmentTab(
                theme = theme,
                label = if (compact) "Media" else "Multimedia",
                icon = Icons.Outlined.MusicNote,
                selected = selectedMode == TrackpadMode.MEDIA,
                compact = compact,
                onClick = { onSelect(TrackpadMode.MEDIA) },
                modifier = if (compact) Modifier else Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SegmentTab(
    theme: SekaiResolvedTheme,
    label: String,
    icon: ImageVector,
    selected: Boolean,
    compact: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(if (selected) theme.accentA else Color.Transparent)
            .then(
                if (selected) Modifier.border(1.6.dp, theme.line, shape) else Modifier
            )
            .clickable(onClick = onClick)
            .padding(
                horizontal = if (compact) 10.dp else 10.dp,
                vertical = if (compact) 5.dp else 6.dp
            ),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) Color(0xFF1A1110) else theme.inkSub,
            modifier = Modifier.size(if (compact) 13.dp else 14.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            color = if (selected) Color(0xFF1A1110) else theme.inkSub,
            fontSize = if (compact) 11.sp else 11.5.sp,
            fontWeight = FontWeight.Black,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PadModeContent(
    theme: SekaiResolvedTheme,
    isLandscape: Boolean,
    immersiveMode: Boolean,
    directKeyboardOpen: Boolean,
    directFieldValue: TextFieldValue,
    directPreviewText: String,
    directFocusRequester: FocusRequester,
    activePointers: Int,
    edgeScrolling: Boolean,
    touchSlop: Float,
    buttonMask: Int,
    ctrlActive: Boolean,
    shiftActive: Boolean,
    altActive: Boolean,
    superActive: Boolean,
    onPointersChanged: (Int) -> Unit,
    onEdgeScrollingChanged: (Boolean) -> Unit,
    onMouseMove: (dx: Int, dy: Int, wheel: Int) -> Unit,
    onTapPointers: (Int) -> Unit,
    onMaskChange: (Int) -> Unit,
    onDirectValueChange: (TextFieldValue) -> Unit,
    onOpenDirectKeyboard: () -> Unit,
    onHideDirectKeyboard: () -> Unit,
    onClearDirectBuffer: () -> Unit,
    onDirectBackspace: () -> Unit,
    onToggleCtrl: () -> Unit,
    onToggleShift: () -> Unit,
    onToggleAlt: () -> Unit,
    onToggleSuper: () -> Unit,
    onSendKeyTap: (hidUsage: Int, linuxKeycode: Int) -> Unit,
    onSendConsumer: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (immersiveMode) {
        // Fullscreen Trackpad View (~85-90% touch surface + oversized tactile mouse buttons)
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TrackpadTouchSurface(
                theme = theme,
                activePointers = activePointers,
                edgeScrolling = edgeScrolling,
                touchSlop = touchSlop,
                compact = isLandscape,
                onPointersChanged = onPointersChanged,
                onEdgeScrollingChanged = onEdgeScrollingChanged,
                onMouseMove = onMouseMove,
                onTapPointers = onTapPointers,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )

            MouseButtonsBar(
                theme = theme,
                buttonMask = buttonMask,
                oversized = !isLandscape,
                compact = isLandscape,
                onMaskChange = onMaskChange
            )

            AnimatedVisibility(visible = directKeyboardOpen) {
                DirectKeyboardBar(
                    theme = theme,
                    compact = isLandscape,
                    fieldValue = directFieldValue,
                    previewText = directPreviewText,
                    focusRequester = directFocusRequester,
                    onValueChange = onDirectValueChange,
                    onFocusRequest = onOpenDirectKeyboard,
                    onBackspace = onDirectBackspace,
                    onClear = onClearDirectBuffer,
                    onHideKeyboard = onHideDirectKeyboard
                )
            }
        }
        return
    }

    if (isLandscape) {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Left Pane: Full-height Touch Surface + Mouse Buttons underneath
                Column(
                    modifier = Modifier
                        .weight(1.15f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    TrackpadTouchSurface(
                        theme = theme,
                        activePointers = activePointers,
                        edgeScrolling = edgeScrolling,
                        touchSlop = touchSlop,
                        compact = true,
                        onPointersChanged = onPointersChanged,
                        onEdgeScrollingChanged = onEdgeScrollingChanged,
                        onMouseMove = onMouseMove,
                        onTapPointers = onTapPointers,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )

                    MouseButtonsBar(
                        theme = theme,
                        buttonMask = buttonMask,
                        oversized = false,
                        compact = true,
                        onMaskChange = onMaskChange
                    )
                }

                // Right Pane: Direct Keyboard Trigger + Modifiers + Navigation + Media Quick Deck
                Column(
                    modifier = Modifier
                        .weight(0.95f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    KeyboardControlsCard(
                        theme = theme,
                        compact = true,
                        directKeyboardOpen = directKeyboardOpen,
                        onToggleDirectKeyboard = {
                            if (directKeyboardOpen) onHideDirectKeyboard() else onOpenDirectKeyboard()
                        },
                        ctrlActive = ctrlActive,
                        shiftActive = shiftActive,
                        altActive = altActive,
                        superActive = superActive,
                        onToggleCtrl = onToggleCtrl,
                        onToggleShift = onToggleShift,
                        onToggleAlt = onToggleAlt,
                        onToggleSuper = onToggleSuper,
                        onSendKeyTap = onSendKeyTap
                    )

                    MediaControlsCard(
                        theme = theme,
                        compact = true,
                        onSendConsumer = onSendConsumer
                    )
                }
            }

            AnimatedVisibility(visible = directKeyboardOpen) {
                DirectKeyboardBar(
                    theme = theme,
                    compact = true,
                    fieldValue = directFieldValue,
                    previewText = directPreviewText,
                    focusRequester = directFocusRequester,
                    onValueChange = onDirectValueChange,
                    onFocusRequest = onOpenDirectKeyboard,
                    onBackspace = onDirectBackspace,
                    onClear = onClearDirectBuffer,
                    onHideKeyboard = onHideDirectKeyboard
                )
            }
        }
    } else if (directKeyboardOpen) {
        // Portrait with live soft keyboard open: keep trackpad + mouse + modifiers + direct bar cleanly above IME
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            TrackpadTouchSurface(
                theme = theme,
                activePointers = activePointers,
                edgeScrolling = edgeScrolling,
                touchSlop = touchSlop,
                compact = true,
                onPointersChanged = onPointersChanged,
                onEdgeScrollingChanged = onEdgeScrollingChanged,
                onMouseMove = onMouseMove,
                onTapPointers = onTapPointers,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .heightIn(min = 92.dp)
            )

            MouseButtonsBar(
                theme = theme,
                buttonMask = buttonMask,
                oversized = false,
                compact = true,
                onMaskChange = onMaskChange
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                ModifierChipKey(theme, "Ctrl", ctrlActive, Modifier.weight(1f), onToggleCtrl)
                ModifierChipKey(theme, "Shift", shiftActive, Modifier.weight(1f), onToggleShift)
                ModifierChipKey(theme, "Alt", altActive, Modifier.weight(1f), onToggleAlt)
                ModifierChipKey(theme, "Super", superActive, Modifier.weight(1f), onToggleSuper)
                SekaiKeyPill(
                    theme = theme,
                    shadowOffset = 2.dp,
                    onClick = { onSendKeyTap(HidKeyMap.HID_ESC, HidKeyMap.KEY_ESC) }
                ) {
                    Text("Esc", color = theme.ink, fontSize = 10.5.sp, fontWeight = FontWeight.Black)
                }
                SekaiKeyPill(
                    theme = theme,
                    active = true,
                    shadowOffset = 2.dp,
                    onClick = { onSendKeyTap(HidKeyMap.HID_ENTER, HidKeyMap.KEY_ENTER) }
                ) {
                    Text("↵", color = Color(0xFF1A1110), fontSize = 12.sp, fontWeight = FontWeight.Black)
                }
            }

            DirectKeyboardBar(
                theme = theme,
                compact = false,
                fieldValue = directFieldValue,
                previewText = directPreviewText,
                focusRequester = directFocusRequester,
                onValueChange = onDirectValueChange,
                onFocusRequest = onOpenDirectKeyboard,
                onBackspace = onDirectBackspace,
                onClear = onClearDirectBuffer,
                onHideKeyboard = onHideDirectKeyboard
            )
        }
    } else {
        // Normal Portrait Vertical Stack
        Column(
            modifier = modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            TrackpadTouchSurface(
                theme = theme,
                activePointers = activePointers,
                edgeScrolling = edgeScrolling,
                touchSlop = touchSlop,
                compact = false,
                onPointersChanged = onPointersChanged,
                onEdgeScrollingChanged = onEdgeScrollingChanged,
                onMouseMove = onMouseMove,
                onTapPointers = onTapPointers,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 148.dp, max = 165.dp)
            )

            MouseButtonsBar(
                theme = theme,
                buttonMask = buttonMask,
                oversized = false,
                compact = false,
                onMaskChange = onMaskChange
            )

            KeyboardControlsCard(
                theme = theme,
                compact = false,
                directKeyboardOpen = false,
                onToggleDirectKeyboard = onOpenDirectKeyboard,
                ctrlActive = ctrlActive,
                shiftActive = shiftActive,
                altActive = altActive,
                superActive = superActive,
                onToggleCtrl = onToggleCtrl,
                onToggleShift = onToggleShift,
                onToggleAlt = onToggleAlt,
                onToggleSuper = onToggleSuper,
                onSendKeyTap = onSendKeyTap
            )

            MediaControlsCard(
                theme = theme,
                compact = false,
                onSendConsumer = onSendConsumer
            )
        }
    }
}

@Composable
private fun DirectKeyboardBar(
    theme: SekaiResolvedTheme,
    compact: Boolean,
    fieldValue: TextFieldValue,
    previewText: String,
    focusRequester: FocusRequester,
    onValueChange: (TextFieldValue) -> Unit,
    onFocusRequest: () -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    onHideKeyboard: () -> Unit
) {
    val btnSize = if (compact) 30.dp else 33.dp
    SekaiPlate(
        theme = theme,
        cornerRadius = 16.dp,
        borderWidth = 2.2.dp,
        shadowOffset = 3.dp,
        fillColor = theme.surfaceHover,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = if (compact) 5.dp else 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(theme.accentB)
                    .border(1.6.dp, theme.line, RoundedCornerShape(999.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "DIRECTO",
                    color = Color(0xFF1A1110),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.5.sp
                )
            }

            // Sleek live preview + attached BasicTextField for instant key-by-key IME forwarding
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(theme.surface)
                    .border(1.8.dp, theme.line, RoundedCornerShape(10.dp))
                    .clickable { onFocusRequest() }
                    .padding(horizontal = 9.dp, vertical = if (compact) 6.dp else 7.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                val formattedPreview = remember(previewText) {
                    previewText.replace('\n', '↵').replace('\t', '⇥')
                }
                if (formattedPreview.isEmpty()) {
                    Text(
                        text = "Escribe directo al PC...",
                        color = theme.inkSub,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    val displayText = if (formattedPreview.length > 26) {
                        "…" + formattedPreview.takeLast(25)
                    } else {
                        formattedPreview
                    }
                    Text(
                        text = displayText,
                        color = theme.ink,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Transparent BasicTextField keeps IME connected without showing the internal sentinel
                BasicTextField(
                    value = fieldValue,
                    onValueChange = onValueChange,
                    singleLine = false,
                    maxLines = 1,
                    keyboardOptions = KeyboardOptions(
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Default
                    ),
                    textStyle = TextStyle(
                        color = Color.Transparent,
                        fontSize = 12.sp
                    ),
                    cursorBrush = SolidColor(Color.Transparent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onPreviewKeyEvent { keyEvent ->
                            if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.Enter) {
                                onValueChange(
                                    TextFieldValue(
                                        text = fieldValue.text + "\n",
                                        selection = TextRange(fieldValue.text.length + 1)
                                    )
                                )
                                true
                            } else {
                                false
                            }
                        }
                )
            }

            SekaiIconButton(
                theme = theme,
                icon = Icons.AutoMirrored.Outlined.Backspace,
                contentDescription = "Retroceso",
                size = btnSize,
                onClick = onBackspace
            )

            SekaiIconButton(
                theme = theme,
                icon = Icons.Outlined.Close,
                contentDescription = "Limpiar vista previa",
                enabled = previewText.isNotEmpty(),
                size = btnSize,
                onClick = onClear
            )

            SekaiIconButton(
                theme = theme,
                icon = Icons.Outlined.KeyboardHide,
                contentDescription = "Ocultar teclado",
                active = true,
                fillColor = theme.accentA,
                size = btnSize,
                onClick = onHideKeyboard
            )
        }
    }
}

@Composable
private fun TrackpadTouchSurface(
    theme: SekaiResolvedTheme,
    activePointers: Int,
    edgeScrolling: Boolean,
    touchSlop: Float,
    compact: Boolean,
    onPointersChanged: (Int) -> Unit,
    onEdgeScrollingChanged: (Boolean) -> Unit,
    onMouseMove: (dx: Int, dy: Int, wheel: Int) -> Unit,
    onTapPointers: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val latestOnPointersChanged by rememberUpdatedState(onPointersChanged)
    val latestOnEdgeScrollingChanged by rememberUpdatedState(onEdgeScrollingChanged)
    val latestOnMouseMove by rememberUpdatedState(onMouseMove)
    val latestOnTapPointers by rememberUpdatedState(onTapPointers)

    SekaiPlate(
        theme = theme,
        cornerRadius = 22.dp,
        borderWidth = 2.6.dp,
        shadowOffset = 4.dp,
        fillColor = if (activePointers > 0) theme.surfaceHover else theme.surface,
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(touchSlop) {
                    val scrollStripPx = 28.dp.toPx()
                    awaitEachGesture {
                        val firstDown = awaitFirstDown(requireUnconsumed = false)
                        val downTime = System.currentTimeMillis()
                        val isEdgeScroll = firstDown.position.x >= (size.width - scrollStripPx)
                        var maxPointers = 1
                        var totalMoved = 0f
                        var accumX = 0f
                        var accumY = 0f
                        var accumScroll = 0f
                        val prevPositions = HashMap<Long, Pair<Float, Float>>()
                        prevPositions[firstDown.id.value] = firstDown.position.x to firstDown.position.y
                        latestOnPointersChanged(1)
                        latestOnEdgeScrollingChanged(isEdgeScroll)

                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val pressedChanges = event.changes.filter { it.pressed }
                                latestOnPointersChanged(pressedChanges.size)
                                if (pressedChanges.size > maxPointers) {
                                    maxPointers = pressedChanges.size
                                }
                                if (pressedChanges.isEmpty()) break

                                if (pressedChanges.size == 1) {
                                    val c = pressedChanges[0]
                                    val prev = prevPositions[c.id.value]
                                    if (prev != null) {
                                        val dx = c.position.x - prev.first
                                        val dy = c.position.y - prev.second
                                        totalMoved += abs(dx) + abs(dy)

                                        if (isEdgeScroll) {
                                            accumScroll += dy / 22f
                                            val wheelSteps = accumScroll.toInt().coerceIn(-12, 12)
                                            if (wheelSteps != 0) {
                                                accumScroll -= wheelSteps
                                                latestOnMouseMove(0, 0, -wheelSteps)
                                            }
                                        } else {
                                            accumX += dx * 1.35f
                                            accumY += dy * 1.35f
                                            val sendDx = accumX.roundToInt().coerceIn(-127, 127)
                                            val sendDy = accumY.roundToInt().coerceIn(-127, 127)
                                            if (sendDx != 0 || sendDy != 0) {
                                                accumX -= sendDx
                                                accumY -= sendDy
                                                latestOnMouseMove(sendDx, sendDy, 0)
                                            }
                                        }
                                    }
                                } else {
                                    latestOnEdgeScrollingChanged(false)
                                    var avgDy = 0f
                                    var count = 0
                                    for (c in pressedChanges) {
                                        val prev = prevPositions[c.id.value]
                                        if (prev != null) {
                                            avgDy += (c.position.y - prev.second)
                                            totalMoved += abs(c.position.x - prev.first) + abs(c.position.y - prev.second)
                                            count++
                                        }
                                    }
                                    if (count > 0) {
                                        accumScroll += (avgDy / count) / 28f
                                        val wheelSteps = accumScroll.toInt().coerceIn(-12, 12)
                                        if (wheelSteps != 0) {
                                            accumScroll -= wheelSteps
                                            latestOnMouseMove(0, 0, -wheelSteps)
                                        }
                                    }
                                }

                                prevPositions.clear()
                                for (c in pressedChanges) {
                                    prevPositions[c.id.value] = c.position.x to c.position.y
                                    c.consume()
                                }
                            }
                        } finally {
                            latestOnPointersChanged(0)
                            latestOnEdgeScrollingChanged(false)
                        }

                        val elapsed = System.currentTimeMillis() - downTime
                        if (elapsed < 240L && totalMoved < touchSlop * 2f) {
                            if (isEdgeScroll && maxPointers == 1) {
                                val step = if (firstDown.position.y < size.height / 2f) 2 else -2
                                latestOnMouseMove(0, 0, step)
                            } else {
                                latestOnTapPointers(maxPointers)
                            }
                        }
                    }
                }
        ) {
            // Subtle dot grid inside trackpad surface
            Canvas(modifier = Modifier.fillMaxSize()) {
                val dotColor = theme.line.copy(alpha = if (theme.isDark) 0.16f else 0.08f)
                val step = 22.dp.toPx()
                val usableWidth = (size.width - 28.dp.toPx()).coerceAtLeast(0f)
                val cols = (usableWidth / step).toInt()
                val rows = (size.height / step).toInt()
                for (c in 1..cols) {
                    for (r in 1 until rows) {
                        drawCircle(
                            color = dotColor,
                            radius = 1.4.dp.toPx(),
                            center = Offset(c * step, r * step)
                        )
                    }
                }
            }

            // Center gesture hint
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(start = 12.dp, end = 34.dp, top = 8.dp, bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 4.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.TouchApp,
                    contentDescription = null,
                    tint = if (activePointers > 0) theme.accentA else theme.inkSub,
                    modifier = Modifier.size(if (compact) 24.dp else 30.dp)
                )
                Text(
                    text = when {
                        edgeScrolling -> "Scroll vertical"
                        activePointers >= 2 -> "Scroll con 2 dedos"
                        activePointers == 1 -> "Moviendo cursor..."
                        compact -> "1 dedo: Mover • Borde der.: Scroll"
                        else -> "1 dedo: Mover / Clic • 2 dedos o borde der.: Scroll"
                    },
                    color = theme.inkSub,
                    fontSize = if (compact) 10.5.sp else 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Right-edge vertical scroll rail visual cue (^ ... | ... v) inside rightmost 28.dp
            Row(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(28.dp)
                    .fillMaxHeight()
                    .background(
                        if (edgeScrolling) {
                            theme.accentA.copy(alpha = if (theme.isDark) 0.22f else 0.30f)
                        } else {
                            theme.line.copy(alpha = if (theme.isDark) 0.08f else 0.04f)
                        }
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(1.5.dp)
                        .fillMaxHeight()
                        .padding(vertical = 10.dp)
                        .background(
                            if (edgeScrolling) theme.accentA else theme.line.copy(alpha = 0.22f)
                        )
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Icon(
                        imageVector = Icons.Outlined.KeyboardArrowUp,
                        contentDescription = "Scroll arriba",
                        tint = if (edgeScrolling) theme.accentA else theme.inkSub.copy(alpha = 0.78f),
                        modifier = Modifier.size(16.dp)
                    )
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .weight(1f)
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(
                                if (edgeScrolling) theme.accentA else theme.line.copy(alpha = 0.24f)
                            )
                    )
                    Icon(
                        imageVector = Icons.Outlined.KeyboardArrowDown,
                        contentDescription = "Scroll abajo",
                        tint = if (edgeScrolling) theme.accentA else theme.inkSub.copy(alpha = 0.78f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun MouseButtonsBar(
    theme: SekaiResolvedTheme,
    buttonMask: Int,
    oversized: Boolean,
    compact: Boolean,
    onMaskChange: (Int) -> Unit
) {
    val height = when {
        oversized -> 50.dp
        compact -> 36.dp
        else -> 40.dp
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        HoldableMouseKey(
            theme = theme,
            label = "Izquierdo",
            bit = MOUSE_LEFT,
            currentMask = buttonMask,
            height = height,
            onMaskChange = onMaskChange,
            modifier = Modifier.weight(if (oversized) 1.5f else 1.35f)
        )
        HoldableMouseKey(
            theme = theme,
            label = "Medio",
            bit = MOUSE_MIDDLE,
            currentMask = buttonMask,
            height = height,
            onMaskChange = onMaskChange,
            modifier = Modifier.weight(if (oversized) 0.9f else 1.0f)
        )
        HoldableMouseKey(
            theme = theme,
            label = "Derecho",
            bit = MOUSE_RIGHT,
            currentMask = buttonMask,
            height = height,
            onMaskChange = onMaskChange,
            modifier = Modifier.weight(if (oversized) 1.5f else 1.35f)
        )
    }
}

@Composable
private fun HoldableMouseKey(
    theme: SekaiResolvedTheme,
    label: String,
    bit: Int,
    currentMask: Int,
    height: Dp,
    onMaskChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val pressed = (currentMask and bit) != 0
    val latestMask by rememberUpdatedState(currentMask)

    SekaiPlate(
        theme = theme,
        cornerRadius = 14.dp,
        borderWidth = 2.2.dp,
        shadowOffset = 3.dp,
        fillColor = if (pressed) theme.accentA else theme.surface,
        pressed = pressed,
        contentAlignment = Alignment.Center,
        modifier = modifier.pointerInput(bit) {
            detectTapGestures(
                onPress = {
                    onMaskChange(latestMask or bit)
                    try {
                        tryAwaitRelease()
                    } finally {
                        onMaskChange(latestMask and bit.inv())
                    }
                }
            )
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = if (pressed) Color(0xFF1A1110) else theme.ink,
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@Composable
private fun KeyboardControlsCard(
    theme: SekaiResolvedTheme,
    compact: Boolean,
    directKeyboardOpen: Boolean,
    onToggleDirectKeyboard: () -> Unit,
    ctrlActive: Boolean,
    shiftActive: Boolean,
    altActive: Boolean,
    superActive: Boolean,
    onToggleCtrl: () -> Unit,
    onToggleShift: () -> Unit,
    onToggleAlt: () -> Unit,
    onToggleSuper: () -> Unit,
    onSendKeyTap: (hidUsage: Int, linuxKeycode: Int) -> Unit
) {
    val actionBtnSize = if (compact) 29.dp else 33.dp
    SekaiPlate(
        theme = theme,
        cornerRadius = 18.dp,
        shadowOffset = 3.dp,
        fillColor = theme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (compact) 6.dp else 9.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp)
        ) {
            // Instant Direct Keyboard trigger pill (replaces old buffered text + send button)
            SekaiKeyPill(
                theme = theme,
                active = directKeyboardOpen,
                fillColor = if (directKeyboardOpen) theme.accentB else theme.surfaceHover,
                shadowOffset = 2.dp,
                cornerRadius = 14.dp,
                modifier = Modifier.fillMaxWidth(),
                onClick = onToggleDirectKeyboard
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Keyboard,
                            contentDescription = null,
                            tint = if (directKeyboardOpen) Color(0xFF1A1110) else theme.ink,
                            modifier = Modifier.size(if (compact) 16.dp else 18.dp)
                        )
                        Text(
                            text = if (directKeyboardOpen) {
                                "Teclado directo activo"
                            } else {
                                "Abrir teclado directo"
                            },
                            color = if (directKeyboardOpen) Color(0xFF1A1110) else theme.ink,
                            fontSize = if (compact) 11.5.sp else 12.sp,
                            fontWeight = FontWeight.Black,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = if (directKeyboardOpen) "OCULTAR" else "TIEMPO REAL",
                        color = if (directKeyboardOpen) Color(0xFF1A1110) else theme.inkSub,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.5.sp
                    )
                }
            }

            // Modifier toggles cleanly aligned across full width
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ModifierChipKey(theme, "Ctrl", ctrlActive, Modifier.weight(1f), onToggleCtrl)
                ModifierChipKey(theme, "Shift", shiftActive, Modifier.weight(1f), onToggleShift)
                ModifierChipKey(theme, "Alt", altActive, Modifier.weight(1f), onToggleAlt)
                ModifierChipKey(theme, "Super", superActive, Modifier.weight(1f), onToggleSuper)
            }

            // Action & Arrow keys split cleanly into two balanced rows
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SekaiKeyPill(
                    theme = theme,
                    shadowOffset = 2.dp,
                    onClick = { onSendKeyTap(HidKeyMap.HID_ESC, HidKeyMap.KEY_ESC) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Esc", color = theme.ink, fontSize = 11.sp, fontWeight = FontWeight.Black, maxLines = 1)
                }
                SekaiKeyPill(
                    theme = theme,
                    shadowOffset = 2.dp,
                    onClick = { onSendKeyTap(HidKeyMap.HID_TAB, HidKeyMap.KEY_TAB) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Tab", color = theme.ink, fontSize = 11.sp, fontWeight = FontWeight.Black, maxLines = 1)
                }
                SekaiIconButton(
                    theme = theme,
                    icon = Icons.AutoMirrored.Outlined.Backspace,
                    contentDescription = "Backspace",
                    size = actionBtnSize,
                    onClick = { onSendKeyTap(HidKeyMap.HID_BACKSPACE, HidKeyMap.KEY_BACKSPACE) }
                )
                SekaiIconButton(
                    theme = theme,
                    icon = Icons.AutoMirrored.Outlined.KeyboardReturn,
                    contentDescription = "Enter",
                    active = true,
                    size = actionBtnSize,
                    onClick = { onSendKeyTap(HidKeyMap.HID_ENTER, HidKeyMap.KEY_ENTER) }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SekaiIconButton(
                    theme = theme,
                    icon = Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                    contentDescription = "Izquierda",
                    size = actionBtnSize,
                    modifier = Modifier.weight(1f),
                    onClick = { onSendKeyTap(HidKeyMap.HID_LEFT, HidKeyMap.KEY_LEFT) }
                )
                SekaiIconButton(
                    theme = theme,
                    icon = Icons.Outlined.KeyboardArrowUp,
                    contentDescription = "Arriba",
                    size = actionBtnSize,
                    modifier = Modifier.weight(1f),
                    onClick = { onSendKeyTap(HidKeyMap.HID_UP, HidKeyMap.KEY_UP) }
                )
                SekaiIconButton(
                    theme = theme,
                    icon = Icons.Outlined.KeyboardArrowDown,
                    contentDescription = "Abajo",
                    size = actionBtnSize,
                    modifier = Modifier.weight(1f),
                    onClick = { onSendKeyTap(HidKeyMap.HID_DOWN, HidKeyMap.KEY_DOWN) }
                )
                SekaiIconButton(
                    theme = theme,
                    icon = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = "Derecha",
                    size = actionBtnSize,
                    modifier = Modifier.weight(1f),
                    onClick = { onSendKeyTap(HidKeyMap.HID_RIGHT, HidKeyMap.KEY_RIGHT) }
                )
            }
        }
    }
}

@Composable
private fun ModifierChipKey(
    theme: SekaiResolvedTheme,
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit
) {
    SekaiKeyPill(
        theme = theme,
        active = active,
        fillColor = if (active) theme.accentB else theme.surfaceHover,
        shadowOffset = 2.dp,
        modifier = modifier,
        onClick = onToggle
    ) {
        Text(
            text = label,
            color = if (active) Color(0xFF1A1110) else theme.ink,
            fontSize = 11.sp,
            fontWeight = FontWeight.Black,
            maxLines = 1
        )
    }
}

@Composable
private fun MediaControlsCard(
    theme: SekaiResolvedTheme,
    compact: Boolean,
    onSendConsumer: (Int) -> Unit
) {
    SekaiPlate(
        theme = theme,
        cornerRadius = 18.dp,
        shadowOffset = 3.dp,
        fillColor = theme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = if (compact) 8.dp else 10.dp, vertical = if (compact) 6.dp else 9.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp)
        ) {
            if (!compact) {
                Text(
                    text = "MULTIMEDIA Y VOLUMEN",
                    color = theme.inkSub,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.7.sp
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                SekaiIconButton(
                    theme = theme,
                    icon = Icons.Outlined.SkipPrevious,
                    contentDescription = "Anterior",
                    size = if (compact) 32.dp else 36.dp,
                    onClick = { onSendConsumer(HidKeyMap.CONSUMER_PREV) }
                )
                SekaiIconButton(
                    theme = theme,
                    icon = Icons.Outlined.PlayArrow,
                    contentDescription = "Play / Pausa",
                    active = true,
                    size = if (compact) 32.dp else 36.dp,
                    onClick = { onSendConsumer(HidKeyMap.CONSUMER_PLAY_PAUSE) }
                )
                SekaiIconButton(
                    theme = theme,
                    icon = Icons.Outlined.SkipNext,
                    contentDescription = "Siguiente",
                    size = if (compact) 32.dp else 36.dp,
                    onClick = { onSendConsumer(HidKeyMap.CONSUMER_NEXT) }
                )
                SekaiIconButton(
                    theme = theme,
                    icon = Icons.AutoMirrored.Outlined.VolumeOff,
                    contentDescription = "Silenciar",
                    size = if (compact) 32.dp else 36.dp,
                    onClick = { onSendConsumer(HidKeyMap.CONSUMER_MUTE) }
                )
                SekaiIconButton(
                    theme = theme,
                    icon = Icons.AutoMirrored.Outlined.VolumeDown,
                    contentDescription = "Bajar volumen",
                    size = if (compact) 32.dp else 36.dp,
                    onClick = { onSendConsumer(HidKeyMap.CONSUMER_VOL_DOWN) }
                )
                SekaiIconButton(
                    theme = theme,
                    icon = Icons.AutoMirrored.Outlined.VolumeUp,
                    contentDescription = "Subir volumen",
                    size = if (compact) 32.dp else 36.dp,
                    onClick = { onSendConsumer(HidKeyMap.CONSUMER_VOL_UP) }
                )
            }
        }
    }
}

@Composable
private fun MultimediaModeContent(
    theme: SekaiResolvedTheme,
    isLandscape: Boolean,
    tightPortrait: Boolean,
    activePointers: Int,
    edgeScrolling: Boolean,
    touchSlop: Float,
    onPointersChanged: (Int) -> Unit,
    onEdgeScrollingChanged: (Boolean) -> Unit,
    onMouseMove: (dx: Int, dy: Int, wheel: Int) -> Unit,
    onTapPointers: (Int) -> Unit,
    onSendConsumer: (Int) -> Unit,
    onSendKeyTap: (hidUsage: Int, linuxKeycode: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (isLandscape) {
        // Ergonomic 2-column remote for 792dp x 363.4dp Landscape phone geometry
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1.05f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MediaSystemTopRow(
                    theme = theme,
                    compact = true,
                    onSendConsumer = onSendConsumer
                )
                MediaTransportRow(
                    theme = theme,
                    compact = true,
                    onSendConsumer = onSendConsumer
                )
                TrackpadTouchSurface(
                    theme = theme,
                    activePointers = activePointers,
                    edgeScrolling = edgeScrolling,
                    touchSlop = touchSlop,
                    compact = true,
                    onPointersChanged = onPointersChanged,
                    onEdgeScrollingChanged = onEdgeScrollingChanged,
                    onMouseMove = onMouseMove,
                    onTapPointers = onTapPointers,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
            }

            Column(
                modifier = Modifier
                    .weight(0.95f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MediaDPadCard(
                    theme = theme,
                    compact = true,
                    onSendKeyTap = onSendKeyTap,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
                MediaPlayPauseBigPill(
                    theme = theme,
                    compact = true,
                    onSendConsumer = onSendConsumer
                )
                MediaDesktopNavRow(
                    theme = theme,
                    compact = true,
                    onSendKeyTap = onSendKeyTap
                )
            }
        }
    } else {
        val portraitModifier = if (tightPortrait) {
            modifier.verticalScroll(rememberScrollState())
        } else {
            modifier
        }
        Column(
            modifier = portraitModifier,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MediaSystemTopRow(
                theme = theme,
                compact = false,
                onSendConsumer = onSendConsumer
            )
            MediaTransportRow(
                theme = theme,
                compact = false,
                onSendConsumer = onSendConsumer
            )
            TrackpadTouchSurface(
                theme = theme,
                activePointers = activePointers,
                edgeScrolling = edgeScrolling,
                touchSlop = touchSlop,
                compact = true,
                onPointersChanged = onPointersChanged,
                onEdgeScrollingChanged = onEdgeScrollingChanged,
                onMouseMove = onMouseMove,
                onTapPointers = onTapPointers,
                modifier = if (tightPortrait) {
                    Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                } else {
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .heightIn(min = 100.dp)
                }
            )
            MediaDPadCard(
                theme = theme,
                compact = false,
                onSendKeyTap = onSendKeyTap,
                modifier = Modifier.fillMaxWidth()
            )
            MediaPlayPauseBigPill(
                theme = theme,
                compact = false,
                onSendConsumer = onSendConsumer
            )
            MediaDesktopNavRow(
                theme = theme,
                compact = false,
                onSendKeyTap = onSendKeyTap
            )
        }
    }
}

@Composable
private fun MediaSystemTopRow(
    theme: SekaiResolvedTheme,
    compact: Boolean,
    onSendConsumer: (Int) -> Unit
) {
    val btnSize = if (compact) 34.dp else 38.dp
    SekaiPlate(
        theme = theme,
        cornerRadius = 16.dp,
        shadowOffset = 3.dp,
        fillColor = theme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = if (compact) 5.dp else 7.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SekaiIconButton(
                theme = theme,
                icon = Icons.Outlined.MicOff,
                contentDescription = "Silenciar micrófono",
                size = btnSize,
                onClick = { EcosystemStateHub.sendDockSystemAction("mic_mute") }
            )
            SekaiIconButton(
                theme = theme,
                icon = Icons.AutoMirrored.Outlined.VolumeDown,
                contentDescription = "Bajar volumen",
                size = btnSize,
                onClick = { onSendConsumer(HidKeyMap.CONSUMER_VOL_DOWN) }
            )
            SekaiIconButton(
                theme = theme,
                icon = Icons.AutoMirrored.Outlined.VolumeOff,
                contentDescription = "Silenciar audio",
                size = btnSize,
                fillColor = theme.surfaceHover,
                onClick = { onSendConsumer(HidKeyMap.CONSUMER_MUTE) }
            )
            SekaiIconButton(
                theme = theme,
                icon = Icons.AutoMirrored.Outlined.VolumeUp,
                contentDescription = "Subir volumen",
                size = btnSize,
                onClick = { onSendConsumer(HidKeyMap.CONSUMER_VOL_UP) }
            )
            SekaiIconButton(
                theme = theme,
                icon = Icons.Outlined.Lock,
                contentDescription = "Bloquear PC",
                size = btnSize,
                fillColor = theme.pinkAccent,
                iconTint = Color(0xFF1A1110),
                onClick = { EcosystemStateHub.sendDockSystemAction("lock") }
            )
        }
    }
}

@Composable
private fun MediaTransportRow(
    theme: SekaiResolvedTheme,
    compact: Boolean,
    onSendConsumer: (Int) -> Unit
) {
    val btnSize = if (compact) 34.dp else 38.dp
    SekaiPlate(
        theme = theme,
        cornerRadius = 16.dp,
        shadowOffset = 3.dp,
        fillColor = theme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = if (compact) 5.dp else 7.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SekaiIconButton(
                theme = theme,
                icon = Icons.Outlined.SkipPrevious,
                contentDescription = "Pista anterior",
                size = btnSize,
                onClick = { onSendConsumer(HidKeyMap.CONSUMER_PREV) }
            )
            SekaiIconButton(
                theme = theme,
                icon = Icons.Outlined.FastRewind,
                contentDescription = "Retroceder",
                size = btnSize,
                onClick = { onSendConsumer(HidKeyMap.CONSUMER_REWIND) }
            )
            SekaiIconButton(
                theme = theme,
                icon = Icons.Outlined.FastForward,
                contentDescription = "Avanzar",
                size = btnSize,
                onClick = { onSendConsumer(HidKeyMap.CONSUMER_FAST_FORWARD) }
            )
            SekaiIconButton(
                theme = theme,
                icon = Icons.Outlined.SkipNext,
                contentDescription = "Pista siguiente",
                size = btnSize,
                onClick = { onSendConsumer(HidKeyMap.CONSUMER_NEXT) }
            )
        }
    }
}

@Composable
private fun MediaDPadCard(
    theme: SekaiResolvedTheme,
    compact: Boolean,
    onSendKeyTap: (hidUsage: Int, linuxKeycode: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val keySize = if (compact) 36.dp else 46.dp
    SekaiPlate(
        theme = theme,
        cornerRadius = 20.dp,
        shadowOffset = 3.5.dp,
        fillColor = theme.surface,
        contentAlignment = Alignment.Center,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = if (compact) 6.dp else 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp)
        ) {
            SekaiIconButton(
                theme = theme,
                icon = Icons.Outlined.KeyboardArrowUp,
                contentDescription = "Arriba",
                size = keySize,
                fillColor = theme.surfaceHover,
                onClick = { onSendKeyTap(HidKeyMap.HID_UP, HidKeyMap.KEY_UP) }
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SekaiIconButton(
                    theme = theme,
                    icon = Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                    contentDescription = "Izquierda",
                    size = keySize,
                    fillColor = theme.surfaceHover,
                    onClick = { onSendKeyTap(HidKeyMap.HID_LEFT, HidKeyMap.KEY_LEFT) }
                )

                SekaiPlate(
                    theme = theme,
                    cornerRadius = 14.dp,
                    borderWidth = 2.2.dp,
                    shadowOffset = 2.5.dp,
                    fillColor = theme.accentB,
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(width = keySize + 14.dp, height = keySize)
                        .clickable { onSendKeyTap(HidKeyMap.HID_ENTER, HidKeyMap.KEY_ENTER) }
                ) {
                    Text(
                        text = "OK",
                        color = Color(0xFF1A1110),
                        fontSize = if (compact) 12.sp else 13.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 1
                    )
                }

                SekaiIconButton(
                    theme = theme,
                    icon = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = "Derecha",
                    size = keySize,
                    fillColor = theme.surfaceHover,
                    onClick = { onSendKeyTap(HidKeyMap.HID_RIGHT, HidKeyMap.KEY_RIGHT) }
                )
            }

            SekaiIconButton(
                theme = theme,
                icon = Icons.Outlined.KeyboardArrowDown,
                contentDescription = "Abajo",
                size = keySize,
                fillColor = theme.surfaceHover,
                onClick = { onSendKeyTap(HidKeyMap.HID_DOWN, HidKeyMap.KEY_DOWN) }
            )
        }
    }
}

@Composable
private fun MediaPlayPauseBigPill(
    theme: SekaiResolvedTheme,
    compact: Boolean,
    onSendConsumer: (Int) -> Unit
) {
    SekaiKeyPill(
        theme = theme,
        active = true,
        fillColor = theme.accentA,
        shadowOffset = 3.5.dp,
        cornerRadius = 16.dp,
        modifier = Modifier.fillMaxWidth(),
        onClick = { onSendConsumer(HidKeyMap.CONSUMER_PLAY_PAUSE) }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = if (compact) 2.dp else 5.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.PlayArrow,
                contentDescription = null,
                tint = Color(0xFF1A1110),
                modifier = Modifier.size(if (compact) 20.dp else 24.dp)
            )
            Icon(
                imageVector = Icons.Outlined.Pause,
                contentDescription = null,
                tint = Color(0xFF1A1110),
                modifier = Modifier.size(if (compact) 18.dp else 21.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "PLAY / PAUSA",
                color = Color(0xFF1A1110),
                fontSize = if (compact) 12.5.sp else 14.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.6.sp,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun MediaDesktopNavRow(
    theme: SekaiResolvedTheme,
    compact: Boolean,
    onSendKeyTap: (hidUsage: Int, linuxKeycode: Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MediaNavPill(
            theme = theme,
            label = "Atrás",
            icon = Icons.AutoMirrored.Outlined.ArrowBack,
            compact = compact,
            modifier = Modifier.weight(1f),
            onClick = { onSendKeyTap(HidKeyMap.HID_ESC, HidKeyMap.KEY_ESC) }
        )
        MediaNavPill(
            theme = theme,
            label = "Inicio",
            icon = Icons.Outlined.Home,
            compact = compact,
            modifier = Modifier.weight(1f),
            onClick = { onSendKeyTap(HidKeyMap.MOD_SUPER_HID, HidKeyMap.MOD_SUPER_LINUX) }
        )
        MediaNavPill(
            theme = theme,
            label = "Menú",
            icon = Icons.Outlined.Menu,
            compact = compact,
            modifier = Modifier.weight(1f),
            onClick = { onSendKeyTap(HidKeyMap.HID_COMPOSE, HidKeyMap.KEY_COMPOSE) }
        )
    }
}

@Composable
private fun MediaNavPill(
    theme: SekaiResolvedTheme,
    label: String,
    icon: ImageVector,
    compact: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    SekaiKeyPill(
        theme = theme,
        fillColor = theme.surface,
        shadowOffset = 2.5.dp,
        cornerRadius = 14.dp,
        modifier = modifier,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(vertical = if (compact) 0.dp else 2.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = theme.ink,
                modifier = Modifier.size(if (compact) 15.dp else 17.dp)
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = label,
                color = theme.ink,
                fontSize = if (compact) 11.sp else 12.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

private const val DIRECT_IME_SENTINEL = " "
private const val DIRECT_PREVIEW_MAX_CHARS = 40
private const val MOUSE_LEFT = 0x01
private const val MOUSE_RIGHT = 0x02
private const val MOUSE_MIDDLE = 0x04
