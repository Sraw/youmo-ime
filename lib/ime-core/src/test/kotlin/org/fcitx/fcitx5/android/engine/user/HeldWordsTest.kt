/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.data.TestCharLevels
import org.fcitx.fcitx5.android.engine.learn.ImportedWords
import org.fcitx.fcitx5.android.engine.learn.import
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/** 生僻字屏蔽's gate by code point, the dictionary's words it holds back by id, and the user model asking both. */
class HeldWordsTest {

    private fun syl(vararg s: String) = s.map { Syllables.id(it) }.toIntArray()

    private val data = PinyinData.load(
        ByteBuffer.wrap(
            PinyinDataBuilder()
                .unigram("<unk>", -7f, 0f)
                .unigram("大", -2f, 0f).entry("大", syl("da"))
                .unigram("龘", -6f, 0f).entry("龘", syl("da"))
                .unigram("𤝰", -7f, 0f).entry("𤝰", syl("da"))
                .unigram("大龘", -6f, 0f).entry("大龘", syl("da", "da"))
                .unigram("大大", -4f, 0f).entry("大大", syl("da", "da"))
                .build().toByteArray(),
        ),
    )

    // the extension B group held, as a first turn-on holds it
    private val gate = RareChoice.parse("on\t1\n").gate(TestCharLevels.levels)
    private val outToo = RareChoice.parse("on\t1\ngroups\tOUT EXT\n").gate(TestCharLevels.levels)

    private fun id(text: String) = data.wordIndex.find(text)

    @Test
    fun theGateAsksByCodePoint() = assertRows(
        "𤝰 asked as one code point" to { assertTrue(gate.holds(TestCharLevels.EXT)) },
        "its high surrogate alone is not it" to { assertFalse(gate.holds("𤝰"[0].code)) },
        "a text with it" to { assertTrue(gate.holds("大𤝰")) },
        "a text without it" to { assertFalse(gate.holds("大龘")) },
        "England's flag, a tag sequence of plane 14, holds nothing back" to { assertFalse(outToo.holds("🏴󠁧󠁢󠁥󠁮󠁧󠁿")) },
        "NONE holds nothing" to { assertFalse(CharGate.NONE.holds("𤝰")) },
        "one of any predicate" to { assertTrue(CharGate.of { it == '大'.code }.holds("大")) },
    )

    @Test
    fun theDictionarysWordsByIdOnce() {
        val ext = HeldWords.of(gate, data.vocabulary)
        val out = HeldWords.of(outToo, data.vocabulary)
        assertRows(
            "EXT: 𤝰 alone" to {
                assertTrue(ext.has(id("𤝰")))
                assertFalse(ext.has(id("龘")))
                assertEquals(1, ext.count)
            },
            "OUT too: 龘 and the word with it" to {
                assertTrue(out.has(id("龘")))
                assertTrue(out.has(id("大龘")))
                assertFalse(out.has(id("大大")))
                assertEquals(3, out.count)
            },
            "no gate, no walk: NONE" to { assertSame(HeldWords.NONE, HeldWords.of(CharGate.NONE, data.vocabulary)) },
        )
    }

    @Test
    @Uat("WD-56")
    fun theUserModelAsksItsWordsAsTheDictionarysAndTheUsersByText() {
        val model = UserModel(data.dictionary, data.vocabulary)
        val mine = model.own(UserModel.Entry("龘龘", syl("da", "da")))
        val before = model.changes
        model.held = HeldWords.of(outToo, data.vocabulary)
        assertRows(
            "a new HeldWords is a change, as a block is: a glide's cache reads the model anew" to { assertTrue(model.changes > before) },
            "the dictionary's 龘 blocked anyhow and at any node" to {
                assertTrue(model.blockedAnyhow(id("龘")))
                assertTrue(model.blocked(id("龘"), 0))
            },
            "the dictionary's 大 not" to { assertFalse(model.blockedAnyhow(id("大"))) },
            "the user's own 龘龘, past the dictionary's ids, by its text" to {
                assertTrue(mine >= data.vocabulary.size)
                assertTrue(model.blockedAnyhow(mine))
            },
            "held no longer, neither is" to {
                model.held = HeldWords.NONE
                assertFalse(model.blockedAnyhow(id("龘")))
                assertFalse(model.blockedAnyhow(mine))
            },
            "an imported word with 龘, past the merged ids, by its text" to {
                model.attachImports(ImportedWords().import("龘好" to "da hao", "大好" to "da hao"))
                model.held = HeldWords.of(outToo, data.vocabulary)
                val imported = model.known(UserModel.Entry("龘好", syl("da", "hao")))
                assertTrue(imported >= data.vocabulary.size)
                assertTrue(model.blockedAnyhow(imported))
                assertFalse(model.blockedAnyhow(model.known(UserModel.Entry("大好", syl("da", "hao")))))
            },
            "a whole phrase learned with 龘 not continued after 大; one without it is" to {
                model.continuations.set("大龘大", 3, 5, 3)
                model.continuations.set("大大好", 3, 5, 3)
                assertEquals(listOf("大好"), model.continuationsAfter("大", intArrayOf(0), "大").map { it.rest })
                model.held = HeldWords.NONE
                assertEquals(setOf("龘大", "大好"), model.continuationsAfter("大", intArrayOf(0), "大").map { it.rest }.toSet())
            },
            "the same HeldWords again is no change" to {
                val held = HeldWords.of(gate, data.vocabulary)
                model.held = held
                val now = model.changes
                model.held = held
                assertEquals(now, model.changes)
            },
        )
    }
}
