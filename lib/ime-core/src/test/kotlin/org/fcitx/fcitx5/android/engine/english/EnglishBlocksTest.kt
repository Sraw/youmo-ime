/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.english

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.platform.MemoryFiles
import org.fcitx.fcitx5.android.engine.platform.UserFiles
import org.fcitx.fcitx5.android.engine.platform.write
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.fcitx.fcitx5.android.engine.store.Unsaved
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** English's blocked words (EnglishBlocks): what a block reaches, in a session and kept. */
class EnglishBlocksTest {

    private val data = EnglishDecoderTest.data()

    private fun EnglishSession.type(text: String): Snapshot = text.map { apply(Action.Key(it)) }.last()

    private fun EnglishSession.commit(word: String): String {
        type(word)
        return apply(Action.Key(' ')).commit
    }

    // a line not taken for a word is written back as it was, not lost to the next block
    @Test
    fun aLineThatDoesNotReadIsKeptThroughABlock() {
        val dir = MemoryFiles()
        dir.file(EnglishBlocks.FILE).write("the\n!later form\n".toByteArray())
        val blocks = EnglishBlocks(dir)
        assertEquals(listOf("the"), blocks.blocked)
        blocks.block(listOf("US"))
        assertEquals("the\nUS\n!later form\n", dir.file(EnglishBlocks.FILE).read().decodeToString())
    }

    // 全部清空 takes the lines not taken for a word too; a file of such lines alone as well
    @Test
    fun aClearEmptiesTheWordsAndTheLinesThatDoNotRead() = assertRows(
        listOf("words and a line not read" to "the\n!later form\n", "lines not read alone" to "!later form\n"),
        { it.first },
    ) { (_, text) ->
        val dir = MemoryFiles()
        dir.file(EnglishBlocks.FILE).write(text.toByteArray())
        val blocks = EnglishBlocks(dir)
        blocks.clear()
        assertEquals(emptyList<String>(), blocks.blocked)
        assertEquals("", dir.file(EnglishBlocks.FILE).read().decodeToString())
        blocks.block(listOf("US"))
        assertEquals("US\n", dir.file(EnglishBlocks.FILE).read().decodeToString())
    }

    @Test
    fun aClearDeletesAListThatDidNotRead() = assertRows(
        listOf<Triple<String, (MemoryFiles) -> Unit, Boolean>>(
            Triple("what stands there can be deleted: gone, and the list is written again", { it.dir(EnglishBlocks.FILE).mkdirs() }, true),
            Triple("it cannot be (a directory with something in it): told as not kept, still not written over", { it.dir(EnglishBlocks.FILE).dir("x").mkdirs() }, false),
        ),
        { it.first },
    ) { (why, there, deleted) ->
        val dir = MemoryFiles().also(there)
        val errors = ArrayList<IOException>()
        val blocks = EnglishBlocks(dir, errors::add)
        assertEquals(why, 1, errors.size)
        blocks.clear()
        assertEquals(why, if (deleted) 1 else 2, errors.size)
        assertEquals(listOf("US"), blocks.block(listOf("US")))
        if (deleted) {
            assertEquals(why, "US\n", dir.file(EnglishBlocks.FILE).read().decodeToString())
            assertEquals(why, 1, errors.size)
        } else {
            assertTrue(why, errors.drop(1).all { it is Unsaved } && errors.size == 3)
            assertTrue(why, dir.dir(EnglishBlocks.FILE).dir("x").exists())
        }
    }

    // told as Unsaved, not as an I/O error; blocked for the run all the same
    @Uat("WD-48")
    @Test
    fun aBlockThatCannotBeKeptIsBlockedInMemoryAndToldAsUnsaved() = assertRows(
        listOf<Pair<String, (MemoryFiles) -> UserFiles>>(
            "the list cannot be written: a file where its directory should be" to { root -> root.file("lists").createNew(); root.dir("lists") },
            "the list did not read: a directory where it should be" to { root -> root.dir(EnglishBlocks.FILE).mkdirs(); root },
        ),
        { it.first },
    ) { (why, dir) ->
        val errors = ArrayList<IOException>()
        val blocks = EnglishBlocks(dir(MemoryFiles()), errors::add)
        val read = errors.size
        assertEquals(listOf("the"), blocks.block(listOf("the")))
        assertTrue(blocks.has("The"))
        assertEquals(why, 1, errors.size - read)
        assertTrue("$why: ${errors.last()}", errors.last() is Unsaved)
    }

    @Test
    fun aWordInLowerCaseIsBlockedInAnyCaseOneWithACapitalAsWritten() {
        val blocks = EnglishBlocks(null)
        blocks.block(listOf("the", "US"))
        assertTrue(blocks.has("the") && blocks.has("The") && blocks.has("THE"))
        assertTrue(blocks.has("US"))
        assertFalse(blocks.has("us"))
        assertFalse(blocks.has("Us"))
        val dir = MemoryFiles()
        EnglishBlocks(dir).block(listOf("US", "the"))
        val read = EnglishBlocks(dir)
        assertEquals(listOf("US", "the"), read.blocked)
        assertFalse(read.has("us"))
        assertEquals(listOf("US"), read.unblock(listOf("US", "us")))
    }

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
