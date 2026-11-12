package com.controid.protocol

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

class ProtocolException(message: String, cause: Throwable? = null) : IOException(message, cause)

class BufferPool(
    val bufferSize: Int = ProtocolConstants.MAX_PAYLOAD_SIZE,
    private val maxPooled: Int = 64
) {
    private val byteArrays = ConcurrentLinkedQueue<ByteArray>()
    private val arrayCount = AtomicInteger(0)

    private val byteBuffers = ConcurrentLinkedQueue<ByteBuffer>()
    private val bufferCount = AtomicInteger(0)

    fun acquire(minSize: Int = bufferSize): ByteArray {
        if (minSize == 0) return Frame.EMPTY_PAYLOAD
        if (minSize < 0 || minSize > bufferSize) {
            throw ProtocolException("Requested buffer size $minSize exceeds pool limit $bufferSize")
        }
        val pooled = byteArrays.poll()
        if (pooled != null) {
            arrayCount.decrementAndGet()
            return pooled
        }
        return ByteArray(bufferSize)
    }

    fun release(buffer: ByteArray) {
        if (buffer.size != bufferSize) return
        while (true) {
            val current = arrayCount.get()
            if (current >= maxPooled) return
            if (arrayCount.compareAndSet(current, current + 1)) {
                byteArrays.offer(buffer)
                return
            }
        }
    }

    fun acquireByteBuffer(): ByteBuffer {
        val pooled = byteBuffers.poll()
        if (pooled != null) {
            bufferCount.decrementAndGet()
            pooled.clear()
            return pooled
        }
        return ByteBuffer.allocate(bufferSize).order(ByteOrder.BIG_ENDIAN)
    }

    fun releaseByteBuffer(buffer: ByteBuffer) {
        if (buffer.capacity() != bufferSize) return
        while (true) {
            val current = bufferCount.get()
            if (current >= maxPooled) return
            if (bufferCount.compareAndSet(current, current + 1)) {
                buffer.clear()
                byteBuffers.offer(buffer)
                return
            }
        }
    }
}

