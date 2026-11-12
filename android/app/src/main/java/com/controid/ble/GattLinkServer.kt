package com.controid.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.controid.protocol.AuthManager
import com.controid.protocol.LinkStatus
import com.controid.service.IRemoteTetheringCallback
import com.controid.service.ShizukuBridge
import com.controid.service.ShizukuState
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object BleConstants {
    const val SERVICE_UUID = "0000a001-0000-1000-8000-00805f9b34fb"
    const val CHAR_STATE_TRIGGER_UUID = "0000b001-0000-1000-8000-00805f9b34fb"
    const val CHAR_AUTH_CHALLENGE_UUID = "0000b002-0000-1000-8000-00805f9b34fb"
    const val CHAR_LINK_STATUS_UUID = "0000b003-0000-1000-8000-00805f9b34fb"
    const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"
}

class GattLinkServer(
    context: Context,
    private val authManager: AuthManager,
    private val shizukuBridge: ShizukuBridge,
    private val scope: CoroutineScope,
    private val onStatusUpdate: (LinkStatus, String?) -> Unit = { _, _ -> },
    private val onClientsUpdate: (Int) -> Unit = {},
    private val onBleTriggeredHotspot: () -> Unit = {},
    private val onPeerPaired: (ByteArray) -> Unit = {}
) {
    private val appContext = context.applicationContext
    private val secureRandom = SecureRandom()
    private val started = AtomicBoolean(false)
    private val receiverRegistered = AtomicBoolean(false)
    private val tetherMutex = Mutex()
    private val serverLock = Any()

    private val _bleActive = MutableStateFlow(false)
    val bleActive: StateFlow<Boolean> = _bleActive.asStateFlow()

    private val _linkStatus = MutableStateFlow(LinkStatus.IDLE)
    val linkStatus: StateFlow<LinkStatus> = _linkStatus.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val sessions = ConcurrentHashMap<String, DeviceSession>()

    private var bluetoothManager: BluetoothManager? = null
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var linkStatusCharacteristic: BluetoothGattCharacteristic? = null

    private val tetheringCallback = object : IRemoteTetheringCallback.Stub() {
        override fun onStatusChanged(statusCode: Int, detail: String?) {
            val mapped = LinkStatus.fromCode((statusCode and 0xFF).toByte()) ?: LinkStatus.ERR_UNKNOWN
            scope.launch(Dispatchers.IO) {
                notifyLinkStatus(mapped)
                onStatusUpdate(mapped, detail)
            }
        }

        override fun onClientsChanged(clientCount: Int) {
            scope.launch(Dispatchers.IO) {
                onClientsUpdate(clientCount)
            }
        }
    }

    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            scope.launch(Dispatchers.IO) {
                when (state) {
                    BluetoothAdapter.STATE_ON -> if (started.get()) startServerAndAdvertising()
                    BluetoothAdapter.STATE_TURNING_OFF,
                    BluetoothAdapter.STATE_OFF -> stopServerAndAdvertising()
                }
            }
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            _bleActive.value = true
            _lastError.value = null
        }

        override fun onStartFailure(errorCode: Int) {
            _bleActive.value = false
            _lastError.value = "BLE advertise failed (code=$errorCode)"
            Log.w(TAG, "BLE advertising failed: $errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            if (device == null) return
            val addr = device.address ?: return
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    val session = sessions.computeIfAbsent(addr) { DeviceSession(device) }
                    session.device = device
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    sessions.remove(addr)
                }
            }
        }

        override fun onServiceAdded(status: Int, service: android.bluetooth.BluetoothGattService?) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                scope.launch(Dispatchers.IO) {
                    startAdvertisingInternal()
                }
            } else {
                _lastError.value = "GATT addService failed ($status)"
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic?
        ) {
            val server = gattServer ?: return
            if (device == null || characteristic == null) return
            if (!hasConnectPermission()) return

            val session = sessions.computeIfAbsent(device.address) { DeviceSession(device) }
            val fullPayload: ByteArray = when (characteristic.uuid) {
                CHAR_AUTH_CHALLENGE_UUID_OBJ -> {
                    if (offset == 0) {
                        val nonce = ByteArray(16).also { secureRandom.nextBytes(it) }
                        session.pendingNonce = nonce
                        session.nonceIssuedAtMs = SystemClock.elapsedRealtime()
                        session.authorized = false
                        nonce
                    } else {
                        session.pendingNonce ?: run {
                            sendGattResponse(server, device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                            return
                        }
                    }
                }

                CHAR_LINK_STATUS_UUID_OBJ -> byteArrayOf(_linkStatus.value.code)

                CHAR_STATE_TRIGGER_UUID_OBJ -> {
                    val activeByte = when (_linkStatus.value) {
                        LinkStatus.ACTIVE, LinkStatus.STARTING -> 0x01.toByte()
                        else -> 0x00.toByte()
                    }
                    byteArrayOf(activeByte)
                }

                else -> {
                    sendGattResponse(
                        server,
                        device,
                        requestId,
                        BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                        offset,
                        null
                    )
                    return
                }
            }

            if (offset < 0 || offset > fullPayload.size) {
                sendGattResponse(server, device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
                return
            }
            val slice = if (offset == 0) fullPayload else fullPayload.copyOfRange(offset, fullPayload.size)
            sendGattResponse(server, device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            val server = gattServer ?: return
            if (device == null || characteristic == null) return
            if (!hasConnectPermission()) return

            val session = sessions.computeIfAbsent(device.address) { DeviceSession(device) }
            val bytes = value ?: ByteArray(0)

            if (preparedWrite) {
                val ok = session.appendPreparedWrite(characteristic.uuid, offset, bytes)
                if (responseNeeded) {
                    val status = if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_INVALID_OFFSET
                    sendGattResponse(server, device, requestId, status, offset, bytes)
                }
                return
            }

            val status = processCharacteristicWrite(session, characteristic.uuid, bytes)
            if (responseNeeded) {
                sendGattResponse(server, device, requestId, status, offset, bytes)
            }
        }

        override fun onExecuteWrite(device: BluetoothDevice?, requestId: Int, execute: Boolean) {
            val server = gattServer ?: return
            if (device == null || !hasConnectPermission()) return
            val session = sessions[device.address]
            if (session == null) {
                sendGattResponse(server, device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                return
            }
            if (!execute) {
                session.clearPreparedWrites()
                sendGattResponse(server, device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                return
            }

            val queued = session.drainPreparedWrites()
            var finalStatus = BluetoothGatt.GATT_SUCCESS
            val authPayload = queued[CHAR_AUTH_CHALLENGE_UUID_OBJ]
            if (authPayload != null) {
                finalStatus = processCharacteristicWrite(session, CHAR_AUTH_CHALLENGE_UUID_OBJ, authPayload)
            }
            val triggerPayload = queued[CHAR_STATE_TRIGGER_UUID_OBJ]
            if (finalStatus == BluetoothGatt.GATT_SUCCESS && triggerPayload != null) {
                finalStatus = processCharacteristicWrite(session, CHAR_STATE_TRIGGER_UUID_OBJ, triggerPayload)
            }
            sendGattResponse(server, device, requestId, finalStatus, 0, null)
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor?
        ) {
            val server = gattServer ?: return
            if (device == null || descriptor == null || !hasConnectPermission()) return
            if (descriptor.uuid != CCCD_UUID_OBJ) {
                sendGattResponse(
                    server,
                    device,
                    requestId,
                    BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                    offset,
                    null
                )
                return
            }
            val session = sessions[device.address]
            val value = if (session?.cccdEnabled == true) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            }
            if (offset < 0 || offset > value.size) {
                sendGattResponse(server, device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
                return
            }
            sendGattResponse(
                server,
                device,
                requestId,
                BluetoothGatt.GATT_SUCCESS,
                offset,
                value.copyOfRange(offset, value.size)
            )
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            descriptor: BluetoothGattDescriptor?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            val server = gattServer ?: return
            if (device == null || descriptor == null || !hasConnectPermission()) return
            if (descriptor.uuid != CCCD_UUID_OBJ || descriptor.characteristic?.uuid != CHAR_LINK_STATUS_UUID_OBJ) {
                if (responseNeeded) {
                    sendGattResponse(
                        server,
                        device,
                        requestId,
                        BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                        offset,
                        value
                    )
                }
                return
            }

            val session = sessions.computeIfAbsent(device.address) { DeviceSession(device) }
            val enable = value != null && (
                value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ||
                    value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
                )
            session.cccdEnabled = enable
            if (responseNeeded) {
                sendGattResponse(server, device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        registerBluetoothReceiver()
        scope.launch(Dispatchers.IO) {
            startServerAndAdvertising()
        }
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        unregisterBluetoothReceiver()
        stopServerAndAdvertising()
    }

    fun refreshPermissionsAndRestart() {
        if (!started.get()) return
        scope.launch(Dispatchers.IO) {
            startServerAndAdvertising()
        }
    }

    fun requestStartHotspot(prefer5Ghz: Boolean = true, fromBleTrigger: Boolean = false) {
        scope.launch(Dispatchers.IO) {
            tetherMutex.withLock {
                if (fromBleTrigger) {
                    onBleTriggeredHotspot()
                }
                val service = shizukuBridge.awaitTetheringService(SHIZUKU_AWAIT_TIMEOUT_MS)
                if (service == null || !service.asBinder().isBinderAlive) {
                    _lastError.value = if (shizukuBridge.state.value == ShizukuState.PERMISSION_REQUIRED) {
                        "Shizuku permission required"
                    } else {
                        "Shizuku daemon unavailable"
                    }
                    notifyLinkStatus(LinkStatus.ERR_SHIZUKU_UNAVAILABLE)
                    onStatusUpdate(LinkStatus.ERR_SHIZUKU_UNAVAILABLE, _lastError.value)
                    return@withLock
                }

                notifyLinkStatus(LinkStatus.STARTING)
                onStatusUpdate(LinkStatus.STARTING, "Starting Wi-Fi hotspot")
                try {
                    service.startWifiTethering(prefer5Ghz, tetheringCallback)
                } catch (t: Throwable) {
                    shizukuBridge.handleBinderFailureIfDead()
                    _lastError.value = t.message ?: "Failed calling RemoteTetheringService"
                    notifyLinkStatus(LinkStatus.ERR_SHIZUKU_UNAVAILABLE)
                    onStatusUpdate(LinkStatus.ERR_SHIZUKU_UNAVAILABLE, _lastError.value)
                }
            }
        }
    }

    fun requestStopHotspot() {
        scope.launch(Dispatchers.IO) {
            tetherMutex.withLock {
                val service = shizukuBridge.currentTetheringService()
                if (service == null) {
                    notifyLinkStatus(LinkStatus.IDLE)
                    onStatusUpdate(LinkStatus.IDLE, "Hotspot stopped")
                    return@withLock
                }
                try {
                    service.stopWifiTethering()
                } catch (_: Throwable) {
                    shizukuBridge.handleBinderFailureIfDead()
                    notifyLinkStatus(LinkStatus.IDLE)
                    onStatusUpdate(LinkStatus.IDLE, "Hotspot stopped")
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun notifyLinkStatus(status: LinkStatus) {
        _linkStatus.value = status
        val server = gattServer ?: return
        val statusChar = linkStatusCharacteristic ?: return
        if (!hasConnectPermission()) return

        val payload = byteArrayOf(status.code)
        for (session in sessions.values) {
            if (!session.cccdEnabled) continue
            val device = session.device
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    server.notifyCharacteristicChanged(device, statusChar, false, payload)
                } else {
                    @Suppress("DEPRECATION")
                    statusChar.value = payload
                    @Suppress("DEPRECATION")
                    server.notifyCharacteristicChanged(device, statusChar, false)
                }
            }
        }
    }

    private fun processCharacteristicWrite(
        session: DeviceSession,
        charUuid: UUID,
        value: ByteArray
    ): Int {
        return when (charUuid) {
            CHAR_AUTH_CHALLENGE_UUID_OBJ -> {
                if (verifyChallengeWrite(session, value)) {
                    BluetoothGatt.GATT_SUCCESS
                } else {
                    BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION
                }
            }

            CHAR_STATE_TRIGGER_UUID_OBJ -> {
                if (!session.authorized) {
                    return BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION
                }
                if (value.isEmpty()) {
                    return BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH
                }
                when (value[0].toInt() and 0xFF) {
                    0x01 -> {
                        requestStartHotspot(prefer5Ghz = true, fromBleTrigger = true)
                        BluetoothGatt.GATT_SUCCESS
                    }

                    0x00 -> {
                        requestStopHotspot()
                        BluetoothGatt.GATT_SUCCESS
                    }

                    else -> BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                }
            }

            else -> BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
        }
    }

    private fun verifyChallengeWrite(session: DeviceSession, payload: ByteArray): Boolean {
        val nonce = session.pendingNonce ?: return false
        session.pendingNonce = null

        val ageMs = SystemClock.elapsedRealtime() - session.nonceIssuedAtMs
        if (ageMs < 0L || ageMs > AUTH_NONCE_TTL_MS) {
            session.authorized = false
            return false
        }

        val ok = when (payload.size) {
            64 -> {
                val pairedPubkey = authManager.loadPairedPublicKey() ?: return false
                authManager.verifyEd25519(pairedPubkey, nonce, payload)
            }

            96 -> {
                val pubkey = payload.copyOfRange(0, 32)
                val signature = payload.copyOfRange(32, 96)
                val pairedPubkey = authManager.loadPairedPublicKey()
                if (pairedPubkey == null) {
                    val verified = authManager.verifyEd25519(pubkey, nonce, signature)
                    if (verified) {
                        runCatching { authManager.savePairedPublicKey(pubkey) }
                        onPeerPaired(pubkey)
                    }
                    verified
                } else {
                    MessageDigest.isEqual(pairedPubkey, pubkey) &&
                        authManager.verifyEd25519(pubkey, nonce, signature)
                }
            }

            else -> false
        }

        session.authorized = ok
        return ok
    }

    @SuppressLint("MissingPermission")
    private fun startServerAndAdvertising() {
        synchronized(serverLock) {
            if (!started.get()) return
            if (gattServer != null) {
                startAdvertisingInternal()
                return
            }

            val manager = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
                ?.also { bluetoothManager = it }
                ?: run {
                    _lastError.value = "BluetoothManager unavailable"
                    return
                }

            val adapter = manager.adapter
            if (adapter == null || !adapter.isEnabled) {
                _bleActive.value = false
                return
            }

            if (!hasConnectPermission()) {
                _lastError.value = "Missing BLUETOOTH_CONNECT permission"
                _bleActive.value = false
                return
            }

            val server = try {
                manager.openGattServer(appContext, gattCallback)
            } catch (se: SecurityException) {
                _lastError.value = "Bluetooth connect permission denied"
                return
            } catch (t: Throwable) {
                _lastError.value = "Failed opening GATT server: ${t.message}"
                return
            }

            if (server == null) {
                _lastError.value = "BluetoothGattServer returned null"
                return
            }

            val service = android.bluetooth.BluetoothGattService(
                SERVICE_UUID_OBJ,
                android.bluetooth.BluetoothGattService.SERVICE_TYPE_PRIMARY
            )

            val triggerChar = BluetoothGattCharacteristic(
                CHAR_STATE_TRIGGER_UUID_OBJ,
                BluetoothGattCharacteristic.PROPERTY_READ or
                    BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
            )

            val authChar = BluetoothGattCharacteristic(
                CHAR_AUTH_CHALLENGE_UUID_OBJ,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
            )

            val statusChar = BluetoothGattCharacteristic(
                CHAR_LINK_STATUS_UUID_OBJ,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ
            )
            statusChar.addDescriptor(
                BluetoothGattDescriptor(
                    CCCD_UUID_OBJ,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
                )
            )

            service.addCharacteristic(triggerChar)
            service.addCharacteristic(authChar)
            service.addCharacteristic(statusChar)

            gattServer = server
            linkStatusCharacteristic = statusChar

            val added = try {
                server.addService(service)
            } catch (se: SecurityException) {
                _lastError.value = "Missing permission for GATT addService"
                false
            }
            if (!added) {
                runCatching { server.close() }
                gattServer = null
                linkStatusCharacteristic = null
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertisingInternal() {
        synchronized(serverLock) {
            if (!started.get() || gattServer == null) return
            if (!hasAdvertisePermission()) {
                _lastError.value = "Missing BLUETOOTH_ADVERTISE permission"
                _bleActive.value = false
                return
            }

            val adapter = bluetoothManager?.adapter ?: return
            if (!adapter.isEnabled) {
                _bleActive.value = false
                return
            }

            val leAdvertiser = adapter.bluetoothLeAdvertiser ?: run {
                _lastError.value = "BLE peripheral advertising unsupported on hardware"
                _bleActive.value = false
                return
            }

            advertiser = leAdvertiser
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .build()

            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(ParcelUuid(SERVICE_UUID_OBJ))
                .build()

            val scanResponse = AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .build()

            try {
                runCatching { leAdvertiser.stopAdvertising(advertiseCallback) }
                leAdvertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
            } catch (se: SecurityException) {
                _bleActive.value = false
                _lastError.value = "Missing BLUETOOTH_ADVERTISE permission"
            } catch (t: Throwable) {
                _bleActive.value = false
                _lastError.value = "BLE advertise error: ${t.message}"
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopServerAndAdvertising() {
        synchronized(serverLock) {
            _bleActive.value = false
            sessions.clear()

            advertiser?.let { adv ->
                if (hasAdvertisePermission()) {
                    runCatching { adv.stopAdvertising(advertiseCallback) }
                }
            }
            advertiser = null

            gattServer?.let { server ->
                if (hasConnectPermission()) {
                    runCatching { server.clearServices() }
                    runCatching { server.close() }
                }
            }
            gattServer = null
            linkStatusCharacteristic = null
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendGattResponse(
        server: BluetoothGattServer,
        device: BluetoothDevice,
        requestId: Int,
        status: Int,
        offset: Int,
        value: ByteArray?
    ) {
        runCatching {
            server.sendResponse(device, requestId, status, offset, value)
        }
    }

    private fun registerBluetoothReceiver() {
        if (!receiverRegistered.compareAndSet(false, true)) return
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        ContextCompat.registerReceiver(
            appContext,
            btStateReceiver,
            filter,
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

    private fun hasAdvertisePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_ADVERTISE) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_ADMIN) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private class DeviceSession(
        @Volatile var device: BluetoothDevice
    ) {
        @Volatile var pendingNonce: ByteArray? = null
        @Volatile var nonceIssuedAtMs: Long = 0L
        @Volatile var authorized: Boolean = false
        @Volatile var cccdEnabled: Boolean = false
        private val preparedWrites = LinkedHashMap<UUID, ByteArrayOutputStream>()

        @Synchronized
        fun appendPreparedWrite(uuid: UUID, offset: Int, chunk: ByteArray): Boolean {
            if (offset < 0 || offset + chunk.size > MAX_PREPARED_WRITE_BYTES) {
                return false
            }
            val stream = preparedWrites.getOrPut(uuid) { ByteArrayOutputStream() }
            if (offset == 0 && stream.size() > 0) {
                stream.reset()
            }
            if (offset != stream.size()) {
                return false
            }
            stream.write(chunk)
            return true
        }

        @Synchronized
        fun clearPreparedWrites() {
            preparedWrites.clear()
        }

        @Synchronized
        fun drainPreparedWrites(): Map<UUID, ByteArray> {
            val out = LinkedHashMap<UUID, ByteArray>(preparedWrites.size)
            for ((k, v) in preparedWrites) {
                out[k] = v.toByteArray()
            }
            preparedWrites.clear()
            return out
        }
    }

    companion object {
        private const val TAG = "GattLinkServer"
        private const val AUTH_NONCE_TTL_MS = 30_000L
        private const val SHIZUKU_AWAIT_TIMEOUT_MS = 4_000L
        private const val MAX_PREPARED_WRITE_BYTES = 256

        private val SERVICE_UUID_OBJ: UUID = UUID.fromString(BleConstants.SERVICE_UUID)
        private val CHAR_STATE_TRIGGER_UUID_OBJ: UUID = UUID.fromString(BleConstants.CHAR_STATE_TRIGGER_UUID)
        private val CHAR_AUTH_CHALLENGE_UUID_OBJ: UUID = UUID.fromString(BleConstants.CHAR_AUTH_CHALLENGE_UUID)
        private val CHAR_LINK_STATUS_UUID_OBJ: UUID = UUID.fromString(BleConstants.CHAR_LINK_STATUS_UUID)
        private val CCCD_UUID_OBJ: UUID = UUID.fromString(BleConstants.CCCD_UUID)
    }
}
