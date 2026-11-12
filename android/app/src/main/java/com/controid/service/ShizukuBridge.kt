package com.controid.service

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.controid.BuildConfig
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

class ShizukuBridge(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val appContext = context.applicationContext
    private val started = AtomicBoolean(false)
    private val hadBinderEver = AtomicBoolean(false)
    private val bindingMutex = Mutex()

    private val _state = MutableStateFlow(ShizukuState.UNAVAILABLE)
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    private val _uid = MutableStateFlow<Int?>(null)
    val uid: StateFlow<Int?> = _uid.asStateFlow()

    private val _installed = MutableStateFlow(false)
    val installed: StateFlow<Boolean> = _installed.asStateFlow()

    private val _tetheringService = MutableStateFlow<IRemoteTetheringService?>(null)
    val tetheringService: StateFlow<IRemoteTetheringService?> = _tetheringService.asStateFlow()

    private val _streamService = MutableStateFlow<IRemoteStreamService?>(null)
    val streamService: StateFlow<IRemoteStreamService?> = _streamService.asStateFlow()

    private var tetheringBoundRequested = false
    private var streamBoundRequested = false

    private val tetheringUserServiceArgs: Shizuku.UserServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(appContext.packageName, RemoteTetheringService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("privileged")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val streamUserServiceArgs: Shizuku.UserServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(appContext.packageName, REMOTE_STREAM_SERVICE_CLASS)
        )
            .daemon(false)
            .processNameSuffix("privileged")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private var tetheringDeathRecipient: IBinder.DeathRecipient? = null
    private var tetheringRawBinder: IBinder? = null

    private var streamDeathRecipient: IBinder.DeathRecipient? = null
    private var streamRawBinder: IBinder? = null

    @Volatile
    private var clipboardObserver: ((String) -> Unit)? = null

    @Volatile
    private var clipboardStreamObserver: ((ExternalClipboardStream) -> Unit)? = null

    private val remoteClipboardCallback = object : IRemoteClipboardCallback.Stub() {
        override fun onPrimaryClipTextChanged(text: String?) {
            val value = text ?: return
            if (value.isEmpty()) return
            clipboardObserver?.invoke(value)
        }

        override fun onPrimaryClipStreamChanged(
            mime: String?,
            uriString: String?,
            dataPipe: ParcelFileDescriptor?
        ) {
            val pfd = dataPipe ?: return
            val observer = clipboardStreamObserver
            if (observer == null) {
                runCatching { pfd.close() }
                return
            }
            observer.invoke(
                ExternalClipboardStream(
                    mime = mime ?: "application/octet-stream",
                    uriString = uriString ?: "",
                    pfd = pfd
                )
            )
        }
    }

    private val tetheringConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service == null || !service.isBinderAlive) return
            val stub = IRemoteTetheringService.Stub.asInterface(service)
            val recipient = IBinder.DeathRecipient {
                scope.launch(Dispatchers.IO) {
                    clearTetheringBinding()
                    if (isBinderAlive()) {
                        delay(REBIND_DELAY_MS)
                        bindUserServicesIfReady()
                    } else {
                        markDaemonUnavailable()
                    }
                }
            }
            runCatching { service.linkToDeath(recipient, 0) }
            tetheringRawBinder = service
            tetheringDeathRecipient = recipient
            _tetheringService.value = stub
            if (clipboardObserver != null || clipboardStreamObserver != null) {
                scope.launch(Dispatchers.IO) {
                    runCatching { stub.registerClipboardCallback(remoteClipboardCallback) }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            clearTetheringBinding()
        }
    }

    private val streamConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service == null || !service.isBinderAlive) return
            val stub = IRemoteStreamService.Stub.asInterface(service)
            val recipient = IBinder.DeathRecipient {
                scope.launch(Dispatchers.IO) {
                    clearStreamBinding()
                    if (isBinderAlive()) {
                        delay(REBIND_DELAY_MS)
                        bindUserServicesIfReady()
                    } else {
                        markDaemonUnavailable()
                    }
                }
            }
            runCatching { service.linkToDeath(recipient, 0) }
            streamRawBinder = service
            streamDeathRecipient = recipient
            _streamService.value = stub
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            clearStreamBinding()
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        hadBinderEver.set(true)
        scope.launch(Dispatchers.IO) {
            refreshStateAndBind()
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        scope.launch(Dispatchers.IO) {
            markDaemonUnavailable()
        }
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode != REQUEST_CODE_SHIZUKU) return@OnRequestPermissionResultListener
            scope.launch(Dispatchers.IO) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    refreshStateAndBind()
                } else {
                    _state.value = if (isBinderAlive()) {
                        ShizukuState.PERMISSION_REQUIRED
                    } else {
                        deadOrUnavailableState()
                    }
                }
            }
        }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        runCatching { Shizuku.addBinderReceivedListenerSticky(binderReceivedListener) }
        runCatching { Shizuku.addBinderDeadListener(binderDeadListener) }
        runCatching { Shizuku.addRequestPermissionResultListener(permissionResultListener) }

        scope.launch(Dispatchers.IO) {
            refreshStateAndBind()
        }
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        runCatching { Shizuku.removeBinderReceivedListener(binderReceivedListener) }
        runCatching { Shizuku.removeBinderDeadListener(binderDeadListener) }
        runCatching { Shizuku.removeRequestPermissionResultListener(permissionResultListener) }
        unbindUserServices()
    }

    fun requestPermission() {
        scope.launch(Dispatchers.IO) {
            if (!isBinderAlive()) {
                _state.value = deadOrUnavailableState()
                return@launch
            }
            if (hasShizukuPermission()) {
                refreshStateAndBind()
                return@launch
            }
            runCatching {
                if (!Shizuku.isPreV11()) {
                    Shizuku.requestPermission(REQUEST_CODE_SHIZUKU)
                }
            }.onFailure {
                _state.value = deadOrUnavailableState()
            }
        }
    }

    fun currentTetheringService(): IRemoteTetheringService? =
        _tetheringService.value?.takeIf { it.asBinder().isBinderAlive }

    fun currentStreamService(): IRemoteStreamService? =
        _streamService.value?.takeIf { it.asBinder().isBinderAlive }

    suspend fun awaitTetheringService(timeoutMs: Long = BIND_TIMEOUT_MS): IRemoteTetheringService? {
        currentTetheringService()?.let { return it }
        if (!refreshStateAndBind()) return null
        return withTimeoutOrNull(timeoutMs) {
            tetheringService.filterNotNull().first()
        }
    }

    suspend fun awaitStreamService(timeoutMs: Long = BIND_TIMEOUT_MS): IRemoteStreamService? {
        currentStreamService()?.let { return it }
        if (!refreshStateAndBind()) return null
        return withTimeoutOrNull(timeoutMs) {
            streamService.filterNotNull().first()
        }
    }

    suspend fun grantClipboardBackgroundPermission(
        packageName: String = appContext.packageName
    ): Boolean = withContext(Dispatchers.IO) {
        val svc = awaitTetheringService(BIND_TIMEOUT_MS) ?: return@withContext false
        try {
            svc.grantClipboardBackgroundPermission(packageName) == 0
        } catch (_: Throwable) {
            handleBinderFailureIfDead()
            false
        }
    }

    fun setClipboardObserver(
        onText: (String) -> Unit,
        onStream: ((ExternalClipboardStream) -> Unit)? = null
    ) {
        clipboardObserver = onText
        clipboardStreamObserver = onStream
        val svc = currentTetheringService() ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { svc.registerClipboardCallback(remoteClipboardCallback) }
        }
    }

    suspend fun readPrimaryClipboardText(): String? = withContext(Dispatchers.IO) {
        val svc = currentTetheringService()
            ?: (if (_state.value == ShizukuState.READY) awaitTetheringService(RPC_BIND_TIMEOUT_MS) else null)
            ?: return@withContext null
        try {
            svc.readPrimaryClipboardText()
        } catch (_: Throwable) {
            handleBinderFailureIfDead()
            null
        }
    }

    suspend fun readPrimaryClipboardStream(): ExternalClipboardStream? = withContext(Dispatchers.IO) {
        val svc = currentTetheringService()
            ?: (if (_state.value == ShizukuState.READY) awaitTetheringService(RPC_BIND_TIMEOUT_MS) else null)
            ?: return@withContext null
        try {
            val meta = arrayOfNulls<String>(2)
            val pfd = svc.readPrimaryClipboardStream(meta) ?: return@withContext null
            ExternalClipboardStream(
                mime = meta[0] ?: "application/octet-stream",
                uriString = meta[1] ?: "",
                pfd = pfd
            )
        } catch (_: Throwable) {
            handleBinderFailureIfDead()
            null
        }
    }

    suspend fun executeFileRpc(request: ByteArray): ByteArray? = withContext(Dispatchers.IO) {
        val svc = currentTetheringService()
            ?: (if (_state.value == ShizukuState.READY) awaitTetheringService(RPC_BIND_TIMEOUT_MS) else null)
            ?: return@withContext null
        try {
            svc.handleFileRpc(request)
        } catch (_: Throwable) {
            handleBinderFailureIfDead()
            null
        }
    }

    fun unbindUserServices() {
        val alive = isBinderAlive()
        currentTetheringService()?.let { svc ->
            runCatching { svc.unregisterClipboardCallback(remoteClipboardCallback) }
        }
        synchronized(this) {
            if (tetheringBoundRequested) {
                tetheringBoundRequested = false
                if (alive) {
                    runCatching {
                        Shizuku.unbindUserService(tetheringUserServiceArgs, tetheringConnection, true)
                    }
                }
            }
            if (streamBoundRequested) {
                streamBoundRequested = false
                if (alive) {
                    runCatching {
                        Shizuku.unbindUserService(streamUserServiceArgs, streamConnection, true)
                    }
                }
            }
        }
        clearTetheringBinding()
        clearStreamBinding()
    }

    fun handleBinderFailureIfDead() {
        scope.launch(Dispatchers.IO) {
            if (!isBinderAlive()) {
                markDaemonUnavailable()
            }
        }
    }

    private suspend fun refreshStateAndBind(): Boolean = withContext(Dispatchers.IO) {
        _installed.value = checkShizukuInstalled()

        if (!isBinderAlive()) {
            _uid.value = null
            _state.value = deadOrUnavailableState()
            return@withContext false
        }

        hadBinderEver.set(true)
        _installed.value = true
        _uid.value = runCatching { Shizuku.getUid() }.getOrNull()

        if (runCatching { Shizuku.isPreV11() }.getOrDefault(true)) {
            _state.value = ShizukuState.UNAVAILABLE
            return@withContext false
        }

        if (!hasShizukuPermission()) {
            _state.value = ShizukuState.PERMISSION_REQUIRED
            return@withContext false
        }

        _state.value = ShizukuState.READY
        bindUserServicesIfReady()
        true
    }

    private suspend fun bindUserServicesIfReady() {
        bindingMutex.withLock {
            if (!started.get() || !isBinderAlive() || !hasShizukuPermission()) return
            if (currentTetheringService() == null) {
                runCatching {
                    synchronized(this@ShizukuBridge) { tetheringBoundRequested = true }
                    Shizuku.bindUserService(tetheringUserServiceArgs, tetheringConnection)
                }.onFailure {
                    synchronized(this@ShizukuBridge) { tetheringBoundRequested = false }
                    if (!isBinderAlive()) markDaemonUnavailable()
                }
            }
            if (currentStreamService() == null) {
                runCatching {
                    synchronized(this@ShizukuBridge) { streamBoundRequested = true }
                    Shizuku.bindUserService(streamUserServiceArgs, streamConnection)
                }.onFailure {
                    synchronized(this@ShizukuBridge) { streamBoundRequested = false }
                    if (!isBinderAlive()) markDaemonUnavailable()
                }
            }
        }
    }

    private fun markDaemonUnavailable() {
        synchronized(this) {
            tetheringBoundRequested = false
            streamBoundRequested = false
        }
        clearTetheringBinding()
        clearStreamBinding()
        _uid.value = null
        _state.value = if (hadBinderEver.get()) {
            ShizukuState.DAEMON_UNAVAILABLE
        } else {
            ShizukuState.UNAVAILABLE
        }
    }

    private fun clearTetheringBinding() {
        _tetheringService.value?.takeIf { it.asBinder().isBinderAlive }?.let { svc ->
            runCatching { svc.unregisterClipboardCallback(remoteClipboardCallback) }
        }
        val binder = tetheringRawBinder
        val recipient = tetheringDeathRecipient
        tetheringRawBinder = null
        tetheringDeathRecipient = null
        if (binder != null && recipient != null) {
            runCatching { binder.unlinkToDeath(recipient, 0) }
        }
        _tetheringService.value = null
    }

    private fun clearStreamBinding() {
        val binder = streamRawBinder
        val recipient = streamDeathRecipient
        streamRawBinder = null
        streamDeathRecipient = null
        if (binder != null && recipient != null) {
            runCatching { binder.unlinkToDeath(recipient, 0) }
        }
        _streamService.value = null
    }

    private fun isBinderAlive(): Boolean =
        runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    private fun hasShizukuPermission(): Boolean =
        runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
            .getOrDefault(false)

    private fun deadOrUnavailableState(): ShizukuState =
        if (hadBinderEver.get()) ShizukuState.DAEMON_UNAVAILABLE else ShizukuState.UNAVAILABLE

    private fun checkShizukuInstalled(): Boolean {
        if (isBinderAlive()) return true
        return runCatching {
            appContext.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        }.getOrDefault(false)
    }

    companion object {
        private const val REQUEST_CODE_SHIZUKU = 2001
        private const val BIND_TIMEOUT_MS = 6_000L
        private const val RPC_BIND_TIMEOUT_MS = 1_500L
        private const val REBIND_DELAY_MS = 500L
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val REMOTE_STREAM_SERVICE_CLASS = "com.controid.stream.RemoteStreamService"
    }
}

data class ExternalClipboardStream(
    val mime: String,
    val uriString: String,
    val pfd: ParcelFileDescriptor
)
