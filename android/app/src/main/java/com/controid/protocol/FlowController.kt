package com.controid.protocol

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Semaphore

data class CreditGrantUpdate(
    val creditsGranted: Int,
    val cumulativeBytesConsumed: Long
)

class FlowController(
    private val initialCredits: Int = ProtocolConstants.INITIAL_WINDOW_CREDITS
) {
    private val semaphore = Semaphore(initialCredits)
    private val cumulativeBytes = AtomicLong(0L)
    private val pendingBytes = AtomicLong(0L)
    private val pendingChunks = AtomicInteger(0)
    private val chunkThreshold = (initialCredits / 2).coerceAtLeast(1)

    suspend fun acquireCredit() {
        semaphore.acquire()
    }

    fun tryAcquireCredit(): Boolean = semaphore.tryAcquire()

    fun grantCredits(credits: Int, bytesConsumed: Long) {
        cumulativeBytes.set(bytesConsumed)
        if (credits <= 0) return
        repeat(credits) {
            if (semaphore.availablePermits >= initialCredits) return
            try {
                semaphore.release()
            } catch (_: IllegalStateException) {
                return
            }
        }
    }

    fun shouldGrantCredits(consumed: Long): Boolean =
        consumed >= ProtocolConstants.CREDIT_THRESHOLD_BYTES

    /**
     * Accounts for inbound bytes consumed and returns a [CreditGrantUpdate] when either
     * 512 KB or half of the initial credit window (32 chunks) has been processed.
     */
    fun reconcileBytes(bytes: Int): CreditGrantUpdate? {
        val total = cumulativeBytes.addAndGet(bytes.toLong())
        val pendingB = pendingBytes.addAndGet(bytes.toLong())
        val pendingC = pendingChunks.incrementAndGet()

        if (shouldGrantCredits(pendingB) || pendingC >= chunkThreshold) {
            val chunks = pendingChunks.getAndSet(0)
            pendingBytes.set(0L)
            if (chunks > 0) {
                val credits = chunks.coerceAtMost(0xFFFF)
                return CreditGrantUpdate(credits, total and 0xFFFF_FFFFL)
            }
        }
        return null
    }

    fun availableCredits(): Int = semaphore.availablePermits

    fun cumulativeBytes(): Long = cumulativeBytes.get()
}
