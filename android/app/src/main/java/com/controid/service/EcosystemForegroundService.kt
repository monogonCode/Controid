package com.controid.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.controid.MainActivity
import com.controid.R
import com.controid.ble.GattLinkServer
import com.controid.clipboard.ClipboardEntry
import com.controid.clipboard.ClipboardSyncManager
import com.controid.files.FileRpcController
import com.controid.hid.BluetoothHidController
import com.controid.protocol.AuthManager
import com.controid.protocol.ControlMessage
import com.controid.protocol.Frame
import com.controid.protocol.LinkStatus
import com.controid.protocol.Multiplexer
import com.controid.protocol.MultiplexerListener
import com.controid.protocol.ProtocolCodec
import com.controid.protocol.ProtocolConstants
import com.controid.stream.ScreenCaptureController
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

class EcosystemForegroundService : Service() {

    inner class LocalBinder : Binder() {
        fun service(): EcosystemForegroundService = this@EcosystemForegroundService
    }

    private val localBinder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val destroyed = AtomicBoolean(false)
    private val batteryReceiverRegistered = AtomicBoolean(false)
    private val handshakeSlots = Semaphore(MAX_CONCURRENT_HANDSHAKES)

    private lateinit var authManager: AuthManager
    private lateinit var shizukuBridge: ShizukuBridge
    private lateinit var gattLinkServer: GattLinkServer
    private lateinit var hidController: BluetoothHidController
    private lateinit var clipboardSyncManager: ClipboardSyncManager
    private lateinit var screenCaptureController: ScreenCaptureController
    private lateinit var fileRpcController: FileRpcController

    @Volatile
    private var activeMultiplexer: Multiplexer? = null

    @Volatile
    private var tcpServerSocket: ServerSocket? = null

    @Volatile
    private var associationTimeoutJob: Job? = null

    @Volatile
    private var lastBatteryPct: Int = 100

