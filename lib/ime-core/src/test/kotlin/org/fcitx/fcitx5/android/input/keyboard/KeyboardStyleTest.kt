/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.input.keyboard.KeyboardStyle.FLOATING
import org.fcitx.fcitx5.android.input.keyboard.KeyboardStyle.NORMAL
import org.fcitx.fcitx5.android.input.keyboard.KeyboardStyle.ONE_HANDED
import org.fcitx.fcitx5.android.input.keyboard.KeyboardStyle.SPLIT
import org.fcitx.fcitx5.android.input.keyboard.KeyboardStyleMigration.Split060
import org.fcitx.fcitx5.android.input.keyboard.KeyboardStyleMigration.Stored
import org.fcitx.fcitx5.android.input.keyboard.KeyboardStyleUnder.ONE_HANDED_OVER_NORMAL
import org.fcitx.fcitx5.android.input.keyboard.KeyboardStyleUnder.ONE_HANDED_OVER_SPLIT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.function.ThrowingRunnable
import org.fcitx.fcitx5.android.input.keyboard.KeyboardStyleUnder as Under

class KeyboardStyleTest {

    private fun state(style: KeyboardStyle, under: Under) = KeyboardStyleState.of(style, under, NORMAL)

    private fun assertState(style: KeyboardStyle, under: Under, actual: KeyboardStyleState) {
        assertEquals(style, actual.style)
        assertEquals(under, actual.under)
    }

    @Test
    fun eachOfTheSixteenPairsReadsAsOneOfTheEight() {
        // the under of 普通 and split is the style itself; one-handed is over a two-handed one; floating over any
        val legal = setOf(
            NORMAL to Under.NORMAL, SPLIT to Under.SPLIT,
            ONE_HANDED to Under.NORMAL, ONE_HANDED to Under.SPLIT,
            FLOATING to Under.NORMAL, FLOATING to Under.SPLIT, FLOATING to ONE_HANDED_OVER_NORMAL, FLOATING to ONE_HANDED_OVER_SPLIT,
        )
        val pairs = listOf(NORMAL, SPLIT).flatMap { f -> KeyboardStyle.entries.flatMap { s -> Under.entries.map { u -> Triple(f, s, u) } } }
        for ((fallback, style, under) in pairs) {
            val read = KeyboardStyleState.of(style, under, fallback)
            assertTrue("$style/$under read as ${read.style}/${read.under}", read.style to read.under in legal)
            assertEquals(style, read.style)
            val expected = when {
                style to under in legal -> under
                style == NORMAL -> Under.NORMAL
                style == SPLIT -> Under.SPLIT
                // one-handed over one-handed: over the shape's two-handed default
                else -> if (fallback == SPLIT) Under.SPLIT else Under.NORMAL
            }
            assertEquals("$style/$under over $fallback", expected, read.under)
        }
    }

    @Test
    fun nothingStoredIsTheShapesTwoHandedDefault() {
        assertState(NORMAL, Under.NORMAL, KeyboardStyleState.of(null, null, NORMAL))
        assertState(SPLIT, Under.SPLIT, KeyboardStyleState.of(null, null, SPLIT))
        // an under alone (a key left of a damaged file): what the style is says
        assertState(SPLIT, Under.SPLIT, KeyboardStyleState.of(null, Under.NORMAL, SPLIT))
        // a style alone: over the default
        assertState(FLOATING, Under.SPLIT, KeyboardStyleState.of(FLOATING, null, SPLIT))
        assertState(ONE_HANDED, Under.NORMAL, KeyboardStyleState.of(ONE_HANDED, null, NORMAL))
        assertThrows(IllegalArgumentException::class.java, ThrowingRunnable { KeyboardStyleState.of(null, null, FLOATING) })
    }

    @Test
    fun defaultSplitsTurnedFrom600Dp() {
        // 0.6.0's 屏幕够宽时: a phone on its side, a foldable's inner screen; not one beside a three-button bar (592 dp)
        assertEquals(SPLIT, KeyboardStyleState.default(landscape = true, widthDp = 600))
        assertEquals(SPLIT, KeyboardStyleState.default(landscape = true, widthDp = 673))
        assertEquals(NORMAL, KeyboardStyleState.default(landscape = true, widthDp = 599))
        assertEquals(NORMAL, KeyboardStyleState.default(landscape = true, widthDp = 592))
        // 竖屏 never, however wide it says it is
        assertEquals(NORMAL, KeyboardStyleState.default(landscape = false, widthDp = 900))
    }

