/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer

class ReadingsRunTest {

    private fun syl(vararg s: String) = s.map { Syllables.id(it) }.toIntArray()

    private val data = PinyinData.load(
        ByteBuffer.wrap(
            PinyinDataBuilder().unigram("<unk>", -7f, 0f)
                .unigram("银行", -2f, 0f).entry("银行", syl("yin", "hang"))
                .unigram("东西", -2f, 0f).entry("东西", syl("dong", "xi"))
                // a word is read by the readings of its characters' own entries (Predictor.reading)
                .entry("银", syl("yin")).entry("行", syl("hang")).entry("东", syl("dong")).entry("西", syl("xi"))
                .charTone('银'.code, Syllables.id("yin"), 2)
                .charTone('行'.code, Syllables.id("hang"), 2)
                .charTone('东'.code, Syllables.id("dong"), 1)
                .charTone('西'.code, Syllables.id("xi"), 1)
                .unigram("行", -3f, 0f).charTone('行'.code, Syllables.id("xing"), 2)
                .wordTones("东西", syl("dong", "xi"), byteArrayOf(1, 5))
                .build().toByteArray(),
        ),
    )

    @Test
    fun countsTheWordsReadRightAndListsTheRest() {
        val set = ReadingsRun.parse(sequenceOf("# comment", "银行\tyín háng", "东西\tdōng xī", ""))
        val r = ReadingsRun(data).measure(set)
        assertEquals(2, r.words)
        assertEquals(1, r.right)
        assertEquals(50.0, r.accuracy, 0.0)
        assertEquals(listOf("东西\tdōng xī\tdōng xi"), r.misses)
    }

    @Test
    fun aLineWithNoReadingIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { ReadingsRun.parse(sequenceOf("银行")) }
    }

    @Test
    fun typedTheWordIsReadByTheSyllablesItWasTypedWith() {
        // 行 alone is likelier xíng; typed as a word of 银行 its syllable is hang either way
        val set = ReadingsRun.parse(sequenceOf("行\txíng", "银行\tyín háng"))
        assertEquals(2, ReadingsRun(data, typed = true).measure(set).right)
    }
}
