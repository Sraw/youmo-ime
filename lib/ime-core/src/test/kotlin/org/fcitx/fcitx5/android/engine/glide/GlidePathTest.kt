/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.glide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlidePathTest {

    private fun path(keys: String, layout: GlideLayout = GlideLayout.QWERTY) =
        keys.flatMap { layout.centre(it).toList() }.toFloatArray()

    // the cost of [word] for a path through [keys], null where it is none
    private fun cost(
        keys: String, word: String, layout: GlideLayout = GlideLayout.QWERTY, path: FloatArray = path(keys),
        weights: GlidePath.Weights = GlidePath.Weights(),
    ): Float? {
        val g = GlidePath(path, layout, weights, 30)
        for ((i, c) in word.withIndex()) if (!g.step(i, c)) return null
        return g.cost(word.length)
    }

    @Test
    fun aTurnNoKeyOfTheWordIsAtCostsByHowFarItTurns() {
        val none = GlidePath.Weights(corner = 0f)
        // n-i-h-a-o turns at h, which niao has not
        assertEquals(0f, cost("nihao", "nihao")!! - cost("nihao", "nihao", weights = none)!!, 1e-4f)
        assertTrue(cost("nihao", "niao")!! > cost("nihao", "niao", weights = none)!! + 0.1f)
        // a straight line turns nowhere
        val straight = FloatArray(20) { if (it % 2 == 0) it * 0.125f else 0f }
        assertEquals(0, GlidePath.Corners(straight, 10, 0.8f).at.size)
        // a right angle: one turn, by 1 (a cosine of 0), at the corner
        val l = FloatArray(18).also { for (i in 0..8) if (i <= 4) { it[2 * i] = i * 0.25f } else { it[2 * i] = 1f; it[2 * i + 1] = (i - 4) * 0.25f } }
        val corners = GlidePath.Corners(l, 9, 0.8f)
        assertEquals(listOf(4), corners.at.toList())
        assertEquals(1f, corners.turn.single(), 1e-4f)
    }

    @Test
    fun startingAwayFromTheFirstKeyCosts() {
        val off = path("the").also { it[0] += 0.8f }
        assertTrue(cost("the", "the", path = off)!! > cost("the", "the", path = off, weights = GlidePath.Weights(edges = 0f))!!)
    }

    @Test
    fun aWordsOwnPathCostsLeast() {
        val own = cost("the", "the")!!
        assertTrue(own < 0.01f)
        assertTrue(own < cost("thre", "the")!!)
        assertNull(cost("the", "they"))
        // a letter doubled passed once, and no dearer for it
        assertEquals(cost("helo", "helo")!!, cost("helo", "hello")!!, 1e-4f)
    }

    @Test
    fun lettersOnOneKeyAreWhereThePathWas() {
        val nine = GlideLayout.nineKeys()
        assertTrue(nine.sameKey('t', 'u'))
        assertFalse(nine.sameKey('t', 'o'))
        assertFalse(GlideLayout.QWERTY.sameKey('a', 's'))
        // t and u both on 8: tuo is the path from 8 to 6, as to is
        val to = path("to", nine)
        assertEquals(cost("to", "to", nine, to)!!, cost("to", "tuo", nine, to)!!, 1e-4f)
        // all on one key: a tap
        assertNull(cost("ta", "ta", nine, path = path("ta", nine) + path("u", nine)))
    }

    @Test
    fun aLetterOnTwoKeysIsNearEither() {
        // the split keyboard: g and v on both halves, the right one far off
        val split = GlideLayout(GlideLayout.rows().let { qwerty -> "abcdefghijklmnopqrstuvwxyz".map { it to qwerty.centre(it) } } + listOf('g' to (12f to 2.1f), 'v' to (14f to 3.5f)))
        val viaLeft = path("give", GlideLayout.QWERTY)
        assertTrue(cost("give", "give", split, viaLeft)!! < 0.01f)
    }

    @Test
    fun aPathOfOnePointIsNone() {
        val g = GlidePath(floatArrayOf(1f, 1f), GlideLayout.QWERTY, GlidePath.Weights(), 30)
        assertEquals(0, g.n)
        assertEquals(false, g.step(0, 'q'))
        // a letter the layout lacks
        assertNull(cost("the", "the", GlideLayout(mapOf('t' to (4.5f to 0.7f), 'e' to (2.5f to 0.7f)))))
    }

    @Test
    fun resampledEvenlyAlongItsLength() {
        val line = GlidePath.resample(floatArrayOf(0f, 0f, 2f, 0f, 2f, 2f), 3, 5, FloatArray(10))
        assertEquals(listOf(0f, 0f, 1f, 0f, 2f, 0f, 2f, 1f, 2f, 2f), line.toList())
        assertEquals(listOf(1f, 1f, 1f, 1f), GlidePath.resample(floatArrayOf(1f, 1f), 1, 2, FloatArray(4)).toList())
        // no two points further apart than a quarter key, none nearer than an eighth but the last
        val dense = GlidePath.dense(floatArrayOf(0f, 0f, 1f, 0f))
        assertTrue(dense.size / 2 >= 5)
        assertEquals(1f, dense[dense.size - 2])
        assertEquals(listOf(0f, 0f, 0.5f, 0f, 0.55f, 0f), GlidePath.thin(floatArrayOf(0f, 0f, 0.01f, 0f, 0.5f, 0f, 0.52f, 0f, 0.55f, 0f)).toList())
    }
}
