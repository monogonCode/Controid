package com.controid.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

enum class LinkStatus(val code: Byte) {
    IDLE(0x00),
    STARTING(0x01),
    ACTIVE(0x02),
    ERR_UNKNOWN(0x80.toByte()),
    ERR_SHIZUKU_UNAVAILABLE(0x81.toByte()),
    ERR_PROVISIONING_FAILED(0x82.toByte()),
    ERR_WIFI_HARDWARE(0x83.toByte()),
    ERR_ASSOCIATION_TIMEOUT(0x84.toByte());

    companion object {
        fun fromCode(code: Byte): LinkStatus? = entries.firstOrNull { it.code == code }
    }
}

sealed class ControlMessage {
    abstract fun encode(): ByteArray

    data class CreditGrant(
        val targetChannel: Byte,
        val creditsGranted: Int,
        val cumulativeBytesConsumed: Long
    ) : ControlMessage() {
        override fun encode(): ByteArray = ByteBuffer.allocate(8)
            .order(ByteOrder.BIG_ENDIAN)
            .put(ProtocolConstants.MSG_CREDIT_GRANT)
            .put(targetChannel)
            .putShort((creditsGranted and 0xFFFF).toShort())
            .putInt((cumulativeBytesConsumed and 0xFFFF_FFFFL).toInt())
            .array()
    }

    data class Heartbeat(
        val isPong: Boolean,
        val timestampMs: Long
    ) : ControlMessage() {
        override fun encode(): ByteArray = ByteBuffer.allocate(8)
            .order(ByteOrder.BIG_ENDIAN)
            .put(ProtocolConstants.MSG_HEARTBEAT)
            .put(if (isPong) ProtocolConstants.HEARTBEAT_PONG else ProtocolConstants.HEARTBEAT_PING)
            .putShort(0)
            .putInt((timestampMs and 0xFFFF_FFFFL).toInt())
            .array()
    }

    data class AuthChallenge(val nonce: ByteArray) : ControlMessage() {
        init {
            require(nonce.size == 32) { "AuthChallenge nonce must be 32 bytes" }
        }

        override fun encode(): ByteArray = ByteArray(33).also { out ->
            out[0] = ProtocolConstants.MSG_AUTH_CHALLENGE
            System.arraycopy(nonce, 0, out, 1, 32)
        }

        override fun equals(other: Any?): Boolean =
            this === other || (other is AuthChallenge && nonce.contentEquals(other.nonce))

        override fun hashCode(): Int = nonce.contentHashCode()
    }

    data class AuthResponse(
        val pubkey: ByteArray,
        val signature: ByteArray
    ) : ControlMessage() {
        init {
            require(pubkey.size == 32) { "AuthResponse pubkey must be 32 bytes" }
            require(signature.size == 64) { "AuthResponse signature must be 64 bytes" }
        }

        override fun encode(): ByteArray = ByteArray(97).also { out ->
            out[0] = ProtocolConstants.MSG_AUTH_RESPONSE
            System.arraycopy(pubkey, 0, out, 1, 32)
            System.arraycopy(signature, 0, out, 33, 64)
        }

        override fun equals(other: Any?): Boolean =
            this === other || (other is AuthResponse &&
                pubkey.contentEquals(other.pubkey) &&
                signature.contentEquals(other.signature))

        override fun hashCode(): Int = 31 * pubkey.contentHashCode() + signature.contentHashCode()
    }

    data class AuthResult(val ok: Boolean) : ControlMessage() {
        override fun encode(): ByteArray = byteArrayOf(
            ProtocolConstants.MSG_AUTH_RESULT,
            if (ok) ProtocolConstants.AUTH_OK else ProtocolConstants.AUTH_REJECTED
        )
    }

    data class StreamReq(
        val maxFps: Int,
        val bitrateMbps: Int,
        val codec: Byte
    ) : ControlMessage() {
        override fun encode(): ByteArray = ByteBuffer.allocate(5)
            .order(ByteOrder.BIG_ENDIAN)
            .put(ProtocolConstants.MSG_STREAM_REQ)
            .putShort((maxFps and 0xFFFF).toShort())
            .put((bitrateMbps and 0xFF).toByte())
            .put(codec)
            .array()
    }

    data class StreamAck(
        val status: Byte,
        val width: Int,
        val height: Int
    ) : ControlMessage() {
        override fun encode(): ByteArray = ByteBuffer.allocate(6)
            .order(ByteOrder.BIG_ENDIAN)
            .put(ProtocolConstants.MSG_STREAM_ACK)
            .put(status)
            .putShort((width and 0xFFFF).toShort())
            .putShort((height and 0xFFFF).toShort())
            .array()
    }

