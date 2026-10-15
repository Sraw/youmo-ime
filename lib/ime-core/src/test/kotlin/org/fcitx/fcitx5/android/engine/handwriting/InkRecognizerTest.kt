/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.handwriting

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

class InkRecognizerTest {

    private val chars = "天地人"

    /** A net that records what it was run on and answers [answer] of the steps it was given. */
    private class Fake(val answer: (shape: LongArray) -> FloatArray) : InkNet {
        var shape = LongArray(0)
        var x = FloatArray(0)
        override fun run(x: FloatArray, shape: LongArray): FloatArray {
            this.x = x
            this.shape = shape
            return answer(shape)
        }
    }

    private val none = Fake { error("not run") }

    private fun recognizer(
        image: InkNet = none,
        path: InkNet = none,
        prior: FloatArray = FloatArray(chars.length),
    ) = InkRecognizer(image, path, chars, prior)

    private val stroke = listOf(floatArrayOf(0f, 0f, 30f, 0f, 30f, 30f), floatArrayOf(10f, 10f, 20f, 20f))

    @Test
    fun `one character is its image and path models weighed, and its frequency`() {
        // the image says 地, the path 人 louder: 0.7 x image + 0.3 x path
        val image = Fake { floatArrayOf(0f, 3f, 0f) }
        val path = Fake { floatArrayOf(0f, 0f, 4f) }
        val read = recognizer(image, path).character(stroke)
        assertEquals(listOf("地", "人", "天"), read.texts)
        assertArrayEquals(longArrayOf(1, 9, 64, 64), image.shape)
        assertEquals(3, path.shape.size)
        assertEquals(7L, path.shape[2])
        assertEquals(path.x.size.toLong(), path.shape[1] * 7)
        // log-probabilities: the scores of all three would sum to at most one each
        assertTrue(read.scores.all { it <= 0.0 })
        // a rare enough character falls behind: 0.2 x its log frequency
        val common = recognizer(image, path, prior = floatArrayOf(0f, -20f, 0f)).character(stroke, count = 2)
        assertEquals(listOf("人", "天"), common.texts)
    }

    @Test
    fun `a model answering the wrong size, or a frequency missing, is refused`() {
        assertThrows(IllegalArgumentException::class.java) { recognizer(prior = FloatArray(2)) }
        val short = Fake { floatArrayOf(0f, 1f) }
        assertThrows(IllegalArgumentException::class.java) { recognizer(short, short).character(stroke) }
        assertThrows(IllegalArgumentException::class.java) { InkReading(listOf("天"), DoubleArray(0)) }
    }

    @Test
    fun `log-softmax gives log-probabilities in place`() {
        val v = floatArrayOf(9f, 1f, 2f, 3f, 9f)
        InkRecognizer.logSoftmax(v, 1, 3)
        val z = ln(exp(1.0) + exp(2.0) + exp(3.0))
        assertEquals(9f, v[0])
        assertEquals(9f, v[4])
        for ((i, x) in listOf(1.0, 2.0, 3.0).withIndex()) assertTrue(abs(v[i + 1] - (x - z)) < 1e-6)
    }
}
