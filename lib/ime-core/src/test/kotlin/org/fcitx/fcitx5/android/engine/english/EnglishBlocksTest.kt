/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.english

import org.fcitx.fcitx5.android.engine.platform.MemoryFiles
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** English's blocked words (EnglishBlocks): what a block reaches, in a session and kept. */
class EnglishBlocksTest {

    private val data = EnglishDecoderTest.data()

    private fun EnglishSession.type(text: String): Snapshot = text.map { apply(Action.Key(it)) }.last()

    private fun EnglishSession.commit(word: String): String {
        type(word)
        return apply(Action.Key(' ')).commit
    }

    // review L1: a word blocked in lower case is blocked in any case; one with a capital as written alone
    @Test
    fun aWordInLowerCaseIsBlockedInAnyCaseOneWithACapitalAsWritten() {
        val blocks = EnglishBlocks(null)
        blocks.block(listOf("the", "US"))
        assertTrue(blocks.has("the") && blocks.has("The") && blocks.has("THE"))
        assertTrue(blocks.has("US"))
        assertFalse(blocks.has("us"))
        assertFalse(blocks.has("Us"))
        // kept as written
        val dir = MemoryFiles()
        EnglishBlocks(dir).block(listOf("US", "the"))
        val read = EnglishBlocks(dir)
        assertEquals(listOf("US", "the"), read.blocked)
        assertFalse(read.has("us"))
        assertEquals(listOf("US"), read.unblock(listOf("US", "us")))
    }

    // what is shown in the input's case is blocked as written: Hello at a capital, HELLO in capitals, hello's
    @Test
    fun aWordShownInTheInputsCaseIsBlockedAsWritten() {
        for (typed in listOf("Hel", "HEL")) {
            val blocks = EnglishBlocks(null)
            val session = EnglishSession(data, blocks = blocks)
            val shown = session.type(typed).candidates
            val at = shown.indexOfFirst { it.equals("hello", ignoreCase = true) }
            assertTrue(shown.toString(), at > 0)
            session.apply(Action.Block(at))
            assertEquals(listOf("hello"), blocks.blocked)
            assertFalse("hello" in EnglishSession(data, blocks = blocks).type("hel").candidates)
        }
    }

    // a misspelling's target blocked is not corrected to (Autocorrect's map, not the decoder's found)
    @Test
    fun aMisspellingsTargetBlockedIsNotCorrectedTo() {
        val blocks = EnglishBlocks(null)
        blocks.block(listOf("hello"))
        val session = EnglishSession(data, misspellings = mapOf("hell" to "hello"), blocks = blocks)
        session.type("hell")
        assertEquals("hell ", session.apply(Action.Key(' ')).commit)
    }

    // what the user typed after a word, blocked, is not predicted after it
    @Test
    fun aFollowerBlockedIsNotPredicted() {
        val blocks = EnglishBlocks(null)
        val user = EnglishUser()
        val session = EnglishSession(data, user = user, blocks = blocks)
        session.apply(Action.Context(""))
        session.commit("Hello")
        session.commit("youmo")
        fun followers(): List<String> {
            session.apply(Action.Reset)
            session.apply(Action.Context("Fine. "))
            session.commit("Hello")
            return session.candidates(0, 10).map { it.text }
        }
        assertTrue("youmo" in followers())
        blocks.block(listOf("youmo"))
        assertFalse("youmo" in followers())
    }
}