    data object StreamStop : ControlMessage() {
        override fun encode(): ByteArray = byteArrayOf(ProtocolConstants.MSG_STREAM_STOP)
    }

    data object StreamStopped : ControlMessage() {
        override fun encode(): ByteArray = byteArrayOf(ProtocolConstants.MSG_STREAM_STOPPED)
    }

    data class Ack(
        val streamId: Int,
        val sequenceNumber: Long
    ) : ControlMessage() {
        override fun encode(): ByteArray = ByteBuffer.allocate(7)
            .order(ByteOrder.BIG_ENDIAN)
            .put(ProtocolConstants.MSG_ACK)
            .putShort((streamId and 0xFFFF).toShort())
            .putInt((sequenceNumber and 0xFFFF_FFFFL).toInt())
            .array()
    }

    data class DeviceStatus(
        val batteryPct: Int,
        val charging: Boolean,
        val tethering: Boolean,
        val hidConnected: Boolean
    ) : ControlMessage() {
        override fun encode(): ByteArray = byteArrayOf(
            ProtocolConstants.MSG_DEVICE_STATUS,
            (batteryPct and 0xFF).toByte(),
            if (charging) 1.toByte() else 0.toByte(),
            if (tethering) 1.toByte() else 0.toByte(),
            if (hidConnected) 1.toByte() else 0.toByte()
        )
    }

    companion object {
        fun decode(buf: ByteArray, offset: Int = 0, length: Int = buf.size): ControlMessage {
            if (length <= 0 || offset < 0 || offset + length > buf.size) {
                throw ProtocolException("Invalid control message buffer length: $length")
            }

            val bb = ByteBuffer.wrap(buf, offset, length).order(ByteOrder.BIG_ENDIAN)
            return when (val type = buf[offset]) {
                ProtocolConstants.MSG_CREDIT_GRANT -> {
                    requireLen(length, 8, type)
                    CreditGrant(
                        targetChannel = buf[offset + 1],
                        creditsGranted = bb.getShort(offset + 2).toInt() and 0xFFFF,
                        cumulativeBytesConsumed = bb.getInt(offset + 4).toLong() and 0xFFFF_FFFFL
                    )
                }

                ProtocolConstants.MSG_HEARTBEAT -> {
                    requireLen(length, 8, type)
                    val isPong = when (buf[offset + 1]) {
                        ProtocolConstants.HEARTBEAT_PING -> false
                        ProtocolConstants.HEARTBEAT_PONG -> true
                        else -> throw ProtocolException("Invalid heartbeat subtype: ${buf[offset + 1]}")
                    }
                    Heartbeat(
                        isPong = isPong,
                        timestampMs = bb.getInt(offset + 4).toLong() and 0xFFFF_FFFFL
                    )
                }

                ProtocolConstants.MSG_AUTH_CHALLENGE -> {
                    requireLen(length, 33, type)
                    AuthChallenge(buf.copyOfRange(offset + 1, offset + 33))
                }

                ProtocolConstants.MSG_AUTH_RESPONSE -> {
                    requireLen(length, 97, type)
                    AuthResponse(
                        pubkey = buf.copyOfRange(offset + 1, offset + 33),
                        signature = buf.copyOfRange(offset + 33, offset + 97)
                    )
                }

                ProtocolConstants.MSG_AUTH_RESULT -> {
                    requireLen(length, 2, type)
                    val ok = when (buf[offset + 1]) {
                        ProtocolConstants.AUTH_OK -> true
                        ProtocolConstants.AUTH_REJECTED -> false
                        else -> throw ProtocolException("Invalid auth result: ${buf[offset + 1]}")
                    }
                    AuthResult(ok)
                }

                ProtocolConstants.MSG_STREAM_REQ -> {
                    requireLen(length, 5, type)
                    StreamReq(
                        maxFps = bb.getShort(offset + 1).toInt() and 0xFFFF,
                        bitrateMbps = buf[offset + 3].toInt() and 0xFF,
                        codec = buf[offset + 4]
                    )
                }

                ProtocolConstants.MSG_STREAM_ACK -> {
                    requireLen(length, 6, type)
                    StreamAck(
                        status = buf[offset + 1],
                        width = bb.getShort(offset + 2).toInt() and 0xFFFF,
                        height = bb.getShort(offset + 4).toInt() and 0xFFFF
                    )
                }

                ProtocolConstants.MSG_STREAM_STOP -> {
                    requireLen(length, 1, type)
                    StreamStop
                }

                ProtocolConstants.MSG_STREAM_STOPPED -> {
                    requireLen(length, 1, type)
                    StreamStopped
                }

                ProtocolConstants.MSG_ACK -> {
                    requireLen(length, 7, type)
                    Ack(
                        streamId = bb.getShort(offset + 1).toInt() and 0xFFFF,
                        sequenceNumber = bb.getInt(offset + 3).toLong() and 0xFFFF_FFFFL
                    )
                }

                ProtocolConstants.MSG_DEVICE_STATUS -> {
                    requireLen(length, 5, type)
                    DeviceStatus(
                        batteryPct = buf[offset + 1].toInt() and 0xFF,
                        charging = buf[offset + 2] != 0.toByte(),
                        tethering = buf[offset + 3] != 0.toByte(),
                        hidConnected = buf[offset + 4] != 0.toByte()
                    )
                }

                else -> throw ProtocolException("Unknown control message type: 0x%02x".format(type))
            }
        }

        private fun requireLen(actual: Int, expected: Int, type: Byte) {
            if (actual != expected) {
                throw ProtocolException(
                    "Invalid length $actual for message 0x%02x (expected $expected)".format(type)
                )
            }
        }
    }
}

