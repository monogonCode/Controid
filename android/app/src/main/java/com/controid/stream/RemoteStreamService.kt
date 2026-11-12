package com.controid.stream

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import com.controid.service.IRemoteStreamCallback
import com.controid.service.IRemoteStreamService
import java.io.IOException
import java.lang.reflect.Method
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.exitProcess

class RemoteStreamService : IRemoteStreamService.Stub() {

    private val stateLock = Any()
    private val lifecycleLock = Any()
    private val inputInjectorLock = Any()
    private val touchLock = Any()
    private val keyLock = Any()
    private val streaming = AtomicBoolean(false)
    private val lastHeartbeatNs = AtomicLong(0L)
    private val activePointers = LinkedHashMap<Int, Pair<Float, Float>>()
    private val keyDownTimes = HashMap<Int, Long>()

    @Volatile
    private var cachedInputInjector: InputInjector? = null

    @Volatile
    private var cachedDisplayMetrics: CachedDisplayMetrics? = null

    @Volatile
    private var touchDownTimeMs: Long = 0L

    private var activeSession: StreamSession? = null
    private var watchdogExecutor: ScheduledExecutorService? = null
    private var watchdogFuture: ScheduledFuture<*>? = null

    override fun startStream(
        maxFps: Int,
        bitrateBps: Int,
        codecMime: String?,
        outputPipe: ParcelFileDescriptor?,
        callback: IRemoteStreamCallback?
    ) {
        synchronized(lifecycleLock) {
            if (outputPipe == null) {
                runCatching { callback?.onStreamError(ERR_INVALID_ARGS, "outputPipe is null") }
                return@synchronized
            }

            stopStreamInternal(notifyStopped = false)

            val mime = codecMime?.takeIf { it.isNotBlank() } ?: MediaFormat.MIMETYPE_VIDEO_AVC
            val fps = maxFps.coerceIn(1, 240)
            val bitrate = bitrateBps.coerceIn(500_000, 100_000_000)

            val displayMetrics = queryDisplayMetrics(forceRefresh = true)
            val encWidth = (displayMetrics.width and 1.inv()).coerceAtLeast(2)
            val encHeight = (displayMetrics.height and 1.inv()).coerceAtLeast(2)

            var encoder: MediaCodec? = null
            var inputSurface: Surface? = null
            var mirrorHandle: DisplayMirrorHandle? = null

            try {
                encoder = MediaCodec.createEncoderByType(mime)
                configureEncoder(encoder, mime, encWidth, encHeight, fps, bitrate)
                inputSurface = encoder.createInputSurface()

                mirrorHandle = createDisplayMirror(
                    inputSurface = inputSurface,
                    rawWidth = displayMetrics.width,
                    rawHeight = displayMetrics.height,
                    encWidth = encWidth,
                    encHeight = encHeight,
                    layerStack = displayMetrics.layerStack,
                    densityDpi = displayMetrics.densityDpi
                )

                encoder.start()
            } catch (t: Throwable) {
                runCatching { mirrorHandle?.release() }
                runCatching { inputSurface?.release() }
                runCatching { encoder?.release() }
                runCatching { outputPipe.close() }
                runCatching {
                    callback?.onStreamError(ERR_ENCODER_INIT, t.message ?: t.javaClass.simpleName)
                }
                return@synchronized
            }

            val session = StreamSession(
                encoder = encoder,
                inputSurface = inputSurface,
                mirrorHandle = mirrorHandle,
                outputPipe = outputPipe,
                callback = callback
            )

            synchronized(stateLock) {
                activeSession = session
                lastHeartbeatNs.set(System.nanoTime())
                streaming.set(true)
                startWatchdogLocked()
            }

            val drainThread = Thread(
                { runEncoderDrainLoop(session) },
                "Controid-StreamDrain"
            ).apply {
                isDaemon = true
            }
            session.drainThread = drainThread
            drainThread.start()

            runCatching { callback?.onStreamReady(encWidth, encHeight) }
        }
    }

    override fun notifyHeartbeat() {
        lastHeartbeatNs.set(System.nanoTime())
    }