class ProtocolCodec(
    val pool: BufferPool = BufferPool()
) {
    private val readHeaderBytes = ByteArray(ProtocolConstants.HEADER_SIZE)
    private val readHeaderBuf = ByteBuffer.wrap(readHeaderBytes).order(ByteOrder.BIG_ENDIAN)

    private val writeFrameBytes = ByteArray(ProtocolConstants.MAX_FRAME_SIZE)
    private val writeFrameBuf = ByteBuffer.wrap(writeFrameBytes).order(ByteOrder.BIG_ENDIAN)

    fun readFrame(input: InputStream, usePool: Boolean = true): Frame {
        readFully(input, readHeaderBytes, 0, ProtocolConstants.HEADER_SIZE)

        val magic = readHeaderBytes[0]
        if (magic != ProtocolConstants.MAGIC_BYTE) {
            throw ProtocolException("Invalid magic byte: 0x%02x".format(magic.toInt() and 0xFF))
        }

        val payloadLen = readHeaderBuf.getShort(6).toInt() and 0xFFFF
        if (payloadLen > ProtocolConstants.MAX_PAYLOAD_SIZE) {
            throw ProtocolException(
                "Payload length $payloadLen exceeds max ${ProtocolConstants.MAX_PAYLOAD_SIZE}"
            )
        }

        val channelId = readHeaderBytes[1]
        val streamId = readHeaderBuf.getShort(2)
        val flags = readHeaderBuf.getShort(4)
        val sequenceNumber = readHeaderBuf.getInt(8)

        if (payloadLen == 0) {
            return Frame(channelId, streamId, flags, sequenceNumber, Frame.EMPTY_PAYLOAD, 0)
        }

        val payload = if (usePool) pool.acquire(payloadLen) else ByteArray(payloadLen)
        try {
            readFully(input, payload, 0, payloadLen)
        } catch (e: IOException) {
            if (usePool) pool.release(payload)
            throw e
        }

        return Frame(channelId, streamId, flags, sequenceNumber, payload, payloadLen)
    }

    fun writeFrame(output: OutputStream, frame: Frame) {
        val len = frame.payloadLength
        if (len < 0 || len > ProtocolConstants.MAX_PAYLOAD_SIZE || len > frame.payload.size) {
            throw ProtocolException("Invalid frame payload length: $len")
        }

        writeFrameBuf.clear()
        writeFrameBuf.put(ProtocolConstants.MAGIC_BYTE)
        writeFrameBuf.put(frame.channelId)
        writeFrameBuf.putShort(frame.streamId)
        writeFrameBuf.putShort(frame.flags)
        writeFrameBuf.putShort((len and 0xFFFF).toShort())
        writeFrameBuf.putInt(frame.sequenceNumber)

        if (len > 0) {
            System.arraycopy(frame.payload, 0, writeFrameBytes, ProtocolConstants.HEADER_SIZE, len)
        }
        output.write(writeFrameBytes, 0, ProtocolConstants.HEADER_SIZE + len)
    }

    fun encodeFrame(frame: Frame): ByteArray {
        val len = frame.payloadLength
        if (len < 0 || len > ProtocolConstants.MAX_PAYLOAD_SIZE || len > frame.payload.size) {
            throw ProtocolException("Invalid frame payload length: $len")
        }
        val out = ByteArray(ProtocolConstants.HEADER_SIZE + len)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN)
        bb.put(ProtocolConstants.MAGIC_BYTE)
        bb.put(frame.channelId)
        bb.putShort(frame.streamId)
        bb.putShort(frame.flags)
        bb.putShort((len and 0xFFFF).toShort())
        bb.putInt(frame.sequenceNumber)
        if (len > 0) {
            System.arraycopy(frame.payload, 0, out, ProtocolConstants.HEADER_SIZE, len)
        }
        return out
    }

    fun decodeFrame(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): Frame {
        if (length < ProtocolConstants.HEADER_SIZE || offset < 0 || offset + length > bytes.size) {
            throw ProtocolException("Truncated frame header: $length bytes")
        }
        if (bytes[offset] != ProtocolConstants.MAGIC_BYTE) {
            throw ProtocolException(
                "Invalid magic byte: 0x%02x".format(bytes[offset].toInt() and 0xFF)
            )
        }
        val bb = ByteBuffer.wrap(bytes, offset, ProtocolConstants.HEADER_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
        val channelId = bb.get(offset + 1)
        val streamId = bb.getShort(offset + 2)
        val flags = bb.getShort(offset + 4)
        val payloadLen = bb.getShort(offset + 6).toInt() and 0xFFFF
        if (payloadLen > ProtocolConstants.MAX_PAYLOAD_SIZE) {
            throw ProtocolException(
                "Payload length $payloadLen exceeds max ${ProtocolConstants.MAX_PAYLOAD_SIZE}"
            )
        }
        if (length < ProtocolConstants.HEADER_SIZE + payloadLen) {
            throw ProtocolException("Truncated frame payload: expected $payloadLen bytes")
        }
        val seq = bb.getInt(offset + 8)
        val payload = if (payloadLen == 0) {
            Frame.EMPTY_PAYLOAD
        } else {
            bytes.copyOfRange(
                offset + ProtocolConstants.HEADER_SIZE,
                offset + ProtocolConstants.HEADER_SIZE + payloadLen
            )
        }
        return Frame(channelId, streamId, flags, seq, payload, payloadLen)
    }

    fun recycle(frame: Frame) {
        if (frame.payload.isNotEmpty()) {
            pool.release(frame.payload)
        }
    }

    private fun readFully(input: InputStream, dst: ByteArray, offset: Int, length: Int) {
        var totalRead = 0
        while (totalRead < length) {
            val read = input.read(dst, offset + totalRead, length - totalRead)
            if (read < 0) {
                throw EOFException(
                    if (totalRead == 0) "Socket closed at frame boundary"
                    else "Unexpected EOF after $totalRead of $length bytes"
                )
            }
            totalRead += read
        }
    }
}