data class StreamStartMeta(
    val totalLen: Long,
    val mime: String,
    val initialData: ByteArray
) {
    fun encode(): ByteArray {
        val mimeBytes = mime.toByteArray(Charsets.UTF_8)
        if (mimeBytes.size > 0xFFFF) {
            throw ProtocolException("MIME length ${mimeBytes.size} exceeds u16 limit")
        }
        val out = ByteArray(10 + mimeBytes.size + initialData.size)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN)
        bb.putLong(totalLen)
        bb.putShort((mimeBytes.size and 0xFFFF).toShort())
        bb.put(mimeBytes)
        bb.put(initialData)
        return out
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is StreamStartMeta &&
            totalLen == other.totalLen &&
            mime == other.mime &&
            initialData.contentEquals(other.initialData))

    override fun hashCode(): Int {
        var result = totalLen.hashCode()
        result = 31 * result + mime.hashCode()
        result = 31 * result + initialData.contentHashCode()
        return result
    }

    companion object {
        fun decode(buf: ByteArray, offset: Int = 0, length: Int = buf.size): StreamStartMeta {
            if (length < 10 || offset < 0 || offset + length > buf.size) {
                throw ProtocolException("Invalid StreamStartMeta length: $length")
            }
            val bb = ByteBuffer.wrap(buf, offset, length).order(ByteOrder.BIG_ENDIAN)
            val totalLen = bb.getLong()
            val mimeLen = bb.getShort().toInt() and 0xFFFF
            if (length - 10 < mimeLen) {
                throw ProtocolException("Truncated StreamStartMeta MIME: need $mimeLen bytes")
            }
            val mimeDecoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val mime = try {
                mimeDecoder.decode(ByteBuffer.wrap(buf, offset + 10, mimeLen)).toString()
            } catch (e: CharacterCodingException) {
                throw ProtocolException("Invalid UTF-8 in StreamStartMeta MIME", e)
            }
            val initialData = buf.copyOfRange(offset + 10 + mimeLen, offset + length)
            return StreamStartMeta(totalLen, mime, initialData)
        }
    }
}

sealed class InputFallbackEvent {
    abstract fun encode(): ByteArray

    data class MouseRel(val dx: Short, val dy: Short, val wheel: Byte) : InputFallbackEvent() {
        override fun encode(): ByteArray = ByteBuffer.allocate(6)
            .order(ByteOrder.BIG_ENDIAN)
            .put(ProtocolConstants.INPUT_MOUSE_REL)
            .putShort(dx)
            .putShort(dy)
            .put(wheel)
            .array()
    }

    data class MouseBtn(val button: Int, val pressed: Boolean) : InputFallbackEvent() {
        override fun encode(): ByteArray = byteArrayOf(
            ProtocolConstants.INPUT_MOUSE_BTN,
            (button and 0xFF).toByte(),
            if (pressed) 1.toByte() else 0.toByte()
        )
    }

    data class KeyEvent(val linuxKeycode: Int, val pressed: Boolean) : InputFallbackEvent() {
        override fun encode(): ByteArray = ByteBuffer.allocate(4)
            .order(ByteOrder.BIG_ENDIAN)
            .put(ProtocolConstants.INPUT_KEY_EVENT)
            .putShort((linuxKeycode and 0xFFFF).toShort())
            .put(if (pressed) 1.toByte() else 0.toByte())
            .array()
    }

