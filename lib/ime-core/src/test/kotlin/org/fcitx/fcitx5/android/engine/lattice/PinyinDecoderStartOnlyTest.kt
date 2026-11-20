/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.lattice

import org.fcitx.fcitx5.android.engine.data.pinyinDataOf
import org.fcitx.fcitx5.android.engine.learn.ImportedWords
import org.fcitx.fcitx5.android.engine.learn.import
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.fcitx.fcitx5.android.engine.user.UserScorer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A decoder going on from its last decode ([PinyinDecoder] collectOn) where a word only imported starts the input. */
class PinyinDecoderStartOnlyTest {

    private val data = pinyinDataOf(
        Triple("你", "ni", -2f),
        Triple("吗", "ma", -3.5f),
        Triple("在", "zai", -2f),
        Triple("人民", "ren min", -3f),
    )

    private fun describe(d: Decoding) = (d.sentences + listOf(null) + d.words).map { c -> c?.let { "${it.text} ${it.end} ${it.score} ${it.words.toList()}" } }

    // the input is also kept as typed when the decoder goes on from a decode a word started
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
