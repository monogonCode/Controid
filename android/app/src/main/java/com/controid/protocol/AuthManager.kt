package com.controid.protocol

import android.content.Context
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

class AuthManager(
    private val pairedKeyFile: File
) {
    constructor(context: Context) : this(File(context.filesDir, "paired_peer_ed25519.pub"))

    private val secureRandom = SecureRandom()

    fun generateChallenge(): ByteArray = ByteArray(32).also { secureRandom.nextBytes(it) }

    fun loadPairedPublicKey(): ByteArray? {
        if (!pairedKeyFile.exists()) return null
        val bytes = pairedKeyFile.readBytes()
        return if (bytes.size == 32) bytes else null
    }

    fun savePairedPublicKey(pubkey: ByteArray) {
        require(pubkey.size == 32) { "Ed25519 public key must be 32 bytes" }
        pairedKeyFile.parentFile?.mkdirs()
        pairedKeyFile.writeBytes(pubkey)
        pairedKeyFile.setReadable(false, false)
        pairedKeyFile.setWritable(false, false)
        pairedKeyFile.setExecutable(false, false)
        pairedKeyFile.setReadable(true, true)
        pairedKeyFile.setWritable(true, true)
    }

    fun clearPairedPublicKey() {
        if (pairedKeyFile.exists()) {
            pairedKeyFile.delete()
        }
    }

    fun isPairedKey(pubkey: ByteArray): Boolean {
        if (pubkey.size != 32) return false
        val paired = loadPairedPublicKey() ?: return false
        return MessageDigest.isEqual(paired, pubkey)
    }

    fun verifyChallengeResponse(
        nonce: ByteArray,
        pubkey: ByteArray,
        signature: ByteArray,
        allowTrustOnFirstUse: Boolean = false
    ): Boolean {
        if (nonce.size != 32 || pubkey.size != 32 || signature.size != 64) {
            return false
        }
        val paired = loadPairedPublicKey()
        if (paired != null) {
            if (!MessageDigest.isEqual(paired, pubkey)) return false
        } else if (!allowTrustOnFirstUse) {
            return false
        }

        val verified = verifyEd25519(pubkey, nonce, signature)
        if (verified && paired == null && allowTrustOnFirstUse) {
            savePairedPublicKey(pubkey)
        }
        return verified
    }

    fun verifyEd25519(pubkey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (pubkey.size != 32 || signature.size != 64) return false

        return try {
            val spki = ByteArray(ED25519_SPKI_PREFIX.size + 32)
            System.arraycopy(ED25519_SPKI_PREFIX, 0, spki, 0, ED25519_SPKI_PREFIX.size)
            System.arraycopy(pubkey, 0, spki, ED25519_SPKI_PREFIX.size, 32)
            val keyFactory = KeyFactory.getInstance("Ed25519")
            val publicKey = keyFactory.generatePublic(X509EncodedKeySpec(spki))
            val verifier = Signature.getInstance("Ed25519")
            verifier.initVerify(publicKey)
            verifier.update(message)
            verifier.verify(signature)
        } catch (_: NoSuchAlgorithmException) {
            // Android API 28-32 lacks JCA Ed25519 provider; verify via RFC 8032 fallback.
            Rfc8032Verifier.verify(pubkey, message, signature)
        } catch (_: Exception) {
            false
        }
    }

    private object Rfc8032Verifier {
        private val P: BigInteger =
            BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
        private val L: BigInteger =
            BigInteger.ONE.shiftLeft(252).add(BigInteger("27742317777372353535851937790883648493"))
        private val D: BigInteger = BigInteger.valueOf(-121665)
            .multiply(BigInteger.valueOf(121666).modInverse(P))
            .mod(P)
        private val TWO_D: BigInteger = D.shiftLeft(1).mod(P)
        private val SQRT_M1: BigInteger = BigInteger.valueOf(2)
            .modPow(P.subtract(BigInteger.ONE).shiftRight(2), P)
        private val SQRT_EXP: BigInteger = P.add(BigInteger.valueOf(3)).shiftRight(3)

        private data class ExtPoint(
            val x: BigInteger,
            val y: BigInteger,
            val z: BigInteger,
            val t: BigInteger
        )

        private val IDENTITY = ExtPoint(
            BigInteger.ZERO,
            BigInteger.ONE,
            BigInteger.ONE,
            BigInteger.ZERO
        )

        private val BASE_POINT: ExtPoint = run {
            val by = BigInteger.valueOf(4)
                .multiply(BigInteger.valueOf(5).modInverse(P))
                .mod(P)
            val bx = BigInteger(
                "15112221349535400772501151409588531511454012693041857206046113283949847762202"
            )
            ExtPoint(bx, by, BigInteger.ONE, bx.multiply(by).mod(P))
        }

        fun verify(pubkey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
            val rBytes = signature.copyOfRange(0, 32)
            val s = fromLittleEndian(signature, 32, 32)
            if (s >= L) return false

            val aPoint = decodePoint(pubkey) ?: return false
            val rPoint = decodePoint(rBytes) ?: return false

            val digest = MessageDigest.getInstance("SHA-512").apply {
                update(rBytes)
                update(pubkey)
                update(message)
            }.digest()
            val k = fromLittleEndian(digest, 0, digest.size).mod(L)

            val lhs = scalarMul(BASE_POINT, s)
            val rhs = addPoints(rPoint, scalarMul(aPoint, k))
            return pointsEqual(lhs, rhs)
        }

        private fun fromLittleEndian(bytes: ByteArray, offset: Int, length: Int): BigInteger {
            val be = ByteArray(length)
            for (i in 0 until length) {
                be[length - 1 - i] = bytes[offset + i]
            }
            return BigInteger(1, be)
        }

        private fun decodePoint(encoded: ByteArray): ExtPoint? {
            if (encoded.size != 32) return null
            val copy = encoded.copyOf()
            val xSign = (copy[31].toInt() ushr 7) and 1
            copy[31] = (copy[31].toInt() and 0x7F).toByte()

            val y = fromLittleEndian(copy, 0, 32)
            if (y >= P) return null

            val y2 = y.multiply(y).mod(P)
            val u = y2.subtract(BigInteger.ONE).mod(P)
            val v = D.multiply(y2).add(BigInteger.ONE).mod(P)
            if (v == BigInteger.ZERO) return null

            val x2 = u.multiply(v.modInverse(P)).mod(P)
            var x = x2.modPow(SQRT_EXP, P)
            val check = x.multiply(x).mod(P)
            if (check != x2) {
                if (check != P.subtract(x2).mod(P)) return null
                x = x.multiply(SQRT_M1).mod(P)
            }
            if (x == BigInteger.ZERO && xSign == 1) return null
            if (x.testBit(0) != (xSign == 1)) {
                x = P.subtract(x)
            }
            return ExtPoint(x, y, BigInteger.ONE, x.multiply(y).mod(P))
        }

        private fun addPoints(p1: ExtPoint, p2: ExtPoint): ExtPoint {
            val a = p1.y.subtract(p1.x).multiply(p2.y.subtract(p2.x)).mod(P)
            val b = p1.y.add(p1.x).multiply(p2.y.add(p2.x)).mod(P)
            val c = p1.t.multiply(TWO_D).multiply(p2.t).mod(P)
            val d = p1.z.shiftLeft(1).multiply(p2.z).mod(P)
            val e = b.subtract(a).mod(P)
            val f = d.subtract(c).mod(P)
            val g = d.add(c).mod(P)
            val h = b.add(a).mod(P)
            return ExtPoint(
                x = e.multiply(f).mod(P),
                y = g.multiply(h).mod(P),
                z = f.multiply(g).mod(P),
                t = e.multiply(h).mod(P)
            )
        }

        private fun scalarMul(point: ExtPoint, scalar: BigInteger): ExtPoint {
            var acc = IDENTITY
            var cur = point
            val bits = scalar.bitLength()
            for (i in 0 until bits) {
                if (scalar.testBit(i)) {
                    acc = addPoints(acc, cur)
                }
                cur = addPoints(cur, cur)
            }
            return acc
        }

        private fun pointsEqual(p1: ExtPoint, p2: ExtPoint): Boolean {
            val x1z2 = p1.x.multiply(p2.z).mod(P)
            val x2z1 = p2.x.multiply(p1.z).mod(P)
            val y1z2 = p1.y.multiply(p2.z).mod(P)
            val y2z1 = p2.y.multiply(p1.z).mod(P)
            return x1z2 == x2z1 && y1z2 == y2z1
        }
    }

    companion object {
        private val ED25519_SPKI_PREFIX = byteArrayOf(
            0x30, 0x2A, 0x30, 0x05, 0x06, 0x03, 0x2B, 0x65, 0x70, 0x03, 0x21, 0x00
        )
    }
}
