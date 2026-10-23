/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.learn.ImportedWords
import org.fcitx.fcitx5.android.engine.learn.import
import org.fcitx.fcitx5.android.engine.learn.Row
import org.fcitx.fcitx5.android.engine.lattice.StartWords
import org.fcitx.fcitx5.android.engine.user.UserFixtures.data
import org.fcitx.fcitx5.android.engine.user.UserFixtures.entry
import org.fcitx.fcitx5.android.engine.user.UserFixtures.inTrie
import org.fcitx.fcitx5.android.engine.user.UserFixtures.syl
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.log10

/**
 * The dictionaries the user merged, in the model: read from their compiled file in place
 * (DictionaryFile), walked in its own trie ([UserModel.mergedWords]), listed all; a word the base
 * has too keeps the base's id and is offered only from there (design D2, D3).
 */
class UserModelMergedTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun merged(vararg lines: String): DictionaryFile {
        val file = File(folder.root, "merged.ymd")
        DictionaryCompiler.compile(listOf(lines.asSequence()), file, inBase = DictionaryCompiler.inBase(data))
        return DictionaryFile.open(file)
    }

    private fun model(file: DictionaryFile) = UserModel(data.dictionary, data.vocabulary, data.wordIndex, file)

    private fun StartWords.at(vararg syllables: String): List<Int> {
        var node = root
        for (s in syl(*syllables)) node = child(node, s).takeIf { it >= 0 } ?: return emptyList()
        return (0 until wordCount(node)).map { word(node, it) }
    }

    private val niHao = entry("妮好", "ni", "hao")

    @Test
    fun aMergedWordIsListedFromTheFileAndNoneOfTheUsersOwnTrie() {
        val m = model(merged("妮好 ni'hao 0.01", "泥 ni 0.1", "你 ni 0.5"))
        val id = m.known(niHao)
        assertNotEquals(NO_WORD, id)
        assertTrue(m.isMerged(id))
        assertEquals("妮好", m.text(id))
        assertEquals(Row.DICTIONARY, m.origin(niHao))
        assertTrue(m.knowsText("妮好"))
        assertFalse(inTrie(m, "妮好", "ni", "hao"))
        assertEquals(listOf(id), m.mergedWords!!.at("ni", "hao"))
        // not the user's: no reading of theirs, scored below the unknown word as its dictionary ranks it
        assertNull(m.reading(id))
        assertEquals(id, m.modelWord(id))
        // against its dictionary's best, 你 0.5, though the base has that one
        assertEquals(log10(0.02f), m.mergedScore(id), 1e-5f)
        assertEquals(emptyList<Pair<UserModel.Entry, Float>>(), m.madeWords())
        // listed for the tables from the file, not as a list
        assertFalse("妮好" in m.listedTexts())
    }

    @Test
    fun aWordTheBaseHasKeepsItsIdAndIsOfferedFromTheBaseAlone() {
        val m = model(merged("你 ni 0.5", "泥 ni 0.1"))
        val ni = m.known(entry("你", "ni"))
        assertTrue(ni < data.vocabulary.size)
        // the file's trie has 泥 at ni, not 你 a second time
        assertEquals(listOf("泥"), m.mergedWords!!.at("ni").map(m::text))
        // blocked in the base, it stays blocked: no arc of the file's brings it back
        assertTrue(m.block(entry("你", "ni")))
        assertTrue(m.blockedAnyhow(ni))
        assertTrue(m.mergedWords!!.at("ni").none { m.text(it) == "你" })
    }

    @Test
    fun aMergedWordBlockedTypedOwnedAndAddedByHandIsAWordOfTheUsersByItsOneId() {
        val m = model(merged("妮好 ni'hao 0.01"))
        val id = m.known(niHao)
        assertTrue(m.block(niHao))
        assertTrue(m.blocked(id, -1))
        m.unblock(niHao)
        assertFalse(m.blocked(id, -1))
        // the decoder's per-key questions, kept as owning, adding and counting change (review F3293, F3277, F3286)
        assertFalse(m.anyMergedChosen())
        assertFalse(m.anyMergedCounted())
        assertEquals(id, m.own(niHao))
        assertTrue(m.chosen(id))
        assertTrue(m.anyMergedChosen())
        assertArrayEquals(syl("ni", "hao"), m.reading(id))
        // the user's choice: scored as theirs, no longer by the file's rank
        assertTrue(m.mergedScore(id).isNaN())
        assertEquals(listOf(id), m.idsOfText("妮好"))
        m.disown(niHao)
        assertFalse(m.chosen(id))
        assertFalse(m.anyMergedChosen())
        assertEquals(id, m.known(niHao))
        assertEquals(id, m.listByHand(niHao))
        assertTrue(m.addedByHand(id))
        assertTrue(m.chosen(id))
        assertTrue(m.anyMergedChosen())
        // deleted by hand, the dictionary's word stays its word
        m.unlist(niHao)
        assertFalse(m.addedByHand(id))
        assertFalse(m.chosen(id))
        assertFalse(m.anyMergedChosen())
        // typed: counted; forgotten: not
        m.learn(null, listOf(niHao))
        assertTrue(m.anyMergedCounted())
        m.forget(listOf(niHao))
        assertFalse(m.anyMergedCounted())
        // the app's way: counts cleared and set again from the habits (review F3410)
        m.clearCounts()
        m.setCount(id, 2f)
        assertTrue(m.anyMergedCounted())
        m.clearCounts()
        assertFalse(m.anyMergedCounted())
        assertEquals(id, m.known(niHao))
        assertEquals(listOf(id), m.mergedWords!!.at("ni", "hao"))
    }

    @Test
    fun aMergedWordAnOldLogReplaysIsTheDictionarysNotMade() {
        // legacyModel's order: the log replayed into a model that has the file from the first
        val m = model(merged("妮好 ni'hao 0.01"))
        m.learn(null, listOf(niHao))
        assertTrue(m.isMerged(m.known(niHao)))
        assertEquals(emptyList<Pair<UserModel.Entry, Float>>(), m.madeWords())
        assertEquals(0, m.size)
    }

    @Test
    fun anImportedWordAMergedDictionaryHasIsTheDictionarysNotStartOnly() {
        val m = model(merged("妮好 ni'hao 0.01"))
        m.attachImports(ImportedWords().import("妮好" to "ni hao", "拟浩" to "ni hao"))
        val id = m.known(niHao)
        assertTrue(m.isMerged(id))
        assertFalse(m.startOnly(id))
        assertFalse(m.isImported(niHao))
        // left out of the start trie: offered once, from the file's
        assertEquals(listOf("拟浩"), m.startWords!!.at("ni", "hao").map(m::text))
    }

    @Test
    fun mergedTextsAreFoundByTextAndListedForTheTables() {
        val m = model(merged("妮好 ni'hao 0.01", "妮 ni 0.1", "你 ni 0.5"))
        val ids = ArrayList<Int>()
        m.forEachMergedOfText("妮") { ids += it }
        assertEquals(listOf("妮"), ids.map(m::text))
        // 你 the base has: kept past the file's trie, no merged word (review F3342)
        m.forEachMergedOfText("你") { ids += it }
        assertEquals(listOf("妮"), ids.map(m::text))
        // the file's left to the tables' index of it (CodedIndex)
        assertEquals(emptyList<String>(), m.listedTexts())
        assertTrue(m.listByHand(entry("拟好", "ni", "hao")) >= 0)
        assertEquals(listOf("拟好"), m.listedTexts())
    }

    // review S3: asked before any id was made for it, the import a merged file has is the file's still
    @Test
    fun anImportTheFileHasIsNotOnlyImportedThoughNeverLookedUpBefore() {
        val m = model(merged("妮好 ni'hao 0.01"))
        m.attachImports(ImportedWords().import("妮好" to "ni hao"))
        assertFalse(m.isImported(niHao))
        assertEquals(Row.DICTIONARY, m.origin(niHao))
    }

    // review S2: a merged word never typed in pinyin, committed in a table or handwriting, is found by its text
    @Test
    fun aMergedWordNeverTypedIsFoundByItsTextForLearningAndPairs() {
        val m = model(merged("妮好 ni'hao 0.5", "妮好 ni'hao 0.01", "你 ni 0.5"))
        val id = m.idsOfText("妮好").single()
        assertTrue(m.isMerged(id))
        assertEquals(niHao, m.newEntryOf("妮好"))
        // one the base has comes back as the base's id, not the file's
        assertEquals(listOf(m.known(entry("你", "ni"))), m.idsOfText("你"))
        assertNull(m.newEntryOf("没有"))
    }

    // of a text's readings in the file, its best ranked (review F3380)
    @Test
    fun aMergedTextsEntryIsItsBestRankedReading() {
        val m = model(merged("鬲 ni 0.01", "鬲 hao 0.1"))
        assertEquals(entry("鬲", "hao"), m.newEntryOf("鬲"))
    }

    // review S5: the walkers look past a node's limit only when the user chose a merged word
    @Test
    fun aChosenMergedWordIsToldSoTheWalkersLookPastTheLimitForIt() {
        val m = model(merged("妮好 ni'hao 0.5", "拟浩 ni'hao 0.01"))
        assertFalse(m.anyMergedChosen())
        val id = m.listByHand(entry("拟浩", "ni", "hao"))
        assertTrue(m.anyMergedChosen())
        assertTrue(m.chosen(id))
        m.unlist(entry("拟浩", "ni", "hao"))
        assertFalse(m.anyMergedChosen())
    }

    // review S7: a file compiled against an older base, while its recompile is owed: a word the base
    // gained since is the base's alone, its block not undone by the file's copy
    @Test
    fun aStaleFilesWordTheBaseHasNowIsLeftToTheBase() {
        val file = File(folder.root, "stale.ymd")
        // compiled as if the base lacked 你
        DictionaryCompiler.compile(listOf(sequenceOf("你 ni 0.5", "妮 ni 0.1")), file)
        val m = UserModel(data.dictionary, data.vocabulary, data.wordIndex, DictionaryFile.open(file), mergedStale = true)
        val copy = m.mergedWords!!.at("ni").single { m.text(it) == "你" }
        assertTrue(m.blockedAnyhow(copy))
        assertFalse(m.blockedAnyhow(m.mergedWords!!.at("ni").single { m.text(it) == "妮" }))
        // fresh, the same file's copy is offered (never so: the compile left the base's out)
        val fresh = UserModel(data.dictionary, data.vocabulary, data.wordIndex, DictionaryFile.open(file))
        assertFalse(fresh.blockedAnyhow(copy))
    }
}
