package com.controid.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.automirrored.outlined.KeyboardReturn
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.VolumeDown
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrackpadScreen(
    state: EcosystemUiState,
    onMouseMove: (dx: Int, dy: Int, wheel: Int) -> Unit,
    onMouseButtons: (mask: Int) -> Unit,
    onSendKey: (modifiers: Int, hidUsage: Int, pressed: Boolean, linuxKeycode: Int?) -> Unit,
    onSendConsumer: (usage: Int) -> Unit,
    onReconnectHid: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val viewConfig = LocalViewConfiguration.current

    var buttonMask by remember { mutableIntStateOf(0) }
    var activePointers by remember { mutableIntStateOf(0) }

    var ctrlActive by remember { mutableStateOf(false) }
    var shiftActive by remember { mutableStateOf(false) }
    var altActive by remember { mutableStateOf(false) }
    var superActive by remember { mutableStateOf(false) }
    var textInput by remember { mutableStateOf("") }

    fun currentModifierMask(extraShift: Boolean = false): Int {
        var mask = 0
        if (ctrlActive) mask = mask or 0x01
        if (shiftActive || extraShift) mask = mask or 0x02
        if (altActive) mask = mask or 0x04
        if (superActive) mask = mask or 0x08
        return mask
    }

    fun sendKeyTap(hidUsage: Int, linuxKeycode: Int, withModifiers: Boolean = true) {
        scope.launch {
            val modMask = if (withModifiers) currentModifierMask() else 0
            onSendKey(modMask, hidUsage, true, linuxKeycode)
            delay(15L)
            onSendKey(modMask, hidUsage, false, linuxKeycode)
        }
    }

    fun dispatchText(text: String) {
        if (text.isEmpty()) return
        scope.launch {
            for (ch in text) {
                val map = HidKeyMap.mapChar(ch) ?: continue
                val modMask = currentModifierMask(extraShift = map.shift)
                onSendKey(modMask, map.hidUsage, true, map.linuxKeycode)
                delay(12L)
                onSendKey(modMask, map.hidUsage, false, map.linuxKeycode)
                delay(8L)
            }
        }
    }

    fun clickMouseButton(bit: Int) {
        scope.launch {
            onMouseButtons(buttonMask or bit)
            delay(30L)
            onMouseButtons(buttonMask and bit.inv())
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Remote Input",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            val routeLabel = when {
                state.hidConnected -> "Bluetooth HID"
                state.fallbackInputActive || state.tcpAuthenticated -> "TCP uinput Fallback"
                else -> "Disconnected"
            }
            AssistChip(
                onClick = { if (!state.hidConnected) onReconnectHid() },
                enabled = !state.hidConnected,
                label = {
                    Text(if (state.hidConnected) routeLabel else "$routeLabel • Reconnect HID")
                }
            )
        }

        // Touch Trackpad Surface
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 240.dp, max = 340.dp)
                .clip(MaterialTheme.shapes.large)
                .pointerInput(viewConfig.touchSlop) {
                    val touchSlop = viewConfig.touchSlop
                    awaitEachGesture {
                        val firstDown = awaitFirstDown(requireUnconsumed = false)
                        val downTime = System.currentTimeMillis()
                        var maxPointers = 1
                        var totalMoved = 0f
                        var accumX = 0f
                        var accumY = 0f
                        var accumScroll = 0f
                        val prevPositions = HashMap<Long, Pair<Float, Float>>()
                        prevPositions[firstDown.id.value] = firstDown.position.x to firstDown.position.y
                        activePointers = 1

                        while (true) {
                            val event = awaitPointerEvent()
                            val pressedChanges = event.changes.filter { it.pressed }
                            activePointers = pressedChanges.size
                            if (pressedChanges.size > maxPointers) {
                                maxPointers = pressedChanges.size
                            }

                            if (pressedChanges.isEmpty()) {
                                break
                            }

                            if (pressedChanges.size == 1) {
                                val c = pressedChanges[0]
                                val prev = prevPositions[c.id.value]
                                if (prev != null) {
                                    val dx = c.position.x - prev.first
                                    val dy = c.position.y - prev.second
                                    totalMoved += abs(dx) + abs(dy)
                                    accumX += dx * 1.35f
                                    accumY += dy * 1.35f

                                    val sendDx = accumX.roundToInt().coerceIn(-127, 127)
                                    val sendDy = accumY.roundToInt().coerceIn(-127, 127)
                                    if (sendDx != 0 || sendDy != 0) {
                                        accumX -= sendDx
                                        accumY -= sendDy
                                        onMouseMove(sendDx, sendDy, 0)
                                    }
                                }
                            } else if (pressedChanges.size >= 2) {
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
                                        onMouseMove(0, 0, -wheelSteps)
                                    }
                                }
                            }

                            prevPositions.clear()
                            for (c in pressedChanges) {
                                prevPositions[c.id.value] = c.position.x to c.position.y
                                c.consume()
                            }
                        }

                        activePointers = 0
                        val elapsed = System.currentTimeMillis() - downTime
                        if (elapsed < 240L && totalMoved < touchSlop * 2f) {
                            when (maxPointers) {
                                1 -> clickMouseButton(MOUSE_LEFT)
                                2 -> clickMouseButton(MOUSE_RIGHT)
                            }
                        }
                    }
                },
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.TouchApp,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(32.dp)
                    )
                    Text(
                        text = if (activePointers >= 2) "Two-Finger Scroll" else "1-Finger Move / Tap • 2-Finger Scroll / Right Click",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Physical Mouse Buttons (supports hold for drag!)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HoldableMouseButton(
                label = "Left",
                bit = MOUSE_LEFT,
                currentMask = buttonMask,
                onMaskChange = { newMask ->
                    buttonMask = newMask
                    onMouseButtons(newMask)
                },
                modifier = Modifier.weight(2f)
            )
            HoldableMouseButton(
                label = "Middle",
                bit = MOUSE_MIDDLE,
                currentMask = buttonMask,
                onMaskChange = { newMask ->
                    buttonMask = newMask
                    onMouseButtons(newMask)
                },
                modifier = Modifier.weight(1f)
            )
            HoldableMouseButton(
                label = "Right",
                bit = MOUSE_RIGHT,
                currentMask = buttonMask,
                onMaskChange = { newMask ->
                    buttonMask = newMask
                    onMouseButtons(newMask)
                },
                modifier = Modifier.weight(2f)
            )
        }

        // Keyboard Input Bar
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Type text to send to PC…") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                val toSend = textInput
                                textInput = ""
                                dispatchText(toSend)
                            }
                        )
                    )
                    Button(
                        onClick = {
                            val toSend = textInput
                            textInput = ""
                            dispatchText(toSend)
                        },
                        enabled = textInput.isNotEmpty()
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "Send text")
                    }
                }

                // Modifiers
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = ctrlActive,
                        onClick = { ctrlActive = !ctrlActive },
                        label = { Text("Ctrl") }
                    )
                    FilterChip(
                        selected = shiftActive,
                        onClick = { shiftActive = !shiftActive },
                        label = { Text("Shift") }
                    )
                    FilterChip(
                        selected = altActive,
                        onClick = { altActive = !altActive },
                        label = { Text("Alt") }
                    )
                    FilterChip(
                        selected = superActive,
                        onClick = { superActive = !superActive },
                        label = { Text("Super") }
                    )
                }

                // Navigation & Action Keys
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(onClick = { sendKeyTap(HidKeyMap.HID_ESC, HidKeyMap.KEY_ESC) }) {
                        Text("Esc")
                    }
                    OutlinedButton(onClick = { sendKeyTap(HidKeyMap.HID_TAB, HidKeyMap.KEY_TAB) }) {
                        Text("Tab")
                    }
                    FilledTonalIconButton(
                        onClick = { sendKeyTap(HidKeyMap.HID_BACKSPACE, HidKeyMap.KEY_BACKSPACE) }
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.Backspace, contentDescription = "Backspace")
                    }
                    FilledTonalIconButton(
                        onClick = { sendKeyTap(HidKeyMap.HID_ENTER, HidKeyMap.KEY_ENTER) }
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.KeyboardReturn, contentDescription = "Enter")
                    }
                    FilledTonalIconButton(
                        onClick = { sendKeyTap(HidKeyMap.HID_LEFT, HidKeyMap.KEY_LEFT) }
                    ) {
                        Icon(Icons.Outlined.KeyboardArrowLeft, contentDescription = "Left")
                    }
                    FilledTonalIconButton(
                        onClick = { sendKeyTap(HidKeyMap.HID_UP, HidKeyMap.KEY_UP) }
                    ) {
                        Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = "Up")
                    }
                    FilledTonalIconButton(
                        onClick = { sendKeyTap(HidKeyMap.HID_DOWN, HidKeyMap.KEY_DOWN) }
                    ) {
                        Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "Down")
                    }
                    FilledTonalIconButton(
                        onClick = { sendKeyTap(HidKeyMap.HID_RIGHT, HidKeyMap.KEY_RIGHT) }
                    ) {
                        Icon(Icons.Outlined.KeyboardArrowRight, contentDescription = "Right")
                    }
                }
            }
        }

        // Media Consumer Keys
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Media & Consumer Controls",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    FilledTonalIconButton(onClick = { onSendConsumer(HidKeyMap.CONSUMER_PREV) }) {
                        Icon(Icons.Outlined.SkipPrevious, contentDescription = "Previous")
                    }
                    FilledTonalIconButton(onClick = { onSendConsumer(HidKeyMap.CONSUMER_PLAY_PAUSE) }) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = "Play/Pause")
                    }
                    FilledTonalIconButton(onClick = { onSendConsumer(HidKeyMap.CONSUMER_NEXT) }) {
                        Icon(Icons.Outlined.SkipNext, contentDescription = "Next")
                    }
                    FilledTonalIconButton(onClick = { onSendConsumer(HidKeyMap.CONSUMER_MUTE) }) {
                        Icon(Icons.AutoMirrored.Outlined.VolumeOff, contentDescription = "Mute")
                    }
                    FilledTonalIconButton(onClick = { onSendConsumer(HidKeyMap.CONSUMER_VOL_DOWN) }) {
                        Icon(Icons.Outlined.VolumeDown, contentDescription = "Volume Down")
                    }
                    FilledTonalIconButton(onClick = { onSendConsumer(HidKeyMap.CONSUMER_VOL_UP) }) {
                        Icon(Icons.AutoMirrored.Outlined.VolumeUp, contentDescription = "Volume Up")
                    }
                }
            }
        }
    }
}

@Composable
private fun HoldableMouseButton(
    label: String,
    bit: Int,
    currentMask: Int,
    onMaskChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val pressed = (currentMask and bit) != 0
    val latestMask = androidx.compose.runtime.rememberUpdatedState(currentMask)
    Surface(
        modifier = modifier
            .height(48.dp)
            .clip(MaterialTheme.shapes.medium)
            .pointerInput(bit) {
                detectTapGestures(
                    onPress = {
                        onMaskChange(latestMask.value or bit)
                        try {
                            tryAwaitRelease()
                        } finally {
                            onMaskChange(latestMask.value and bit.inv())
                        }
                    }
                )
            },
        shape = MaterialTheme.shapes.medium,
        color = if (pressed) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

private const val MOUSE_LEFT = 0x01
private const val MOUSE_RIGHT = 0x02
private const val MOUSE_MIDDLE = 0x04
