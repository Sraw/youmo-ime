/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The simulated hands of `pinyin --taps` ([Taps]). */
class TapsTest {

    private val samples = listOf(Sample("nihao", "你好", "t"), Sample("bugao", "不高", "t"), Sample("lama", "拉马", "t"))

    private fun taps(hand: String, seed: Long = 1L) = Taps.convert(samples, Taps.Spec(Taps.Hand.of(hand), 1f, seed))

    @Test
    fun theInputIsTheKeysAndEachKeyHasAPlace() {
        val typed = taps("perfect")
        assertEquals(NineKeyLetters.G14.typings("nihao").single(), typed[0].input)
        typed.forEach { assertEquals(it.input.length, it.taps!!.size) }
    }

    @Test
    fun aPerfectHandTapsEachLettersCentre() {
        // n i h a o: right, right, right, left, left; L and M keys of one letter with no place, as sent
        assertEquals(listOf(0.75f, 0.75f, 0.75f, 0.25f, 0.25f), taps("perfect")[0].taps)
        assertEquals(listOf(Float.NaN, 0.25f, Float.NaN, 0.25f), taps("perfect")[2].taps)
    }

    @Test
    fun theSameSeedTapsTheSameAndAnotherNot() {
        assertEquals(taps("sigma0.2").map { it.taps }, taps("sigma0.2").map { it.taps })
        assertTrue(taps("sigma0.2").map { it.taps } != taps("sigma0.2", seed = 2L).map { it.taps })
    }

    @Test
    fun theHandsDrawAsTheyAreDefined() {
        // many taps of right letters (ni ni …: n and i, 0.75) in one sample, one draw after another:
        // each hand's mean where it says, all on the key's places
        val many = listOf(Sample("ni".repeat(200), "你".repeat(200), "t"))
        fun mean(hand: String) = Taps.convert(many, Taps.Spec(Taps.Hand.of(hand), 1f)).single().taps!!.average()
        assertEquals(0.75, mean("sigma0.2"), 0.03)
        // 0.9 cut at the right edge: some 0.86
        assertEquals(0.86, mean("right0.15"), 0.03)
        assertEquals(0.75 - 0.15, mean("left0.15"), 0.03)
        assertEquals(0.5, mean("middle"), 0.02)
        assertEquals(0.5, mean("uniform"), 0.04)
        val places = Taps.convert(many, Taps.Spec(Taps.Hand.UNIFORM, 1f)).single().taps!!
        assertTrue(places.all { it in 0f..1f && (it * 16f) % 1f == 0f })
    }

    @Test
    fun onlyTheNamedHandsAndASpecOfTwoOrThreeParts() {
        assertEquals(Taps.Spec(Taps.Hand.SIGMA03, 1.5f, 7L), Taps.parse("sigma0.3:1.5:7"))
        assertEquals(Taps.Spec(Taps.Hand.MIDDLE, 1f, 1L), Taps.parse("middle:1"))
        assertThrows(IllegalArgumentException::class.java) { Taps.parse("0.2:1") }
        assertThrows(IllegalArgumentException::class.java) { Taps.parse("perfect") }
        // α a cost: a negative or NaN one would reward the far edge or poison every path
        assertThrows(IllegalArgumentException::class.java) { Taps.parse("perfect:-1") }
        assertThrows(IllegalArgumentException::class.java) { Taps.parse("perfect:NaN") }
    }
}
