package com.controid.service

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.ClipData
import android.content.ContentResolver
import android.content.res.AssetFileDescriptor
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.FileObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Parcelable
import android.os.Process
import android.os.ResultReceiver
import android.provider.MediaStore
import android.system.OsConstants
import android.util.Log
import android.webkit.MimeTypeMap
import com.controid.files.RemoteFileSystemHandler
import com.controid.protocol.LinkStatus
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import rikka.shizuku.SystemServiceHelper

class RemoteTetheringService : IRemoteTetheringService.Stub() {

    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "Controid-Tether").apply { isDaemon = true }
    }

    private val activeCallback = AtomicReference<IRemoteTetheringCallback?>(null)
    private val expectedActive = AtomicBoolean(false)
    private val sawTetheredWifi = AtomicBoolean(false)
    private val startedAtMs = AtomicLong(0L)
    private val lastClientCount = AtomicInteger(0)
    private val hadClientsEver = AtomicBoolean(false)
    private val lastNonZeroClientMs = AtomicLong(0L)
    private val activeBand5Ghz = AtomicBoolean(true)
    private val destroyed = AtomicBoolean(false)

    private var registeredEventCallbackProxy: Any? = null
    private var monitorFuture: ScheduledFuture<*>? = null
    private var savedOriginalSoftApConfig: Any? = null

    private val clipboardCallbacks = CopyOnWriteArrayList<IRemoteClipboardCallback>()
    private val clipboardLock = Any()
    private var clipboardPollFuture: ScheduledFuture<*>? = null
    private var clipboardSystemListenerProxy: Any? = null
    private var lastClipboardHash: ByteArray? = null
    private var lastObservedUriKey: String? = null
    private val clipChangeSeq = AtomicLong(0L)
    private var lastHandledClipSeq = -1L
    private val screenshotObservers = mutableListOf<FileObserver>()
    private var screenshotObserversStarted = false
    private var screenshotSeeded = false
    private var lastSeenScreenshotId = 0L
    private var screenshotSeedTimeMs = 0L
    private val dispatchedScreenshotKeys = LinkedHashSet<String>()
    private var screenshotPollTick = 0
    private val screenshotScanQueued = AtomicBoolean(false)
    private var mediaContentObserver: ContentObserver? = null
    private var mediaContentObserverResolver: ContentResolver? = null
    private var mediaContentService: Any? = null
    private var mediaContentServiceObserver: Any? = null
    private val warnedClipboardAccess = AtomicBoolean(false)
    private val warnedTetherRestriction = AtomicBoolean(false)
    private val fileSystemHandler by lazy { RemoteFileSystemHandler() }

    override fun startWifiTethering(prefer5Ghz: Boolean, callback: IRemoteTetheringCallback?) {
        if (destroyed.get()) return
        activeCallback.set(callback)
        executor.execute {
            startWifiTetheringInternal(prefer5Ghz = prefer5Ghz, allowFallbackTo2Ghz = true)
        }
    }

    override fun stopWifiTethering() {
        executor.execute {
            stopWifiTetheringInternal(reason = "Stopped by request", notifyIdle = true)
        }
    }

    override fun grantClipboardBackgroundPermission(packageName: String?): Int {
        if (packageName.isNullOrBlank() || !PACKAGE_NAME_REGEX.matches(packageName)) {
            return -1
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return 0
        }
        if (isClipboardReadAllowed(packageName)) {
            return 0
        }
        for (op in APPOP_CLIPBOARD_CANDIDATES) {
            val setOk = runAppOpsCommand("cmd", "appops", "set", packageName, op, "allow") ||
                runAppOpsCommand("appops", "set", packageName, op, "allow")
            if (setOk && isAppOpAllowed(packageName, op)) {
                return 0
            }
        }
        return -1
    }

    override fun registerClipboardCallback(callback: IRemoteClipboardCallback?) {
        if (destroyed.get() || callback == null) return
        val binder = callback.asBinder()
        if (!binder.isBinderAlive) return
        synchronized(clipboardLock) {
            pruneDeadClipboardCallbacksLocked()
            if (clipboardCallbacks.none { it.asBinder() == binder }) {
                clipboardCallbacks.add(callback)
            }
            ensureClipboardObserverStartedLocked()
        }
    }

    override fun unregisterClipboardCallback(callback: IRemoteClipboardCallback?) {
        if (callback == null) return
        val targetBinder = callback.asBinder()
        synchronized(clipboardLock) {
            clipboardCallbacks.removeAll { it.asBinder() == targetBinder || !it.asBinder().isBinderAlive }
            if (clipboardCallbacks.isEmpty()) {
                stopClipboardObserverLocked()
            }
        }
    }

    override fun readPrimaryClipboardText(): String? {
        if (destroyed.get()) return null
        return readPrimaryClipContentResultInternal().second?.text
    }

    override fun readPrimaryClipboardStream(outMeta: Array<String?>?): ParcelFileDescriptor? {
        if (destroyed.get()) return null
        val (ok, content) = readPrimaryClipContentResultInternal()
        if (!ok || content?.uri == null) return null
        val opened = openClipStreamToPipe(content.uri, content.hintMime) ?: return null
        if (outMeta != null) {
            if (outMeta.isNotEmpty()) outMeta[0] = opened.first
            if (outMeta.size > 1) outMeta[1] = content.uri.toString()
        }
        return opened.second
    }

    override fun handleFileRpc(request: ByteArray?): ByteArray {
        if (request == null || request.isEmpty()) {
            return RemoteFileSystemHandler.errorResponse(OsConstants.EINVAL)
        }
        return fileSystemHandler.handleRpc(request)
    }

    override fun destroy() {
        if (!destroyed.compareAndSet(false, true)) return
        synchronized(clipboardLock) {
            clipboardCallbacks.clear()
            stopClipboardObserverLocked()
        }
        runCatching {
            stopWifiTetheringInternal(reason = "Service destroyed", notifyIdle = false)
        }
        activeCallback.set(null)
        runCatching {
            monitorFuture?.cancel(true)
            monitorFuture = null
        }
        runCatching { executor.shutdownNow() }
    }

    private fun startWifiTetheringInternal(prefer5Ghz: Boolean, allowFallbackTo2Ghz: Boolean) {
        if (destroyed.get()) return
        notifyStatus(LinkStatus.STARTING, "Provisioning Wi-Fi Hotspot")

        val tetheringBinder = runCatching { SystemServiceHelper.getSystemService("tethering") }.getOrNull()
        if (tetheringBinder != null && tetheringBinder.isBinderAlive) {
            startViaTetheringConnector(
                tetheringBinder = tetheringBinder,
                prefer5Ghz = prefer5Ghz,
                allowFallbackTo2Ghz = allowFallbackTo2Ghz
            )
            return
        }

        // API 28-29 fallback via ConnectivityManager
        startViaLegacyConnectivityManager()
    }

    @SuppressLint("PrivateApi")
    private fun startViaTetheringConnector(
        tetheringBinder: IBinder,
        prefer5Ghz: Boolean,
        allowFallbackTo2Ghz: Boolean
    ) {
        try {
            val stubClass = Class.forName("android.net.ITetheringConnector\$Stub")
            val connector = stubClass
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, tetheringBinder)
                ?: throw IllegalStateException("ITetheringConnector.asInterface returned null")

            ensureTetheringEventCallbackRegistered(connector)

            val wifiManager = resolveWifiManager()
            val originalConfig = wifiManager?.let { getSoftApConfig(it) }
            val ssid = originalConfig?.let { extractSoftApSsid(it) }

            var applied5Ghz = false
            var requestSoftApConfig: Any? = null
            if (prefer5Ghz && originalConfig != null) {
                val band5Config = buildBandSoftApConfig(originalConfig, BAND_5GHZ)
                if (band5Config != null) {
                    requestSoftApConfig = band5Config
                    applied5Ghz = true
                }
            }

            val builderClass = Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder")
            val builder = builderClass.getConstructor(Int::class.javaPrimitiveType).newInstance(TETHERING_WIFI)

            runCatching {
                builderClass.getMethod("setShouldShowEntitlementUi", Boolean::class.javaPrimitiveType)
                    .invoke(builder, false)
            }
            runCatching {
                builderClass.getMethod("setExemptFromEntitlementCheck", Boolean::class.javaPrimitiveType)
                    .invoke(builder, true)
            }

            var softApAttachedToRequest = false
            if (requestSoftApConfig != null) {
                val softApClass = Class.forName("android.net.wifi.SoftApConfiguration")
                softApAttachedToRequest = runCatching {
                    builderClass.getMethod("setSoftApConfiguration", softApClass)
                        .invoke(builder, requestSoftApConfig)
                    true
                }.getOrDefault(false)

                if (!softApAttachedToRequest && wifiManager != null) {
                    // Android 11 fallback: configure SoftAP band on IWifiManager temporarily
                    if (savedOriginalSoftApConfig == null) {
                        savedOriginalSoftApConfig = originalConfig
                    }
                    applied5Ghz = setSoftApConfig(wifiManager, requestSoftApConfig)
                }
            }

            activeBand5Ghz.set(applied5Ghz)
            val request = builderClass.getMethod("build").invoke(builder)
            val parcel = extractRequestParcel(request)

            val resultListener = createIntResultListener { resultCode ->
                executor.execute {
                    handleStartTetheringResult(
                        resultCode = resultCode,
                        ssid = ssid,
                        prefer5Ghz = prefer5Ghz,
                        allowFallbackTo2Ghz = allowFallbackTo2Ghz
                    )
                }
            }

            invokeStartTethering(connector, parcel, resultListener)
        } catch (t: Throwable) {
            val cause = unwrapCause(t)
            Log.e(TAG, "Failed to start Wi-Fi tethering via ITetheringConnector", cause)
            if (prefer5Ghz && allowFallbackTo2Ghz) {
                restoreSavedSoftApConfigIfNeeded()
                startWifiTetheringInternal(prefer5Ghz = false, allowFallbackTo2Ghz = false)
            } else if (isStructuralTetherApiMismatch(cause)) {
                Log.w(TAG, "ITetheringConnector API mismatch on OEM build; trying legacy ConnectivityManager fallback")
                restoreSavedSoftApConfigIfNeeded()
                startViaLegacyConnectivityManager()
            } else if (cause is SecurityException) {
                restoreSavedSoftApConfigIfNeeded()
                if (warnedTetherRestriction.compareAndSet(false, true)) {
                    Log.w(TAG, "OEM or carrier restricted programmatic tethering: ${cause.message}")
                }
                notifyStatus(
                    LinkStatus.ERR_PROVISIONING_FAILED,
                    "OEM/permission restriction: ${cause.message ?: cause.javaClass.simpleName}"
                )
            } else {
                notifyStatus(LinkStatus.ERR_UNKNOWN, cause.message ?: cause.javaClass.simpleName)
            }
        }
    }

    private fun handleStartTetheringResult(
        resultCode: Int,
        ssid: String?,
        prefer5Ghz: Boolean,
        allowFallbackTo2Ghz: Boolean
    ) {
        if (resultCode == TETHER_ERROR_NO_ERROR) {
            expectedActive.set(true)
            sawTetheredWifi.set(false)
            startedAtMs.set(System.currentTimeMillis())
            hadClientsEver.set(false)
            lastNonZeroClientMs.set(System.currentTimeMillis())
            startSafetyMonitor()
            val bandLabel = if (activeBand5Ghz.get()) "5 GHz" else "2.4 GHz"
            val detail = buildString {
                append("band=").append(bandLabel)
                if (!ssid.isNullOrBlank()) {
                    append(";ssid=").append(ssid)
                }
            }
            notifyStatus(LinkStatus.ACTIVE, detail)
            return
        }

        if (prefer5Ghz && allowFallbackTo2Ghz && isHardwareOrBandUnsupported(resultCode)) {
            Log.w(TAG, "5 GHz SoftAP failed (code=$resultCode), retrying with default dual-band config")
            restoreSavedSoftApConfigIfNeeded()
            startWifiTetheringInternal(prefer5Ghz = false, allowFallbackTo2Ghz = false)
            return
        }

        expectedActive.set(false)
        stopSafetyMonitor()
        restoreSavedSoftApConfigIfNeeded()
        val mapped = mapTetherError(resultCode)
        notifyStatus(mapped, "Tethering failed (code=$resultCode)")
    }

    @SuppressLint("PrivateApi")
    private fun startViaLegacyConnectivityManager() {
        try {
            val connBinder = SystemServiceHelper.getSystemService("connectivity")
                ?: throw IllegalStateException("connectivity service unavailable")
            val stubClass = Class.forName("android.net.IConnectivityManager\$Stub")
            val cm = stubClass.getMethod("asInterface", IBinder::class.java).invoke(null, connBinder)
                ?: throw IllegalStateException("IConnectivityManager.asInterface returned null")

            val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    executor.execute {
                        if (resultCode == TETHER_ERROR_NO_ERROR) {
                            expectedActive.set(true)
                            startedAtMs.set(System.currentTimeMillis())
                            lastNonZeroClientMs.set(System.currentTimeMillis())
                            activeBand5Ghz.set(false)
                            startSafetyMonitor()
                            notifyStatus(LinkStatus.ACTIVE, "band=2.4 GHz")
                        } else {
                            expectedActive.set(false)
                            notifyStatus(mapTetherError(resultCode), "Legacy tethering error $resultCode")
                        }
                    }
                }
            }

            val methods = cm.javaClass.methods.filter { it.name == "startTethering" }
            val m4 = methods.firstOrNull {
                it.parameterCount == 4 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    it.parameterTypes[1] == ResultReceiver::class.java &&
                    it.parameterTypes[2] == Boolean::class.javaPrimitiveType &&
                    it.parameterTypes[3] == String::class.java
            }
            val m3 = methods.firstOrNull {
                it.parameterCount == 3 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    it.parameterTypes[1] == ResultReceiver::class.java &&
                    it.parameterTypes[2] == Boolean::class.javaPrimitiveType
            }

            when {
                m4 != null -> m4.invoke(cm, TETHERING_WIFI, receiver, false, SHELL_PKG)
                m3 != null -> m3.invoke(cm, TETHERING_WIFI, receiver, false)
                else -> throw NoSuchMethodException("IConnectivityManager.startTethering not found")
            }
        } catch (t: Throwable) {
            val cause = unwrapCause(t)
            Log.e(TAG, "Legacy tethering start failed", cause)
            if (cause is SecurityException) {
                notifyStatus(
                    LinkStatus.ERR_PROVISIONING_FAILED,
                    "OEM/permission restriction: ${cause.message ?: cause.javaClass.simpleName}"
                )
            } else {
                notifyStatus(LinkStatus.ERR_UNKNOWN, cause.message ?: "Legacy tethering error")
            }
        }
    }

    @SuppressLint("PrivateApi")
    private fun stopWifiTetheringInternal(reason: String, notifyIdle: Boolean) {
        expectedActive.set(false)
        sawTetheredWifi.set(false)
        lastClientCount.set(0)
        stopSafetyMonitor()

        val tetheringBinder = runCatching { SystemServiceHelper.getSystemService("tethering") }.getOrNull()
        if (tetheringBinder != null && tetheringBinder.isBinderAlive) {
            runCatching {
                val stubClass = Class.forName("android.net.ITetheringConnector\$Stub")
                val connector = stubClass
                    .getMethod("asInterface", IBinder::class.java)
                    .invoke(null, tetheringBinder)
                if (connector != null) {
                    val listener = createIntResultListener { /* best-effort stop */ }
                    invokeStopTethering(connector, listener)
                    unregisterTetheringEventCallback(connector)
                }
            }.onFailure {
                Log.w(TAG, "Error stopping tethering via ITetheringConnector", it)
            }
        } else {
            runCatching {
                val connBinder = SystemServiceHelper.getSystemService("connectivity") ?: return@runCatching
                val stubClass = Class.forName("android.net.IConnectivityManager\$Stub")
                val cm = stubClass.getMethod("asInterface", IBinder::class.java).invoke(null, connBinder)
                    ?: return@runCatching
                val m2 = cm.javaClass.methods.firstOrNull {
                    it.name == "stopTethering" && it.parameterCount == 2 &&
                        it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                        it.parameterTypes[1] == String::class.java
                }
                val m1 = cm.javaClass.methods.firstOrNull {
                    it.name == "stopTethering" && it.parameterCount == 1 &&
                        it.parameterTypes[0] == Int::class.javaPrimitiveType
                }
                when {
                    m2 != null -> m2.invoke(cm, TETHERING_WIFI, SHELL_PKG)
                    m1 != null -> m1.invoke(cm, TETHERING_WIFI)
                }
            }
        }

        restoreSavedSoftApConfigIfNeeded()
        if (notifyIdle) {
            notifyClients(0)
            notifyStatus(LinkStatus.IDLE, reason)
        }
    }

    private fun invokeStartTethering(connector: Any, parcel: Any, resultListener: Any) {
        val methods = connector.javaClass.methods.filter { it.name == "startTethering" }
        val m4 = methods.firstOrNull { it.parameterCount == 4 }
        val m3 = methods.firstOrNull { it.parameterCount == 3 }
        when {
            m4 != null -> m4.invoke(connector, parcel, SHELL_PKG, null, resultListener)
            m3 != null -> m3.invoke(connector, parcel, SHELL_PKG, resultListener)
            else -> throw NoSuchMethodException("No supported ITetheringConnector.startTethering overload")
        }
    }

    private fun invokeStopTethering(connector: Any, resultListener: Any) {
        val methods = connector.javaClass.methods.filter { it.name == "stopTethering" }
        val m4 = methods.firstOrNull {
            it.parameterCount == 4 && it.parameterTypes[0] == Int::class.javaPrimitiveType
        }
        val m3 = methods.firstOrNull {
            it.parameterCount == 3 && it.parameterTypes[0] == Int::class.javaPrimitiveType
        }
        val m2 = methods.firstOrNull {
            it.parameterCount == 2 && it.parameterTypes[0] == Int::class.javaPrimitiveType
        }
        when {
            m4 != null -> m4.invoke(connector, TETHERING_WIFI, SHELL_PKG, null, resultListener)
            m3 != null -> m3.invoke(connector, TETHERING_WIFI, SHELL_PKG, resultListener)
            m2 != null -> m2.invoke(connector, TETHERING_WIFI, SHELL_PKG)
        }
    }

    @SuppressLint("PrivateApi")
    private fun ensureTetheringEventCallbackRegistered(connector: Any) {
        if (registeredEventCallbackProxy != null) return
        val callbackProxy = createTetheringEventCallbackProxy() ?: return
        val regMethods = connector.javaClass.methods.filter {
            it.name == "registerTetheringEventCallback"
        }
        val m2 = regMethods.firstOrNull { it.parameterCount == 2 }
        val m1 = regMethods.firstOrNull { it.parameterCount == 1 }
        val ok = runCatching {
            when {
                m2 != null -> m2.invoke(connector, callbackProxy, SHELL_PKG)
                m1 != null -> m1.invoke(connector, callbackProxy)
                else -> return
            }
            true
        }.getOrDefault(false)
        if (ok) {
            registeredEventCallbackProxy = callbackProxy
        }
    }

    private fun unregisterTetheringEventCallback(connector: Any) {
        val proxy = registeredEventCallbackProxy ?: return
        registeredEventCallbackProxy = null
        val unregMethods = connector.javaClass.methods.filter {
            it.name == "unregisterTetheringEventCallback"
        }
        val m2 = unregMethods.firstOrNull { it.parameterCount == 2 }
        val m1 = unregMethods.firstOrNull { it.parameterCount == 1 }
        runCatching {
            when {
                m2 != null -> m2.invoke(connector, proxy, SHELL_PKG)
                m1 != null -> m1.invoke(connector, proxy)
            }
        }
    }

    @SuppressLint("PrivateApi")
    private fun createIntResultListener(onResult: (Int) -> Unit): Any {
        val listenerClass = Class.forName("android.net.IIntResultListener")
        val stubClass = Class.forName("android.net.IIntResultListener\$Stub")
        val txOnResult = runCatching {
            stubClass.getDeclaredField("TRANSACTION_onResult").apply { isAccessible = true }.getInt(null)
        }.getOrDefault(IBinder.FIRST_CALL_TRANSACTION)

        val binder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == txOnResult) {
                    data.enforceInterface("android.net.IIntResultListener")
                    val resultCode = data.readInt()
                    if ((flags and IBinder.FLAG_ONEWAY) == 0) {
                        reply?.writeNoException()
                    }
                    onResult(resultCode)
                    return true
                }
                return super.onTransact(code, data, reply, flags)
            }
        }

        return Proxy.newProxyInstance(
            listenerClass.classLoader,
            arrayOf(listenerClass)
        ) { _, method, _ ->
            when (method.name) {
                "asBinder" -> binder
                "equals" -> false
                "hashCode" -> System.identityHashCode(binder)
                "toString" -> "DynamicIntResultListener"
                else -> null
            }
        }
    }

    @SuppressLint("PrivateApi")
    private fun createTetheringEventCallbackProxy(): Any? {
        return runCatching {
            val cbInterface = Class.forName("android.net.ITetheringEventCallback")
            val stubClass = Class.forName("android.net.ITetheringEventCallback\$Stub")

            val txMap = HashMap<Int, String>()
            for (field in stubClass.declaredFields) {
                if (field.name.startsWith("TRANSACTION_") && field.type == Int::class.javaPrimitiveType) {
                    field.isAccessible = true
                    txMap[field.getInt(null)] = field.name.removePrefix("TRANSACTION_")
                }
            }

            @Suppress("UNCHECKED_CAST")
            val startedCreator = runCatching {
                Class.forName("android.net.TetheringCallbackStartedParcel")
                    .getField("CREATOR").get(null) as Parcelable.Creator<Any>
            }.getOrNull()
            @Suppress("UNCHECKED_CAST")
            val statesCreator = runCatching {
                Class.forName("android.net.TetherStatesParcel")
                    .getField("CREATOR").get(null) as Parcelable.Creator<Any>
            }.getOrNull()
            @Suppress("UNCHECKED_CAST")
            val clientCreator = runCatching {
                Class.forName("android.net.TetheredClient")
                    .getField("CREATOR").get(null) as Parcelable.Creator<Any>
            }.getOrNull()

            val binder = object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    val methodName = txMap[code] ?: return super.onTransact(code, data, reply, flags)
                    data.enforceInterface("android.net.ITetheringEventCallback")
                    if ((flags and IBinder.FLAG_ONEWAY) == 0) {
                        reply?.writeNoException()
                    }
                    when (methodName) {
                        "onCallbackStarted" -> {
                            val startedParcel = if (data.readInt() != 0) {
                                startedCreator?.createFromParcel(data)
                            } else null
                            val states = startedParcel?.let { extractField(it, "states") }
                            if (states != null) {
                                executor.execute { handleTetherStatesParcel(states) }
                            }
                        }

                        "onTetherStatesChanged" -> {
                            val states = if (data.readInt() != 0) {
                                statesCreator?.createFromParcel(data)
                            } else null
                            if (states != null) {
                                executor.execute { handleTetherStatesParcel(states) }
                            }
                        }

                        "onClientsChanged" -> {
                            val count = if (clientCreator != null) {
                                data.createTypedArrayList(clientCreator)?.size ?: 0
                            } else 0
                            executor.execute { handleClientsChanged(count) }
                        }

                        "onCallbackStopped" -> {
                            val err = data.readInt()
                            executor.execute {
                                if (expectedActive.get()) {
                                    expectedActive.set(false)
                                    sawTetheredWifi.set(false)
                                    stopSafetyMonitor()
                                    notifyClients(0)
                                    val status = if (err == 0) LinkStatus.IDLE else mapTetherError(err)
                                    notifyStatus(status, "Tethering callback stopped (code=$err)")
                                }
                            }
                        }
                    }
                    return true
                }
            }

            Proxy.newProxyInstance(
                cbInterface.classLoader,
                arrayOf(cbInterface)
            ) { _, method, _ ->
                when (method.name) {
                    "asBinder" -> binder
                    "equals" -> false
                    "hashCode" -> System.identityHashCode(binder)
                    "toString" -> "DynamicTetheringEventCallback"
                    else -> null
                }
            }
        }.onFailure {
            Log.w(TAG, "Unable to construct ITetheringEventCallback proxy", it)
        }.getOrNull()
    }

    private fun handleClientsChanged(count: Int) {
        lastClientCount.set(count)
        if (count > 0) {
            hadClientsEver.set(true)
            lastNonZeroClientMs.set(System.currentTimeMillis())
        }
        notifyClients(count)
    }

    private fun handleTetherStatesParcel(states: Any) {
        val tethered = extractStringArrayField(states, "tetheredList")
        val errored = extractStringArrayField(states, "erroredIfaceList")
        val hasWifiTether = tethered.any { iface ->
            iface.startsWith("wlan") || iface.startsWith("ap") ||
                iface.startsWith("swlan") || iface.startsWith("softap")
        }

        if (hasWifiTether) {
            sawTetheredWifi.set(true)
            return
        }

        if (expectedActive.get() && sawTetheredWifi.get() && tethered.isEmpty()) {
            // OEM ROM auto-shutoff or user disabled SoftAP externally.
            expectedActive.set(false)
            sawTetheredWifi.set(false)
            lastClientCount.set(0)
            stopSafetyMonitor()
            restoreSavedSoftApConfigIfNeeded()
            notifyClients(0)
            val detail = if (errored.isNotEmpty()) {
                "Hotspot interface dropped (${errored.joinToString()})"
            } else {
                "Hotspot turned off externally (idle auto-shutoff)"
            }
            notifyStatus(
                if (errored.isNotEmpty()) LinkStatus.ERR_WIFI_HARDWARE else LinkStatus.IDLE,
                detail
            )
        }
    }

    private fun startSafetyMonitor() {
        stopSafetyMonitor()
        monitorFuture = executor.scheduleWithFixedDelay(
            {
                if (!expectedActive.get() || destroyed.get()) return@scheduleWithFixedDelay
                val now = System.currentTimeMillis()

                // 1. Check IWifiManager SoftAP enabled state for silent OEM shutoff
                val wifiManager = resolveWifiManager()
                if (wifiManager != null) {
                    val apState = getWifiApState(wifiManager)
                    val uptime = now - startedAtMs.get()
                    if (uptime > AP_WARMUP_GRACE_MS &&
                        (apState == WIFI_AP_STATE_DISABLED || apState == WIFI_AP_STATE_FAILED)
                    ) {
                        expectedActive.set(false)
                        sawTetheredWifi.set(false)
                        lastClientCount.set(0)
                        stopSafetyMonitor()
                        restoreSavedSoftApConfigIfNeeded()
                        notifyClients(0)
                        notifyStatus(
                            if (apState == WIFI_AP_STATE_FAILED) LinkStatus.ERR_WIFI_HARDWARE else LinkStatus.IDLE,
                            "Hotspot disabled by system (state=$apState)"
                        )
                        return@scheduleWithFixedDelay
                    }
                }

                // 2. Client-based idle auto-shutoff fallback
                if (lastClientCount.get() == 0) {
                    val idleMs = if (hadClientsEver.get()) {
                        now - lastNonZeroClientMs.get()
                    } else {
                        now - startedAtMs.get()
                    }
                    val threshold = if (hadClientsEver.get()) {
                        IDLE_AFTER_DISCONNECT_MS
                    } else {
                        IDLE_NEVER_CONNECTED_MS
                    }
                    if (idleMs >= threshold) {
                        stopWifiTetheringInternal(
                            reason = "Idle auto-shutoff fallback (0 connected clients)",
                            notifyIdle = true
                        )
                    }
                }
            },
            MONITOR_INTERVAL_MS,
            MONITOR_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        )
    }

    private fun stopSafetyMonitor() {
        monitorFuture?.cancel(false)
        monitorFuture = null
    }

    @SuppressLint("PrivateApi")
    private fun resolveWifiManager(): Any? {
        return runCatching {
            val binder = SystemServiceHelper.getSystemService("wifi") ?: return@runCatching null
            val stubClass = Class.forName("android.net.wifi.IWifiManager\$Stub")
            stubClass.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        }.getOrNull()
    }

    private fun getSoftApConfig(wifiManager: Any): Any? {
        return runCatching {
            wifiManager.javaClass.getMethod("getSoftApConfiguration").invoke(wifiManager)
        }.getOrNull()
    }

    @SuppressLint("PrivateApi")
    private fun setSoftApConfig(wifiManager: Any, config: Any): Boolean {
        val softApClass = Class.forName("android.net.wifi.SoftApConfiguration")
        return runCatching {
            wifiManager.javaClass.getMethod("setSoftApConfiguration", softApClass, String::class.java)
                .invoke(wifiManager, config, SHELL_PKG) as? Boolean ?: true
        }.getOrDefault(false)
    }

    private fun restoreSavedSoftApConfigIfNeeded() {
        val saved = savedOriginalSoftApConfig ?: return
        savedOriginalSoftApConfig = null
        val wm = resolveWifiManager() ?: return
        setSoftApConfig(wm, saved)
    }

    private fun getWifiApState(wifiManager: Any): Int {
        return runCatching {
            wifiManager.javaClass.getMethod("getWifiApEnabledState").invoke(wifiManager) as Int
        }.getOrDefault(-1)
    }

    @SuppressLint("PrivateApi")
    private fun buildBandSoftApConfig(existing: Any, band: Int): Any? {
        return runCatching {
            val softApClass = Class.forName("android.net.wifi.SoftApConfiguration")
            val builderClass = Class.forName("android.net.wifi.SoftApConfiguration\$Builder")
            val builder = builderClass.getConstructor(softApClass).newInstance(existing)
            val setBandMethod = runCatching {
                builderClass.getMethod("setBand", Int::class.javaPrimitiveType)
            }.getOrNull()
            if (setBandMethod != null) {
                setBandMethod.invoke(builder, band)
            } else {
                builderClass.getMethod("setBands", IntArray::class.java)
                    .invoke(builder, intArrayOf(band))
            }
            builderClass.getMethod("build").invoke(builder)
        }.getOrNull()
    }

    private fun extractSoftApSsid(config: Any): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val wifiSsid = runCatching {
                config.javaClass.getMethod("getWifiSsid").invoke(config)
            }.getOrNull()
            if (wifiSsid != null) {
                val str = wifiSsid.toString().removeSurrounding("\"")
                if (str.isNotBlank() && str != "<unknown ssid>") return str
            }
        }
        return runCatching {
            @Suppress("DEPRECATION")
            config.javaClass.getMethod("getSsid").invoke(config) as? String
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun extractRequestParcel(request: Any): Any {
        return runCatching {
            request.javaClass.getMethod("getParcel").invoke(request)
        }.getOrElse {
            request.javaClass.getDeclaredField("mRequestParcel").apply { isAccessible = true }.get(request)
        } ?: throw IllegalStateException("TetheringRequestParcel is null")
    }

    private fun extractField(target: Any, name: String): Any? {
        return runCatching {
            target.javaClass.getField(name).get(target)
        }.getOrElse {
            runCatching {
                target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
            }.getOrNull()
        }
    }

    private fun extractStringArrayField(target: Any, name: String): Array<String> {
        val value = extractField(target, name) ?: return emptyArray()
        return when (value) {
            is Array<*> -> value.filterIsInstance<String>().toTypedArray()
            is List<*> -> value.filterIsInstance<String>().toTypedArray()
            else -> emptyArray()
        }
    }

    private fun isClipboardReadAllowed(packageName: String): Boolean {
        return APPOP_CLIPBOARD_CANDIDATES.any { op -> isAppOpAllowed(packageName, op) }
    }

    private fun isAppOpAllowed(packageName: String, op: String): Boolean {
        val output = runCommandOutput("cmd", "appops", "get", packageName, op)
            ?: runCommandOutput("appops", "get", packageName, op)
            ?: return false
        return output.contains("allow", ignoreCase = true) &&
            !output.contains("deny", ignoreCase = true) &&
            !output.contains("ignore", ignoreCase = true)
    }

    private fun runAppOpsCommand(vararg args: String): Boolean {
        return runCatching {
            val process = ProcessBuilder(*args)
                .redirectErrorStream(true)
                .start()
            process.inputStream.bufferedReader().use { it.readText() }
            if (!process.waitFor(COMMAND_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                false
            } else {
                process.exitValue() == 0
            }
        }.getOrDefault(false)
    }

    private fun runCommandOutput(vararg args: String): String? {
        return runCatching {
            val process = ProcessBuilder(*args)
                .redirectErrorStream(true)
                .start()
            val text = process.inputStream.bufferedReader().use { it.readText() }
            if (!process.waitFor(COMMAND_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return@runCatching null
            }
            if (process.exitValue() == 0) text else null
        }.getOrNull()
    }

    private fun notifyStatus(status: LinkStatus, detail: String?) {
        val cb = activeCallback.get() ?: return
        runCatching {
            if (cb.asBinder().isBinderAlive) {
                cb.onStatusChanged(status.code.toInt() and 0xFF, detail)
            }
        }
    }

    private fun notifyClients(count: Int) {
        val cb = activeCallback.get() ?: return
        runCatching {
            if (cb.asBinder().isBinderAlive) {
                cb.onClientsChanged(count)
            }
        }
    }

    private fun pruneDeadClipboardCallbacksLocked() {
        clipboardCallbacks.removeAll { !it.asBinder().isBinderAlive }
    }

    private fun ensureClipboardObserverStartedLocked() {
        if (destroyed.get() || clipboardCallbacks.isEmpty()) return
        val justStarted = clipboardPollFuture == null
        if (clipboardSystemListenerProxy == null) {
            val proxy = createPrimaryClipChangedListenerProxy()
            if (proxy != null) {
                val icb = resolveClipboardService()
                if (icb != null && invokeClipboardListenerMethod(icb, "addPrimaryClipChangedListener", proxy)) {
                    clipboardSystemListenerProxy = proxy
                }
            }
        }
        if (justStarted) {
            runCatching { executor.execute { seedInitialClipboardHash() } }
            runCatching { executor.execute { seedInitialScreenshotState() } }
            clipboardPollFuture = executor.scheduleWithFixedDelay(
                { pollAndDispatchClipboard() },
                CLIPBOARD_POLL_INTERVAL_MS,
                CLIPBOARD_POLL_INTERVAL_MS,
                TimeUnit.MILLISECONDS
            )
        }
        startScreenshotWatcherLocked()
    }

    private fun stopClipboardObserverLocked() {
        stopScreenshotWatcherLocked()
        runCatching {
            clipboardPollFuture?.cancel(false)
            clipboardPollFuture = null
        }
        val proxy = clipboardSystemListenerProxy
        clipboardSystemListenerProxy = null
        lastClipboardHash = null
        lastObservedUriKey = null
        lastHandledClipSeq = -1L
        if (proxy != null) {
            runCatching {
                val icb = resolveClipboardService()
                if (icb != null) {
                    invokeClipboardListenerMethod(icb, "removePrimaryClipChangedListener", proxy)
                }
            }
        }
    }

    private fun startScreenshotWatcherLocked() {
        if (destroyed.get() || screenshotObserversStarted || clipboardCallbacks.isEmpty()) return
        registerMediaContentObserverLocked()
        val seenCanonical = HashSet<String>()
        for (dir in candidateScreenshotDirs()) {
            val canonical = runCatching { dir.canonicalPath }.getOrElse { dir.absolutePath }
            if (!seenCanonical.add(canonical)) continue
            if (!dir.isDirectory || !dir.canRead()) {
                continue
            }
            val observer = createScreenshotFileObserver(dir) ?: continue
            val started = runCatching {
                observer.startWatching()
                true
            }.onFailure {
                Log.w(TAG, "FileObserver failed to watch ${dir.absolutePath}", it)
            }.getOrDefault(false)
            if (started) {
                screenshotObservers.add(observer)
            }
        }
        Log.i(
            TAG,
            "Screenshot watchers started: fileObservers=${screenshotObservers.size} contentObserver=${mediaContentObserver != null}"
        )
        screenshotObserversStarted = true
    }

    @SuppressLint("PrivateApi")
    private fun registerMediaContentObserverLocked() {
        if (mediaContentObserver != null) return
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                onChange(selfChange, null)
            }

            override fun onChange(selfChange: Boolean, uri: Uri?) {
                if (destroyed.get()) return
                Log.d(TAG, "MediaStore Images ContentObserver change uri=$uri")
                scheduleScreenshotCheck(
                    pendingFallbackFile = null,
                    allowDirectFallback = false,
                    delayMs = SCREENSHOT_EVENT_DELAY_MS
                )
            }
        }

        val resolver = resolveAppContentResolver()
        if (resolver != null) {
            val ok = runCatching {
                resolver.registerContentObserver(MEDIA_IMAGES_URI, true, observer)
                true
            }.onFailure {
                Log.w(TAG, "ContentResolver.registerContentObserver failed; trying IContentService fallback", it)
            }.getOrDefault(false)
            if (ok) {
                mediaContentObserver = observer
                mediaContentObserverResolver = resolver
                return
            }
        }

        val contentBinder = runCatching { SystemServiceHelper.getSystemService("content") }.getOrNull()
        if (contentBinder != null && contentBinder.isBinderAlive) {
            val registered = runCatching {
                val stubClass = Class.forName("android.content.IContentService\$Stub")
                val service = stubClass.getMethod("asInterface", IBinder::class.java).invoke(null, contentBinder)
                    ?: return@runCatching false
                val iObserver = observer.javaClass.getMethod("getContentObserver").invoke(observer)
                    ?: return@runCatching false
                val methods = service.javaClass.methods
                    .filter { it.name == "registerContentObserver" }
                    .sortedBy { it.parameterCount }
                for (method in methods) {
                    val args = buildContentObserverRegisterArgs(method, iObserver) ?: continue
                    val invoked = runCatching {
                        method.invoke(service, *args)
                        true
                    }.getOrDefault(false)
                    if (invoked) {
                        mediaContentObserver = observer
                        mediaContentService = service
                        mediaContentServiceObserver = iObserver
                        return@runCatching true
                    }
                }
                false
            }.onFailure {
                Log.w(TAG, "IContentService.registerContentObserver fallback failed", unwrapCause(it))
            }.getOrDefault(false)
            if (registered) return
        }

        runCatching { observer.javaClass.getMethod("releaseContentObserver").invoke(observer) }
    }

    @SuppressLint("PrivateApi")
    private fun resolveAppContentResolver(): ContentResolver? {
        return runCatching {
            val atClass = Class.forName("android.app.ActivityThread")
            val app = atClass.getMethod("currentApplication").invoke(null) as? android.content.Context
            if (app != null) return@runCatching app.contentResolver
            val thread = atClass.getMethod("currentActivityThread").invoke(null) ?: return@runCatching null
            val sysCtx = atClass.getMethod("getSystemContext").invoke(thread) as? android.content.Context
            sysCtx?.contentResolver
        }.getOrNull()
    }

    private fun buildContentObserverRegisterArgs(method: Method, iObserver: Any): Array<Any?>? {
        val params = method.parameterTypes
        if (params.size < 3) return null
        val args = arrayOfNulls<Any?>(params.size)
        var sawUri = false
        var sawNotifyDesc = false
        var sawObserver = false
        var intIdx = 0
        for (i in params.indices) {
            val p = params[i]
            when {
                p == Uri::class.java && !sawUri -> {
                    args[i] = MEDIA_IMAGES_URI
                    sawUri = true
                }
                (p == Boolean::class.javaPrimitiveType || p == java.lang.Boolean::class.java) && !sawNotifyDesc -> {
                    args[i] = true
                    sawNotifyDesc = true
                }
                p.isInstance(iObserver) && !sawObserver -> {
                    args[i] = iObserver
                    sawObserver = true
                }
                p == Int::class.javaPrimitiveType || p == Integer::class.java -> {
                    // userHandle (0) first, targetSdkVersion second if present
                    args[i] = if (intIdx == 0) 0 else Build.VERSION.SDK_INT
                    intIdx++
                }
                else -> return null
            }
        }
        return if (sawUri && sawNotifyDesc && sawObserver) args else null
    }

    private fun unregisterMediaContentObserverLocked() {
        val observer = mediaContentObserver
        val resolver = mediaContentObserverResolver
        val service = mediaContentService
        val iObserver = mediaContentServiceObserver
        mediaContentObserver = null
        mediaContentObserverResolver = null
        mediaContentService = null
        mediaContentServiceObserver = null

        if (observer != null && resolver != null) {
            runCatching { resolver.unregisterContentObserver(observer) }
        }
        if (service != null && iObserver != null) {
            runCatching {
                val m = service.javaClass.methods.firstOrNull {
                    it.name == "unregisterContentObserver" &&
                        it.parameterCount == 1 &&
                        it.parameterTypes[0].isInstance(iObserver)
                }
                m?.invoke(service, iObserver)
            }
        }
        if (observer != null) {
            runCatching { observer.javaClass.getMethod("releaseContentObserver").invoke(observer) }
        }
    }

    private fun stopScreenshotWatcherLocked() {
        unregisterMediaContentObserverLocked()
        for (observer in screenshotObservers) {
            runCatching { observer.stopWatching() }
        }
        screenshotObservers.clear()
        screenshotObserversStarted = false
        screenshotSeeded = false
        lastSeenScreenshotId = 0L
        screenshotSeedTimeMs = 0L
        dispatchedScreenshotKeys.clear()
        screenshotPollTick = 0
        screenshotScanQueued.set(false)
    }

    private fun createScreenshotFileObserver(dir: File): FileObserver? {
        val mask = FileObserver.MOVED_TO or FileObserver.CLOSE_WRITE
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                object : FileObserver(dir, mask) {
                    override fun onEvent(event: Int, path: String?) {
                        handleScreenshotDirEvent(dir, path)
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                object : FileObserver(dir.absolutePath, mask) {
                    override fun onEvent(event: Int, path: String?) {
                        handleScreenshotDirEvent(dir, path)
                    }
                }
            }
        }.getOrNull()
    }

    private fun handleScreenshotDirEvent(dir: File, relativePath: String?) {
        if (destroyed.get() || relativePath.isNullOrBlank()) return
        if (!isCompletedScreenshotFilename(relativePath)) return
        val targetFile = File(dir, relativePath)
        Log.i(TAG, "Screenshot dir event matched: ${targetFile.name}")
        scheduleScreenshotCheck(pendingFallbackFile = targetFile, allowDirectFallback = false, delayMs = SCREENSHOT_EVENT_DELAY_MS)
    }

    private fun scheduleScreenshotCheck(
        pendingFallbackFile: File?,
        allowDirectFallback: Boolean,
        delayMs: Long
    ) {
        if (destroyed.get()) return
        if (pendingFallbackFile == null && !screenshotScanQueued.compareAndSet(false, true)) return
        runCatching {
            executor.schedule(
                {
                    if (pendingFallbackFile == null) {
                        screenshotScanQueued.set(false)
                    }
                    pollNewScreenshots(pendingFallbackFile, allowDirectFallback)
                },
                delayMs,
                TimeUnit.MILLISECONDS
            )
        }.onFailure {
            if (pendingFallbackFile == null) screenshotScanQueued.set(false)
        }
    }

    private fun seedInitialClipboardHash() {
        if (destroyed.get()) return
        val seq = clipChangeSeq.get()
        val (ok, content) = readPrimaryClipContentResultInternal()
        if (!ok) return
        synchronized(clipboardLock) {
            if (clipboardCallbacks.isEmpty()) return
            if (lastClipboardHash == null && lastObservedUriKey == null && content != null) {
                if (content.uri != null) {
                    lastObservedUriKey = uriDedupKey(content.uri, content.timestamp)
                    lastHandledClipSeq = seq
                } else if (!content.text.isNullOrEmpty()) {
                    lastClipboardHash = sha256(content.text)
                    lastHandledClipSeq = seq
                }
            }
        }
    }

    private fun seedInitialScreenshotState() {
        if (destroyed.get()) return
        val scan = queryLatestMediaScreenshots(afterId = -1L, descending = true, limit = 1)
        if (scan == null) {
            Log.w(TAG, "seedInitialScreenshotState: MediaStore query returned null")
        } else {
            Log.i(TAG, "seedInitialScreenshotState: maxId=${scan.maxScannedId}")
        }
        val maxId = scan?.maxScannedId ?: 0L
        val nowMs = System.currentTimeMillis()
        synchronized(clipboardLock) {
            if (clipboardCallbacks.isEmpty()) return
            if (!screenshotSeeded && (scan != null || screenshotSeedTimeMs == 0L)) {
                lastSeenScreenshotId = maxId
                screenshotSeedTimeMs = nowMs
                if (scan != null) {
                    screenshotSeeded = true
                }
            }
        }
    }

    private fun pollAndDispatchClipboard() {
        if (destroyed.get()) return
        val hasCallbacks = synchronized(clipboardLock) {
            pruneDeadClipboardCallbacksLocked()
            if (clipboardCallbacks.isEmpty()) {
                stopClipboardObserverLocked()
                false
            } else {
                true
            }
        }
        if (!hasCallbacks) return

        val shouldPollScreenshots = synchronized(clipboardLock) {
            screenshotPollTick++
            screenshotPollTick % SCREENSHOT_FALLBACK_POLL_TICKS == 0
        }
        if (shouldPollScreenshots) {
            pollNewScreenshots(pendingFallbackFile = null, allowDirectFallback = false)
        }

        val seq = clipChangeSeq.get()
        val (ok, content) = readPrimaryClipContentResultInternal()
        if (!ok) return
        if (content == null || (content.uri == null && content.text.isNullOrEmpty())) {
            synchronized(clipboardLock) {
                lastClipboardHash = null
                lastObservedUriKey = null
            }
            return
        }

        if (content.uri != null) {
            val uri = content.uri
            val uriKey = uriDedupKey(uri, content.timestamp)
            val alreadySeen = synchronized(clipboardLock) {
                if (clipboardCallbacks.isEmpty()) return
                if (lastObservedUriKey == uriKey) {
                    content.timestamp > 0L || clipboardSystemListenerProxy == null || lastHandledClipSeq == seq
                } else {
                    false
                }
            }
            if (alreadySeen) return

            val dispatchedAny = dispatchStreamToClipboardCallbacks(uri, content.hintMime)
            if (dispatchedAny) {
                synchronized(clipboardLock) {
                    lastObservedUriKey = uriKey
                    lastObservedHashClearLocked()
                    lastHandledClipSeq = seq
                }
            }
            return
        }

        val text = content.text ?: return
        val hash = sha256(text)
        val shouldDispatch = synchronized(clipboardLock) {
            if (clipboardCallbacks.isEmpty()) return
            val prev = lastClipboardHash
            if (prev != null && prev.contentEquals(hash)) {
                false
            } else {
                lastClipboardHash = hash
                lastObservedUriKey = null
                lastHandledClipSeq = seq
                true
            }
        }
        if (!shouldDispatch) return

        for (cb in clipboardCallbacks) {
            if (!cb.asBinder().isBinderAlive) {
                clipboardCallbacks.remove(cb)
                continue
            }
            try {
                cb.onPrimaryClipTextChanged(text)
            } catch (_: Throwable) {
                clipboardCallbacks.remove(cb)
            }
        }

        synchronized(clipboardLock) {
            pruneDeadClipboardCallbacksLocked()
            if (clipboardCallbacks.isEmpty()) {
                stopClipboardObserverLocked()
            }
        }
    }

    private fun dispatchStreamToClipboardCallbacks(uri: Uri, hintMime: String?): Boolean {
        val targets = synchronized(clipboardLock) {
            pruneDeadClipboardCallbacksLocked()
            clipboardCallbacks.filter { it.asBinder().isBinderAlive }
        }
        if (targets.isEmpty()) {
            synchronized(clipboardLock) {
                pruneDeadClipboardCallbacksLocked()
                if (clipboardCallbacks.isEmpty()) stopClipboardObserverLocked()
            }
            return false
        }

        var dispatchedAny = false
        for (cb in targets) {
            val opened = openClipStreamToPipe(uri, hintMime)
            if (opened == null) {
                Log.w(TAG, "openClipStreamToPipe returned null for $uri (hintMime=$hintMime)")
                break
            }
            val mime = opened.first
            val readEnd = opened.second
            try {
                cb.onPrimaryClipStreamChanged(mime, uri.toString(), readEnd)
                dispatchedAny = true
            } catch (t: Throwable) {
                Log.w(TAG, "onPrimaryClipStreamChanged callback failed", t)
                clipboardCallbacks.remove(cb)
            } finally {
                runCatching { readEnd.close() }
            }
        }

        synchronized(clipboardLock) {
            pruneDeadClipboardCallbacksLocked()
            if (clipboardCallbacks.isEmpty()) {
                stopClipboardObserverLocked()
            }
        }
        return dispatchedAny
    }

    private fun pollNewScreenshots(pendingFallbackFile: File?, allowDirectFallback: Boolean) {
        if (destroyed.get()) return
        val (seeded, afterId, seedTimeMs) = synchronized(clipboardLock) {
            if (clipboardCallbacks.isEmpty()) return
            Triple(screenshotSeeded, lastSeenScreenshotId, screenshotSeedTimeMs)
        }
        if (!seeded) {
            seedInitialScreenshotState()
            if (pendingFallbackFile == null || !allowDirectFallback) return
        }

        var matchedFallback = false
        val scan = if (seeded) {
            queryLatestMediaScreenshots(
                afterId = afterId,
                descending = false,
                limit = MAX_SCREENSHOT_BATCH
            )
        } else {
            null
        }
        val rows = scan?.rows ?: emptyList()

        var heldByPending = false
        for (row in rows) {
            if (row.isPending != 0 || row.sizeBytes <= 0L) {
                heldByPending = true
                continue
            }
            if (!isCompletedScreenshotFilename(row.displayName) ||
                row.sizeBytes > MAX_CLIPBOARD_STREAM_BYTES
            ) {
                if (!heldByPending) {
                    synchronized(clipboardLock) {
                        if (row.id > lastSeenScreenshotId) lastSeenScreenshotId = row.id
                    }
                }
                continue
            }
            val idKey = "id:${row.id}"
            val fileKey = screenshotFileDedupKey(row.displayName, row.sizeBytes)
            if (pendingFallbackFile != null && row.displayName == pendingFallbackFile.name) {
                matchedFallback = true
            }

            val alreadyDispatched = synchronized(clipboardLock) {
                if (clipboardCallbacks.isEmpty()) return
                dispatchedScreenshotKeys.contains(idKey) ||
                    (fileKey != null && dispatchedScreenshotKeys.contains(fileKey))
            }
            if (alreadyDispatched) {
                synchronized(clipboardLock) {
                    if (!heldByPending && row.id > lastSeenScreenshotId) lastSeenScreenshotId = row.id
                    rememberDispatchedScreenshotKeyLocked(idKey)
                }
                continue
            }

            val mediaUri = Uri.withAppendedPath(MEDIA_IMAGES_URI, row.id.toString())
            val mime = row.mimeType
                ?.takeIf { !isWildcardOrEmptyMime(it) && it.startsWith("image/", ignoreCase = true) }
                ?: guessMimeFromPath(row.displayName)

            val dispatched = dispatchStreamToClipboardCallbacks(mediaUri, mime)
            Log.i(TAG, "Screenshot MediaStore dispatch id=${row.id} name=${row.displayName} mime=$mime dispatched=$dispatched")
            if (dispatched) {
                synchronized(clipboardLock) {
                    if (!heldByPending && row.id > lastSeenScreenshotId) lastSeenScreenshotId = row.id
                    rememberDispatchedScreenshotKeyLocked(idKey)
                    if (fileKey != null) rememberDispatchedScreenshotKeyLocked(fileKey)
                }
            }
        }

        if (scan != null && !heldByPending && scan.maxScannedId > 0L) {
            synchronized(clipboardLock) {
                if (scan.maxScannedId > lastSeenScreenshotId) {
                    lastSeenScreenshotId = scan.maxScannedId
                }
            }
        }

        if (pendingFallbackFile == null || matchedFallback) return
        if (!allowDirectFallback) {
            scheduleScreenshotCheck(
                pendingFallbackFile = pendingFallbackFile,
                allowDirectFallback = true,
                delayMs = SCREENSHOT_MEDIA_RETRY_DELAY_MS
            )
            return
        }

        if (!pendingFallbackFile.isFile || !pendingFallbackFile.canRead()) return
        val len = pendingFallbackFile.length()
        if (len <= 0L || len > MAX_CLIPBOARD_STREAM_BYTES) return
        if (seedTimeMs > 0L && pendingFallbackFile.lastModified() < seedTimeMs - 2_000L) return

        val fileKey = screenshotFileDedupKey(pendingFallbackFile.name, len) ?: return
        val alreadyDispatched = synchronized(clipboardLock) {
            if (clipboardCallbacks.isEmpty()) return
            dispatchedScreenshotKeys.contains(fileKey)
        }
        if (alreadyDispatched) return

        val mime = guessMimeFromPath(pendingFallbackFile.name)
        if (!mime.startsWith("image/", ignoreCase = true)) return

        val dispatched = dispatchStreamToClipboardCallbacks(Uri.fromFile(pendingFallbackFile), mime)
        Log.i(TAG, "Screenshot direct fallback dispatch name=${pendingFallbackFile.name} mime=$mime dispatched=$dispatched")
        if (dispatched) {
            synchronized(clipboardLock) {
                rememberDispatchedScreenshotKeyLocked(fileKey)
            }
        }
    }

    private fun rememberDispatchedScreenshotKeyLocked(key: String) {
        if (dispatchedScreenshotKeys.contains(key)) return
        dispatchedScreenshotKeys.add(key)
        while (dispatchedScreenshotKeys.size > MAX_DISPATCHED_SCREENSHOT_KEYS) {
            val it = dispatchedScreenshotKeys.iterator()
            if (!it.hasNext()) break
            it.next()
            it.remove()
        }
    }

    private fun candidateScreenshotDirs(): List<File> {
        val roots = linkedSetOf("/storage/emulated/0")
        runCatching { Environment.getExternalStorageDirectory()?.absolutePath }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { roots.add(it) }
        val dirs = mutableListOf<File>()
        val staticRelatives = listOf(
            "Pictures/Screenshots",
            "DCIM/Screenshots",
            "Screenshots",
            "Pictures/ScreenShots",
            "DCIM/ScreenShots",
            "ScreenShots",
            "Pictures/ScreenShot",
            "DCIM/ScreenShot",
            "Pictures/Screencaps",
            "DCIM/Screencaps",
            "Pictures/Capturas de pantalla",
            "DCIM/Capturas de pantalla",
            "Capturas de pantalla",
            "MIUI/screenshot"
        )
        for (root in roots) {
            for (rel in staticRelatives) {
                dirs.add(File(root, rel))
            }
            for (parentRel in listOf("", "Pictures", "DCIM", "MIUI")) {
                val parent = if (parentRel.isEmpty()) File(root) else File(root, parentRel)
                val children = runCatching { parent.listFiles() }.getOrNull() ?: continue
                for (child in children) {
                    if (child.isDirectory && isScreenshotDirectoryName(child.name)) {
                        dirs.add(child)
                    }
                }
            }
        }
        return dirs
    }

    private data class ScreenshotMediaRow(
        val id: Long,
        val displayName: String,
        val mimeType: String?,
        val sizeBytes: Long,
        val isPending: Int
    )

    private data class ScreenshotMediaScanResult(
        val rows: List<ScreenshotMediaRow>,
        val maxScannedId: Long
    )

    @SuppressLint("PrivateApi")
    private fun queryLatestMediaScreenshots(
        afterId: Long,
        descending: Boolean,
        limit: Int
    ): ScreenshotMediaScanResult? {
        val am = resolveActivityManager() ?: return null
        val token = Binder()
        val holder = acquireContentProviderExternal(am, "media", 0, token) ?: return null
        val provider = extractField(holder, "provider")
        if (provider == null) {
            releaseContentProviderExternal(am, "media", token, 0)
            return null
        }

        var cursor: Cursor? = null
        return try {
            val projection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.MIME_TYPE,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.DATA,
                    MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                    MediaStore.Images.Media.RELATIVE_PATH,
                    MediaStore.Images.Media.IS_PENDING
                )
            } else {
                arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.MIME_TYPE,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.DATA,
                    MediaStore.Images.Media.BUCKET_DISPLAY_NAME
                )
            }

            val selectionParts = mutableListOf<String>()
            val selectionArgs = mutableListOf<String>()
            if (afterId >= 0L) {
                selectionParts.add("${MediaStore.Images.Media._ID} > ?")
                selectionArgs.add(afterId.toString())
            }

            val args = Bundle().apply {
                if (selectionParts.isNotEmpty()) {
                    putString("android:query-arg-sql-selection", selectionParts.joinToString(" AND "))
                    putStringArray("android:query-arg-sql-selection-args", selectionArgs.toTypedArray())
                }
                putString(
                    "android:query-arg-sql-sort-order",
                    if (descending) "${MediaStore.Images.Media._ID} DESC" else "${MediaStore.Images.Media._ID} ASC"
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && limit > 0) {
                    putInt("android:query-arg-limit", limit)
                }
            }

            cursor = invokeProviderQuery(provider, MEDIA_IMAGES_URI, projection, args) ?: return null
            val out = mutableListOf<ScreenshotMediaRow>()
            var maxScannedId = 0L
            var scanned = 0
            val idIdx = cursor.getColumnIndex(MediaStore.Images.Media._ID)
            val nameIdx = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
            val mimeIdx = cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
            val sizeIdx = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)
            val dataIdx = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
            val bucketIdx = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val relIdx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
            } else {
                -1
            }
            val pendingIdx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cursor.getColumnIndex(MediaStore.Images.Media.IS_PENDING)
            } else {
                -1
            }
            while (cursor.moveToNext() && scanned < limit) {
                scanned++
                if (idIdx < 0) continue
                val id = cursor.getLong(idIdx)
                if (id > maxScannedId) maxScannedId = id
                val rawName = if (nameIdx >= 0) cursor.getString(nameIdx) else null
                val rawData = if (dataIdx >= 0) cursor.getString(dataIdx) else null
                val bucket = if (bucketIdx >= 0) cursor.getString(bucketIdx) else null
                val relPath = if (relIdx >= 0) cursor.getString(relIdx) else null
                val isPending = if (pendingIdx >= 0) cursor.getInt(pendingIdx) else 0
                val displayName = rawName?.takeIf { it.isNotBlank() }
                    ?: rawData?.let { File(it).name }
                    ?: continue
                if (!isScreenshotMediaLocation(displayName, relPath, bucket, rawData)) {
                    continue
                }
                val mimeType = if (mimeIdx >= 0) cursor.getString(mimeIdx) else null
                var size = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else 0L
                if (size <= 0L && !rawData.isNullOrBlank()) {
                    size = runCatching { File(rawData).length() }.getOrDefault(0L)
                }
                out.add(
                    ScreenshotMediaRow(
                        id = id,
                        displayName = displayName,
                        mimeType = mimeType,
                        sizeBytes = size,
                        isPending = isPending
                    )
                )
            }
            ScreenshotMediaScanResult(rows = out, maxScannedId = maxScannedId)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed querying MediaStore screenshots", t)
            null
        } finally {
            runCatching { cursor?.close() }
            releaseContentProviderExternal(am, "media", token, 0)
        }
    }

    private fun sortProviderMethods(methods: List<Method>): List<Method> =
        methods.sortedWith(
            compareByDescending<Method> { m ->
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    m.parameterTypes.any { it == AttributionSource::class.java }
            }.thenBy { it.parameterCount }
        )

    private fun invokeProviderQuery(
        provider: Any,
        uri: Uri,
        projection: Array<String>,
        queryArgs: Bundle
    ): Cursor? {
        val methods = sortProviderMethods(provider.javaClass.methods.filter { it.name == "query" })
        var lastError: Throwable? = null
        for (method in methods) {
            val args = buildProviderQueryArgs(method, uri, projection, queryArgs) ?: continue
            val res = runCatching { method.invoke(provider, *args) as? Cursor }
            if (res.isSuccess) {
                val cursor = res.getOrNull()
                if (cursor != null) return cursor
            } else {
                lastError = res.exceptionOrNull()
            }
        }
        if (lastError != null) {
            Log.w(TAG, "invokeProviderQuery failed for $uri", lastError.cause ?: lastError)
        }
        return null
    }

    private fun buildProviderQueryArgs(
        method: Method,
        uri: Uri,
        projection: Array<String>,
        queryArgs: Bundle
    ): Array<Any?>? {
        val params = method.parameterTypes
        val args = arrayOfNulls<Any?>(params.size)
        var sawUri = false
        var sawProj = false
        var sawBundle = false
        var sawPrePkg = false
        for (i in params.indices) {
            val p = params[i]
            when {
                p == Uri::class.java -> {
                    args[i] = uri
                    sawUri = true
                }
                p == Array<String>::class.java -> {
                    args[i] = projection
                    sawProj = true
                }
                p == Bundle::class.java -> {
                    args[i] = queryArgs
                    sawBundle = true
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && p == AttributionSource::class.java -> {
                    args[i] = buildShellAttributionSource() ?: return null
                }
                p == String::class.java -> {
                    if (!sawUri && !sawPrePkg) {
                        args[i] = SHELL_PKG
                        sawPrePkg = true
                    } else {
                        args[i] = null
                    }
                }
                p.name == "android.os.ICancellationSignal" -> args[i] = null
                else -> return null
            }
        }
        return if (sawUri && sawProj && sawBundle) args else null
    }

    private fun resolveClipUriMime(uri: Uri): String? {
        return when (uri.scheme?.lowercase(Locale.ROOT)) {
            "file" -> uri.path?.let { guessMimeFromPath(it) }
            "content" -> {
                val rawAuth = uri.authority ?: return null
                val atIdx = rawAuth.lastIndexOf('@')
                val userId = if (atIdx > 0) rawAuth.substring(0, atIdx).toIntOrNull() ?: 0 else 0
                val authority = if (atIdx > 0) rawAuth.substring(atIdx + 1) else rawAuth
                if (authority.isEmpty()) return null
                val queryUri = if (atIdx > 0) uri.buildUpon().encodedAuthority(authority).build() else uri
                val am = resolveActivityManager() ?: return null
                val token = Binder()
                val holder = acquireContentProviderExternal(am, authority, userId, token) ?: return null
                try {
                    val provider = extractField(holder, "provider") ?: return null
                    invokeProviderGetType(provider, queryUri)
                } finally {
                    releaseContentProviderExternal(am, authority, token, userId)
                }
            }
            else -> null
        }
    }

    private fun lastObservedHashClearLocked() {
        lastClipboardHash = null
    }

    private fun uriDedupKey(uri: Uri, timestamp: Long): String =
        if (timestamp > 0L) "$uri#$timestamp" else uri.toString()

    @SuppressLint("PrivateApi")
    private fun resolveClipboardService(): Any? {
        return runCatching {
            val binder = SystemServiceHelper.getSystemService("clipboard") ?: return@runCatching null
            if (!binder.isBinderAlive) return@runCatching null
            val stubClass = Class.forName("android.content.IClipboard\$Stub")
            stubClass.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        }.getOrNull()
    }

    private fun readPrimaryClipContentResultInternal(): Pair<Boolean, ClipContent?> {
        val icb = resolveClipboardService() ?: return false to null
        val clip = invokeGetPrimaryClip(icb) ?: return false to null
        val clipData = clip.second
        if (!clip.first) return false to null
        if (clipData == null || clipData.itemCount <= 0) return true to null
        return true to extractClipContent(clipData)
    }

    private fun extractClipContent(clipData: ClipData): ClipContent? {
        val desc = clipData.description
        val timestamp = runCatching { desc?.timestamp ?: 0L }.getOrDefault(0L)
        var imageDescMime: String? = null
        var concreteDescMime: String? = null
        if (desc != null) {
            for (i in 0 until desc.mimeTypeCount) {
                val m = desc.getMimeType(i) ?: continue
                if (m.startsWith("image/", ignoreCase = true) && m != "image/*" && imageDescMime == null) {
                    imageDescMime = m
                } else if (m.startsWith("image/", ignoreCase = true) && imageDescMime == null) {
                    imageDescMime = m
                }
                if (m != "*/*" && concreteDescMime == null) {
                    concreteDescMime = m
                }
            }
        }
        val hintMime = imageDescMime ?: concreteDescMime

        var fallbackText: String? = null
        for (i in 0 until clipData.itemCount) {
            val item = clipData.getItemAt(i) ?: continue
            val uri = item.uri
            val itemText = runCatching { item.text?.toString() }.getOrNull()
            if (uri != null) {
                var effectiveHint = hintMime
                val scheme = uri.scheme?.lowercase(Locale.ROOT)
                val looksLikeStream = imageDescMime != null ||
                    itemText.isNullOrBlank() ||
                    itemText == uri.toString()
                val isImageUri = if (!looksLikeStream && (scheme == "content" || scheme == "file")) {
                    val probed = resolveClipUriMime(uri)
                    if (!probed.isNullOrBlank() && probed.startsWith("image/", ignoreCase = true)) {
                        effectiveHint = probed
                        true
                    } else {
                        false
                    }
                } else {
                    false
                }
                if (looksLikeStream || isImageUri) {
                    return ClipContent(
                        text = null,
                        uri = uri,
                        hintMime = effectiveHint,
                        timestamp = timestamp
                    )
                }
            }
            if (fallbackText.isNullOrEmpty() && !itemText.isNullOrEmpty()) {
                fallbackText = itemText
            }
        }

        if (fallbackText.isNullOrEmpty()) return null
        return ClipContent(
            text = fallbackText,
            uri = null,
            hintMime = hintMime,
            timestamp = timestamp
        )
    }

    private fun openClipStreamToPipe(uri: Uri, hintMime: String?): Pair<String, ParcelFileDescriptor>? {
        return when (uri.scheme?.lowercase(Locale.ROOT)) {
            "file" -> {
                val path = uri.path ?: return null
                val file = File(path)
                if (!file.isFile || !file.canRead()) return null
                val len = file.length()
                if (len <= 0L || len > MAX_CLIPBOARD_STREAM_BYTES) return null
                val mime = hintMime?.takeIf { !isWildcardOrEmptyMime(it) } ?: guessMimeFromPath(path)
                val input = runCatching { FileInputStream(file) }.getOrNull() ?: return null
                startPipePump(input, closeHook = null, mime = mime)
            }
            "content" -> openContentProviderToPipe(uri, hintMime)
            else -> null
        }
    }

    @SuppressLint("PrivateApi")
    private fun openContentProviderToPipe(uri: Uri, hintMime: String?): Pair<String, ParcelFileDescriptor>? {
        val rawAuth = uri.authority ?: return null
        val atIdx = rawAuth.lastIndexOf('@')
        val userId = if (atIdx > 0) rawAuth.substring(0, atIdx).toIntOrNull() ?: 0 else 0
        val authority = if (atIdx > 0) rawAuth.substring(atIdx + 1) else rawAuth
        if (authority.isEmpty()) return null
        val queryUri = if (atIdx > 0) uri.buildUpon().encodedAuthority(authority).build() else uri

        val am = resolveActivityManager() ?: return null
        val token = Binder()
        val holder = acquireContentProviderExternal(am, authority, userId, token) ?: return null
        val provider = extractField(holder, "provider")
        if (provider == null) {
            releaseContentProviderExternal(am, authority, token, userId)
            return null
        }

        var handedOff = false
        var openedAfd: AssetFileDescriptor? = null
        var openedPfd: ParcelFileDescriptor? = null
        var inputStream: InputStream? = null
        try {
            val providerMime = invokeProviderGetType(provider, queryUri)
                ?.takeIf { !isWildcardOrEmptyMime(it) }
            val resolvedMime = providerMime
                ?: hintMime?.takeIf { !isWildcardOrEmptyMime(it) }
                ?: "application/octet-stream"

            openedAfd = invokeProviderOpenAssetFile(provider, queryUri, "r")
            if (openedAfd == null) {
                openedPfd = invokeProviderOpenFile(provider, queryUri, "r")
            }
            if (openedAfd == null && openedPfd == null && resolvedMime.startsWith("image/", ignoreCase = true)) {
                openedAfd = invokeProviderOpenTypedAssetFile(provider, queryUri, resolvedMime)
                    ?: invokeProviderOpenTypedAssetFile(provider, queryUri, "image/*")
            }

            if (openedAfd != null) {
                val declaredLen = openedAfd.declaredLength
                if (declaredLen > MAX_CLIPBOARD_STREAM_BYTES) {
                    return null
                }
                inputStream = openedAfd.createInputStream()
            } else if (openedPfd != null) {
                val statSize = openedPfd.statSize
                if (statSize > MAX_CLIPBOARD_STREAM_BYTES) {
                    return null
                }
                inputStream = ParcelFileDescriptor.AutoCloseInputStream(openedPfd)
            } else {
                return null
            }

            val closeHook: () -> Unit = {
                runCatching { openedAfd?.close() }
                runCatching { openedPfd?.close() }
                releaseContentProviderExternal(am, authority, token, userId)
            }
            val result = startPipePump(inputStream, closeHook = closeHook, mime = resolvedMime)
            if (result != null) {
                handedOff = true
            }
            return result
        } catch (t: Throwable) {
            Log.w(TAG, "Failed opening clipboard content URI: $uri", t)
            return null
        } finally {
            if (!handedOff) {
                runCatching { inputStream?.close() }
                runCatching { openedAfd?.close() }
                runCatching { openedPfd?.close() }
                releaseContentProviderExternal(am, authority, token, userId)
            }
        }
    }

    private fun startPipePump(
        input: InputStream,
        closeHook: (() -> Unit)?,
        mime: String
    ): Pair<String, ParcelFileDescriptor>? {
        val pipe = runCatching { ParcelFileDescriptor.createPipe() }.getOrElse {
            runCatching { input.close() }
            runCatching { closeHook?.invoke() }
            return null
        }
        val readEnd = pipe[0]
        val writeEnd = pipe[1]
        val started = runCatching {
            Thread({
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { out ->
                        input.use { src ->
                            val buf = ByteArray(PIPE_CHUNK_SIZE)
                            var copied = 0L
                            while (copied < MAX_CLIPBOARD_STREAM_BYTES) {
                                val n = src.read(buf)
                                if (n <= 0) break
                                copied += n
                                out.write(buf, 0, n)
                            }
                            out.flush()
                        }
                    }
                } catch (_: Throwable) {
                } finally {
                    runCatching { input.close() }
                    runCatching { writeEnd.close() }
                    runCatching { closeHook?.invoke() }
                }
            }, "Controid-ClipPipe").apply {
                isDaemon = true
                start()
            }
            true
        }.getOrDefault(false)

        if (!started) {
            runCatching { readEnd.close() }
            runCatching { writeEnd.close() }
            runCatching { input.close() }
            runCatching { closeHook?.invoke() }
            return null
        }
        return mime to readEnd
    }

    @SuppressLint("PrivateApi")
    private fun resolveActivityManager(): Any? = runCatching {
        val amClass = Class.forName("android.app.ActivityManager")
        amClass.getMethod("getService").invoke(null)
    }.getOrNull()

    private fun acquireContentProviderExternal(
        am: Any,
        authority: String,
        userId: Int,
        token: IBinder
    ): Any? {
        val fourArg = am.javaClass.methods.firstOrNull {
            it.name == "getContentProviderExternal" && it.parameterCount == 4
        }
        if (fourArg != null) {
            return runCatching { fourArg.invoke(am, authority, userId, token, "*controid*") }.getOrNull()
        }
        val threeArg = am.javaClass.methods.firstOrNull {
            it.name == "getContentProviderExternal" && it.parameterCount == 3
        }
        if (threeArg != null) {
            return runCatching { threeArg.invoke(am, authority, userId, token) }.getOrNull()
        }
        return null
    }

    private fun releaseContentProviderExternal(
        am: Any,
        authority: String,
        token: IBinder,
        userId: Int
    ) {
        val asUser = am.javaClass.methods.firstOrNull {
            it.name == "removeContentProviderExternalAsUser" && it.parameterCount == 3
        }
        if (asUser != null) {
            runCatching { asUser.invoke(am, authority, token, userId) }
            return
        }
        val legacy = am.javaClass.methods.firstOrNull {
            it.name == "removeContentProviderExternal" && it.parameterCount == 2
        }
        if (legacy != null) {
            runCatching { legacy.invoke(am, authority, token) }
        }
    }

    private fun invokeProviderGetType(provider: Any, uri: Uri): String? {
        val methods = sortProviderMethods(
            provider.javaClass.methods.filter { it.name == "getType" && it.returnType == String::class.java }
        )
        for (method in methods) {
            val args = buildProviderCallArgs(method, uri, tailString = null) ?: continue
            val res = runCatching { method.invoke(provider, *args) as? String }
            if (res.isSuccess) return res.getOrNull()
        }
        return null
    }

    private fun invokeProviderOpenAssetFile(provider: Any, uri: Uri, mode: String): AssetFileDescriptor? {
        val methods = sortProviderMethods(provider.javaClass.methods.filter { it.name == "openAssetFile" })
        for (method in methods) {
            val args = buildProviderCallArgs(method, uri, tailString = mode) ?: continue
            val res = runCatching { method.invoke(provider, *args) as? AssetFileDescriptor }
            if (res.isSuccess && res.getOrNull() != null) return res.getOrNull()
        }
        return null
    }

    private fun invokeProviderOpenFile(provider: Any, uri: Uri, mode: String): ParcelFileDescriptor? {
        val methods = sortProviderMethods(provider.javaClass.methods.filter { it.name == "openFile" })
        for (method in methods) {
            val args = buildProviderCallArgs(method, uri, tailString = mode) ?: continue
            val res = runCatching { method.invoke(provider, *args) as? ParcelFileDescriptor }
            if (res.isSuccess && res.getOrNull() != null) return res.getOrNull()
        }
        return null
    }

    private fun invokeProviderOpenTypedAssetFile(
        provider: Any,
        uri: Uri,
        mimeTypeFilter: String
    ): AssetFileDescriptor? {
        val methods = sortProviderMethods(provider.javaClass.methods.filter { it.name == "openTypedAssetFile" })
        for (method in methods) {
            val args = buildProviderCallArgs(method, uri, tailString = mimeTypeFilter) ?: continue
            val res = runCatching { method.invoke(provider, *args) as? AssetFileDescriptor }
            if (res.isSuccess && res.getOrNull() != null) return res.getOrNull()
        }
        return null
    }

    private fun buildProviderCallArgs(
        method: Method,
        uri: Uri,
        tailString: String?
    ): Array<Any?>? {
        val params = method.parameterTypes
        val args = arrayOfNulls<Any?>(params.size)
        var sawUri = false
        var sawPrePkg = false
        var sawTailStr = false
        for (i in params.indices) {
            val p = params[i]
            when {
                p == Uri::class.java -> {
                    args[i] = uri
                    sawUri = true
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && p == AttributionSource::class.java -> {
                    args[i] = buildShellAttributionSource() ?: return null
                }
                p == String::class.java -> {
                    if (!sawUri) {
                        if (!sawPrePkg) {
                            args[i] = SHELL_PKG
                            sawPrePkg = true
                        } else {
                            args[i] = null
                        }
                    } else if (!sawTailStr && tailString != null) {
                        args[i] = tailString
                        sawTailStr = true
                    } else {
                        args[i] = null
                    }
                }
                p == Bundle::class.java || p == IBinder::class.java -> args[i] = null
                p.name == "android.os.ICancellationSignal" -> args[i] = null
                else -> return null
            }
        }
        return if (sawUri) args else null
    }

    private fun buildShellAttributionSource(): Any? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return runCatching {
            AttributionSource.Builder(Process.myUid())
                .setPackageName(SHELL_PKG)
                .setPid(Process.myPid())
                .build()
        }.getOrNull()
    }

    private fun isWildcardOrEmptyMime(mime: String): Boolean {
        val clean = mime.trim()
        return clean.isEmpty() || clean == "*/*" || clean == "image/*"
    }

    private fun guessMimeFromPath(path: String): String {
        val ext = MimeTypeMap.getFileExtensionFromUrl(path)
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotEmpty() }
            ?: path.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return guessImageMimeFromExtension(ext)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }

    private data class ClipContent(
        val text: String?,
        val uri: Uri?,
        val hintMime: String?,
        val timestamp: Long
    )

    private fun invokeGetPrimaryClip(icb: Any): Pair<Boolean, ClipData?>? {
        val methods = icb.javaClass.methods.filter { it.name == "getPrimaryClip" }
        val ordered = sortClipboardCandidates(methods)
        var lastError: Throwable? = null
        for (method in ordered) {
            val args = buildClipboardArgs(method, listenerProxy = null) ?: continue
            val result = runCatching { method.invoke(icb, *args) }
            if (result.isSuccess) {
                warnedClipboardAccess.set(false)
                return true to (result.getOrNull() as? ClipData)
            }
            lastError = result.exceptionOrNull()?.let { unwrapCause(it) }
        }
        if (lastError != null && warnedClipboardAccess.compareAndSet(false, true)) {
            Log.w(TAG, "OEM/system rejected IClipboard.getPrimaryClip: ${lastError.message}", lastError)
        }
        return null
    }

    @SuppressLint("PrivateApi")
    private fun invokeClipboardListenerMethod(icb: Any, methodName: String, proxy: Any): Boolean {
        val listenerClass = runCatching {
            Class.forName("android.content.IOnPrimaryClipChangedListener")
        }.getOrNull() ?: return false
        val methods = icb.javaClass.methods.filter {
            it.name == methodName &&
                it.parameterCount >= 1 &&
                it.parameterTypes[0].isAssignableFrom(listenerClass)
        }
        for (method in sortClipboardCandidates(methods)) {
            val args = buildClipboardArgs(method, listenerProxy = proxy) ?: continue
            val ok = runCatching {
                method.invoke(icb, *args)
                true
            }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }

    private fun sortClipboardCandidates(methods: List<Method>): List<Method> =
        methods.sortedWith(
            compareByDescending<Method> { m ->
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    m.parameterTypes.any { it == AttributionSource::class.java }
            }.thenBy { it.parameterCount }
        )

    private fun buildClipboardArgs(method: Method, listenerProxy: Any?): Array<Any?>? {
        val params = method.parameterTypes
        val args = arrayOfNulls<Any?>(params.size)
        var startIdx = 0
        if (listenerProxy != null) {
            if (params.isEmpty()) return null
            args[0] = listenerProxy
            startIdx = 1
        }
        var sawPackage = false
        for (i in startIdx until params.size) {
            val p = params[i]
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && p == AttributionSource::class.java -> {
                    args[i] = buildShellAttributionSource() ?: return null
                }
                p == String::class.java -> {
                    if (!sawPackage) {
                        args[i] = SHELL_PKG
                        sawPackage = true
                    } else {
                        args[i] = null
                    }
                }
                p == Int::class.javaPrimitiveType || p == Integer::class.java -> args[i] = 0
                p == Boolean::class.javaPrimitiveType || p == java.lang.Boolean::class.java -> args[i] = false
                p == Long::class.javaPrimitiveType || p == java.lang.Long::class.java -> args[i] = 0L
                else -> return null
            }
        }
        return args
    }

    @SuppressLint("PrivateApi")
    private fun createPrimaryClipChangedListenerProxy(): Any? {
        return runCatching {
            val listenerClass = Class.forName("android.content.IOnPrimaryClipChangedListener")
            val stubClass = Class.forName("android.content.IOnPrimaryClipChangedListener\$Stub")
            val txCode = runCatching {
                stubClass.getDeclaredField("TRANSACTION_dispatchPrimaryClipChanged")
                    .apply { isAccessible = true }
                    .getInt(null)
            }.getOrDefault(IBinder.FIRST_CALL_TRANSACTION)

            val binder = object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    if (code == txCode) {
                        data.enforceInterface("android.content.IOnPrimaryClipChangedListener")
                        if ((flags and IBinder.FLAG_ONEWAY) == 0) {
                            reply?.writeNoException()
                        }
                        if (!destroyed.get()) {
                            clipChangeSeq.incrementAndGet()
                            runCatching { executor.execute { pollAndDispatchClipboard() } }
                        }
                        return true
                    }
                    return super.onTransact(code, data, reply, flags)
                }
            }

            Proxy.newProxyInstance(
                listenerClass.classLoader,
                arrayOf(listenerClass)
            ) { _, method, _ ->
                when (method.name) {
                    "asBinder" -> binder
                    "equals" -> false
                    "hashCode" -> System.identityHashCode(binder)
                    "toString" -> "DynamicPrimaryClipChangedListener"
                    else -> null
                }
            }
        }.onFailure {
            Log.w(TAG, "Unable to create IOnPrimaryClipChangedListener proxy", it)
        }.getOrNull()
    }

    private fun sha256(text: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))

    private fun isHardwareOrBandUnsupported(code: Int): Boolean =
        code == TETHER_ERROR_UNSUPPORTED ||
            code == TETHER_ERROR_UNAVAIL_IFACE ||
            code == TETHER_ERROR_TETHER_IFACE_ERROR ||
            code == TETHER_ERROR_IFACE_CFG_ERROR ||
            code == TETHER_ERROR_INTERNAL_ERROR

    private fun mapTetherError(code: Int): LinkStatus = when (code) {
        TETHER_ERROR_PROVISIONING_FAILED,
        TETHER_ERROR_ENTITLEMENT_UNKNOWN,
        TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION,
        TETHER_ERROR_NO_ACCESS_TETHERING_PERMISSION -> LinkStatus.ERR_PROVISIONING_FAILED

        TETHER_ERROR_SERVICE_UNAVAIL,
        TETHER_ERROR_UNSUPPORTED,
        TETHER_ERROR_UNAVAIL_IFACE,
        TETHER_ERROR_TETHER_IFACE_ERROR,
        TETHER_ERROR_IFACE_CFG_ERROR -> LinkStatus.ERR_WIFI_HARDWARE

        else -> LinkStatus.ERR_UNKNOWN
    }

    companion object {
        private const val TAG = "RemoteTetheringService"
        private const val SHELL_PKG = "com.android.shell"
        private val APPOP_CLIPBOARD_CANDIDATES = listOf(
            "READ_CLIPBOARD_IN_BACKGROUND",
            "READ_CLIPBOARD"
        )
        private const val COMMAND_TIMEOUT_SEC = 5L
        private val PACKAGE_NAME_REGEX = Regex("^[a-zA-Z0-9_.]+$")

        private const val TETHERING_WIFI = 0
        private const val BAND_5GHZ = 2

        private const val WIFI_AP_STATE_DISABLED = 11
        private const val WIFI_AP_STATE_FAILED = 14

        private const val TETHER_ERROR_NO_ERROR = 0
        private const val TETHER_ERROR_SERVICE_UNAVAIL = 2
        private const val TETHER_ERROR_UNSUPPORTED = 3
        private const val TETHER_ERROR_UNAVAIL_IFACE = 4
        private const val TETHER_ERROR_INTERNAL_ERROR = 5
        private const val TETHER_ERROR_TETHER_IFACE_ERROR = 6
        private const val TETHER_ERROR_IFACE_CFG_ERROR = 10
        private const val TETHER_ERROR_PROVISIONING_FAILED = 11
        private const val TETHER_ERROR_ENTITLEMENT_UNKNOWN = 13
        private const val TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION = 14
        private const val TETHER_ERROR_NO_ACCESS_TETHERING_PERMISSION = 15

        private const val MONITOR_INTERVAL_MS = 15_000L
        private const val CLIPBOARD_POLL_INTERVAL_MS = 1_000L
        private const val SCREENSHOT_FALLBACK_POLL_TICKS = 2
        private const val SCREENSHOT_EVENT_DELAY_MS = 150L
        private const val SCREENSHOT_MEDIA_RETRY_DELAY_MS = 450L
        private const val MAX_SCREENSHOT_BATCH = 24
        private const val MAX_DISPATCHED_SCREENSHOT_KEYS = 64
        private val MEDIA_IMAGES_URI: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        private val SCREENSHOT_IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "heic", "heif", "bmp")
        private val SCREENSHOT_LOCATION_TOKENS = listOf(
            "screenshot",
            "screen_shot",
            "screen-shot",
            "screencap",
            "captura",
            "截屏",
            "スクリーンショット"
        )
        private const val PIPE_CHUNK_SIZE = 16 * 1024
        private const val MAX_CLIPBOARD_STREAM_BYTES = 64L * 1024L * 1024L
        private const val AP_WARMUP_GRACE_MS = 10_000L
        private const val IDLE_AFTER_DISCONNECT_MS = 120_000L
        private const val IDLE_NEVER_CONNECTED_MS = 300_000L

        internal fun unwrapCause(t: Throwable): Throwable {
            var cur = t
            while (cur is InvocationTargetException && cur.targetException != null) {
                cur = cur.targetException!!
            }
            return cur
        }

        internal fun isStructuralTetherApiMismatch(cause: Throwable): Boolean =
            cause is NoSuchMethodException ||
                cause is ClassNotFoundException ||
                cause is IllegalArgumentException ||
                cause is IllegalAccessException ||
                cause is NoSuchFieldException

        internal fun isScreenshotDirectoryName(name: String): Boolean {
            val lower = name.trim().lowercase(Locale.ROOT)
            if (lower.isEmpty()) return false
            return SCREENSHOT_LOCATION_TOKENS.any { token -> lower.contains(token) }
        }

        internal fun isScreenshotMediaLocation(
            displayName: String?,
            relativePath: String?,
            bucketDisplayName: String?,
            dataPath: String?
        ): Boolean {
            val relLower = relativePath?.lowercase(Locale.ROOT).orEmpty()
            val bucketLower = bucketDisplayName?.lowercase(Locale.ROOT).orEmpty()
            val parentDataLower = dataPath
                ?.substringBeforeLast('/', "")
                ?.lowercase(Locale.ROOT)
                .orEmpty()
            if (SCREENSHOT_LOCATION_TOKENS.any { token ->
                    relLower.contains(token) || bucketLower.contains(token) || parentDataLower.contains(token)
                }
            ) {
                return true
            }
            val fileLower = displayName?.lowercase(Locale.ROOT).orEmpty()
            val inPicturesOrDcim = relLower.contains("pictures") ||
                relLower.contains("dcim") ||
                parentDataLower.contains("/pictures") ||
                parentDataLower.contains("/dcim")
            return inPicturesOrDcim && (
                fileLower.startsWith("screenshot") ||
                    fileLower.startsWith("screen_shot") ||
                    fileLower.startsWith("captura")
                )
        }

        internal fun isCompletedScreenshotFilename(name: String): Boolean {
            val clean = name.substringAfterLast('/').trim()
            if (clean.isEmpty() || clean.startsWith(".")) return false
            val lower = clean.lowercase(Locale.ROOT)
            if (lower.endsWith(".tmp") || lower.endsWith(".pending")) return false
            val ext = lower.substringAfterLast('.', "")
            return ext in SCREENSHOT_IMAGE_EXTENSIONS
        }

        internal fun screenshotFileDedupKey(displayName: String, sizeBytes: Long): String? {
            if (sizeBytes <= 0L) return null
            val clean = displayName.substringAfterLast('/').trim()
            if (clean.isEmpty()) return null
            return "file:$clean:$sizeBytes"
        }

        internal fun guessImageMimeFromExtension(ext: String): String? = when (ext.lowercase(Locale.ROOT)) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "heic" -> "image/heic"
            "heif" -> "image/heif"
            "bmp" -> "image/bmp"
            else -> null
        }
    }
}
