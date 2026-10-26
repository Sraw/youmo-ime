/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.g14
import org.fcitx.fcitx5.android.engine.pinyin.g14Code
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Where each key was tapped, kept beside the input through its edits ([PressedKeys.taps]). */
class PressedKeysTapsTest {

    private val g14 = NineKeyLetters.G14

    private fun FloatArray?.shown() = this?.joinToString(" ") { if (it.isNaN()) "-" else it.toString() }

    // ni hao, a tap each: BN UI GH AS OP
    private fun typed(): Pair<PressedKeys, StringBuilder> {
        val keys = PressedKeys()
        val input = StringBuilder()
        val codes = g14.typings("nihao").single()
        val places = listOf(0.75f, 0.75f, 0.75f, 0.25f, 0.75f)
        for ((k, c) in codes.withIndex()) {
            keys.insert(k, c.toString(), tapAt = places[k], input = input, letters = g14)
            input.append(c)
        }
        return keys to input
    }

    @Test
    fun eachPlaceIsAtItsKeyAndTheSpanAsLongAsAsked() {
        val (keys, _) = typed()
        assertEquals("0.75 0.75 0.75 0.25 0.75", keys.taps(0, 5).shown())
        assertEquals("0.75 0.25", keys.taps(2, 4).shown())
        assertEquals(3, keys.taps(2, 5)!!.size)
        // the keys as learning files them, as with no place
        val plain = PressedKeys().apply { insert(0, g14.typings("nihao").single()) }
        assertEquals(plain.keys(0, 5), keys.keys(0, 5))
        assertNull(plain.taps(0, 5))
    }

    @Test
    fun theyFollowADeletionAnInsertionAndASyllableTakenAndGivenBack() {
        val (keys, input) = typed()
        keys.delete(1, input, g14)
        input.deleteCharAt(1)
        assertEquals("0.75 0.75 0.25 0.75", keys.taps(0, 4).shown())
        // typed again in the middle
        keys.insert(1, g14("ui"), tapAt = 0.25f, input = input, letters = g14)
        input.insert(1, g14Code('u'))
        assertEquals("0.75 0.25 0.75 0.25 0.75", keys.taps(0, 5).shown())
        // ni taken: its two places one run of the letters and a separator, no place; then given back
        val was = keys.runs(0, 2)
        keys.replace(0, 2, 2, "bnui", added = 1)
        assertEquals("- - - 0.75 0.25 0.75", keys.taps(0, 6).shown())
        keys.putBack(0, 3, was)
        assertEquals("0.75 0.25 0.75 0.25 0.75", keys.taps(0, 5).shown())
        // a place of a syllable taken deleted: the rest split, no place
        keys.replace(0, 2, 2, "bnui", added = 1)
        keys.delete(1, "ni'", g14)
        assertEquals("- - 0.75 0.25 0.75", keys.taps(0, 5).shown())
    }

    @Test
    fun aTextOfSeveralAndALetterTappedAgainHaveNoPlace() {
        val keys = PressedKeys()
        keys.insert(0, g14("bn ui"), tapAt = 0.75f)
        assertNull(keys.taps(0, 2))
        keys.insert(2, g14("gh"), tapAt = 0.25f)
        keys.set(2, "h", multiTap = true)
        assertNull(keys.taps(0, 3))
    }
}
