package com.controid.hid

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.controid.protocol.Frame
import com.controid.protocol.InputFallbackEvent
import com.controid.protocol.Multiplexer
import com.controid.protocol.ProtocolConstants
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BluetoothHidController(
    context: Context,
    private val multiplexerProvider: () -> Multiplexer?
) {
    private val appContext = context.applicationContext
    private val started = AtomicBoolean(false)
    private val receiverRegistered = AtomicBoolean(false)
    private val proxyLock = Any()

    @Volatile
    private var executor: ScheduledExecutorService = newExecutor()

    private val _hidConnected = MutableStateFlow(false)
    val hidConnected: StateFlow<Boolean> = _hidConnected.asStateFlow()

    private val _hidFallbackActive = MutableStateFlow(false)
    val hidFallbackActive: StateFlow<Boolean> = _hidFallbackActive.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var hidDevice: BluetoothHidDevice? = null
    private var connectedDevice: BluetoothDevice? = null
    private var appRegistered = false
    private var reconnectInProgress = false
    private var lastReconnectAttemptMs = 0L

    // Tracked HID state (accessed exclusively on single-thread executor)
    private var currentMouseButtons = 0
    private var lastFallbackMouseButtons = 0
    private var trackedModifiers = 0
    private var lastFallbackModifiers = 0
    private val pressedKeys = ArrayList<Int>(6)

    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            when (state) {
                BluetoothAdapter.STATE_ON -> {
                    if (started.get()) {
                        executor.execute { initProxy() }
                        executor.schedule(
                            {
                                if (started.get() && hidDevice == null) {
                                    initProxy()
                                }
                            },
                            1500L,
                            TimeUnit.MILLISECONDS
                        )
                    }
                }

                BluetoothAdapter.STATE_TURNING_OFF,
                BluetoothAdapter.STATE_OFF -> {
                    executor.execute { releaseProxyLocked(unregister = false) }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            executor.execute {
                appRegistered = registered
                if (!registered) {
                    connectedDevice = null
                    resetInputState()
                    updateConnectionState(false)
                    return@execute
                }

                val proxy = hidDevice ?: return@execute
                if (!hasConnectPermission()) return@execute

                val active = runCatching {
                    proxy.getDevicesMatchingConnectionStates(
                        intArrayOf(BluetoothProfile.STATE_CONNECTED)
                    ).firstOrNull()
                }.getOrNull()

                val candidate = pluggedDevice ?: findBondedCandidate()
                if (active != null) {
                    connectedDevice = active
                    resetInputState()
                    updateConnectionState(true)
                } else if (candidate != null) {
                    val devState = runCatching { proxy.getConnectionState(candidate) }
                        .getOrDefault(BluetoothProfile.STATE_DISCONNECTED)
                    when (devState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            connectedDevice = candidate
                            resetInputState()
                            updateConnectionState(true)
                        }

                        BluetoothProfile.STATE_CONNECTING,
                        BluetoothProfile.STATE_DISCONNECTING -> {
                            // Clear dangling L2CAP link state before re-connecting
                            runCatching { proxy.disconnect(candidate) }
                            executor.schedule(
                                {
                                    if (started.get() && appRegistered && connectedDevice == null && hasConnectPermission()) {
                                        runCatching { proxy.connect(candidate) }
                                    }
                                },
                                L2CAP_SETTLE_DELAY_MS,
                                TimeUnit.MILLISECONDS
                            )
                        }

                        else -> {
                            runCatching { proxy.connect(candidate) }
                        }
                    }
                }
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            executor.execute {
                when (state) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        connectedDevice = device
                        resetInputState()
                        _lastError.value = null
                        updateConnectionState(true)
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {
                        if (device == null || connectedDevice == null || device.address == connectedDevice?.address) {
                            connectedDevice = null
                            resetInputState()
                            updateConnectionState(false)
                        }
                    }
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
            val proxy = hidDevice ?: return
            if (device == null || !hasConnectPermission()) return
            val payload = when (id.toInt()) {
                REPORT_ID_MOUSE -> byteArrayOf(currentMouseButtons.toByte(), 0, 0, 0, 0, 0)
                REPORT_ID_KEYBOARD -> buildKeyboardPayload(trackedModifiers)
                REPORT_ID_CONSUMER -> byteArrayOf(0, 0)
                else -> {
                    runCatching {
                        proxy.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID)
                    }
                    return
                }
            }
            runCatching { proxy.replyReport(device, type, id, payload) }
        }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        @SuppressLint("MissingPermission")
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            if (profile != BluetoothProfile.HID_DEVICE || proxy !is BluetoothHidDevice) return
            executor.execute {
                synchronized(proxyLock) {
                    hidDevice = proxy
                }
                if (!started.get() || !hasConnectPermission()) return@execute

                val sdp = BluetoothHidDeviceAppSdpSettings(
                    "Controid HID",
                    "Composite Mouse, Keyboard & Consumer Control",
                    "Controid",
                    BluetoothHidDevice.SUBCLASS1_COMBO,
                    HID_REPORT_DESCRIPTOR
                )
                val outQos = BluetoothHidDeviceAppQosSettings(
                    BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
                    800,
                    9,
                    0,
                    11_250,
                    BluetoothHidDeviceAppQosSettings.MAX
                )

                try {
                    val ok = proxy.registerApp(sdp, null, outQos, executor, hidCallback)
                    if (!ok) {
                        _lastError.value = "BluetoothHidDevice.registerApp returned false"
                    }
                } catch (se: SecurityException) {
                    _lastError.value = "Missing BLUETOOTH_CONNECT for HID registerApp"
                } catch (t: Throwable) {
                    _lastError.value = "HID registerApp error: ${t.message}"
                }
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            executor.execute {
                synchronized(proxyLock) {
                    hidDevice = null
                    appRegistered = false
                    connectedDevice = null
                }
                resetInputState()
                updateConnectionState(false)
            }
        }
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        if (executor.isShutdown) {
            executor = newExecutor()
        }
        registerBluetoothReceiver()
        executor.execute { initProxy() }
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        unregisterBluetoothReceiver()
        executor.execute {
            releaseProxyLocked(unregister = true)
            resetInputState()
            updateConnectionState(false)
        }
        executor.shutdown()
    }

    fun refreshPermissionsAndInit() {
        if (!started.get()) return
        executor.execute { initProxy() }
    }

    fun onTcpConnectionChanged(tcpConnected: Boolean) {
        _hidFallbackActive.value = tcpConnected && !_hidConnected.value
    }

    @SuppressLint("MissingPermission")
    fun reconnect() {
        if (!started.get()) return
        executor.execute {
            val now = System.currentTimeMillis()
            if (reconnectInProgress || now - lastReconnectAttemptMs < RECONNECT_COOLDOWN_MS) {
                return@execute
            }
            lastReconnectAttemptMs = now
            reconnectInProgress = true

            try {
                val proxy = hidDevice
                if (proxy == null || !appRegistered) {
                    initProxy()
                    return@execute
                }
                if (!hasConnectPermission()) {
                    _lastError.value = "Missing BLUETOOTH_CONNECT permission"
                    return@execute
                }

                // Disconnect any dangling CONNECTING/DISCONNECTING devices before reconnecting
                val dangling = runCatching {
                    proxy.getDevicesMatchingConnectionStates(
                        intArrayOf(
                            BluetoothProfile.STATE_CONNECTING,
                            BluetoothProfile.STATE_DISCONNECTING,
                            BluetoothProfile.STATE_CONNECTED
                        )
                    )
                }.getOrDefault(emptyList())

                for (dev in dangling) {
                    runCatching { proxy.disconnect(dev) }
                }

                val target = dangling.firstOrNull() ?: findBondedCandidate()
                if (target != null) {
                    executor.schedule(
                        {
                            if (started.get() && hasConnectPermission()) {
                                runCatching { proxy.connect(target) }
                            }
                        },
                        L2CAP_SETTLE_DELAY_MS,
                        TimeUnit.MILLISECONDS
                    )
                }
            } finally {
                reconnectInProgress = false
            }
        }
    }

    fun sendMouseMove(dx: Int, dy: Int, wheel: Int = 0) {
        if (dx == 0 && dy == 0 && wheel == 0) return
        val clampedDx = dx.coerceIn(-32_767, 32_767)
        val clampedDy = dy.coerceIn(-32_767, 32_767)
        val clampedWheel = wheel.coerceIn(-127, 127)

        executor.execute {
            val payload = byteArrayOf(
                (currentMouseButtons and 0x1F).toByte(),
                (clampedDx and 0xFF).toByte(),
                ((clampedDx ushr 8) and 0xFF).toByte(),
                (clampedDy and 0xFF).toByte(),
                ((clampedDy ushr 8) and 0xFF).toByte(),
                clampedWheel.toByte()
            )
            if (!trySendHidReport(REPORT_ID_MOUSE, payload)) {
                emitFallback(
                    InputFallbackEvent.MouseRel(
                        dx = clampedDx.toShort(),
                        dy = clampedDy.toShort(),
                        wheel = clampedWheel.toByte()
                    )
                )
            }
        }
    }

    fun sendMouseButtons(mask: Int) {
        val newMask = mask and 0x1F
        executor.execute {
            currentMouseButtons = newMask
            val payload = byteArrayOf(newMask.toByte(), 0, 0, 0, 0, 0)
            if (!trySendHidReport(REPORT_ID_MOUSE, payload)) {
                val prev = lastFallbackMouseButtons
                lastFallbackMouseButtons = newMask
                for (bit in 0 until 5) {
                    val bitMask = 1 shl bit
                    val wasDown = (prev and bitMask) != 0
                    val isDown = (newMask and bitMask) != 0
                    if (wasDown != isDown) {
                        emitFallback(InputFallbackEvent.MouseBtn(button = bit + 1, pressed = isDown))
                    }
                }
            } else {
                lastFallbackMouseButtons = newMask
            }
        }
    }

    fun sendKey(modifiers: Int, hidUsage: Int, pressed: Boolean, linuxKeycodeOverride: Int? = null) {
        executor.execute {
            if (hidUsage in 0xE0..0xE7) {
                val modBit = 1 shl (hidUsage - 0xE0)
                trackedModifiers = if (pressed) {
                    trackedModifiers or modBit
                } else {
                    trackedModifiers and modBit.inv()
                }
            } else if (hidUsage in 1..255) {
                if (pressed) {
                    if (!pressedKeys.contains(hidUsage)) {
                        if (pressedKeys.size >= 6) {
                            pressedKeys.removeAt(0)
                        }
                        pressedKeys.add(hidUsage)
                    }
                } else {
                    pressedKeys.remove(hidUsage)
                }
            }

            val effectiveModifiers = (modifiers or trackedModifiers) and 0xFF
            val payload = buildKeyboardPayload(effectiveModifiers)
            if (!trySendHidReport(REPORT_ID_KEYBOARD, payload)) {
                emitFallbackModifiersDelta(modifiers and 0xFF)
                val linuxCode = linuxKeycodeOverride ?: hidUsageToLinuxKeycode(hidUsage)
                if (linuxCode != null) {
                    emitFallback(InputFallbackEvent.KeyEvent(linuxKeycode = linuxCode, pressed = pressed))
                }
            } else {
                lastFallbackModifiers = effectiveModifiers
            }
        }
    }

    fun sendConsumer(usage: Int) {
        val clamped = usage and 0x03FF
        executor.execute {
            val pressPayload = byteArrayOf(
                (clamped and 0xFF).toByte(),
                ((clamped ushr 8) and 0xFF).toByte()
            )
            val sent = trySendHidReport(REPORT_ID_CONSUMER, pressPayload) &&
                trySendHidReport(REPORT_ID_CONSUMER, CONSUMER_RELEASE_PAYLOAD)

            if (!sent) {
                val linuxCode = consumerUsageToLinuxKeycode(clamped) ?: return@execute
                emitFallback(InputFallbackEvent.KeyEvent(linuxKeycode = linuxCode, pressed = true))
                emitFallback(InputFallbackEvent.KeyEvent(linuxKeycode = linuxCode, pressed = false))
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun initProxy() {
        if (!started.get()) return
        val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter ?: run {
            _lastError.value = "BluetoothAdapter unavailable"
            return
        }
        bluetoothAdapter = adapter
        if (!adapter.isEnabled) {
            updateConnectionState(false)
            return
        }
        if (!hasConnectPermission()) {
            _lastError.value = "Missing BLUETOOTH_CONNECT permission"
            return
        }
        if (hidDevice != null) return

        try {
            val requested = adapter.getProfileProxy(appContext, profileListener, BluetoothProfile.HID_DEVICE)
            if (!requested) {
                _lastError.value = "HID_DEVICE profile proxy unavailable"
            }
        } catch (se: SecurityException) {
            _lastError.value = "Bluetooth permission denied for HID profile"
        } catch (t: Throwable) {
            _lastError.value = "Failed getting HID proxy: ${t.message}"
        }
    }

    @SuppressLint("MissingPermission")
    private fun releaseProxyLocked(unregister: Boolean) {
        val proxy: BluetoothHidDevice?
        val adapter: BluetoothAdapter?
        synchronized(proxyLock) {
            proxy = hidDevice
            adapter = bluetoothAdapter
            hidDevice = null
            appRegistered = false
            connectedDevice = null
        }

        if (proxy != null) {
            if (unregister && hasConnectPermission()) {
                runCatching { proxy.unregisterApp() }
            }
            if (adapter != null) {
                runCatching { adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, proxy) }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun trySendHidReport(reportId: Int, payload: ByteArray): Boolean {
        val proxy = hidDevice ?: return false
        val device = connectedDevice ?: return false
        if (!appRegistered || !hasConnectPermission()) return false

        return try {
            val sent = proxy.sendReport(device, reportId, payload)
            if (!sent) {
                handleSendFailure(proxy, device)
            }
            sent
        } catch (se: SecurityException) {
            _lastError.value = "Missing BLUETOOTH_CONNECT for HID sendReport"
            updateConnectionState(false)
            false
        } catch (t: Throwable) {
            Log.w(TAG, "HID sendReport failed", t)
            handleSendFailure(proxy, device)
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleSendFailure(proxy: BluetoothHidDevice, device: BluetoothDevice) {
        val state = runCatching { proxy.getConnectionState(device) }
            .getOrDefault(BluetoothProfile.STATE_DISCONNECTED)
        if (state != BluetoothProfile.STATE_CONNECTED) {
            connectedDevice = null
            resetInputState()
            updateConnectionState(false)
        }
    }

    private fun emitFallback(event: InputFallbackEvent) {
        val mux = multiplexerProvider() ?: return
        _hidFallbackActive.value = true
        mux.trySendUrgent(
            Frame(
                channelId = ProtocolConstants.CHANNEL_INPUT_FALLBACK,
                streamId = 0,
                flags = ProtocolConstants.FLAG_URGENT,
                sequenceNumber = 0,
                payload = event.encode()
            )
        )
    }

    private fun emitFallbackModifiersDelta(explicitModifiers: Int) {
        for (bit in 0 until 8) {
            val mask = 1 shl bit
            val prevDown = (lastFallbackModifiers and mask) != 0
            val newDown = (explicitModifiers and mask) != 0
            if (prevDown != newDown) {
                val linuxCode = hidUsageToLinuxKeycode(0xE0 + bit) ?: continue
                emitFallback(InputFallbackEvent.KeyEvent(linuxKeycode = linuxCode, pressed = newDown))
            }
        }
        lastFallbackModifiers = explicitModifiers
    }

    private fun buildKeyboardPayload(modifiers: Int): ByteArray {
        val out = ByteArray(8)
        out[0] = (modifiers and 0xFF).toByte()
        out[1] = 0x00
        for (i in 0 until pressedKeys.size.coerceAtMost(6)) {
            out[2 + i] = (pressedKeys[i] and 0xFF).toByte()
        }
        return out
    }

    private fun resetInputState() {
        currentMouseButtons = 0
        lastFallbackMouseButtons = 0
        trackedModifiers = 0
        lastFallbackModifiers = 0
        pressedKeys.clear()
    }

    private fun updateConnectionState(connected: Boolean) {
        _hidConnected.value = connected
        _hidFallbackActive.value = !connected && multiplexerProvider() != null
    }

    @SuppressLint("MissingPermission")
    private fun findBondedCandidate(): BluetoothDevice? {
        if (!hasConnectPermission()) return null
        return runCatching {
            val bonded = bluetoothAdapter?.bondedDevices.orEmpty()
            val computers = bonded.filter {
                it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.COMPUTER
            }
            val isConnectedFn: (BluetoothDevice) -> Boolean = { dev ->
                runCatching {
                    dev.javaClass.getMethod("isConnected").invoke(dev) as? Boolean ?: false
                }.getOrDefault(false)
            }
            computers.firstOrNull(isConnectedFn)
                ?: computers.firstOrNull()
                ?: bonded.firstOrNull(isConnectedFn)
                ?: bonded.firstOrNull()
        }.getOrNull()
    }

    private fun registerBluetoothReceiver() {
        if (!receiverRegistered.compareAndSet(false, true)) return
        ContextCompat.registerReceiver(
            appContext,
            btStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    private fun unregisterBluetoothReceiver() {
        if (!receiverRegistered.compareAndSet(true, false)) return
        runCatching { appContext.unregisterReceiver(btStateReceiver) }
    }

    private fun hasConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private fun consumerUsageToLinuxKeycode(usage: Int): Int? = when (usage) {
        0x00B5 -> 163 // KEY_NEXTSONG
        0x00B6 -> 165 // KEY_PREVIOUSSONG
        0x00CD -> 164 // KEY_PLAYPAUSE
        0x00E2 -> 113 // KEY_MUTE
        0x00E9 -> 115 // KEY_VOLUMEUP
        0x00EA -> 114 // KEY_VOLUMEDOWN
        else -> null
    }

    private fun hidUsageToLinuxKeycode(usage: Int): Int? = when (usage) {
        in 0x04..0x1D -> LINUX_ALPHA_KEYS[usage - 0x04]
        in 0x1E..0x27 -> LINUX_DIGIT_KEYS[usage - 0x1E]
        0x28 -> 28  // KEY_ENTER
        0x29 -> 1   // KEY_ESC
        0x2A -> 14  // KEY_BACKSPACE
        0x2B -> 15  // KEY_TAB
        0x2C -> 57  // KEY_SPACE
        0x2D -> 12  // KEY_MINUS
        0x2E -> 13  // KEY_EQUAL
        0x2F -> 26  // KEY_LEFTBRACE
        0x30 -> 27  // KEY_RIGHTBRACE
        0x31 -> 43  // KEY_BACKSLASH
        0x32 -> 43  // KEY_BACKSLASH (non-US #/~)
        0x33 -> 39  // KEY_SEMICOLON
        0x34 -> 40  // KEY_APOSTROPHE
        0x35 -> 41  // KEY_GRAVE
        0x36 -> 51  // KEY_COMMA
        0x37 -> 52  // KEY_DOT
        0x38 -> 53  // KEY_SLASH
        0x39 -> 58  // KEY_CAPSLOCK
        in 0x3A..0x43 -> 59 + (usage - 0x3A) // F1..F10
        0x44 -> 87  // KEY_F11
        0x45 -> 88  // KEY_F12
        0x49 -> 110 // KEY_INSERT
        0x4A -> 102 // KEY_HOME
        0x4B -> 104 // KEY_PAGEUP
        0x4C -> 111 // KEY_DELETE
        0x4D -> 107 // KEY_END
        0x4E -> 109 // KEY_PAGEDOWN
        0x4F -> 106 // KEY_RIGHT
        0x50 -> 105 // KEY_LEFT
        0x51 -> 108 // KEY_DOWN
        0x52 -> 103 // KEY_UP
        0xE0 -> 29  // KEY_LEFTCTRL
        0xE1 -> 42  // KEY_LEFTSHIFT
        0xE2 -> 56  // KEY_LEFTALT
        0xE3 -> 125 // KEY_LEFTMETA
        0xE4 -> 97  // KEY_RIGHTCTRL
        0xE5 -> 54  // KEY_RIGHTSHIFT
        0xE6 -> 100 // KEY_RIGHTALT
        0xE7 -> 126 // KEY_RIGHTMETA
        else -> null
    }

    private fun newExecutor(): ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "Controid-HID").apply { isDaemon = true }
        }

    companion object {
        private const val TAG = "BluetoothHidController"
        private const val REPORT_ID_MOUSE = 1
        private const val REPORT_ID_KEYBOARD = 2
        private const val REPORT_ID_CONSUMER = 3
        private const val L2CAP_SETTLE_DELAY_MS = 250L
        private const val RECONNECT_COOLDOWN_MS = 1_500L

        private val CONSUMER_RELEASE_PAYLOAD = byteArrayOf(0x00, 0x00)

        private val LINUX_ALPHA_KEYS = intArrayOf(
            30, 48, 46, 32, 18, 33, 34, 35, 23, 36, 37, 38, 50,
            49, 24, 25, 16, 19, 31, 20, 22, 47, 17, 45, 21, 44
        )
        private val LINUX_DIGIT_KEYS = intArrayOf(
            2, 3, 4, 5, 6, 7, 8, 9, 10, 11
        )

        val HID_REPORT_DESCRIPTOR = byteArrayOf(
            // Report ID 1: Mouse (5 buttons + 3-bit padding, 16-bit rel X/Y, 8-bit wheel)
            0x05.toByte(), 0x01.toByte(),
            0x09.toByte(), 0x02.toByte(),
            0xA1.toByte(), 0x01.toByte(),
            0x85.toByte(), REPORT_ID_MOUSE.toByte(),
            0x09.toByte(), 0x01.toByte(),
            0xA1.toByte(), 0x00.toByte(),
            0x05.toByte(), 0x09.toByte(),
            0x19.toByte(), 0x01.toByte(),
            0x29.toByte(), 0x05.toByte(),
            0x15.toByte(), 0x00.toByte(),
            0x25.toByte(), 0x01.toByte(),
            0x95.toByte(), 0x05.toByte(),
            0x75.toByte(), 0x01.toByte(),
            0x81.toByte(), 0x02.toByte(),
            0x95.toByte(), 0x01.toByte(),
            0x75.toByte(), 0x03.toByte(),
            0x81.toByte(), 0x03.toByte(),
            0x05.toByte(), 0x01.toByte(),
            0x09.toByte(), 0x30.toByte(),
            0x09.toByte(), 0x31.toByte(),
            0x16.toByte(), 0x01.toByte(), 0x80.toByte(),
            0x26.toByte(), 0xFF.toByte(), 0x7F.toByte(),
            0x75.toByte(), 0x10.toByte(),
            0x95.toByte(), 0x02.toByte(),
            0x81.toByte(), 0x06.toByte(),
            0x09.toByte(), 0x38.toByte(),
            0x15.toByte(), 0x81.toByte(),
            0x25.toByte(), 0x7F.toByte(),
            0x75.toByte(), 0x08.toByte(),
            0x95.toByte(), 0x01.toByte(),
            0x81.toByte(), 0x06.toByte(),
            0xC0.toByte(),
            0xC0.toByte(),

            // Report ID 2: Keyboard (8-bit modifier mask, 1 reserved byte, 6-byte keycode array)
            0x05.toByte(), 0x01.toByte(),
            0x09.toByte(), 0x06.toByte(),
            0xA1.toByte(), 0x01.toByte(),
            0x85.toByte(), REPORT_ID_KEYBOARD.toByte(),
            0x05.toByte(), 0x07.toByte(),
            0x19.toByte(), 0xE0.toByte(),
            0x29.toByte(), 0xE7.toByte(),
            0x15.toByte(), 0x00.toByte(),
            0x25.toByte(), 0x01.toByte(),
            0x75.toByte(), 0x01.toByte(),
            0x95.toByte(), 0x08.toByte(),
            0x81.toByte(), 0x02.toByte(),
            0x95.toByte(), 0x01.toByte(),
            0x75.toByte(), 0x08.toByte(),
            0x81.toByte(), 0x03.toByte(),
            0x95.toByte(), 0x06.toByte(),
            0x75.toByte(), 0x08.toByte(),
            0x15.toByte(), 0x00.toByte(),
            0x26.toByte(), 0xFF.toByte(), 0x00.toByte(),
            0x05.toByte(), 0x07.toByte(),
            0x19.toByte(), 0x00.toByte(),
            0x2A.toByte(), 0xFF.toByte(), 0x00.toByte(),
            0x81.toByte(), 0x00.toByte(),
            0xC0.toByte(),

            // Report ID 3: Consumer Control (16-bit usage)
            0x05.toByte(), 0x0C.toByte(),
            0x09.toByte(), 0x01.toByte(),
            0xA1.toByte(), 0x01.toByte(),
            0x85.toByte(), REPORT_ID_CONSUMER.toByte(),
            0x15.toByte(), 0x00.toByte(),
            0x26.toByte(), 0xFF.toByte(), 0x03.toByte(),
            0x19.toByte(), 0x00.toByte(),
            0x2A.toByte(), 0xFF.toByte(), 0x03.toByte(),
            0x75.toByte(), 0x10.toByte(),
            0x95.toByte(), 0x01.toByte(),
            0x81.toByte(), 0x00.toByte(),
            0xC0.toByte()
        )
    }
}
