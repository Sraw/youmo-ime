/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.assertRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

class StrokeShapeTest {

    // a line from (x, y), [length] key widths at [degrees] from level to the right, up positive, in [steps]
    private fun line(degrees: Double, length: Float = 1.5f, x: Float = 2f, y: Float = 1f, steps: Int = 12): List<Pair<Float, Float>> {
        val r = Math.toRadians(degrees)
        return (0..steps).map { i -> (x + length * i / steps * cos(r).toFloat()) to (y - length * i / steps * sin(r).toFloat()) }
    }

    // [legs] one after the other, each from where the last ended
    private fun path(vararg legs: Pair<Double, Float>): FloatArray {
        val points = ArrayList<Pair<Float, Float>>()
        var x = 2f
        var y = 1f
        for ((degrees, length) in legs) {
            val leg = line(degrees, length, x, y)
            points += if (points.isEmpty()) leg else leg.drop(1)
            x = leg.last().first
            y = leg.last().second
        }
        return points.flatMap { listOf(it.first, it.second) }.toFloatArray()
    }

    private fun shape(vararg legs: Pair<Double, Float>) = StrokeShape.classify(path(*legs))

    // [legs] as [path] has them, each corner drawn round as a steady finger draws it: an arc of [radius] key
    // widths, the legs on either side shortened by what the arc takes of them
    private fun rounded(radius: Float, vararg legs: Pair<Double, Float>): FloatArray {
        val turns = (0 until legs.size - 1).map { i -> ((legs[i + 1].first - legs[i].first + 540.0) % 360.0) - 180.0 }
        val cuts = turns.map { radius * tan(Math.toRadians(abs(it)) / 2).toFloat() }
        val out = arrayListOf(2f, 1f)
        fun step(degrees: Double, length: Float) {
            val r = Math.toRadians(degrees)
            out += out[out.size - 2] + length * cos(r).toFloat()
            out += out[out.size - 2] - length * sin(r).toFloat()
        }
        legs.forEachIndexed { i, (degrees, length) ->
            val straight = length - (if (i > 0) cuts[i - 1] else 0f) - (if (i < cuts.size) cuts[i] else 0f)
            repeat(STEPS) { step(degrees, straight / STEPS) }
            if (i < turns.size) {
                val arc = radius * Math.toRadians(abs(turns[i])).toFloat()
                for (k in 0 until ARC_STEPS) step(degrees + turns[i] * (k + 0.5) / ARC_STEPS, arc / ARC_STEPS)
            }
        }
        return out.toFloatArray()
    }

    // [path] with every other place off its line by [by], across and down in turn
    private fun wobbled(path: FloatArray, by: Float) = FloatArray(path.size) { path[it] + if (it / 2 % 2 == 0) by else -by }

    @Test
    fun eachStrokeByItsDirection() {
        assertEquals('h', shape(0.0 to 1.5f))
        assertEquals('s', shape(-90.0 to 1.8f))
        assertEquals('p', shape(-135.0 to 1.5f))
        assertEquals('n', shape(-45.0 to 1.2f))
        // right to left is level too; up to the right is 提, which the strokes count as 横
        assertEquals('h', shape(180.0 to 1.5f))
        assertEquals('h', shape(50.0 to 1.2f))
    }

    @Test
    fun upOrUpToTheLeftIsNoStroke() {
        assertNull(shape(90.0 to 1.5f))
        assertNull(shape(120.0 to 1.5f))
        assertNull(StrokeShape.classify(floatArrayOf(1f, 1f)))
        // back where it began
        assertNull(StrokeShape.classify(floatArrayOf(1f, 1f, 1f, 1f)))
    }

    @Test
    fun theEdgesOfEachDirection() {
        assertEquals('h', shape(-29.0 to 1.5f))
        assertEquals('n', shape(-31.0 to 1.5f))
        assertEquals('n', shape(-69.0 to 1.5f))
        assertEquals('s', shape(-71.0 to 1.5f))
        assertEquals('s', shape(-109.0 to 1.5f))
        assertEquals('p', shape(-111.0 to 1.5f))
        assertEquals('p', shape(-149.0 to 1.5f))
        assertEquals('h', shape(-151.0 to 1.5f))
        assertEquals('h', shape(74.0 to 1.5f))
        assertNull(shape(76.0 to 1.5f))
    }

    @Test
    fun aBendIsZheAHookAtTheEndIsNone() {
        // 横折: right, then down; 竖折: down, then right; 撇折: down-left, then right
        assertEquals('z', shape(0.0 to 1.5f, -90.0 to 1.5f))
        assertEquals('z', shape(-90.0 to 1.8f, 0.0 to 1.2f))
        assertEquals('z', shape(-120.0 to 1f, 10.0 to 1f))
        // a short arm still bends, a third of the other
        assertEquals('z', shape(0.0 to 1.5f, -90.0 to 0.5f))
        // a little hook at a stroke's end is no bend: 竖 with its 钩
        assertEquals('s', shape(-90.0 to 2f, 135.0 to 0.25f))
    }

