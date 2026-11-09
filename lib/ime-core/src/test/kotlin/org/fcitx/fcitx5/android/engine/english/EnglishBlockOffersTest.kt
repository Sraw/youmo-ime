/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.english

import org.fcitx.fcitx5.android.engine.english.GlideDecoderTest.Companion.glided
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Offer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** English's long-press 屏蔽 where what is shown is not the letters typed: the nine keys' first word, a glide's others. */
class EnglishBlockOffersTest {

    private val data = EnglishDecoderTest.data()

    // the nine keys' first word is the decoder's, not letters the user typed: it can be blocked like any other
    @Test
    fun theNineKeysFirstWordCanBeBlocked() {
        val blocks = EnglishBlocks(null)
        val s = EnglishSession(data, blocks = blocks)
        "843".forEach { s.apply(Action.NineKey(it, false)) }
        assertEquals("the", s.candidates(0, 1).single().text)
        assertTrue(Offer.BLOCK in s.offers(0))
        val after = s.apply(Action.Block(0))
        assertEquals(listOf("the"), blocks.blocked)
        assertFalse("the" in after.candidates)
    }

    // a glide's other blocked leaves the rest offered: one of them still takes the glided word's place
    @Test
    fun aGlidesOtherBlockedLeavesTheRestToPick() {
        val blocks = EnglishBlocks(null)
        val s = EnglishSession(data, blocks = blocks).apply { apply(Action.Context("")) }
        val shown = s.glided("helo")
        assertEquals("hello ", shown.commit)
        val help = shown.candidates.indexOf("help")
        assertTrue(help > 0)
        val after = s.apply(Action.Block(help))
        assertEquals(listOf("help"), blocks.blocked)
        assertEquals(shown.candidates - "help", after.candidates)
        // what is left still the glide's to pick: the last of it takes the glided word's place
        val last = after.candidates.lastIndex
        val picked = s.apply(Action.Pick(last))
        assertEquals("${after.candidates[last]} ", picked.commit)
        assertEquals("hello ".length, picked.deleteBefore)
    }
}
