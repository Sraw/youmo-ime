/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.pack

import java.math.BigInteger
import java.security.MessageDigest

/**
 * Ed25519 signatures checked (RFC 8032 §5.1.7), the resource packs' ([PackManifest]): Android 10's
 * security providers have no Ed25519 (Conscrypt's came with Android 14), and nothing else the app
 * links does. Verification only, on public data: no secret is handled, so constant time does not
 * matter, and BigInteger is fast enough for a signature or two an import (a few ms).
 *
 * Strict where RFC 8032 says to be: an encoding of y not below p, S not below L, a point not on the
 * curve are refused. The check is the cofactorless one, `[S]B = R + [k]A`, as the JDK's (Java 15+)
 * makes it, which signs the packs at build time; the tests hold the two to each other.
 */
object Ed25519 {

    const val KEY_SIZE = 32
    const val SIGNATURE_SIZE = 64

    private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val L: BigInteger = BigInteger.ONE.shiftLeft(252).add(BigInteger("27742317777372353535851937790883648493"))
    private val TWO = BigInteger.valueOf(2)

    // d = -121665 / 121666
    private val D: BigInteger = BigInteger.valueOf(-121665).mod(P).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P)
    private val D2: BigInteger = D.shiftLeft(1).mod(P)

    // sqrt(-1) = 2^((p-1)/4)
    private val SQRT_M1: BigInteger = TWO.modPow(P.subtract(BigInteger.ONE).shiftRight(2), P)

    /** A point in extended coordinates (x = X/Z, y = Y/Z, xy = T/Z). */
    private class Point(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger)

    private val IDENTITY = Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)

    // y = 4/5, x even
    private val BASE: Point = run {
        val y = BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P)
        checkNotNull(recover(y, 0)) { "Ed25519's base point" }
    }

    /** Whether [signature] is [publicKey]'s over [message]; false for anything malformed. */
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != KEY_SIZE || signature.size != SIGNATURE_SIZE) return false
        val a = decode(publicKey) ?: return false
        val rBytes = signature.copyOfRange(0, KEY_SIZE)
        val r = decode(rBytes) ?: return false
        val s = littleEndian(signature.copyOfRange(KEY_SIZE, SIGNATURE_SIZE))
        if (s >= L) return false
        val digest = MessageDigest.getInstance("SHA-512")
        digest.update(rBytes)
        digest.update(publicKey)
        digest.update(message)
        val k = littleEndian(digest.digest()).mod(L)
        return same(times(BASE, s), add(r, times(a, k)))
    }

    private fun littleEndian(bytes: ByteArray): BigInteger = BigInteger(1, bytes.reversedArray())

    // RFC 8032 §5.1.3
    private fun decode(bytes: ByteArray): Point? {
        val sign = (bytes[KEY_SIZE - 1].toInt() ushr 7) and 1
        val clear = bytes.copyOf()
        clear[KEY_SIZE - 1] = (clear[KEY_SIZE - 1].toInt() and 0x7f).toByte()
        val y = littleEndian(clear)
        if (y >= P) return null
        return recover(y, sign)
    }

    private fun recover(y: BigInteger, sign: Int): Point? {
        val yy = y.multiply(y).mod(P)
        val u = yy.subtract(BigInteger.ONE).mod(P)
        val v = D.multiply(yy).add(BigInteger.ONE).mod(P)
        // x = u v^3 (u v^7)^((p-5)/8)
        val v3 = v.multiply(v).multiply(v).mod(P)
        val v7 = v3.multiply(v3).multiply(v).mod(P)
        var x = u.multiply(v3).multiply(u.multiply(v7).mod(P).modPow(P.subtract(BigInteger.valueOf(5)).shiftRight(3), P)).mod(P)
        val vxx = v.multiply(x).multiply(x).mod(P)
        when {
            vxx == u -> {}
            vxx == u.negate().mod(P) -> x = x.multiply(SQRT_M1).mod(P)
            else -> return null
        }
        if (x.signum() == 0 && sign == 1) return null
        if (x.testBit(0) != (sign == 1)) x = P.subtract(x)
        return Point(x, y, BigInteger.ONE, x.multiply(y).mod(P))
    }

    // RFC 8032 §5.1.4's addition, complete for a = -1: doubling too
    private fun add(p: Point, q: Point): Point {
        val a = p.y.subtract(p.x).multiply(q.y.subtract(q.x)).mod(P)
        val b = p.y.add(p.x).multiply(q.y.add(q.x)).mod(P)
        val c = p.t.multiply(D2).multiply(q.t).mod(P)
        val d = p.z.shiftLeft(1).multiply(q.z).mod(P)
        val e = b.subtract(a)
        val f = d.subtract(c)
        val g = d.add(c)
        val h = b.add(a)
        return Point(e.multiply(f).mod(P), g.multiply(h).mod(P), f.multiply(g).mod(P), e.multiply(h).mod(P))
    }

    private fun times(p: Point, n: BigInteger): Point {
        var result = IDENTITY
        for (i in n.bitLength() - 1 downTo 0) {
            result = add(result, result)
            if (n.testBit(i)) result = add(result, p)
        }
        return result
    }

    private fun same(p: Point, q: Point): Boolean =
        p.x.multiply(q.z).subtract(q.x.multiply(p.z)).mod(P).signum() == 0 &&
            p.y.multiply(q.z).subtract(q.y.multiply(p.z)).mod(P).signum() == 0
}
