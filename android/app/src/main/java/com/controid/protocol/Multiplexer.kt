package com.controid.protocol

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

interface MultiplexerListener {
    suspend fun onControlMessage(msg: ControlMessage)
    suspend fun onDataFrame(frame: Frame)
    suspend fun onStreamAborted(streamId: Int, reason: String) {}
    suspend fun onDisconnected(error: Throwable?) {}
}

class Multiplexer(
    private val socket: Socket,
    parentScope: CoroutineScope,
    private val listener: MultiplexerListener,
    private val codec: ProtocolCodec = ProtocolCodec(),
    highPriorityCapacity: Int = 256,
    lowPriorityCapacity: Int = 128
) : Closeable {

    private data class StreamState(
        val channelId: Byte,
        var expectedSeq: Int,
        var lastActivityNs: Long
    )

    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val closed = AtomicBoolean(false)

    private val highPriorityChannel = Channel<Frame>(highPriorityCapacity)
    private val lowPriorityChannel = Channel<Frame>(lowPriorityCapacity)

    private val activeStreams = ConcurrentHashMap<Int, StreamState>()
    private val deadStreams = ConcurrentHashMap<Int, Long>()

    private val outboundFlows = ConcurrentHashMap<Byte, FlowController>()
    private val inboundFlows = ConcurrentHashMap<Byte, FlowController>()

    private val lastTxNs = AtomicLong(System.nanoTime())
    private val lastRxNs = AtomicLong(System.nanoTime())

    private val readerJob: Job
    private val writerJob: Job
    private val reaperJob: Job

    init {
        socket.tcpNoDelay = true

        readerJob = scope.launch(Dispatchers.IO) { runReader() }
        writerJob = scope.launch(Dispatchers.IO) { runWriter() }
        reaperJob = scope.launch(Dispatchers.IO) { runMaintenance() }
    }

    fun outboundFlowFor(channelId: Byte): FlowController =
        outboundFlows.computeIfAbsent(channelId) { FlowController() }

    private fun inboundFlowFor(channelId: Byte): FlowController =
        inboundFlows.computeIfAbsent(channelId) { FlowController() }

    suspend fun sendControl(msg: ControlMessage, urgent: Boolean = true) {
        sendFrame(Frame.ctrl(msg.encode(), urgent = urgent), highPriority = true)
    }

    suspend fun sendFrame(frame: Frame, highPriority: Boolean = isHighPriority(frame)) {
        if (closed.get()) throw IOException("Multiplexer is closed")
        if (frame.channelId == ProtocolConstants.CHANNEL_CLIP_DATA &&
            (frame.flags.toInt() and ProtocolConstants.FLAG_ABORT.toInt()) == 0
        ) {
            outboundFlowFor(frame.channelId).acquireCredit()
        }
        if (highPriority) {
            highPriorityChannel.send(frame)
        } else {
            lowPriorityChannel.send(frame)
        }
    }

    fun trySendUrgent(frame: Frame): Boolean {
        if (closed.get()) return false
        return highPriorityChannel.trySend(frame).isSuccess
    }

    fun recycle(frame: Frame) {
        codec.recycle(frame)
    }

    private fun isHighPriority(frame: Frame): Boolean =
        frame.channelId == ProtocolConstants.CHANNEL_CTRL ||
            frame.channelId == ProtocolConstants.CHANNEL_INPUT_FALLBACK ||
            frame.channelId == ProtocolConstants.CHANNEL_REMOTE_INPUT ||
            (frame.flags.toInt() and ProtocolConstants.FLAG_URGENT.toInt()) != 0

    private suspend fun runReader() {
        var disconnectError: Throwable? = null
        try {
            val input = BufferedInputStream(socket.getInputStream(), 65536)
            while (scope.isActive && !closed.get()) {
                val frame = codec.readFrame(input, usePool = true)
                lastRxNs.set(System.nanoTime())
                handleInboundFrame(frame)
            }
        } catch (_: CancellationException) {
            return
        } catch (t: Throwable) {
            disconnectError = t
        } finally {
            shutdown(disconnectError)
        }
    }

    private suspend fun handleInboundFrame(frame: Frame) {
        if (frame.channelId == ProtocolConstants.CHANNEL_CTRL && frame.streamId.toInt() == 0) {
            try {
                val msg = ControlMessage.decode(frame.payload, 0, frame.payloadLength)
                handleControlMessage(msg)
            } finally {
                codec.recycle(frame)
            }
            return
        }

        // Stateless fallback/remote input frames with streamId == 0 bypass stream state machine.
        if ((frame.channelId == ProtocolConstants.CHANNEL_INPUT_FALLBACK ||
                frame.channelId == ProtocolConstants.CHANNEL_REMOTE_INPUT) &&
            frame.streamId.toInt() == 0
        ) {
            listener.onDataFrame(frame)
            return
        }

        val sid = frame.streamId.toInt() and 0xFFFF
        val now = System.nanoTime()

        val tombstoneExpiry = deadStreams[sid]
        if (tombstoneExpiry != null) {
            if (now < tombstoneExpiry) {
                codec.recycle(frame)
                return
            }
            deadStreams.remove(sid, tombstoneExpiry)
        }

        val flags = frame.flags.toInt()
        val isStart = (flags and ProtocolConstants.FLAG_START.toInt()) != 0
        val isEnd = (flags and ProtocolConstants.FLAG_END.toInt()) != 0
        val isAbort = (flags and ProtocolConstants.FLAG_ABORT.toInt()) != 0

        if (isAbort) {
            codec.recycle(frame)
            abortStreamLocal(sid, frame.channelId, "Remote abort", notifyPeer = false)
            return
        }

        val state = activeStreams[sid]
        if (state == null) {
            if (!isStart || frame.sequenceNumber != 0) {
                codec.recycle(frame)
                abortStreamLocal(
                    sid,
                    frame.channelId,
                    "Unexpected frame without START seq=0 (got seq=${frame.sequenceNumber})",
                    notifyPeer = true
                )
                return
            }
            if (!isEnd) {
                activeStreams[sid] = StreamState(
                    channelId = frame.channelId,
                    expectedSeq = 1,
                    lastActivityNs = now
                )
            }
        } else {
            if (isStart || frame.sequenceNumber != state.expectedSeq) {
                codec.recycle(frame)
                abortStreamLocal(
                    sid,
                    frame.channelId,
                    "Out-of-order sequence: expected ${state.expectedSeq}, got ${frame.sequenceNumber}",
                    notifyPeer = true
                )
                return
            }
            state.expectedSeq = state.expectedSeq + 1
            state.lastActivityNs = now
            if (isEnd) {
                activeStreams.remove(sid)
            }
        }

        if (frame.channelId == ProtocolConstants.CHANNEL_CLIP_DATA) {
            val grant = inboundFlowFor(frame.channelId).reconcileBytes(frame.payloadLength)
            if (grant != null) {
                val creditMsg = ControlMessage.CreditGrant(
                    targetChannel = frame.channelId,
                    creditsGranted = grant.creditsGranted,
                    cumulativeBytesConsumed = grant.cumulativeBytesConsumed
                )
                highPriorityChannel.trySend(Frame.ctrl(creditMsg.encode(), urgent = true))
            }
        }

        if ((flags and ProtocolConstants.FLAG_ACK_REQ.toInt()) != 0) {
            val ack = ControlMessage.Ack(sid, frame.sequenceNumber.toLong() and 0xFFFF_FFFFL)
            highPriorityChannel.trySend(Frame.ctrl(ack.encode(), urgent = true))
        }

        listener.onDataFrame(frame)
    }

    private suspend fun handleControlMessage(msg: ControlMessage) {
        when (msg) {
            is ControlMessage.Heartbeat -> {
                if (!msg.isPong) {
                    val pong = ControlMessage.Heartbeat(
                        isPong = true,
                        timestampMs = msg.timestampMs
                    )
                    highPriorityChannel.trySend(Frame.ctrl(pong.encode(), urgent = true))
                }
            }

            is ControlMessage.CreditGrant -> {
                outboundFlowFor(msg.targetChannel).grantCredits(
                    credits = msg.creditsGranted,
                    bytesConsumed = msg.cumulativeBytesConsumed
                )
            }

            else -> Unit
        }
        listener.onControlMessage(msg)
    }

    private suspend fun abortStreamLocal(
        sid: Int,
        channelId: Byte,
        reason: String,
        notifyPeer: Boolean
    ) {
        activeStreams.remove(sid)
        deadStreams[sid] = System.nanoTime() + TOMBSTONE_TTL_NS
        if (notifyPeer) {
            val abortFrame = Frame(
                channelId = channelId,
                streamId = (sid and 0xFFFF).toShort(),
                flags = (ProtocolConstants.FLAG_ABORT.toInt() or
                    ProtocolConstants.FLAG_URGENT.toInt()).toShort(),
                sequenceNumber = 0,
                payload = Frame.EMPTY_PAYLOAD,
                payloadLength = 0
            )
            highPriorityChannel.trySend(abortFrame)
        }
        listener.onStreamAborted(sid, reason)
    }

    private suspend fun runWriter() {
        var disconnectError: Throwable? = null
        try {
            val output = BufferedOutputStream(socket.getOutputStream(), 65536)
            while (scope.isActive && !closed.get()) {
                val frame: Frame = highPriorityChannel.tryReceive().getOrNull()
                    ?: lowPriorityChannel.tryReceive().getOrNull()
                    ?: run {
                        output.flush()
                        select<Frame> {
                            highPriorityChannel.onReceive { it }
                            lowPriorityChannel.onReceive { it }
                        }
                    }

                codec.writeFrame(output, frame)
                lastTxNs.set(System.nanoTime())
            }
        } catch (_: CancellationException) {
            return
        } catch (_: ClosedReceiveChannelException) {
            return
        } catch (t: Throwable) {
            disconnectError = t
        } finally {
            shutdown(disconnectError)
        }
    }

    private suspend fun runMaintenance() {
        try {
            while (scope.isActive && !closed.get()) {
                delay(MAINTENANCE_TICK_MS)
                val now = System.nanoTime()

                val deadIt = deadStreams.entries.iterator()
                while (deadIt.hasNext()) {
                    if (now >= deadIt.next().value) {
                        deadIt.remove()
                    }
                }

                val activeIt = activeStreams.entries.iterator()
                while (activeIt.hasNext()) {
                    val entry = activeIt.next()
                    if (now - entry.value.lastActivityNs >= INACTIVE_STREAM_TIMEOUT_NS) {
                        val sid = entry.key
                        val ch = entry.value.channelId
                        activeIt.remove()
                        deadStreams[sid] = now + TOMBSTONE_TTL_NS
                        highPriorityChannel.trySend(
                            Frame(
                                channelId = ch,
                                streamId = (sid and 0xFFFF).toShort(),
                                flags = (ProtocolConstants.FLAG_ABORT.toInt() or
                                    ProtocolConstants.FLAG_URGENT.toInt()).toShort(),
                                sequenceNumber = 0,
                                payload = Frame.EMPTY_PAYLOAD,
                                payloadLength = 0
                            )
                        )
                        listener.onStreamAborted(sid, "Stream inactive for 10s")
                    }
                }

                if (now - lastTxNs.get() >= HEARTBEAT_INTERVAL_NS) {
                    val ping = ControlMessage.Heartbeat(
                        isPong = false,
                        timestampMs = System.currentTimeMillis() and 0xFFFF_FFFFL
                    )
                    highPriorityChannel.trySend(Frame.ctrl(ping.encode(), urgent = true))
                }
            }
        } catch (_: CancellationException) {
            // Normal shutdown
        }
    }

    private suspend fun shutdown(error: Throwable?) {
        if (!closed.compareAndSet(false, true)) return
        highPriorityChannel.close()
        lowPriorityChannel.close()
        try {
            socket.close()
        } catch (_: IOException) {
        }
        try {
            listener.onDisconnected(error)
        } finally {
            scope.cancel()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        highPriorityChannel.close()
        lowPriorityChannel.close()
        try {
            socket.close()
        } catch (_: IOException) {
        }
        scope.cancel()
    }

    companion object {
        private const val MAINTENANCE_TICK_MS = 1_000L
        private const val HEARTBEAT_INTERVAL_NS =
            ProtocolConstants.HEARTBEAT_INTERVAL_MS * 1_000_000L
        private const val TOMBSTONE_TTL_NS =
            ProtocolConstants.STREAM_TOMBSTONE_TTL_MS * 1_000_000L
        private const val INACTIVE_STREAM_TIMEOUT_NS =
            ProtocolConstants.STREAM_INACTIVE_TIMEOUT_MS * 1_000_000L
    }
}
