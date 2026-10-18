/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.english

import org.fcitx.fcitx5.android.engine.glide.Glide
import org.fcitx.fcitx5.android.engine.glide.GlideLayout
import org.fcitx.fcitx5.android.engine.session.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlideDecoderTest {

    private val data = EnglishDecoderTest.data()
    private val decoder = GlideDecoder(data)
    private val start = EnglishDecoder(data).context(listOf(EnglishText.START))

    private fun words(keys: String, context: Long = start) =
        decoder.decode(path(keys), GlideLayout.QWERTY, context, 5).map { data.word(it.word) }

    @Test
    fun aPathThroughAWordsKeysIsThatWord() {
        assertEquals("hello", words("helo").first())
        assertEquals("the", words("the").first())
        assertEquals("from", words("from").first())
        // a doubled letter passed once
        assertEquals("see", words("se").first())
    }

    @Test
    fun aWordIsOneOnlyWhereThePathStartsAndEndsNearItsEnds() {
        // hell ends at l, they at y: not where the path ends (help's p beside the o is)
        assertFalse("hell" in words("helo"))
        assertTrue("help" in words("helo"))
        assertFalse("they" in words("the"))
        // form's o before its r: a path from f by r to o has none
        assertFalse("form" in words("from"))
    }

    @Test
    fun aKeyMissedByAFingerStillCounts() {
        // each point a third of a key off
        val off = path("the").mapIndexed { i, v -> if (i % 2 == 0) v + 0.3f else v - 0.3f }.toFloatArray()
        assertEquals("the", data.word(decoder.decode(off, GlideLayout.QWERTY, start, 1).single().word))
    }

    @Test
    fun noPathNoWords() {
        assertTrue(decoder.decode(floatArrayOf(1f, 1f), GlideLayout.QWERTY, start, 5).isEmpty())
        assertTrue(decoder.decode(path("the"), GlideLayout.QWERTY, start, 0).isEmpty())
        // a letter the layout lacks: no word has it
        val noH = GlideLayout(mapOf('t' to (4.5f to 0.7f), 'e' to (2.5f to 0.7f)))
        assertTrue(decoder.decode(path("the"), noH, start, 5).none { data.word(it.word) == "the" })
    }

    @Test
    fun theModelWeighsWordsOfLikeShapes() {
        // its and it's: one path, it's the likelier
        assertEquals(listOf("it's", "its"), words("its").filter { it == "it's" || it == "its" })
    }

    companion object {
        /** A finger's path through [keys]' centres on QWERTY, as a glide sends it. */
        fun path(keys: String): FloatArray =
            keys.flatMap { GlideLayout.QWERTY.centre(it).toList() }.toFloatArray()

        fun glide(keys: String, shift: Boolean = false) = Glide(path(keys), GlideLayout.QWERTY, shift)

        fun EnglishSession.glided(keys: String, shift: Boolean = false) = run {
            offerGlide(glide(keys, shift))
            apply(Action.Glide)
        }
    }
}