    override fun stopStream() {
        stopStreamInternal(notifyStopped = true)
    }

    override fun injectTouch(
        action: Int,
        pointerId: Int,
        buttons: Int,
        xNorm: Float,
        yNorm: Float
    ) {
        val metrics = queryDisplayMetrics()
        val nx = if (xNorm.isFinite()) xNorm.coerceIn(0f, 1f) else 0f
        val ny = if (yNorm.isFinite()) yNorm.coerceIn(0f, 1f) else 0f
        val px = nx * metrics.width.toFloat()
        val py = ny * metrics.height.toFloat()
        val eventTime = SystemClock.uptimeMillis()
        val baseAction = action and MotionEvent.ACTION_MASK

        synchronized(touchLock) {
            val mappedAction: Int
            var removeAfter = false
            var clearAfter = false

            when (baseAction) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (!activePointers.containsKey(pointerId)) {
                        val wasEmpty = activePointers.isEmpty()
                        if (wasEmpty) {
                            touchDownTimeMs = eventTime
                        }
                        activePointers[pointerId] = px to py
                        val index = activePointers.keys.indexOf(pointerId)
                        mappedAction = if (wasEmpty) {
                            MotionEvent.ACTION_DOWN
                        } else {
                            MotionEvent.ACTION_POINTER_DOWN or
                                (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                        }
                    } else {
                        activePointers[pointerId] = px to py
                        if (activePointers.size == 1) {
                            touchDownTimeMs = eventTime
                            mappedAction = MotionEvent.ACTION_DOWN
                        } else {
                            mappedAction = MotionEvent.ACTION_MOVE
                        }
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!activePointers.containsKey(pointerId)) return
                    activePointers[pointerId] = px to py
                    mappedAction = MotionEvent.ACTION_MOVE
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_POINTER_UP -> {
                    if (!activePointers.containsKey(pointerId)) return
                    activePointers[pointerId] = px to py
                    val index = activePointers.keys.indexOf(pointerId)
                    mappedAction = if (activePointers.size == 1) {
                        MotionEvent.ACTION_UP
                    } else {
                        MotionEvent.ACTION_POINTER_UP or
                            (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                    }
                    removeAfter = true
                }

                MotionEvent.ACTION_CANCEL -> {
                    if (activePointers.isEmpty()) return
                    if (activePointers.containsKey(pointerId)) {
                        activePointers[pointerId] = px to py
                    }
                    mappedAction = MotionEvent.ACTION_CANCEL
                    clearAfter = true
                }

                else -> return
            }

            val pointerCount = activePointers.size
            if (pointerCount == 0) return

            val pointerProperties = Array(pointerCount) { MotionEvent.PointerProperties() }
            val pointerCoords = Array(pointerCount) { MotionEvent.PointerCoords() }
            var idx = 0
            for ((id, pos) in activePointers) {
                pointerProperties[idx].id = id
                pointerProperties[idx].toolType = MotionEvent.TOOL_TYPE_FINGER
                pointerCoords[idx].x = pos.first
                pointerCoords[idx].y = pos.second
                pointerCoords[idx].pressure = 1f
                pointerCoords[idx].size = 1f
                idx++
            }

            val downTime = if (touchDownTimeMs > 0L) touchDownTimeMs else eventTime
            val event = MotionEvent.obtain(
                downTime,
                eventTime,
                mappedAction,
                pointerCount,
                pointerProperties,
                pointerCoords,
                0,
                buttons,
                1f,
                1f,
                0,
                0,
                InputDevice.SOURCE_TOUCHSCREEN,
                0
            )
            try {
                injectInputEvent(event)
            } finally {
                event.recycle()
                if (clearAfter) {
                    activePointers.clear()
                    touchDownTimeMs = 0L
                } else if (removeAfter) {
                    activePointers.remove(pointerId)
                    if (activePointers.isEmpty()) {
                        touchDownTimeMs = 0L
                    }
                }
            }
        }
    }

    override fun injectScroll(xNorm: Float, yNorm: Float, hScroll: Float, vScroll: Float) {
        val metrics = queryDisplayMetrics()
        val nx = if (xNorm.isFinite()) xNorm.coerceIn(0f, 1f) else 0.5f
        val ny = if (yNorm.isFinite()) yNorm.coerceIn(0f, 1f) else 0.5f
        val px = nx * metrics.width.toFloat()
        val py = ny * metrics.height.toFloat()
        val eventTime = SystemClock.uptimeMillis()

        val pointerProperties = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_MOUSE
            }
        )
        val pointerCoords = arrayOf(
            MotionEvent.PointerCoords().apply {
                x = px
                y = py
                setAxisValue(MotionEvent.AXIS_HSCROLL, if (hScroll.isFinite()) hScroll else 0f)
                setAxisValue(MotionEvent.AXIS_VSCROLL, if (vScroll.isFinite()) vScroll else 0f)
            }
        )

