package com.controid.protocol

import java.nio.ByteBuffer

data class Frame(
    val channelId: Byte,
    val streamId: Short,
    val flags: Short,
    val sequenceNumber: Int,
    val payload: ByteArray,
    val payloadLength: Int = payload.size
) {
    init {
        require(payloadLength in 0..payload.size) {
            "payloadLength ($payloadLength) out of bounds for payload size (${payload.size})"
        }
    }

    fun payloadBytes(): ByteArray =
        if (payloadLength == payload.size) payload else payload.copyOf(payloadLength)

    fun payloadBuffer(): ByteBuffer = ByteBuffer.wrap(payload, 0, payloadLength)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Frame) return false
        if (channelId != other.channelId ||
            streamId != other.streamId ||
            flags != other.flags ||
            sequenceNumber != other.sequenceNumber ||
            payloadLength != other.payloadLength
        ) {
            return false
        }
        for (i in 0 until payloadLength) {
            if (payload[i] != other.payload[i]) return false
        }
        return true
    }

    override fun hashCode(): Int {
        var result = channelId.toInt()
        result = 31 * result + streamId
        result = 31 * result + flags
        result = 31 * result + sequenceNumber
        result = 31 * result + payloadLength
        for (i in 0 until payloadLength) {
            result = 31 * result + payload[i]
        }
        return result
    }

    companion object {
        val EMPTY_PAYLOAD = ByteArray(0)

        fun ctrl(payload: ByteArray, urgent: Boolean = false): Frame = Frame(
            channelId = ProtocolConstants.CHANNEL_CTRL,
            streamId = 0,
            flags = if (urgent) ProtocolConstants.FLAG_URGENT else 0.toShort(),
            sequenceNumber = 0,
            payload = payload
        )
    }
}
