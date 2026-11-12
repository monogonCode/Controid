package com.controid.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

class EchoSuppressor(
    private val ttlNs: Long = ProtocolConstants.ECHO_TTL_MS * 1_000_000L
) {
    private val entries = HashMap<HashKey, Long>()

    private class HashKey(val bytes: ByteArray) {
        private val cachedHash = bytes.contentHashCode()
        override fun equals(other: Any?): Boolean =
            other is HashKey && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = cachedHash
    }

    @Synchronized
    fun record(hash: ByteArray, nowNs: Long = System.nanoTime()) {
        pruneExpired(nowNs)
        entries[HashKey(hash.copyOf())] = nowNs + ttlNs
    }

    @Synchronized
    fun shouldSuppress(hash: ByteArray, nowNs: Long = System.nanoTime()): Boolean {
        pruneExpired(nowNs)
        val expiry = entries[HashKey(hash)] ?: return false
        return expiry > nowNs
    }

    private fun pruneExpired(nowNs: Long) {
        val it = entries.entries.iterator()
        while (it.hasNext()) {
            if (it.next().value <= nowNs) {
                it.remove()
            }
        }
    }
}

object EchoHash {
    fun computeClipboardEchoHash(
        isText: Boolean,
        totalLen: Long,
        head: ByteArray,
        tail: ByteArray = ByteArray(0)
    ): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        if (isText) {
            digest.update(head)
        } else {
            val lenBuf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(totalLen).array()
            digest.update(lenBuf)
            val headLen = head.size.coerceAtMost(ProtocolConstants.ECHO_SLICE_LEN)
            digest.update(head, 0, headLen)
            val tailLen = tail.size.coerceAtMost(ProtocolConstants.ECHO_SLICE_LEN)
            val tailOffset = tail.size - tailLen
            digest.update(tail, tailOffset, tailLen)
        }
        return digest.digest()
    }

    fun computeTextEchoHash(text: String): ByteArray {
        val utf8 = text.toByteArray(Charsets.UTF_8)
        return computeClipboardEchoHash(isText = true, totalLen = utf8.size.toLong(), head = utf8)
    }

    fun computeBinaryEchoHash(data: ByteArray): ByteArray {
        val headLen = data.size.coerceAtMost(ProtocolConstants.ECHO_SLICE_LEN)
        val tailLen = data.size.coerceAtMost(ProtocolConstants.ECHO_SLICE_LEN)
        val lenBuf = ByteBuffer.allocate(8)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(data.size.toLong())
            .array()
        return MessageDigest.getInstance("SHA-256").run {
            update(lenBuf)
            update(data, 0, headLen)
            update(data, data.size - tailLen, tailLen)
            digest()
        }
    }
}