    @Test
    fun aCurveWithATickIsNoZheACornerIs() {
        // a 撇 bowed a little (an eighth of its length), a tick up at its end: it turned as much where it
        // strayed most as a corner does, and was 折 (issue #4); its arms are curves, not straight.
        // 0.9 key widths, ticked right as it starts, bowed out, flicked up to the left as it ends
        val pie = floatArrayOf(
            2.0f, 1.0f, 2.03f, 1.0f, 2.06f, 1.0f, 2.09f, 1.0f, 2.08f, 1.06f, 2.07f, 1.13f, 2.06f, 1.19f, 2.04f, 1.24f,
            2.02f, 1.3f, 2.0f, 1.35f, 1.97f, 1.4f, 1.94f, 1.45f, 1.91f, 1.49f, 1.87f, 1.53f, 1.83f, 1.57f, 1.79f, 1.61f,
            1.74f, 1.65f, 1.69f, 1.68f, 1.63f, 1.71f, 1.57f, 1.74f, 1.54f, 1.71f, 1.51f, 1.67f, 1.48f, 1.64f,
        )
        // a 横 0.9 key widths rising 10°, ticked down as it starts, bowed down a little more than an eighth of
        // its length as a thumb sweeps, flicked up to the left as it ends
        val heng = ArrayList(path(-90.0 to 0.09f).toList())
        val (x0, y0) = heng[heng.size - 2] to heng.last()
        val r = Math.toRadians(10.0)
        for (i in 1..16) {
            val t = i / 16f
            val off = 4 * 0.15f * 0.9f * t * (1 - t)
            heng += x0 + 0.9f * t * cos(r).toFloat() + off * sin(r).toFloat()
            heng += y0 - 0.9f * t * sin(r).toFloat() + off * cos(r).toFloat()
        }
        val (x1, y1) = heng[heng.size - 2] to heng.last()
        for (i in 1..3) {
            heng += x1 - 0.135f * i / 3 * cos(Math.toRadians(45.0)).toFloat()
            heng += y1 - 0.135f * i / 3 * sin(Math.toRadians(45.0)).toFloat()
        }
        assertRows(
            "a bowed 撇 with a tick at each end: its arms curves, not a corner's (issue #4)" to { assertEquals('p', StrokeShape.classify(pie)) },
            "a bowed 横 ticked at both ends: read by its overall direction (issue #4)" to { assertEquals('h', StrokeShape.classify(heng.toFloatArray())) },
            "横折 drawn round, its arms straight: 折 still" to { assertEquals('z', shape(0.0 to 1.5f, -45.0 to 0.18f, -90.0 to 0.6f)) },
            "竖折 drawn round with a short 横: 折 still" to { assertEquals('z', shape(-90.0 to 1f, -45.0 to 0.1f, 0.0 to 0.8f)) },
            "竖折折钩: an arm that is itself a corner counts as one" to { assertEquals('z', shape(-90.0 to 0.6f, 0.0 to 0.5f, -100.0 to 0.8f, 135.0 to 0.3f)) },
            "横折 of a 横 1 and a 竖 0.5, its corner drawn round (radius 0.25), steady: the short arm carries half the arc, straight against the long one" to {
                assertEquals('z', StrokeShape.classify(rounded(0.25f, 0.0 to 1f, -90.0 to 0.5f)))
            },
            "横钩 (乛) of a 横 1 and a hook 0.35, its corner round (radius 0.14), steady: 折" to {
                assertEquals('z', StrokeShape.classify(rounded(0.14f, 0.0 to 1f, -135.0 to 0.35f)))
            },
        )
    }

    @Test
    fun aShakyLineIsStillTheLine() {
        assertEquals('h', StrokeShape.classify(wobbled(path(0.0 to 1.5f), 0.04f)))
        assertEquals('s', StrokeShape.classify(wobbled(path(-90.0 to 1.8f), 0.05f)))
        assertEquals('p', StrokeShape.classify(wobbled(path(-135.0 to 1.5f), 0.04f)))
        // a 撇 drawn as a curve, bowing out an eighth of its length, is still one
        val curve = (0..12).flatMap { i ->
            val t = i / 12f
            listOf(3f - 1.2f * t + 0.6f * t * (1 - t), 1f + 1.2f * t + 0.6f * t * (1 - t))
        }.toFloatArray()
        assertEquals('p', StrokeShape.classify(curve))
    }

    @Test
    fun onlyTheCountGivenIsRead() {
        val p = path(0.0 to 1.5f, -90.0 to 1.5f)
        // the first leg alone: 13 places
        assertEquals('h', StrokeShape.classify(p, 26))
    }

    private companion object {
        const val STEPS = 12
        const val ARC_STEPS = 8
    }
}
