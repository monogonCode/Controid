package com.controid.files

import android.system.OsConstants
import com.controid.protocol.Frame
import com.controid.protocol.Multiplexer
import com.controid.protocol.ProtocolConstants
import com.controid.service.ShizukuBridge
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class FileRpcController(
    private val shizukuBridge: ShizukuBridge,
    private val scope: CoroutineScope
) {
    private val pendingStreams = ConcurrentHashMap<Int, ByteArrayOutputStream>()
    private val localFallbackHandler by lazy { RemoteFileSystemHandler() }

    suspend fun handleIncomingFrame(frame: Frame, multiplexer: Multiplexer) {
        try {
            if (frame.channelId != ProtocolConstants.CHANNEL_FILES) return

            val rawStreamId = frame.streamId
            val sid = rawStreamId.toInt() and 0xFFFF
            val flags = frame.flags.toInt()
            val isStart = (flags and ProtocolConstants.FLAG_START.toInt()) != 0
            val isEnd = (flags and ProtocolConstants.FLAG_END.toInt()) != 0
            val isAbort = (flags and ProtocolConstants.FLAG_ABORT.toInt()) != 0

            if (isAbort) {
                pendingStreams.remove(sid)
                return
            }

            val reqBytes: ByteArray? = when {
                isStart && isEnd -> {
                    pendingStreams.remove(sid)
                    if (frame.payloadLength > MAX_RPC_BYTES) {
                        sendAbort(multiplexer, rawStreamId)
                        null
                    } else {
                        frame.payload.copyOf(frame.payloadLength)
                    }
                }

                isStart -> {
                    if (frame.payloadLength > MAX_RPC_BYTES) {
                        pendingStreams.remove(sid)
                        sendAbort(multiplexer, rawStreamId)
                        null
                    } else {
                        val baos = ByteArrayOutputStream(frame.payloadLength.coerceAtLeast(256))
                        if (frame.payloadLength > 0) {
                            baos.write(frame.payload, 0, frame.payloadLength)
                        }
                        pendingStreams[sid] = baos
                        null
                    }
                }

                else -> {
                    val baos = pendingStreams[sid] ?: return
                    if (baos.size() + frame.payloadLength > MAX_RPC_BYTES) {
                        pendingStreams.remove(sid)
                        sendAbort(multiplexer, rawStreamId)
                        null
                    } else {
                        if (frame.payloadLength > 0) {
                            baos.write(frame.payload, 0, frame.payloadLength)
                        }
                        if (isEnd) {
                            pendingStreams.remove(sid)
                            baos.toByteArray()
                        } else {
                            null
                        }
                    }
                }
            }

            if (reqBytes != null) {
                scope.launch(Dispatchers.IO) {
                    val respBytes = try {
                        shizukuBridge.executeFileRpc(reqBytes)
                            ?: localFallbackHandler.handleRpc(reqBytes)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Throwable) {
                        RemoteFileSystemHandler.errorResponse(OsConstants.EIO)
                    }
                    sendResponse(multiplexer, rawStreamId, respBytes)
                }
            }
        } finally {
            multiplexer.recycle(frame)
        }
    }

    fun abortStream(streamId: Int) {
        pendingStreams.remove(streamId and 0xFFFF)
    }

    fun reset() {
        pendingStreams.clear()
    }

    private suspend fun sendResponse(
        multiplexer: Multiplexer,
        streamId: Short,
        resp: ByteArray
    ) {
        val maxChunk = ProtocolConstants.MAX_PAYLOAD_SIZE
        if (resp.isEmpty()) {
            val frame = Frame(
                channelId = ProtocolConstants.CHANNEL_FILES,
                streamId = streamId,
                flags = (ProtocolConstants.FLAG_START.toInt() or ProtocolConstants.FLAG_END.toInt()).toShort(),
                sequenceNumber = 0,
                payload = Frame.EMPTY_PAYLOAD,
                payloadLength = 0
            )
            runCatching { multiplexer.sendFrame(frame) }
            return
        }

        var offset = 0
        var seq = 0
        while (offset < resp.size) {
            val end = (offset + maxChunk).coerceAtMost(resp.size)
            val isFirst = offset == 0
            val isLast = end >= resp.size
            var flags = 0
            if (isFirst) flags = flags or ProtocolConstants.FLAG_START.toInt()
            if (isLast) flags = flags or ProtocolConstants.FLAG_END.toInt()

            val chunk = resp.copyOfRange(offset, end)
            offset = end

            val frame = Frame(
                channelId = ProtocolConstants.CHANNEL_FILES,
                streamId = streamId,
                flags = flags.toShort(),
                sequenceNumber = seq++,
                payload = chunk
            )
            try {
                multiplexer.sendFrame(frame)
            } catch (_: Exception) {
                sendAbort(multiplexer, streamId)
                return
            }
        }
    }

    private fun sendAbort(multiplexer: Multiplexer, streamId: Short) {
        multiplexer.trySendUrgent(
            Frame(
                channelId = ProtocolConstants.CHANNEL_FILES,
                streamId = streamId,
                flags = (ProtocolConstants.FLAG_ABORT.toInt() or
                    ProtocolConstants.FLAG_URGENT.toInt()).toShort(),
                sequenceNumber = 0,
                payload = Frame.EMPTY_PAYLOAD,
                payloadLength = 0
            )
        )
    }

    companion object {
        private const val MAX_RPC_BYTES = 1024 * 1024
    }
}
