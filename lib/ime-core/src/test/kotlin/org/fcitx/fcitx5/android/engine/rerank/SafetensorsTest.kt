/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.rerank

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The app's kernel takes an int8 matrix only from the mapped file, so a copied weight would throw on the phone. */
class SafetensorsTest {

    // a 2 × 3 int8 matrix "w" with its scales, after [before] bytes that are no part of the file
    private fun mapped(before: Int): ByteBuffer {
        val header = """{"w":{"dtype":"I8","shape":[2,3],"data_offsets":[0,6]},"w.scale":{"dtype":"F32","shape":[2],"data_offsets":[6,14]}}"""
            .toByteArray(Charsets.UTF_8)
        val file = ByteBuffer.allocateDirect(before + 8 + header.size + 14).order(ByteOrder.LITTLE_ENDIAN)
        file.put(ByteArray(before) { Byte.MAX_VALUE }).putLong(header.size.toLong()).put(header)
            .put(byteArrayOf(1, 2, 3, 4, 5, 6)).putFloat(0.5f).putFloat(2f)
        file.flip()
        file.position(before)
        return file.asReadOnlyBuffer()
    }

    @Test
    fun anInt8MatrixIsHandedToTheKernelWhereItLiesInTheMappedFile() {
        var given: ByteBuffer? = null
        val kernel = MatrixKernel { q, scales, rows, columns, x, y ->
            given = q
            MatrixKernel.JVM.times(q, scales, rows, columns, x, y)
        }
        val y = FloatArray(2)
        Safetensors(mapped(before = 5)).matrix("w", 2, 3, kernel).times(floatArrayOf(1f, 1f, 1f), y)
        val q = checkNotNull(given)
        // the file's own bytes: a copy would be a heap buffer, or a direct one that could be written
        assertTrue(q.isDirect)
        assertTrue(q.isReadOnly)
        assertEquals(6, q.capacity())
        assertEquals(listOf<Byte>(1, 2, 3, 4, 5, 6), (0 until 6).map { q[it] })
        assertArrayEquals(floatArrayOf(6 * 0.5f, 15 * 2f), y, 0f)
    }
}