    @Test
    fun setOnTheSettingsPage() {
        assertState(NORMAL, Under.NORMAL, state(SPLIT, Under.SPLIT).set(NORMAL))
        assertState(SPLIT, Under.SPLIT, state(FLOATING, ONE_HANDED_OVER_NORMAL).set(SPLIT))
        // one-handed over the two-handed one it was on
        assertState(ONE_HANDED, Under.SPLIT, state(SPLIT, Under.SPLIT).set(ONE_HANDED))
        assertState(ONE_HANDED, Under.NORMAL, state(NORMAL, Under.NORMAL).set(ONE_HANDED))
        assertState(ONE_HANDED, Under.SPLIT, state(ONE_HANDED, Under.SPLIT).set(ONE_HANDED))
        // floating: one-handed over what was under it, or under it as it was
        assertState(ONE_HANDED, Under.SPLIT, state(FLOATING, ONE_HANDED_OVER_SPLIT).set(ONE_HANDED))
        assertState(ONE_HANDED, Under.SPLIT, state(FLOATING, Under.SPLIT).set(ONE_HANDED))
        assertState(ONE_HANDED, Under.NORMAL, state(FLOATING, Under.NORMAL).set(ONE_HANDED))
        // floating over what there is
        assertState(FLOATING, Under.SPLIT, state(SPLIT, Under.SPLIT).set(FLOATING))
        assertState(FLOATING, ONE_HANDED_OVER_SPLIT, state(ONE_HANDED, Under.SPLIT).set(FLOATING))
        assertState(FLOATING, ONE_HANDED_OVER_NORMAL, state(ONE_HANDED, Under.NORMAL).set(FLOATING))
        assertState(FLOATING, ONE_HANDED_OVER_NORMAL, state(FLOATING, ONE_HANDED_OVER_NORMAL).set(FLOATING))
    }

    @Test
    fun floatingOffGoesBackToWhatWasUnder() {
        assertState(SPLIT, Under.SPLIT, state(FLOATING, Under.SPLIT).floatingOff())
        assertState(NORMAL, Under.NORMAL, state(FLOATING, Under.NORMAL).floatingOff())
        assertState(ONE_HANDED, Under.SPLIT, state(FLOATING, ONE_HANDED_OVER_SPLIT).floatingOff())
        assertState(ONE_HANDED, Under.NORMAL, state(FLOATING, ONE_HANDED_OVER_NORMAL).floatingOff())
        // not floating: as it is (a drag's end and a view taken out may both dock it)
        assertState(SPLIT, Under.SPLIT, state(SPLIT, Under.SPLIT).floatingOff())
    }

    @Test
    fun oneHandedOffGoesBackToTwoHands() {
        assertState(SPLIT, Under.SPLIT, state(ONE_HANDED, Under.SPLIT).oneHandedOff())
        assertState(NORMAL, Under.NORMAL, state(ONE_HANDED, Under.NORMAL).oneHandedOff())
        assertState(FLOATING, Under.SPLIT, state(FLOATING, Under.SPLIT).oneHandedOff())
        assertState(ONE_HANDED, Under.SPLIT, state(SPLIT, Under.SPLIT).oneHandedOn())
        assertState(FLOATING, Under.SPLIT, state(SPLIT, Under.SPLIT).floatingOn())
    }

    @Test
    fun splitOneHandedFloatingDockedAndBothHandsIsSplitAgain() {
        val split = KeyboardStyleState.of(null, null, SPLIT)
        val end = split.oneHandedOn().floatingOn().floatingOff().oneHandedOff()
        assertState(SPLIT, Under.SPLIT, end)
        assertTrue(split.splits)
        assertTrue(split.oneHandedOn().oneHanded)
        assertTrue(split.floatingOn().floating)
        assertFalse(split.floatingOn().splits)
    }

    private fun migrated(split: Split060, oneHanded: Boolean = false, floating: Boolean = false) =
        KeyboardStyleMigration.from060(split, oneHanded, floating).let { it.portrait to it.landscape }

    @Test
    @Uat("PR-59")
    fun from060EachShapeAsItShowed() {
        val none = Stored(null, null)
        val normal = Stored(NORMAL, Under.NORMAL)
        val split = Stored(SPLIT, Under.SPLIT)
        val oneHanded = Stored(ONE_HANDED, Under.NORMAL)
        // 屏幕够宽时: 横屏 not written, read by its width as 0.6.0 split it
        assertEquals(normal to none, migrated(Split060.AUTO))
        assertEquals(oneHanded to none, migrated(Split060.AUTO, oneHanded = true))
        // 总是: split held one-handed down
        assertEquals(split to split, migrated(Split060.ALWAYS))
        assertEquals(split to split, migrated(Split060.ALWAYS, oneHanded = true))
        assertEquals(normal to normal, migrated(Split060.OFF))
        assertEquals(oneHanded to oneHanded, migrated(Split060.OFF, oneHanded = true))
    }

    @Test
    @Uat("PR-59")
    fun from060FloatingOverWhatShowedDocked() {
        assertEquals(Stored(FLOATING, Under.NORMAL) to Stored(FLOATING, null), migrated(Split060.AUTO, floating = true))
        assertEquals(Stored(FLOATING, ONE_HANDED_OVER_NORMAL) to Stored(FLOATING, null), migrated(Split060.AUTO, oneHanded = true, floating = true))
        assertEquals(Stored(FLOATING, Under.SPLIT) to Stored(FLOATING, Under.SPLIT), migrated(Split060.ALWAYS, oneHanded = true, floating = true))
        assertEquals(Stored(FLOATING, ONE_HANDED_OVER_NORMAL) to Stored(FLOATING, ONE_HANDED_OVER_NORMAL), migrated(Split060.OFF, oneHanded = true, floating = true))
        assertEquals(Stored(FLOATING, Under.NORMAL) to Stored(FLOATING, Under.NORMAL), migrated(Split060.OFF, floating = true))
        // 横屏 floating over its width's default: docked on a wide screen, split again
        val read = KeyboardStyleState.of(FLOATING, null, KeyboardStyleState.default(landscape = true, widthDp = 841))
        assertState(SPLIT, Under.SPLIT, read.floatingOff())
    }
}
