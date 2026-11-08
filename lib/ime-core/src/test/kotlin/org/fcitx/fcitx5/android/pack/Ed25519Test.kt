/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.pack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.Signature
import kotlin.random.Random

class Ed25519Test {

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // RFC 8032 §7.1, tests 1-3
    private val vectors = listOf(
        Triple(
            "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a",
            "",
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
        ),
        Triple(
            "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c",
            "72",
            "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00",
        ),
        Triple(
            "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025",
            "af82",
            "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a",
        ),
    )

    @Test
    fun `RFC 8032's vectors verify`() {
        for ((key, message, signature) in vectors) assertTrue(Ed25519.verify(hex(key), hex(message), hex(signature)))
    }

    @Test
    fun `a vector changed anywhere does not`() {
        val (key, message, signature) = vectors[2]
        val k = hex(key)
        val m = hex(message)
        val s = hex(signature)
        for (i in s.indices) assertFalse("signature byte $i", Ed25519.verify(k, m, s.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }))
        for (i in k.indices) assertFalse("key byte $i", Ed25519.verify(k.copyOf().also { it[i] = (it[i].toInt() xor 4).toByte() }, m, s))
        assertFalse(Ed25519.verify(k, byteArrayOf(0xaf.toByte(), 0x83.toByte()), s))
        assertFalse(Ed25519.verify(k, m + 0, s))
    }

    @Test
    fun `the JDK's signatures verify, and another key's or message's do not`() {
        val random = Random(8032)
        val a = TestPacks()
        val b = TestPacks()
        repeat(40) {
            val message = random.nextBytes(random.nextInt(0, 300))
            val signature = a.sign(message)
            assertTrue(Ed25519.verify(a.publicKey, message, signature))
            assertFalse(Ed25519.verify(b.publicKey, message, signature))
            if (message.isNotEmpty()) {
                val other = message.copyOf().also { it[random.nextInt(it.size)] = (it[0].toInt() + 1).toByte() }
                if (!other.contentEquals(message)) assertFalse(Ed25519.verify(a.publicKey, other, signature))
            }
        }
    }

    @Test
    fun `a signature the JDK refuses is refused too`() {
        val a = TestPacks()
        val message = "pack".toByteArray()
        val good = a.sign(message)
        // S + L: the same point equation, S out of range; RFC 8032 (and the JDK) refuse it
        val l = BigInteger.ONE.shiftLeft(252).add(BigInteger("27742317777372353535851937790883648493"))
        val s = BigInteger(1, good.copyOfRange(32, 64).reversedArray()).add(l)
        val sBytes = s.toByteArray().reversedArray().copyOf(32)
        val malleable = good.copyOfRange(0, 32) + sBytes
        assertFalse(Ed25519.verify(a.publicKey, message, malleable))
        val jdk = Signature.getInstance("Ed25519").run {
            initVerify(a.keys.public)
            update(message)
            runCatching { verify(malleable) }.getOrDefault(false)
        }
        assertEquals(jdk, false)
    }

    @Test
    fun `wrong sizes and points off the curve are refused`() {
        val (key, message, signature) = vectors[0]
        assertFalse(Ed25519.verify(hex(key).copyOf(31), hex(message), hex(signature)))
        assertFalse(Ed25519.verify(hex(key), hex(message), hex(signature).copyOf(63)))
        // y = p (not below p), and y = 2 (no x on the curve has it)
        val p = ByteArray(32) { 0xff.toByte() }.also { it[0] = 0xed.toByte(); it[31] = 0x7f }
        assertFalse(Ed25519.verify(p, hex(message), hex(signature)))
        val two = ByteArray(32).also { it[0] = 2 }
        assertFalse(Ed25519.verify(two, hex(message), hex(signature)))
        // x = 0 with its sign bit set: y = 1, the identity, written negative
        val negativeZero = ByteArray(32).also { it[0] = 1; it[31] = 0x80.toByte() }
        assertFalse(Ed25519.verify(negativeZero, hex(message), hex(signature)))
    }
}