    companion object {
        fun decode(buf: ByteArray, offset: Int = 0, length: Int = buf.size): InputFallbackEvent {
            if (length <= 0 || offset < 0 || offset + length > buf.size) {
                throw ProtocolException("Invalid InputFallbackEvent length: $length")
            }
            val bb = ByteBuffer.wrap(buf, offset, length).order(ByteOrder.BIG_ENDIAN)
            return when (val type = buf[offset]) {
                ProtocolConstants.INPUT_MOUSE_REL -> {
                    if (length != 6) throw ProtocolException("Invalid MOUSE_REL length: $length")
                    MouseRel(
                        dx = bb.getShort(offset + 1),
                        dy = bb.getShort(offset + 3),
                        wheel = buf[offset + 5]
                    )
                }

                ProtocolConstants.INPUT_MOUSE_BTN -> {
                    if (length != 3) throw ProtocolException("Invalid MOUSE_BTN length: $length")
                    MouseBtn(
                        button = buf[offset + 1].toInt() and 0xFF,
                        pressed = buf[offset + 2] != 0.toByte()
                    )
                }

                ProtocolConstants.INPUT_KEY_EVENT -> {
                    if (length != 4) throw ProtocolException("Invalid KEY_EVENT length: $length")
                    KeyEvent(
                        linuxKeycode = bb.getShort(offset + 1).toInt() and 0xFFFF,
                        pressed = buf[offset + 3] != 0.toByte()
                    )
                }

                else -> throw ProtocolException("Unknown InputFallbackEvent type: 0x%02x".format(type))
            }
        }
    }
}

sealed class RemoteInputEvent {
    data class Touch(
        val action: Int,
        val pointerId: Int,
        val buttons: Int,
        val xNorm: Float,
        val yNorm: Float
    ) : RemoteInputEvent()

    data class Scroll(
        val xNorm: Float,
        val yNorm: Float,
        val hScroll: Float,
        val vScroll: Float
    ) : RemoteInputEvent()

    data class Key(
        val action: Int,
        val keyCode: Int,
        val metaState: Int,
        val repeat: Int
    ) : RemoteInputEvent()

    data class Text(val text: String) : RemoteInputEvent()

    companion object {
        fun decode(buf: ByteArray, offset: Int = 0, length: Int = buf.size): RemoteInputEvent {
            if (length <= 0 || offset < 0 || offset + length > buf.size) {
                throw ProtocolException("Invalid RemoteInputEvent length: $length")
            }
            val bb = ByteBuffer.wrap(buf, offset, length).order(ByteOrder.BIG_ENDIAN)
            return when (val type = buf[offset]) {
                ProtocolConstants.REMOTE_TOUCH -> {
                    if (length != 8) throw ProtocolException("Invalid REMOTE_TOUCH length: $length")
                    Touch(
                        action = buf[offset + 1].toInt() and 0xFF,
                        pointerId = buf[offset + 2].toInt() and 0xFF,
                        buttons = buf[offset + 3].toInt() and 0xFF,
                        xNorm = (bb.getShort(offset + 4).toInt() and 0xFFFF) / 65535.0f,
                        yNorm = (bb.getShort(offset + 6).toInt() and 0xFFFF) / 65535.0f
                    )
                }

                ProtocolConstants.REMOTE_SCROLL -> {
                    if (length != 9) throw ProtocolException("Invalid REMOTE_SCROLL length: $length")
                    Scroll(
                        xNorm = (bb.getShort(offset + 1).toInt() and 0xFFFF) / 65535.0f,
                        yNorm = (bb.getShort(offset + 3).toInt() and 0xFFFF) / 65535.0f,
                        hScroll = bb.getShort(offset + 5).toFloat() / 100.0f,
                        vScroll = bb.getShort(offset + 7).toFloat() / 100.0f
                    )
                }

                ProtocolConstants.REMOTE_KEY -> {
                    if (length != 10) throw ProtocolException("Invalid REMOTE_KEY length: $length")
                    Key(
                        action = buf[offset + 1].toInt() and 0xFF,
                        keyCode = bb.getShort(offset + 2).toInt() and 0xFFFF,
                        metaState = bb.getInt(offset + 4),
                        repeat = bb.getShort(offset + 8).toInt() and 0xFFFF
                    )
                }

                ProtocolConstants.REMOTE_TEXT -> {
                    if (length > 1025) throw ProtocolException("REMOTE_TEXT exceeds limit: $length")
                    val text = String(buf, offset + 1, length - 1, Charsets.UTF_8)
                    Text(text)
                }

                else -> throw ProtocolException("Unknown RemoteInputEvent type: 0x%02x".format(type))
            }
        }
    }
}