        val event = MotionEvent.obtain(
            eventTime,
            eventTime,
            MotionEvent.ACTION_SCROLL,
            1,
            pointerProperties,
            pointerCoords,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_MOUSE,
            0
        )
        try {
            injectInputEvent(event)
        } finally {
            event.recycle()
        }
    }

    override fun injectKey(action: Int, keyCode: Int, metaState: Int, repeat: Int) {
        val eventTime = SystemClock.uptimeMillis()
        val downTime = synchronized(keyLock) {
            when (action) {
                KeyEvent.ACTION_DOWN -> {
                    if (repeat == 0) {
                        keyDownTimes[keyCode] = eventTime
                        eventTime
                    } else {
                        keyDownTimes.getOrPut(keyCode) { eventTime }
                    }
                }

                KeyEvent.ACTION_UP -> keyDownTimes.remove(keyCode) ?: eventTime
                else -> keyDownTimes[keyCode] ?: eventTime
            }
        }
        val event = KeyEvent(
            downTime,
            eventTime,
            action,
            keyCode,
            repeat,
            metaState,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            0,
            0,
            InputDevice.SOURCE_KEYBOARD
        )
        injectInputEvent(event)
    }

    override fun injectText(text: String?) {
        if (text.isNullOrEmpty()) return
        val kcm = runCatching {
            KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        }.getOrNull() ?: return

        val events = runCatching { kcm.getEvents(text.toCharArray()) }.getOrNull()
        if (events != null) {
            for (event in events) {
                if (event != null) {
                    injectInputEvent(event)
                }
            }
            return
        }

        val single = CharArray(1)
        for (ch in text) {
            single[0] = ch
            val charEvents = runCatching { kcm.getEvents(single) }.getOrNull() ?: continue
            for (event in charEvents) {
                if (event != null) {
                    injectInputEvent(event)
                }
            }
        }
    }

    override fun destroy() {
        stopStreamInternal(notifyStopped = false)
        synchronized(stateLock) {
            watchdogFuture?.cancel(true)
            watchdogFuture = null
            watchdogExecutor?.shutdownNow()
            watchdogExecutor = null
        }
        val uid = runCatching { Process.myUid() }.getOrDefault(-1)
        if (uid == 2000 || uid == 0) {
            exitProcess(0)
        }
    }

    private fun startWatchdogLocked() {
        val executor = watchdogExecutor ?: Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "Controid-StreamWatchdog").apply { isDaemon = true }
        }.also { watchdogExecutor = it }

        watchdogFuture?.cancel(false)
        watchdogFuture = executor.scheduleWithFixedDelay(
            {
                if (!streaming.get()) return@scheduleWithFixedDelay
                val elapsedNs = System.nanoTime() - lastHeartbeatNs.get()
                if (elapsedNs > WATCHDOG_TIMEOUT_NS) {
                    stopStreamInternal(notifyStopped = true)
                }
            },
            WATCHDOG_CHECK_INTERVAL_MS,
            WATCHDOG_CHECK_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        )
    }

    private fun stopStreamInternal(notifyStopped: Boolean) {
        var stoppedSession: StreamSession? = null
        synchronized(lifecycleLock) {
            synchronized(stateLock) {
                streaming.set(false)
                watchdogFuture?.cancel(false)
                watchdogFuture = null
                stoppedSession = activeSession
                activeSession = null
            }

            val session = stoppedSession ?: return@synchronized

            runCatching { session.outputPipe.close() }

            val drain = session.drainThread
            if (drain != null && Thread.currentThread() !== drain) {
                runCatching { drain.join(DRAIN_JOIN_TIMEOUT_MS) }
            }

            session.releaseDisplayAndSurface()
            if (drain == null || !drain.isAlive) {
                session.releaseCodec()
            }
        }

        if (notifyStopped) {
            runCatching { stoppedSession?.callback?.onStreamStopped() }
        }
    }

    private fun runEncoderDrainLoop(session: StreamSession) {
        val encoder = session.encoder
        val bufferInfo = MediaCodec.BufferInfo()
        var scratch = ByteArray(65_536)

        try {
            ParcelFileDescriptor.AutoCloseOutputStream(session.outputPipe).use { outStream ->
                while (streaming.get()) {
                    val outIndex = encoder.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
                    if (outIndex < 0) continue

                    try {
                        val outBuf = encoder.getOutputBuffer(outIndex)
                        if (outBuf != null && bufferInfo.size > 0) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)

                            if (scratch.size < bufferInfo.size) {
                                scratch = ByteArray(bufferInfo.size)
                            }
                            outBuf.get(scratch, 0, bufferInfo.size)

                            if (!hasAnnexBStartCode(scratch, bufferInfo.size)) {
                                outStream.write(ANNEX_B_START_CODE)
                            }
                            outStream.write(scratch, 0, bufferInfo.size)
                            outStream.flush()
                        }
                    } finally {
                        encoder.releaseOutputBuffer(outIndex, false)
                    }

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break
                    }
                }
            }
        } catch (_: IOException) {
            // Pipe closed by peer/watchdog
        } catch (t: Throwable) {
            if (streaming.get()) {
                runCatching {
                    session.callback?.onStreamError(
                        ERR_ENCODER_RUNTIME,
                        t.message ?: t.javaClass.simpleName
                    )
                }
            }
        } finally {
            session.releaseCodec()
            session.releaseDisplayAndSurface()
            val shouldNotify = streaming.compareAndSet(true, false)
            if (shouldNotify) {
                synchronized(stateLock) {
                    if (activeSession === session) {
                        activeSession = null
                    }
                    watchdogFuture?.cancel(false)
                    watchdogFuture = null
                }
                runCatching { session.callback?.onStreamStopped() }
            }
        }
    }

    private fun hasAnnexBStartCode(data: ByteArray, length: Int): Boolean {
        if (length >= 4 &&
            data[0] == 0.toByte() &&
            data[1] == 0.toByte() &&
            data[2] == 0.toByte() &&
            data[3] == 1.toByte()
        ) {
            return true
        }
        return length >= 3 &&
            data[0] == 0.toByte() &&
            data[1] == 0.toByte() &&
            data[2] == 1.toByte()
    }

    private fun configureEncoder(
        encoder: MediaCodec,
        mime: String,
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int
    ) {
        val lowLatencyFormat = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger("latency", 0)
            setInteger("max-bframes", 0)
            setInteger("priority", 0)
            setLong("repeat-previous-frame-after", 100_000L)
        }

        try {
            encoder.configure(lowLatencyFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (_: Exception) {
            encoder.reset()
            val baseFormat = MediaFormat.createVideoFormat(mime, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            encoder.configure(baseFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
    }

    @SuppressLint("PrivateApi")
    private fun queryDisplayMetrics(forceRefresh: Boolean = false): DisplayMetricsInfo {
        val nowNs = System.nanoTime()
        val cached = cachedDisplayMetrics
        if (!forceRefresh && cached != null && (nowNs - cached.fetchedAtNs) < DISPLAY_METRICS_TTL_NS) {
            return cached.info
        }

        val fresh = runCatching {
            val dmgClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
            val dmg = dmgClass.getMethod("getInstance").invoke(null)
            ?: throw IllegalStateException("DisplayManagerGlobal.getInstance returned null")
            val info = dmgClass.getMethod("getDisplayInfo", Int::class.javaPrimitiveType)
                .invoke(dmg, 0) ?: return@runCatching DisplayMetricsInfo()

            val infoClass = info.javaClass
            val width = infoClass.getField("logicalWidth").getInt(info)
            val height = infoClass.getField("logicalHeight").getInt(info)
            val layerStack = runCatching {
                infoClass.getField("layerStack").getInt(info)
            }.getOrDefault(0)
            val densityDpi = runCatching {
                infoClass.getField("logicalDensityDpi").getInt(info)
            }.getOrDefault(320)

            DisplayMetricsInfo(
                width = if (width > 0) width else 1080,
                height = if (height > 0) height else 1920,
                layerStack = layerStack,
                densityDpi = if (densityDpi > 0) densityDpi else 320
            )
        }.getOrDefault(DisplayMetricsInfo())

        cachedDisplayMetrics = CachedDisplayMetrics(fresh, System.nanoTime())
        return fresh
    }

    @SuppressLint("PrivateApi")
    private fun resolveInputInjector(): InputInjector? {
        cachedInputInjector?.let { return it }
        synchronized(inputInjectorLock) {
            cachedInputInjector?.let { return it }
            val resolved = tryResolveInjector("android.hardware.input.InputManagerGlobal")
                ?: tryResolveInjector("android.hardware.input.InputManager")
            cachedInputInjector = resolved
            return resolved
        }
    }

    @SuppressLint("PrivateApi")
    private fun tryResolveInjector(className: String): InputInjector? = runCatching {
        val cls = Class.forName(className)
        val instance = cls.getMethod("getInstance").invoke(null) ?: return@runCatching null
        val method = cls.getMethod(
            "injectInputEvent",
            InputEvent::class.java,
            Int::class.javaPrimitiveType
        )
        InputInjector(instance, method)
    }.getOrNull()

    private fun injectInputEvent(event: InputEvent): Boolean {
        val injector = resolveInputInjector() ?: return false
        return runCatching {
            injector.method.invoke(
                injector.instance,
                event,
                INJECT_INPUT_EVENT_MODE_ASYNC
            ) as? Boolean ?: true
        }.getOrDefault(false)
    }

    private fun createDisplayMirror(
        inputSurface: Surface,
        rawWidth: Int,
        rawHeight: Int,
        encWidth: Int,
        encHeight: Int,
        layerStack: Int,
        densityDpi: Int
    ): DisplayMirrorHandle {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val scHandle = runCatching {
                createSurfaceControlDisplay(
                    inputSurface = inputSurface,
                    rawWidth = rawWidth,
                    rawHeight = rawHeight,
                    encWidth = encWidth,
                    encHeight = encHeight,
                    layerStack = layerStack
                )
            }.getOrNull()
            if (scHandle != null) return scHandle
        }

        return createVirtualDisplayViaDmg(
            inputSurface = inputSurface,
            encWidth = encWidth,
            encHeight = encHeight,
            densityDpi = densityDpi
        )
    }

    @SuppressLint("PrivateApi")
    private fun createSurfaceControlDisplay(
        inputSurface: Surface,
        rawWidth: Int,
        rawHeight: Int,
        encWidth: Int,
        encHeight: Int,
        layerStack: Int
    ): DisplayMirrorHandle {
        val scClass = Class.forName("android.view.SurfaceControl")
        val createDisplayMethod = scClass.getMethod(
            "createDisplay",
            String::class.java,
            Boolean::class.javaPrimitiveType
        )
        val displayToken = try {
            createDisplayMethod.invoke(null, "Controid", true) as IBinder
        } catch (_: Exception) {
            createDisplayMethod.invoke(null, "Controid", false) as IBinder
        }

        val destroyDisplayMethod = scClass.getMethod("destroyDisplay", IBinder::class.java)
        try {
            val physicalToken = resolvePhysicalDisplayToken(scClass)
            val openTx = scClass.getMethod("openTransaction")
            val closeTx = scClass.getMethod("closeTransaction")
            val setSurface = scClass.getMethod(
                "setDisplaySurface",
                IBinder::class.java,
                Surface::class.java
            )
            val setProjection = scClass.getMethod(
                "setDisplayProjection",
                IBinder::class.java,
                Int::class.javaPrimitiveType,
                Rect::class.java,
                Rect::class.java
            )
            val setLayerStack = scClass.getMethod(
                "setDisplayLayerStack",
                IBinder::class.java,
                Int::class.javaPrimitiveType
            )

            openTx.invoke(null)
            try {
                setSurface.invoke(null, displayToken, inputSurface)
                setProjection.invoke(
                    null,
                    displayToken,
                    0,
                    Rect(0, 0, rawWidth, rawHeight),
                    Rect(0, 0, encWidth, encHeight)
                )
                setLayerStack.invoke(null, displayToken, layerStack)
            } finally {
                closeTx.invoke(null)
            }

            if (physicalToken == null) {
                // Physical token not required by setDisplaySurface, kept for API validation
            }
            return DisplayMirrorHandle {
                runCatching { destroyDisplayMethod.invoke(null, displayToken) }
            }
        } catch (t: Throwable) {
            runCatching { destroyDisplayMethod.invoke(null, displayToken) }
            throw t
        }
    }

    @SuppressLint("PrivateApi")
    private fun resolvePhysicalDisplayToken(scClass: Class<*>): IBinder? {
        runCatching {
            val getInternal = scClass.getMethod("getInternalDisplayToken")
            (getInternal.invoke(null) as? IBinder)?.let { return it }
        }
        runCatching {
            val getIds = scClass.getMethod("getPhysicalDisplayIds")
            val ids = getIds.invoke(null) as? LongArray
            if (ids != null && ids.isNotEmpty()) {
                val getToken = scClass.getMethod(
                    "getPhysicalDisplayToken",
                    Long::class.javaPrimitiveType
                )
                (getToken.invoke(null, ids[0]) as? IBinder)?.let { return it }
            }
        }
        runCatching {
            val getBuiltIn = scClass.getMethod("getBuiltInDisplay", Int::class.javaPrimitiveType)
            (getBuiltIn.invoke(null, 0) as? IBinder)?.let { return it }
        }
        return null
    }

    @SuppressLint("PrivateApi")
    private fun createVirtualDisplayViaDmg(
        inputSurface: Surface,
        encWidth: Int,
        encHeight: Int,
        densityDpi: Int
    ): DisplayMirrorHandle {
        val dmgClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
        val dmg = dmgClass.getMethod("getInstance").invoke(null)
            ?: throw IllegalStateException("DisplayManagerGlobal.getInstance returned null")
        val context = resolveShellContext()

        val flagsWithSecure = VIRTUAL_DISPLAY_FLAG_PUBLIC or
            VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or
            VIRTUAL_DISPLAY_FLAG_SECURE
        val flagsNoSecure = VIRTUAL_DISPLAY_FLAG_PUBLIC or VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR

        val virtualDisplay = try {
            invokeCreateVirtualDisplay(dmgClass, dmg, context, inputSurface, encWidth, encHeight, densityDpi, flagsWithSecure)
        } catch (_: Exception) {
            invokeCreateVirtualDisplay(dmgClass, dmg, context, inputSurface, encWidth, encHeight, densityDpi, flagsNoSecure)
        }

        val releaseMethod = virtualDisplay.javaClass.getMethod("release")
        return DisplayMirrorHandle {
            runCatching { releaseMethod.invoke(virtualDisplay) }
        }
    }

    @SuppressLint("PrivateApi")
    private fun invokeCreateVirtualDisplay(
        dmgClass: Class<*>,
        dmg: Any,
        context: Context,
        inputSurface: Surface,
        width: Int,
        height: Int,
        densityDpi: Int,
        flags: Int
    ): Any {
        val configClass = Class.forName("android.hardware.display.VirtualDisplayConfig")
        val builderClass = Class.forName("android.hardware.display.VirtualDisplayConfig\$Builder")
        val builder = builderClass.getConstructor(
            String::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        ).newInstance("ControidMirror", width, height, densityDpi)

        builderClass.getMethod("setSurface", Surface::class.java).invoke(builder, inputSurface)
        builderClass.getMethod("setFlags", Int::class.javaPrimitiveType).invoke(builder, flags)
        runCatching {
            builderClass.getMethod("setDisplayIdToMirror", Int::class.javaPrimitiveType)
                .invoke(builder, 0)
        }
        val config = builderClass.getMethod("build").invoke(builder)

        val method: Method = dmgClass.methods.firstOrNull { m ->
            m.name == "createVirtualDisplay" &&
                m.parameterCount == 5 &&
                m.parameterTypes[0] == Context::class.java &&
                m.parameterTypes[2] == configClass
        } ?: dmgClass.methods.firstOrNull { m ->
            m.name == "createVirtualDisplay" && m.parameterTypes.any { it == configClass }
        } ?: throw NoSuchMethodException("DisplayManagerGlobal.createVirtualDisplay not found")

        val invokeArgs = Array<Any?>(method.parameterCount) { idx ->
            val pType = method.parameterTypes[idx]
            when {
                pType == Context::class.java -> context
                pType == configClass -> config
                Executor::class.java.isAssignableFrom(pType) -> Executor { it.run() }
                else -> null
            }
        }
        return method.invoke(dmg, *invokeArgs)
            ?: throw IllegalStateException("createVirtualDisplay returned null")
    }

    @SuppressLint("PrivateApi")
    private fun resolveShellContext(): Context {
        val atClass = Class.forName("android.app.ActivityThread")
        val at = runCatching { atClass.getMethod("currentActivityThread").invoke(null) }.getOrNull()
            ?: atClass.getMethod("systemMain").invoke(null)
        val baseContext = (runCatching { atClass.getMethod("getSystemContext").invoke(at) }.getOrNull()
            ?: atClass.getMethod("getApplication").invoke(at)) as Context
        val uid = runCatching { Process.myUid() }.getOrDefault(-1)
        val pkg = if (uid == 2000) "com.android.shell" else baseContext.packageName
        return object : ContextWrapper(baseContext) {
            override fun getPackageName(): String = pkg
            override fun getOpPackageName(): String = pkg
        }
    }

    private data class DisplayMetricsInfo(
        val width: Int = 1080,
        val height: Int = 1920,
        val layerStack: Int = 0,
        val densityDpi: Int = 320
    )

    private data class CachedDisplayMetrics(
        val info: DisplayMetricsInfo,
        val fetchedAtNs: Long
    )

    private data class InputInjector(
        val instance: Any,
        val method: Method
    )

    private fun interface DisplayMirrorHandle {
        fun release()
    }

    private class StreamSession(
        val encoder: MediaCodec,
        val inputSurface: Surface,
        val mirrorHandle: DisplayMirrorHandle,
        val outputPipe: ParcelFileDescriptor,
        val callback: IRemoteStreamCallback?
    ) {
        @Volatile
        var drainThread: Thread? = null
        private val codecReleased = AtomicBoolean(false)
        private val displayReleased = AtomicBoolean(false)

        fun releaseCodec() {
            if (!codecReleased.compareAndSet(false, true)) return
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
        }

        fun releaseDisplayAndSurface() {
            if (!displayReleased.compareAndSet(false, true)) return
            runCatching { mirrorHandle.release() }
            runCatching { inputSurface.release() }
        }
    }

    companion object {
        private const val WATCHDOG_TIMEOUT_NS = 5_000_000_000L
        private const val WATCHDOG_CHECK_INTERVAL_MS = 500L
        private const val DEQUEUE_TIMEOUT_US = 10_000L
        private const val DRAIN_JOIN_TIMEOUT_MS = 1_500L
        private const val DISPLAY_METRICS_TTL_NS = 250_000_000L
        private const val INJECT_INPUT_EVENT_MODE_ASYNC = 0

        private const val ERR_INVALID_ARGS = 1
        private const val ERR_ENCODER_INIT = 2
        private const val ERR_ENCODER_RUNTIME = 3

        private const val VIRTUAL_DISPLAY_FLAG_PUBLIC = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
        private const val VIRTUAL_DISPLAY_FLAG_SECURE = DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE
        private const val VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR

        private val ANNEX_B_START_CODE = byteArrayOf(0x00, 0x00, 0x00, 0x01)
    }
}
