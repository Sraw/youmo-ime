/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.scene.skin.SkinPlatform
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.DeflaterOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Sogou's container sealed here with its published key, as the app reads it. */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class AndroidSkinCodecSogouTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private fun zlib(b: ByteArray) = ByteArrayOutputStream().also { o -> DeflaterOutputStream(o).use { it.write(b) } }.toByteArray()

    private fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(hex(SkinPlatform.SOGOU_KEY), "AES"), IvParameterSpec(hex(SkinPlatform.SOGOU_IV)))
        return "Skin".toByteArray() + byteArrayOf(2, 0, 0, 0) + cipher.doFinal(plain)
    }

    // rawLen as said, then the payload deflated
    private fun container(payload: ByteArray, rawLen: Int = payload.size, tail: ByteArray = ByteArray(0)) =
        seal(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(rawLen).array() + zlib(payload) + tail)

    private val payload = ByteArray(5000) { (it * 31 % 251).toByte() }

    private fun plain(c: ByteArray, max: Int = 1 shl 20) = AndroidSkinCodec.sogouPlain(c, max)

    @Test
    fun aSealedPayloadIsReadBack() {
        assertArrayEquals(payload, plain(container(payload)))
        // past the deflated stream: not the payload's
        assertArrayEquals(payload, plain(container(payload, tail = byteArrayOf(1, 2, 3))))
    }

    @Test
    fun aLengthNotTheOneSaidIsNoPayload() {
        assertNull(plain(container(payload, rawLen = payload.size + 1)))
        assertNull(plain(container(payload, rawLen = payload.size - 1)))
        assertNull(plain(container(payload, rawLen = -1)))
    }

    @Test
    fun pastTheLimitOrTheRatioIsNotInflated() {
        assertNull(plain(container(payload), max = payload.size - 1))
        // 4 MB of zeros deflate to a few KB: past 200 to 1
        val zeros = ByteArray(4 shl 20)
        assertNull(plain(container(zeros), max = 8 shl 20))
    }

    @Test
    fun aPhoneLowOnMemoryTakesLess() {
        val big = ByteArray(AndroidSkinCodec.LOW_MEMORY_MAX_BYTES + 1)
        // a tail past the deflated zeros keeps it within the ratio
        val c = container(big, tail = ByteArray(1 shl 19))
        assertEquals(big.size, AndroidSkinCodec.sogouPlain(c, Int.MAX_VALUE, lowMemory = false)?.size)
        assertNull(AndroidSkinCodec.sogouPlain(c, Int.MAX_VALUE, lowMemory = true))
    }

    @Test
    fun aBrokenContainerIsNoPayload() {
        val c = container(payload)
        assertNull(plain(c.copyOf(8)))
        assertNull(plain(c.copyOf(c.size - 5)))
        assertNull(plain(c.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }))
        assertNull(plain(c.copyOf().also { it[20] = (it[20] + 1).toByte() }))
    }
}