    @Volatile
    private var lastCharging: Boolean = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
            updateBatteryFromIntent(intent)
            broadcastDeviceStatusAsync()
        }
    }

    private val commandsHandler = object : EcosystemCommands {
        override fun requestShizukuPermission() {
            shizukuBridge.requestPermission()
        }

        override fun startHotspot(prefer5Ghz: Boolean) {
            gattLinkServer.requestStartHotspot(prefer5Ghz = prefer5Ghz, fromBleTrigger = false)
        }

        override fun stopHotspot() {
            cancelAssociationTimeout()
            gattLinkServer.requestStopHotspot()
        }

        override fun clearPairing() {
            serviceScope.launch(Dispatchers.IO) {
                authManager.clearPairedPublicKey()
                activeMultiplexer?.close()
                activeMultiplexer = null
                EcosystemStateHub.update {
                    it.copy(
                        pairedKeyFingerprint = null,
                        tcpConnected = false,
                        tcpAuthenticated = false,
                        peerAddress = null,
                        hidFallbackActive = false
                    )
                }
            }
        }

        override fun sendClipboardNow() {
            serviceScope.launch(Dispatchers.IO) {
                clipboardSyncManager.sendCurrentClipboardNow()
            }
        }

        override fun copyClipboardEntry(entry: ClipboardEntry) {
            serviceScope.launch(Dispatchers.IO) {
                clipboardSyncManager.copyEntryToClipboard(entry)
            }
        }

        override fun copyTextToClipboard(text: String) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            runCatching { cm.setPrimaryClip(ClipData.newPlainText("Controid", text)) }
        }

        override fun reconnectHid() {
            hidController.reconnect()
        }

        override fun sendMouseMove(dx: Int, dy: Int, wheel: Int) {
            hidController.sendMouseMove(dx, dy, wheel)
        }

        override fun sendMouseButtons(mask: Int) {
            hidController.sendMouseButtons(mask)
        }

        override fun sendKey(
            modifiers: Int,
            hidUsage: Int,
            pressed: Boolean,
            linuxKeycode: Int?
        ) {
            hidController.sendKey(modifiers, hidUsage, pressed, linuxKeycode)
        }

        override fun sendConsumer(usage: Int) {
            hidController.sendConsumer(usage)
        }
    }

    override fun onBind(intent: Intent?): IBinder = localBinder

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()

        if (!startForegroundSafe(buildNotification("Starting link services…"))) {
            stopSelf()
            return
        }

        authManager = AuthManager(applicationContext)
        shizukuBridge = ShizukuBridge(applicationContext, serviceScope)
        gattLinkServer = GattLinkServer(
            context = applicationContext,
            authManager = authManager,
            shizukuBridge = shizukuBridge,
            scope = serviceScope,
            onStatusUpdate = ::handleTetheringStatusUpdate,
            onClientsUpdate = ::handleTetheringClientsUpdate,
            onBleTriggeredHotspot = ::scheduleAssociationTimeout,
            onPeerPaired = { pubkey ->
                EcosystemStateHub.update {
                    it.copy(pairedKeyFingerprint = EcosystemStateHub.formatPublicKeyFingerprint(pubkey))
                }
            }
        )
        hidController = BluetoothHidController(applicationContext) { activeMultiplexer }
        clipboardSyncManager = ClipboardSyncManager(
            context = this,
            multiplexerProvider = { activeMultiplexer },
            shizukuGrantProvider = { grantClipboardBackgroundAndPublish() },
            externalClipboardReader = { shizukuBridge.readPrimaryClipboardText() },
            externalClipboardStreamReader = { shizukuBridge.readPrimaryClipboardStream() }
        )
        shizukuBridge.setClipboardObserver(
            onText = { text ->
                serviceScope.launch(Dispatchers.IO) {
                    clipboardSyncManager.onExternalClipboardText(text)
                }
            },
            onStream = { stream ->
                val launched = runCatching {
                    serviceScope.launch(Dispatchers.IO) {
                        clipboardSyncManager.onExternalClipboardStream(stream)
                    }
                    true
                }.getOrDefault(false)
                if (!launched) {
                    runCatching { stream.pfd.close() }
                }
            }
        )
        screenCaptureController = ScreenCaptureController(applicationContext, serviceScope)
        fileRpcController = FileRpcController(shizukuBridge, serviceScope)

        EcosystemStateHub.registerCommands(commandsHandler)
        EcosystemStateHub.update { it.copy(serviceRunning = true, lastError = null) }

        serviceScope.launch(Dispatchers.IO) {
            val pairedFp = runCatching {
                authManager.loadPairedPublicKey()?.let(EcosystemStateHub::formatPublicKeyFingerprint)
            }.getOrNull()
            EcosystemStateHub.update { it.copy(pairedKeyFingerprint = pairedFp) }
        }

        observeSubsystems()
        registerBatteryMonitor()

        shizukuBridge.start()
        gattLinkServer.start()
        hidController.start()
        clipboardSyncManager.start()

        serviceScope.launch(Dispatchers.IO) { runTcpServerLoop() }
        serviceScope.launch(Dispatchers.IO) { runStatusBroadcastLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_SYNC_CLIPBOARD -> {
                if (::clipboardSyncManager.isInitialized) {
                    serviceScope.launch(Dispatchers.IO) {
                        clipboardSyncManager.sendCurrentClipboardNow()
                    }
                }
            }

            ACTION_REFRESH_PERMISSIONS -> {
                if (::gattLinkServer.isInitialized) gattLinkServer.refreshPermissionsAndRestart()
                if (::hidController.isInitialized) hidController.refreshPermissionsAndInit()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (!destroyed.compareAndSet(false, true)) return

        EcosystemStateHub.unregisterCommands(commandsHandler)
        EcosystemStateHub.update {
            it.copy(
                serviceRunning = false,
                bleActive = false,
                tcpConnected = false,
                tcpAuthenticated = false,
                hidConnected = false,
                hidFallbackActive = false,
                isStreaming = false,
                peerAddress = null
            )
        }

        cancelAssociationTimeout()
        unregisterBatteryMonitor()

        runCatching { tcpServerSocket?.close() }
        tcpServerSocket = null

        val mux = activeMultiplexer
        activeMultiplexer = null
        runCatching { mux?.close() }

        if (::screenCaptureController.isInitialized) {
            runCatching {
                runBlocking {
                    withContext(NonCancellable) {
                        screenCaptureController.stopStream()
                    }
                }
            }
        }
        if (::clipboardSyncManager.isInitialized) runCatching { clipboardSyncManager.stop() }
        if (::gattLinkServer.isInitialized) runCatching { gattLinkServer.stop() }
        if (::hidController.isInitialized) runCatching { hidController.stop() }
        if (::shizukuBridge.isInitialized) runCatching { shizukuBridge.stop() }

        serviceScope.cancel()
        super.onDestroy()
    }

    private fun observeSubsystems() {
        serviceScope.launch {
            shizukuBridge.state.collect { st ->
                EcosystemStateHub.update { it.copy(shizukuState = st) }
                if (st == ShizukuState.READY) {
                    grantClipboardBackgroundAndPublish()
                }
                refreshNotification()
            }
        }

        serviceScope.launch {
            shizukuBridge.installed.collect { inst ->
                EcosystemStateHub.update { it.copy(shizukuInstalled = inst) }
            }
        }

        serviceScope.launch {
            shizukuBridge.uid.collect { uid ->
                EcosystemStateHub.update { it.copy(shizukuUid = uid) }
            }
        }

        serviceScope.launch {
            shizukuBridge.streamService.collect { service ->
                screenCaptureController.bindToStreamService(service)
            }
        }

        serviceScope.launch {
            gattLinkServer.bleActive.collect { active ->
                EcosystemStateHub.update { it.copy(bleActive = active) }
                refreshNotification()
            }
        }

        serviceScope.launch {
            gattLinkServer.linkStatus.collect { linkStatus ->
                EcosystemStateHub.update { it.copy(tetheringStatus = linkStatus) }
                if (linkStatus != LinkStatus.STARTING && linkStatus != LinkStatus.ACTIVE) {
                    cancelAssociationTimeout()
                }
                broadcastDeviceStatusAsync()
                refreshNotification()
            }
        }

        serviceScope.launch {
            gattLinkServer.lastError.collect { err ->
                if (!err.isNullOrBlank()) {
                    EcosystemStateHub.setError(err)
                }
            }
        }

        serviceScope.launch {
            hidController.hidConnected.collect { connected ->
                EcosystemStateHub.update { it.copy(hidConnected = connected) }
                broadcastDeviceStatusAsync()
                refreshNotification()
            }
        }

        serviceScope.launch {
            hidController.hidFallbackActive.collect { fallback ->
                EcosystemStateHub.update { it.copy(hidFallbackActive = fallback) }
            }
        }

        serviceScope.launch {
            hidController.lastError.collect { err ->
                if (!err.isNullOrBlank()) {
                    EcosystemStateHub.setError(err)
                }
            }
        }

        serviceScope.launch {
            clipboardSyncManager.history.collect { entries ->
                EcosystemStateHub.updateClipboardHistory(entries)
            }
        }

        serviceScope.launch {
            screenCaptureController.status.collect { stream ->
                EcosystemStateHub.update {
                    it.copy(
                        isStreaming = stream.active,
                        streamWidth = stream.width,
                        streamHeight = stream.height,
                        streamFps = stream.fps,
                        streamBitrateMbps = stream.bitrateMbps,
                        streamCodec = if (stream.codecMime.contains("hevc", ignoreCase = true)) {
                            "H.265"
                        } else {
                            "H.264"
                        },
                        lastError = stream.lastError ?: it.lastError
                    )
                }
                refreshNotification()
            }
        }
    }

    private suspend fun grantClipboardBackgroundAndPublish(): Boolean {
        val granted = shizukuBridge.grantClipboardBackgroundPermission(packageName)
        EcosystemStateHub.update { it.copy(clipboardBackgroundGranted = granted) }
        return granted
    }

    private fun handleTetheringStatusUpdate(status: LinkStatus, detail: String?) {
        var parsedSsid: String? = null
        var parsed5Ghz: Boolean? = null
        if (!detail.isNullOrBlank()) {
            for (part in detail.split(';')) {
                val trimmed = part.trim()
                when {
                    trimmed.startsWith("ssid=") -> parsedSsid = trimmed.removePrefix("ssid=")
                    trimmed.startsWith("band=") -> parsed5Ghz = trimmed.contains("5", ignoreCase = true)
                }
            }
        }
        val errMsg = if (status.code.toInt() and 0xFF >= 0x80) {
            detail ?: "Hotspot error (${status.name})"
        } else {
            null
        }
        EcosystemStateHub.update {
            it.copy(
                tetheringStatus = status,
                hotspotSsid = parsedSsid ?: if (status == LinkStatus.IDLE) null else it.hotspotSsid,
                hotspotBand5Ghz = parsed5Ghz ?: it.hotspotBand5Ghz,
                lastError = errMsg ?: it.lastError
            )
        }
    }

    private fun handleTetheringClientsUpdate(count: Int) {
        EcosystemStateHub.update { it.copy(connectedClients = count) }
        if (count > 0) {
            cancelAssociationTimeout()
        }
    }

    private fun scheduleAssociationTimeout() {
        cancelAssociationTimeout()
        associationTimeoutJob = serviceScope.launch(Dispatchers.IO) {
            delay(ASSOCIATION_TIMEOUT_MS)
            val snap = EcosystemStateHub.snapshot.value
            if (!snap.tcpConnected &&
                snap.connectedClients == 0 &&
                (snap.tetheringStatus == LinkStatus.ACTIVE || snap.tetheringStatus == LinkStatus.STARTING)
            ) {
                EcosystemStateHub.setError("Wi-Fi association timeout waiting for Linux client")
                gattLinkServer.notifyLinkStatus(LinkStatus.ERR_ASSOCIATION_TIMEOUT)
                gattLinkServer.requestStopHotspot()
            }
        }
    }

    private fun cancelAssociationTimeout() {
        associationTimeoutJob?.cancel()
        associationTimeoutJob = null
    }

    private suspend fun runTcpServerLoop() {
        while (serviceScope.isActive && !destroyed.get()) {
            val server = try {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(ProtocolConstants.DEFAULT_PORT))
                }.also { tcpServerSocket = it }
            } catch (e: IOException) {
                EcosystemStateHub.setError("TCP bind :${ProtocolConstants.DEFAULT_PORT} failed: ${e.message}")
                delay(TCP_BIND_RETRY_MS)
                continue
            }

            try {
                while (serviceScope.isActive && !destroyed.get()) {
                    val client = server.accept()
                    serviceScope.launch(Dispatchers.IO) {
                        handshakeSlots.withPermit {
                            handleIncomingTcpClient(client)
                        }
                    }
                }
            } catch (_: CancellationException) {
                break
            } catch (e: IOException) {
                if (!destroyed.get()) {
                    Log.w(TAG, "TCP accept error: ${e.message}")
                }
            } finally {
                runCatching { server.close() }
                if (tcpServerSocket === server) {
                    tcpServerSocket = null
                }
            }

            if (!destroyed.get()) {
                delay(TCP_BIND_RETRY_MS)
            }
        }
    }

    private suspend fun handleIncomingTcpClient(socket: Socket) {
        val peerAddr = socket.inetAddress?.hostAddress
        val codec = ProtocolCodec()
        try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS

            // Use unbuffered socket streams during L7 auth so no post-auth frame bytes are over-read.
            val rawIn = socket.getInputStream()
            val rawOut = socket.getOutputStream()

            val nonce = authManager.generateChallenge()
            val challengeBytes = ControlMessage.AuthChallenge(nonce).encode()
            codec.writeFrame(rawOut, Frame.ctrl(challengeBytes, urgent = true))
            rawOut.flush()

            val responseFrame = codec.readFrame(rawIn, usePool = false)
            if (responseFrame.channelId != ProtocolConstants.CHANNEL_CTRL ||
                responseFrame.streamId.toInt() != 0
            ) {
                codec.writeFrame(
                    rawOut,
                    Frame.ctrl(ControlMessage.AuthResult(ok = false).encode(), urgent = true)
                )
                rawOut.flush()
                runCatching { socket.close() }
                return
            }

            val msg = ControlMessage.decode(responseFrame.payload, 0, responseFrame.payloadLength)
            if (msg !is ControlMessage.AuthResponse) {
                codec.writeFrame(
                    rawOut,
                    Frame.ctrl(ControlMessage.AuthResult(ok = false).encode(), urgent = true)
                )
                rawOut.flush()
                runCatching { socket.close() }
                return
            }

            val verified = authManager.verifyChallengeResponse(
                nonce = nonce,
                pubkey = msg.pubkey,
                signature = msg.signature,
                allowTrustOnFirstUse = true
            )

            codec.writeFrame(
                rawOut,
                Frame.ctrl(ControlMessage.AuthResult(ok = verified).encode(), urgent = true)
            )
            rawOut.flush()

            if (!verified) {
                EcosystemStateHub.setError("Rejected unauthenticated peer ($peerAddr)")
                runCatching { socket.close() }
                return
            }

            socket.soTimeout = 0
            cancelAssociationTimeout()

            val fingerprint = authManager.loadPairedPublicKey()
                ?.let(EcosystemStateHub::formatPublicKeyFingerprint)

            activeMultiplexer?.close()

            val muxDeferred = CompletableDeferred<Multiplexer>()
            val listener = object : MultiplexerListener {
                override suspend fun onControlMessage(msg: ControlMessage) {
                    val mux = muxDeferred.await()
                    when (msg) {
                        is ControlMessage.Heartbeat,
                        is ControlMessage.CreditGrant,
                        is ControlMessage.Ack,
                        is ControlMessage.DeviceStatus,
                        is ControlMessage.StreamReq,
                        is ControlMessage.StreamStop -> {
                            screenCaptureController.handleControlMessage(msg, mux)
                        }

                        else -> Unit
                    }
                }

                override suspend fun onDataFrame(frame: Frame) {
                    val mux = muxDeferred.await()
                    when (frame.channelId) {
                        ProtocolConstants.CHANNEL_CLIP_META,
                        ProtocolConstants.CHANNEL_CLIP_DATA -> {
                            clipboardSyncManager.handleIncomingFrame(frame)
                        }

                        ProtocolConstants.CHANNEL_REMOTE_INPUT -> {
                            screenCaptureController.handleRemoteInputFrame(frame, mux)
                        }

                        ProtocolConstants.CHANNEL_FILES -> {
                            fileRpcController.handleIncomingFrame(frame, mux)
                        }

                        else -> mux.recycle(frame)
                    }
                }

                override suspend fun onStreamAborted(streamId: Int, reason: String) {
                    clipboardSyncManager.abortStream(streamId)
                    fileRpcController.abortStream(streamId)
                }

                override suspend fun onDisconnected(error: Throwable?) {
                    val mux = muxDeferred.await()
                    if (activeMultiplexer === mux) {
                        activeMultiplexer = null
                        hidController.onTcpConnectionChanged(false)
                        screenCaptureController.stopStream()
                        fileRpcController.reset()
                        EcosystemStateHub.update {
                            it.copy(
                                tcpConnected = false,
                                tcpAuthenticated = false,
                                peerAddress = null,
                                hidFallbackActive = false,
                                isStreaming = false
                            )
                        }
                        refreshNotification()
                    }
                }
            }

            val mux = Multiplexer(socket = socket, parentScope = serviceScope, listener = listener)
            activeMultiplexer = mux
            muxDeferred.complete(mux)
            hidController.onTcpConnectionChanged(true)

            EcosystemStateHub.update {
                it.copy(
                    tcpConnected = true,
                    tcpAuthenticated = true,
                    peerAddress = peerAddr,
                    pairedKeyFingerprint = fingerprint,
                    hidFallbackActive = !it.hidConnected,
                    lastError = null
                )
            }
            refreshNotification()
            sendDeviceStatus(mux)
        } catch (t: Throwable) {
            Log.w(TAG, "TCP client handshake failed ($peerAddr): ${t.message}")
            runCatching { socket.close() }
        }
    }

    private suspend fun runStatusBroadcastLoop() {
        while (serviceScope.isActive && !destroyed.get()) {
            delay(STATUS_BROADCAST_INTERVAL_MS)
            val mux = activeMultiplexer ?: continue
            sendDeviceStatus(mux)
        }
    }

    private fun broadcastDeviceStatusAsync() {
        val mux = activeMultiplexer ?: return
        serviceScope.launch(Dispatchers.IO) {
            sendDeviceStatus(mux)
        }
    }

    private suspend fun sendDeviceStatus(mux: Multiplexer) {
        val snap = EcosystemStateHub.snapshot.value
        val msg = ControlMessage.DeviceStatus(
            batteryPct = lastBatteryPct.coerceIn(0, 100),
            charging = lastCharging,
            tethering = snap.tetheringStatus == LinkStatus.ACTIVE,
            hidConnected = snap.hidConnected
        )
        runCatching { mux.sendControl(msg, urgent = false) }
    }

    private fun registerBatteryMonitor() {
        if (!batteryReceiverRegistered.compareAndSet(false, true)) return
        val sticky = ContextCompat.registerReceiver(
            this,
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_EXPORTED
        )
        if (sticky != null) {
            updateBatteryFromIntent(sticky)
        }
    }

    private fun unregisterBatteryMonitor() {
        if (!batteryReceiverRegistered.compareAndSet(true, false)) return
        runCatching { unregisterReceiver(batteryReceiver) }
    }

    private fun updateBatteryFromIntent(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level >= 0 && scale > 0) {
            lastBatteryPct = ((level * 100) / scale).coerceIn(0, 100)
        }
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        lastCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun ensureNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val existing = nm.getNotificationChannel(NOTIFICATION_CHANNEL_ID)
        if (existing != null) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Controid Link Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps BLE GATT trigger, Bluetooth HID, and TCP link active"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun startForegroundSafe(notification: Notification): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (se: SecurityException) {
            EcosystemStateHub.setError("Connected-device foreground permission missing: ${se.message}")
            Log.e(TAG, "Cannot start connectedDevice foreground service", se)
            false
        }
    }

    private fun refreshNotification() {
        if (destroyed.get()) return
        val snap = EcosystemStateHub.snapshot.value
        val summary = buildString {
            when {
                snap.isStreaming -> append("Streaming ${snap.streamWidth}x${snap.streamHeight}")
                snap.tcpAuthenticated -> append("Connected to Linux PC")
                snap.tetheringStatus == LinkStatus.ACTIVE -> append("5 GHz Hotspot Active")
                snap.bleActive -> append("BLE GATT Standby")
                else -> append("Link Idle")
            }
            if (snap.hidConnected) {
                append(" • HID Ready")
            }
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        runCatching { nm.notify(NOTIFICATION_ID, buildNotification(summary)) }
    }

    private fun buildNotification(contentText: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPi = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val syncIntent = Intent(this, EcosystemForegroundService::class.java).apply {
            action = ACTION_SYNC_CLIPBOARD
        }
        val syncPi = PendingIntent.getService(
            this,
            1,
            syncIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_controid)
            .setContentTitle("Controid")
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPi)
            .addAction(R.drawable.ic_stat_controid, "Sync Clipboard", syncPi)
            .build()
    }

    companion object {
        private const val TAG = "EcosystemFgService"
        private const val NOTIFICATION_CHANNEL_ID = "controid_link"
        private const val NOTIFICATION_ID = 1001

        private const val HANDSHAKE_TIMEOUT_MS = 10_000
        private const val TCP_BIND_RETRY_MS = 2_000L
        private const val STATUS_BROADCAST_INTERVAL_MS = 15_000L
        private const val ASSOCIATION_TIMEOUT_MS = 45_000L
        private const val MAX_CONCURRENT_HANDSHAKES = 4

        const val ACTION_SYNC_CLIPBOARD = "com.controid.ACTION_SYNC_CLIPBOARD"
        const val ACTION_REFRESH_PERMISSIONS = "com.controid.ACTION_REFRESH_PERMISSIONS"
        const val ACTION_STOP_SERVICE = "com.controid.ACTION_STOP_SERVICE"
    }
}
