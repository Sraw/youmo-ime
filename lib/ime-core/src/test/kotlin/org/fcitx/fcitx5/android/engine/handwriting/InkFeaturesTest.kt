/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.handwriting

import org.fcitx.fcitx5.android.engine.rerank.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.GZIPInputStream
import kotlin.math.abs

/**
 * Held to the training code's features: `ink-golden.json.gz` is `gen_golden.py` (in the recognizers'
 * training sources) run on made-up strokes, through `portable.py`, which the training pipeline's own
 * features equal (thinning bit for bit, the planes to 4e-7).
 */
class InkFeaturesTest {

    @Suppress("UNCHECKED_CAST")
    private val golden: Map<String, Any?> by lazy {
        val text = GZIPInputStream(javaClass.getResourceAsStream("ink-golden.json.gz")!!).reader().readText()
        Json.parse(text) as Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    private fun cases(key: String) = golden[key] as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private fun strokes(case: Map<String, Any?>) =
        (case["strokes"] as List<List<Double>>).map { s -> FloatArray(s.size) { s[it].toFloat() } }

    @Suppress("UNCHECKED_CAST")
    private fun floats(case: Map<String, Any?>, key: String) = (case[key] as List<Double>).map { it.toFloat() }.toFloatArray()

    private fun bits(case: Map<String, Any?>, key: String) = (case[key] as String).map { it == '1' }.toBooleanArray()

    private fun assertClose(expected: FloatArray, actual: FloatArray, tolerance: Float) {
        assertEquals(expected.size, actual.size)
        val worst = expected.indices.maxOf { abs(expected[it] - actual[it]) }
        assertTrue("worst difference $worst", worst <= tolerance)
    }

    @Test
    fun `the raster and its skeleton are the training code's, pixel for pixel`() {
        for (case in cases("chars")) {
            val raster = InkFeatures.rasterize(strokes(case))
            assertArrayEquals(bits(case, "raster"), raster)
            assertArrayEquals(bits(case, "skeleton"), InkFeatures.thin(raster, InkFeatures.RASTER, InkFeatures.RASTER))
        }
    }

    @Test
    fun `the character's image planes are the training code's`() {
        for (case in cases("chars")) {
            val planes = InkFeatures.charImage(strokes(case))
            assertEquals((1 + InkFeatures.DIRS) * InkFeatures.SIZE * InkFeatures.SIZE, planes.size)
            assertClose(floats(case, "planes"), planes, 1e-4f)
        }
    }

    @Test
    fun `the character's path is the training code's`() {
        for (case in cases("chars")) assertClose(floats(case, "path"), InkFeatures.charPath(strokes(case)), 1e-5f)
    }

    @Test
    fun `a lone point is a dot, and a path of one point has no move`() {
        val dot = listOf(floatArrayOf(10f, 10f))
        assertTrue(InkFeatures.rasterize(dot).count { it } in 15..25)
        val path = InkFeatures.charPath(dot)
        assertEquals(InkFeatures.CHAR_PATH_DIM, path.size)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 1f), path, 0f)
    }

    @Test
    fun `resampling keeps both ends and steps evenly between them`() {
        val q = InkFeatures.resample(floatArrayOf(0f, 0f, 10f, 0f), 3.0)
        assertArrayEquals(floatArrayOf(0f, 0f, 3f, 0f, 6f, 0f, 9f, 0f, 10f, 0f), q, 1e-6f)
        // a stroke that never moves is its one point
        assertArrayEquals(floatArrayOf(5f, 5f), InkFeatures.resample(floatArrayOf(5f, 5f, 5f, 5f), 1.0), 0f)
    }

    @Test
    fun `no ink, or a stroke that is not pairs, is refused`() {
        assertThrows(IllegalArgumentException::class.java) { InkFeatures.charPath(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { InkFeatures.charImage(listOf(FloatArray(0))) }
        assertThrows(IllegalArgumentException::class.java) { InkFeatures.charPath(listOf(floatArrayOf(1f, 2f, 3f))) }
    }
}
