/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

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
}
