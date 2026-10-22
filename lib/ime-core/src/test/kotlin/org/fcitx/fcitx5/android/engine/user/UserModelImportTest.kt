/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.fcitx.fcitx5.android.engine.lattice.StartWords
import org.fcitx.fcitx5.android.engine.learn.ImportedWords
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.ImportUndo
import org.fcitx.fcitx5.android.engine.learn.LearnState
import org.fcitx.fcitx5.android.engine.learn.Row
import org.fcitx.fcitx5.android.engine.learn.import
import org.fcitx.fcitx5.android.engine.user.UserFixtures.entry
import org.fcitx.fcitx5.android.engine.user.UserFixtures.inTrie
import org.fcitx.fcitx5.android.engine.user.UserFixtures.model
import org.fcitx.fcitx5.android.engine.user.UserFixtures.syl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The words the user imported, in the model (U-101; review-G M3, M5): read from the learning
 * record's, offered from the start alone ([UserModel.startWords]) till the user's, added by hand
 * or listed, then whole words in the trie; out of it again as that ends, never dropped while imported.
 */
class UserModelImportTest {
    private val niHao = entry("妮好", "ni", "hao")
    private val imports = ImportedWords().import("妮好" to "ni hao", "拟浩" to "ni hao", "妮" to "ni")
    private val m = model().also { it.attachImports(imports) }
    private val id = m.known(niHao)

    // the words at the start trie's node [syllables] lead to
    private fun StartWords.at(vararg syllables: String): List<Int>? {
        var node = root
        for (s in syl(*syllables)) node = child(node, s).takeIf { it >= 0 } ?: return null
        return (0 until wordCount(node)).map { word(node, it) }
    }

    @Test
    fun anImportedWordIsStartOnlyAndFiledImported() {
        assertTrue(m.startOnly(id))
        assertEquals("妮好", m.text(id))
        assertEquals(Row.IMPORTED, m.origin(niHao))
        assertTrue(m.isImported(niHao))
        // only in the start trie: no sentence nor handwriting walks it
        assertFalse(inTrie(m, "妮好", "ni", "hao"))
        assertTrue(id in m.startWords!!.at("ni", "hao")!!)
        // not a word the user made, nor theirs to read or score
        assertFalse(m.chosen(id))
        assertNull(m.reading(id))
        assertEquals(id, m.modelWord(id))
        assertEquals(emptyList<Pair<UserModel.Entry, Float>>(), m.madeWords())
        // a character too, one the dictionary has not
        assertTrue(m.startOnly(m.known(entry("妮", "ni"))))
    }

    @Test
    fun ownedItIsAWholeWordAndStaysOnceItsBatchIsUndone() {
        m.own(niHao)
        assertFalse(m.startOnly(id))
        assertEquals(Row.NONE, m.origin(niHao))
        assertTrue(inTrie(m, "妮好", "ni", "hao"))
        assertTrue(m.chosen(id))
        // disowned while imported (the commit that made it theirs undone): imported again, not dropped
        m.disown(niHao)
        assertTrue(m.startOnly(id))
        assertFalse(inTrie(m, "妮好", "ni", "hao"))
        m.own(niHao)
        imports.apply(ImportUndo(1))
        m.importsChanged()
        assertTrue(inTrie(m, "妮好", "ni", "hao"))
        assertEquals("妮好", m.text(id))
        assertNull(m.startWords)
        // theirs no more and imported no more: gone
        m.disown(niHao)
        assertFalse(inTrie(m, "妮好", "ni", "hao"))
        assertFalse(m.startOnly(id))
    }

    @Test
    fun listedOrAddedByHandItIsAWholeWordTillThatEnds() {
        m.list(niHao)
        assertFalse(m.startOnly(id))
        assertEquals(Row.DICTIONARY, m.origin(niHao))
        assertTrue(inTrie(m, "妮好", "ni", "hao"))
        m.unlist(niHao)
        assertTrue(m.startOnly(id))
        assertFalse(inTrie(m, "妮好", "ni", "hao"))
        // by hand: the user's choice, scored as the model's word of its text if it has one
        val other = entry("拟浩", "ni", "hao")
        val otherId = m.listByHand(other)
        assertFalse(m.startOnly(otherId))
        assertEquals(Row.ADDED, m.origin(other))
        assertTrue(m.chosen(otherId))
        m.unlist(other)
        assertTrue(m.startOnly(otherId))
    }

    @Test
    fun forgottenOrBlockedItIsImportedStill() {
        m.learn(null, listOf(niHao))
        assertTrue(m.hasLearned(listOf(niHao)))
        m.forget(listOf(niHao))
        assertFalse(m.hasLearned(listOf(niHao)))
        assertTrue(m.startOnly(id))
        assertTrue(m.block(niHao))
        assertTrue(m.blocked(id, -1))
        m.unblock(niHao)
        assertFalse(m.blocked(id, -1))
    }

    // review-G M5: a model made again from the record (a reload, a rollback) has them, as imported
    @Test
    fun aModelMadeFromTheRecordHasThem() {
        val state = LearnState()
        state.imports.import("妮好" to "ni hao")
        val again = model()
        HabitCounts(again).attach(state)
        val word = again.known(niHao)
        assertTrue(again.startOnly(word))
        assertEquals(listOf(word), again.startWords!!.at("ni", "hao"))
        // one listed before the record was read (a user dictionary) is that word, not imported too
        val listed = model()
        listed.list(niHao)
        HabitCounts(listed).attach(state)
        assertEquals(Row.DICTIONARY, listed.origin(niHao))
        assertNull(listed.startWords!!.at("ni", "hao"))
    }

    // review-G S4: the start trie is made again as words come, not as they go: one gone is no start-only word
    @Test
    fun theStartTrieIsMadeAgainOnlyAsWordsCome() {
        val trie = m.startWords
        val other = m.known(entry("拟浩", "ni", "hao"))
        imports.forget("拟浩")
        m.importsChanged()
        assertSame(trie, m.startWords)
        assertFalse(m.startOnly(other))
        imports.import("鲸园" to "jing yuan")
        assertNotSame(trie, m.startWords)
    }

    // one blocked before it had an id here (never typed nor looked up) was blocked twice, and unblocked stayed so
    @Test
    fun anImportedWordBlockedBeforeItHadAnIdIsUnblockedWhole() {
        val fresh = model().also { it.attachImports(ImportedWords().import("我爱" to "wo ai")) }
        val woAi = entry("我爱", "wo", "ai")
        assertTrue(fresh.block(woAi))
        val word = fresh.known(woAi)
        assertTrue(fresh.blocked(word, -1))
        fresh.unblock(woAi)
        assertFalse(fresh.blocked(word, -1))
    }
}
