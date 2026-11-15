/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.lattice

import org.fcitx.fcitx5.android.engine.learn.ImportedWords
import org.fcitx.fcitx5.android.engine.learn.import
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.platform.jvm.JvmFile
import org.fcitx.fcitx5.android.engine.session.pinyinDataOf
import org.fcitx.fcitx5.android.engine.user.DictionaryCompiler
import org.fcitx.fcitx5.android.engine.user.DictionaryFile
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.fcitx.fcitx5.android.engine.user.UserScorer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 双拼's keys read alone where a key and the next make a syllable (简拼 in the middle) read only the
 * words the user typed or chose ([UserWords.typed]; DECISIONS 0.6.1 双拼中间简拼改 U), 自然码.
 */
class PinyinDecoderTypedTest {

    private fun syl(vararg s: String) = s.map { Syllables.id(it) }.toIntArray()

    private val data = pinyinDataOf(
        Triple("你", "ni", -2f), Triple("拟", "ni", -5f), Triple("好", "hao", -2.5f), Triple("你好", "ni hao", -3f),
        Triple("张", "zhang", -3f), Triple("中", "zhong", -3.5f), Triple("中华", "zhong hua", -1.5f), Triple("人民", "ren min", -3f),
    )

    // the dictionaries the user merged: more of ni
    private fun mergedModel(): UserModel {
        val file = java.io.File.createTempFile("merged", ".ymd").apply { deleteOnExit() }
        DictionaryCompiler.compile(listOf(sequenceOf("妮 ni 0.5", "泥 ni 0.05", "尼 ni 0.005", "倪 ni 0", "你 ni 0.9")), file, inBase = DictionaryCompiler.inBase(data))
        return UserModel(data.dictionary, data.vocabulary, data.wordIndex, DictionaryFile.open(JvmFile(file)))
    }

    private val ziranma = ShuangpinSegmenter(ShuangpinScheme.ZIRANMA, abbreviations = true)

    // the words of the arcs over [keys], 自然码, from the start to the end
    private fun over(user: UserModel?, keys: String): List<String> {
        val graph = ziranma.segment(keys)
        val d = PinyinDecoder(data.dictionary, data.vocabulary, WordScorer.of(data.model), user = user)
        d.decode(graph)
        return d.arcsBetween(graph, 0, keys.length).map { it.text }
    }

    // DECISIONS 0.6.1 双拼中间简拼改 U: over a key read alone where it and the next make a syllable, only
    // the words the user typed or chose. nhk is n and hao (and nang, k): 你好 by n alone
    @Test
    fun aKeyReadAloneReadsOnlyTheWordsTypedOrChosen() {
        val nihao = UserModel.Entry("你好", syl("ni", "hao"))
        assertEquals(listOf("你好"), over(null, "nihk"))
        assertEquals(emptyList<String>(), over(null, "nhk"))
        val user = UserModel(data.dictionary, data.vocabulary, data.wordIndex)
        assertEquals(emptyList<String>(), over(user, "nhk"))
        // counted: typed
        user.setCount(user.id(nihao), 1f, nihao)
        assertEquals(listOf("你好"), over(user, "nhk"))
        // and no other word: 中华 by zh alone and hua is none (vh is zhang)
        assertEquals(emptyList<String>(), over(user, "vhw"))
        // a key that makes no syllable with the next is an initial as it ever was: rm is none, 人民 by r and min
        assertEquals(listOf("人民"), over(user, "rmn"))
        user.setCount(user.id(nihao), 0f, nihao)
        assertEquals(emptyList<String>(), over(user, "nhk"))
        // the user's own: chosen, with no count
        user.own(nihao)
        assertEquals(listOf("你好"), over(user, "nhk"))
        user.disown(nihao)
        assertEquals(emptyList<String>(), over(user, "nhk"))
        // the dictionary's own word added by hand: the user named it, though its reading and score stay the dictionary's
        user.listByHand(nihao)
        assertTrue(!user.chosen(user.id(nihao)))
        assertEquals(listOf("你好"), over(user, "nhk"))
        user.unlistByHand(nihao)
        assertEquals(emptyList<String>(), over(user, "nhk"))
        // one the dictionary lacks, made by the user
        val nizhong = UserModel.Entry("你中", syl("ni", "zhong"))
        user.own(nizhong)
        assertEquals(listOf("你中"), over(user, "nvs"))
        // in whole syllables every word is read, typed or not
        assertEquals(listOf("人民"), over(user, "rfmn"))
    }

    // the same of the dictionaries the user merged and of the words only imported: none over a key
    // read alone till typed or chosen, all of them in whole syllables
    @Test
    fun aKeyReadAloneReadsNoMergedOrImportedWordNeverTyped() {
        val user = mergedModel()
        user.attachImports(ImportedWords().import("妮好" to "ni hao"))
        // nhk from the start: n alone is 妮, 泥, 尼, 倪 of the merged file and 你, 拟; 妮好 imported
        fun alone(): List<String> {
            val graph = ziranma.segment("nhk")
            val d = PinyinDecoder(data.dictionary, data.vocabulary, UserScorer(data.model, user), user = user)
            d.decode(graph)
            return d.arcsBetween(graph, 0, 1).map { it.text } + d.arcsBetween(graph, 0, 3).map { it.text }
        }
        assertEquals(emptyList<String>(), alone())
        assertTrue(over(user, "ni").containsAll(listOf("妮", "泥", "你")))
        assertTrue("妮好" in over(user, "nihk"))
        val ni = UserModel.Entry("泥", syl("ni"))
        user.setCount(user.id(ni), 2f, ni)
        assertEquals(listOf("泥"), alone())
        // added by hand from the merged dictionary: chosen
        user.listByHand(UserModel.Entry("倪", syl("ni")))
        assertEquals(setOf("泥", "倪"), alone().toSet())
        // the imported one, made the user's own
        user.own(UserModel.Entry("妮好", syl("ni", "hao")))
        assertEquals(setOf("泥", "倪", "妮好"), alone().toSet())
    }
}
