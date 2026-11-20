/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.pinyin.TouchBoxes.G14
import org.fcitx.fcitx5.android.engine.pinyin.TouchBoxes.G18
import org.fcitx.fcitx5.android.engine.pinyin.TouchBoxes.tap
import org.fcitx.fcitx5.android.engine.pinyin.TouchBoxes.touchesAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A tap slipped onto a two-letter key is put onto its edge nearest the key hit, so the near letter is meant. */
class NeighbourSlipPlaceTest {

    private val g14 = T9Segmenter(abbreviations = false, letters = NineKeyLetters.G14, neighbours = true)
    private val g18 = T9Segmenter(abbreviations = false, letters = NineKeyLetters.G18, neighbours = true)

    // the slips of [typed] with [at] tapped as [touch], each with how far it goes against the taps
    private fun T9Segmenter.slips(typed: String, taps: FloatArray, at: Int, touch: Touch): Map<String, Float> {
        val g = segment(typed, taps, touchesAt(2, at, touch))
        val out = HashMap<String, Float>()
        for (e in g.edges(0)) {
            if (g.to(e) != 2 || !g.isSlip(e)) continue
            val m = g.matches(e)
            for (i in 0 until m.size) out[Syllables.spelling(m.syllable(i))] = g.against(e, i)
        }
        return out
    }

    // the first key tapped as [touch] at [first] across it, the second in its middle
    private fun T9Segmenter.slips(typed: String, first: Float, touch: Touch) = slips(typed, floatArrayOf(first, MIDDLE), 0, touch)

    // [near] read first: none against it, and [far] (the other letter of the key slipped to) well against
    private fun Map<String, Float>.prefers(near: String, far: String) {
        assertEquals(toString(), 0f, getValue(near), EPSILON)
        assertTrue(toString(), getValue(far) > FAR)
    }

    @Test
    @Uat("PY-152")
    fun theFourteenKeysReadTheHalfOfTheKeySlippedToNearestTheTap() {
        val ui = g14Code('u')
        // UI by its I, toward OP: o, not p; by its U, toward TY: y, not t
        g14.slips(g14("u u"), RIGHT, tap(G14, ui, dx = EDGE)).prefers("ou", "pu")
        g14.slips(g14("u u"), LEFT, tap(G14, ui, dx = -EDGE)).prefers("yu", "tu")
        // the mirrors: OP by its O, toward UI: i, not u; TY by its Y, toward UI: u, not i
        g14.slips(g14("j o"), floatArrayOf(MIDDLE, LEFT), 1, tap(G14, g14Code('o'), dx = -EDGE)).let { it.prefers("ji", "ju"); it.prefers("ji", "ku") }
        g14.slips(g14("j t"), floatArrayOf(MIDDLE, RIGHT), 1, tap(G14, g14Code('t'), dx = EDGE)).prefers("ju", "ji")
        g14.slips(g14("l u"), LEFT, tap(G14, g14Code('l'), dx = -EDGE)).prefers("ku", "ju")
    }

    @Test
    @Uat("PY-152")
    fun theEighteenKeysReadTheHalfOfTheKeySlippedToNearestTheTap() {
        val k = NineKeyLetters.G18::key
        // RT by its R, toward WE: e, not w; WE by its E, toward RT: r, not t; SD by its D, toward FG: f, not g
        g18.slips(g18("r i"), LEFT, tap(G18, k('r'), dx = -EDGE)).prefers("ei", "wo")
        g18.slips(g18("w u"), RIGHT, tap(G18, k('w'), dx = EDGE)).prefers("ru", "tu")
        g18.slips(g18("s u"), RIGHT, tap(G18, k('s'), dx = EDGE)).prefers("fu", "gu")
        // from keys of one letter: P by its left, toward IO, o, not i; U by its right, toward IO, i, not o
        g18.slips(g18("y p"), floatArrayOf(MIDDLE, LEFT), 1, tap(G18, k('p'), dx = -EDGE)).prefers("yo", "yi")
        g18.slips(g18("p u"), floatArrayOf(MIDDLE, RIGHT), 1, tap(G18, k('u'), dx = EDGE)).prefers("pi", "po")
    }

    // up and down: the tap's x on the key above or below picks its nearer letter, and in the middle of one neither
    @Test
    @Uat("PY-152")
    fun aSlipUpOrDownGoesByWhereAcrossTheKeyTheTapFell() {
        // 14 keys: UI tapped at its bottom, onto JK right under it: by its right, k; by its left, j; in the middle neither
        val ui = g14Code('u')
        g14.slips(g14("u u"), RIGHT, tap(G14, ui, dx = SIDE, dy = EDGE)).prefers("ku", "ju")
        g14.slips(g14("u u"), LEFT, tap(G14, ui, dx = -SIDE, dy = EDGE)).prefers("ju", "ku")
        g14.slips(g14("u u"), MIDDLE, tap(G14, ui, dy = EDGE)).let {
            assertEquals(it.toString(), it.getValue("ju"), it.getValue("ku"), EPSILON)
            assertEquals(it.toString(), 0f, it.getValue("ju"), EPSILON)
        }
        // 18 keys: JK tapped at its top, under IO (the rows' keys of other widths): over O, o; over I, i
        val jk = NineKeyLetters.G18.key('j')
        val y = NineKeyLetters.G18.key('y')
        fun up(dx: Float) = g18.slips("$y$jk", floatArrayOf(MIDDLE, HALF + dx), 1, tap(G18, jk, dx = dx, dy = -EDGE))
        up(0.4f).prefers("yo", "yi")
        up(-0.05f).prefers("yi", "yo")
    }

    private companion object {
        const val EDGE = 0.45f
        const val SIDE = 0.3f
        const val HALF = 0.5f
        const val MIDDLE = 0.5f
        const val LEFT = 0.05f
        const val RIGHT = 0.95f
        const val FAR = 0.3f
        const val EPSILON = 1e-4f
    }
}
