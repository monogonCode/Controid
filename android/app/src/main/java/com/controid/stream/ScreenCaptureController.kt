package com.controid.stream

import android.content.Context
import android.media.MediaFormat
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.MotionEvent
import com.controid.protocol.ControlMessage
import com.controid.protocol.Frame
import com.controid.protocol.Multiplexer
import com.controid.protocol.ProtocolConstants
import com.controid.protocol.RemoteInputEvent
import com.controid.service.IRemoteStreamCallback
import com.controid.service.IRemoteStreamService
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class StreamStatus(
    val active: Boolean = false,
    val width: Int = 0,
    val height: Int = 0,
    val fps: Int = 0,
    val bitrateMbps: Int = 0,
    val codecMime: String = MediaFormat.MIMETYPE_VIDEO_AVC,
    val lastError: String? = null
)

class ScreenCaptureController(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val serviceRef = AtomicReference<IRemoteStreamService?>(null)
    private val activeReadPipeRef = AtomicReference<ParcelFileDescriptor?>(null)
    private val activePipeJobRef = AtomicReference<Job?>(null)
    private val streamIdCounter = AtomicInteger(0x8000)
    private val stoppedSent = AtomicBoolean(true)
    private val inputConsumerStarted = AtomicBoolean(false)
    private val pendingInputCount = AtomicInteger(0)
    private val remoteInputChannel = Channel<RemoteInputEvent>(capacity = REMOTE_INPUT_QUEUE_CAPACITY)

    private val _status = MutableStateFlow(StreamStatus())
    val status: StateFlow<StreamStatus> = _status.asStateFlow()

    fun bindToStreamService(service: IRemoteStreamService?) {
        serviceRef.set(service)
        if (service == null) {
            cancelPipeReader()
            _status.value = StreamStatus()
        }
    }

    fun handleRemoteInputFrame(frame: Frame, multiplexer: Multiplexer) {
        try {
            if (frame.channelId != ProtocolConstants.CHANNEL_REMOTE_INPUT) return
            val event = runCatching {
                RemoteInputEvent.decode(frame.payload, 0, frame.payloadLength)
            }.getOrNull() ?: return
            if (serviceRef.get() == null) return
            ensureInputConsumer()
            if (event is RemoteInputEvent.Touch &&
                (event.action and MotionEvent.ACTION_MASK) == MotionEvent.ACTION_MOVE &&
                pendingInputCount.get() >= REMOTE_INPUT_MOVE_SOFT_LIMIT
            ) {
                return
            }
            if (remoteInputChannel.trySend(event).isSuccess) {
                pendingInputCount.incrementAndGet()
            }
        } finally {
            multiplexer.recycle(frame)
        }
    }

    private fun ensureInputConsumer() {
        if (!inputConsumerStarted.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            for (event in remoteInputChannel) {
                pendingInputCount.decrementAndGet()
                val service = serviceRef.get() ?: continue
                runCatching {
                    when (event) {
                        is RemoteInputEvent.Touch -> service.injectTouch(
                            event.action,
                            event.pointerId,
                            event.buttons,
                            event.xNorm,
                            event.yNorm
                        )

                        is RemoteInputEvent.Scroll -> service.injectScroll(
                            event.xNorm,
                            event.yNorm,
                            event.hScroll,
                            event.vScroll
                        )

                        is RemoteInputEvent.Key -> service.injectKey(
                            event.action,
                            event.keyCode,
                            event.metaState,
                            event.repeat
                        )

                        is RemoteInputEvent.Text -> service.injectText(event.text)
                    }
                }
            }
        }
    }

    suspend fun handleControlMessage(msg: ControlMessage, multiplexer: Multiplexer) {
        when (msg) {
            is ControlMessage.Heartbeat -> notifyHeartbeat()
            is ControlMessage.CreditGrant,
            is ControlMessage.Ack,
            is ControlMessage.DeviceStatus -> notifyHeartbeat()

            is ControlMessage.StreamReq -> {
                notifyHeartbeat()
                startStreamFromRequest(msg, multiplexer)
            }

            is ControlMessage.StreamStop -> {
                val wasActive = _status.value.active
                stopStream()
                if (wasActive && stoppedSent.compareAndSet(false, true)) {
                    runCatching { multiplexer.sendControl(ControlMessage.StreamStopped) }
                }
            }

            else -> Unit
        }
    }

    fun notifyHeartbeat() {
        val service = serviceRef.get() ?: return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runCatching { service.notifyHeartbeat() }
        } else {
            scope.launch(Dispatchers.IO) {
                runCatching { service.notifyHeartbeat() }
            }
        }
    }

    suspend fun stopStream() {
        cancelPipeReader()
        withContext(Dispatchers.IO) {
            runCatching { serviceRef.get()?.stopStream() }
        }
        _status.value = _status.value.copy(active = false)
    }

    private suspend fun startStreamFromRequest(
        req: ControlMessage.StreamReq,
        multiplexer: Multiplexer
    ) {
        val service = serviceRef.get()
        if (service == null) {
            _status.value = _status.value.copy(
                active = false,
                lastError = "RemoteStreamService not bound"
            )
            runCatching {
                multiplexer.sendControl(ControlMessage.StreamAck(status = 1, width = 0, height = 0))
            }
            return
        }

        stopStream()

        val codecMime = when (req.codec) {
            ProtocolConstants.CODEC_H265 -> MediaFormat.MIMETYPE_VIDEO_HEVC
            else -> MediaFormat.MIMETYPE_VIDEO_AVC
        }

        val pipe = withContext(Dispatchers.IO) { ParcelFileDescriptor.createPipe() }
        val readPipe = pipe[0]
        val writePipe = pipe[1]
        activeReadPipeRef.set(readPipe)
        stoppedSent.set(false)

        val callback = object : IRemoteStreamCallback.Stub() {
            override fun onStreamReady(width: Int, height: Int) {
                _status.value = StreamStatus(
                    active = true,
                    width = width,
                    height = height,
                    fps = req.maxFps,
                    bitrateMbps = req.bitrateMbps,
                    codecMime = codecMime,
                    lastError = null
                )
                scope.launch(Dispatchers.IO) {
                    runCatching {
                        multiplexer.sendControl(
                            ControlMessage.StreamAck(status = 0, width = width, height = height)
                        )
                    }
                }
            }

            override fun onStreamError(code: Int, message: String?) {
                _status.value = _status.value.copy(
                    active = false,
                    lastError = message ?: "Stream error $code"
                )
                cancelPipeReader()
                scope.launch(Dispatchers.IO) {
                    runCatching {
                        val errStatus = code.coerceIn(1, 255).toByte()
                        multiplexer.sendControl(
                            ControlMessage.StreamAck(status = errStatus, width = 0, height = 0)
                        )
                    }
                }
            }

            override fun onStreamStopped() {
                _status.value = _status.value.copy(active = false)
                cancelPipeReader()
                if (stoppedSent.compareAndSet(false, true)) {
                    scope.launch(Dispatchers.IO) {
                        runCatching { multiplexer.sendControl(ControlMessage.StreamStopped) }
                    }
                }
            }
        }

        val startError = withContext(Dispatchers.IO) {
            try {
                service.startStream(
                    req.maxFps,
                    req.bitrateMbps.coerceAtLeast(1) * 1_000_000,
                    codecMime,
                    writePipe,
                    callback
                )
                null
            } catch (t: Throwable) {
                t
            } finally {
                // Close client-side duplicate of write FD so EOF propagates when service closes its copy.
                runCatching { writePipe.close() }
            }
        }

        if (startError != null) {
            cancelPipeReader()
            _status.value = _status.value.copy(
                active = false,
                lastError = startError.message ?: "Failed to start remote stream"
            )
            runCatching {
                multiplexer.sendControl(ControlMessage.StreamAck(status = 2, width = 0, height = 0))
            }
            return
        }

        val job = scope.launch(Dispatchers.IO) {
            pumpPipeToMultiplexer(readPipe, multiplexer)
        }
        activePipeJobRef.set(job)
    }

    private suspend fun pumpPipeToMultiplexer(
        readPipe: ParcelFileDescriptor,
        multiplexer: Multiplexer
    ) {
        val streamId = nextStreamId()
        var seq = 0
        var started = false
        var failed = false
        val buffer = ByteArray(ProtocolConstants.MAX_PAYLOAD_SIZE)

        try {
            ParcelFileDescriptor.AutoCloseInputStream(readPipe).use { input ->
                while (scope.isActive) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue

                    val payload = buffer.copyOf(read)
                    val flags = if (!started) {
                        started = true
                        ProtocolConstants.FLAG_START
                    } else {
                        0.toShort()
                    }
                    multiplexer.sendFrame(
                        Frame(
                            channelId = ProtocolConstants.CHANNEL_MEDIA_STREAM,
                            streamId = streamId,
                            flags = flags,
                            sequenceNumber = seq++,
                            payload = payload
                        ),
                        highPriority = false
                    )
                }
            }
        } catch (_: CancellationException) {
            // Normal stop
        } catch (_: IOException) {
            failed = true
        } finally {
            activeReadPipeRef.compareAndSet(readPipe, null)
            runCatching { readPipe.close() }
            if (started) {
                if (failed) {
                    multiplexer.trySendUrgent(
                        Frame(
                            channelId = ProtocolConstants.CHANNEL_MEDIA_STREAM,
                            streamId = streamId,
                            flags = (ProtocolConstants.FLAG_ABORT.toInt() or
                                ProtocolConstants.FLAG_URGENT.toInt()).toShort(),
                            sequenceNumber = 0,
                            payload = Frame.EMPTY_PAYLOAD,
                            payloadLength = 0
                        )
                    )
                } else {
                    runCatching {
                        multiplexer.sendFrame(
                            Frame(
                                channelId = ProtocolConstants.CHANNEL_MEDIA_STREAM,
                                streamId = streamId,
                                flags = ProtocolConstants.FLAG_END,
                                sequenceNumber = seq,
                                payload = Frame.EMPTY_PAYLOAD,
                                payloadLength = 0
                            ),
                            highPriority = false
                        )
                    }
                }
            }
        }
    }

    private fun cancelPipeReader() {
        activePipeJobRef.getAndSet(null)?.cancel()
        activeReadPipeRef.getAndSet(null)?.let { pipe ->
            runCatching { pipe.close() }
        }
    }

    private fun nextStreamId(): Short {
        while (true) {
            val current = streamIdCounter.get()
            val next = if (current >= 0xFFFE) 0x8000 else current + 1
            if (streamIdCounter.compareAndSet(current, next)) {
                return current.toShort()
            }
        }
    }

    companion object {
        private const val REMOTE_INPUT_QUEUE_CAPACITY = 256
        private const val REMOTE_INPUT_MOVE_SOFT_LIMIT = 192
    }
}
