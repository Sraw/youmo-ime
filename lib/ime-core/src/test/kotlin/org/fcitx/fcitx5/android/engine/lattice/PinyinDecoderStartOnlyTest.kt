/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.lattice

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.learn.ImportedWords
import org.fcitx.fcitx5.android.engine.learn.import
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.fcitx.fcitx5.android.engine.user.UserScorer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/** A decoder going on from its last decode ([PinyinDecoder] collectOn) where a word only imported starts the input. */
class PinyinDecoderStartOnlyTest {

    private fun syl(vararg s: String) = s.map { Syllables.id(it) }.toIntArray()

    private val data = PinyinData.load(
        ByteBuffer.wrap(
            PinyinDataBuilder()
                .unigram("<unk>", -7f, 0f)
                .unigram("你", -2f, 0f)
                .unigram("吗", -3.5f, 0f)
                .unigram("在", -2f, 0f)
                .unigram("人民", -3f, 0f)
                .entry("你", syl("ni"))
                .entry("吗", syl("ma"))
                .entry("在", syl("zai"))
                .entry("人民", syl("ren", "min"))
                .build().toByteArray(),
        ),
    )

    private fun describe(d: Decoding) = (d.sentences + listOf(null) + d.words).map { c -> c?.let { "${it.text} ${it.end} ${it.score} ${it.words.toList()}" } }

    // nothing of the dictionary starts the input, a word only imported does: the input is kept as typed
    // beside it, also where the decoder goes on from a decode in which a word did start it. That word,
    // kept from the last decode, once passed for a word that paths go on from, and the letters were lost
    @Test
    fun goingOnKeepsTheInputAsTypedWhereOnlyAnImportedWordStartsIt() {
        val user = UserModel(data.dictionary, data.vocabulary)
        user.attachImports(ImportedWords().import("热" to "re"))
        fun decoder() = PinyinDecoder(data.dictionary, data.vocabulary, UserScorer(data.model, user), user = user)
        val segmenter = PinyinSegmenter()
        val kept = decoder()
        var on = 0
        // 人民 of renm is the one word from the start; renma has none, and 热 of re ends before the change
        for (input in listOf("r", "re", "ren", "renm", "renma", "renmaz", "renmaza")) {
            val graph = segmenter.segment(input)
            val read = kept.decode(graph)
            assertEquals(input, describe(decoder().decode(graph)), describe(read))
            if (input == "renma") assertTrue(describe(read).toString(), read.sentences.first().text.startsWith("ren") && kept.collectedOn > 0)
            on += kept.collectedOn
        }
        assertTrue("went on $on times", on > 0)
    }
}
