/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipeOverridesTest {

    @Test
    fun withoutAnOverrideThePresetIsTyped() {
        assertEquals("1", SwipeOverrides.Empty.resolve("Q", "1"))
    }

    @Test
    fun anOverrideIsTheKeysInEitherCase() {
        val overrides = SwipeOverrides.Empty.with("Q", "！", preset = "1")
        assertEquals("！", overrides.resolve("q", "1"))
        assertEquals("！", overrides.resolve("Q", "1"))
        assertEquals("q ！", overrides.serialize())
    }

    @Test
    fun thePresetOrNothingTypedPutsTheBuiltInBack() {
        val overrides = SwipeOverrides.Empty.with("a", "#", preset = "@")
        assertTrue(overrides.with("a", "@", preset = "@").isEmpty)
        assertTrue(overrides.with("a", "  ", preset = "@").isEmpty)
        // two words are not one swipe
        assertTrue(overrides.with("a", "# $", preset = "@").isEmpty)
    }

    @Test
    fun aWordIsTypedWhole() {
        assertEquals("www.", SwipeOverrides.Empty.with("w", " www. ", preset = "2")["W"])
    }

    @Test
    fun parsingSkipsWhatDoesNotReadAndRoundTrips() {
        val parsed = SwipeOverrides.parse("q ！\n\nbroken\nx y z\nA　#\nq ?")
        assertEquals("?", parsed["q"])
        assertEquals("#", parsed["a"])
        assertNull(parsed["x"])
        assertEquals(parsed, SwipeOverrides.parse(parsed.serialize()))
        assertSame(SwipeOverrides.Empty, SwipeOverrides.parse("nonsense"))
    }
}
