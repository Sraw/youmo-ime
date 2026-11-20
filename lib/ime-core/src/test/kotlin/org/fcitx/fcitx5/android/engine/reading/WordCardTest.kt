/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.reading

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.engine.data.syl
import org.fcitx.fcitx5.android.engine.lattice.Predictor
import org.fcitx.fcitx5.android.engine.phrase.CharInfoTest
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.stroke.StrokeShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A held word's card: its reading as 候选词注音 writes it, a row for each Han character. */
class WordCardTest {

    private val data = ReadingData.data
    private val readings = CandidateReadings(data, data.tones) { Predictor.charReadingsOf(data.dictionary, data.vocabulary) }

    // [text] read as its session read it ([typed], else by its text), as Engines.card reads it
    private fun card(text: String, vararg typed: String): WordCard {
        val words = if (typed.isEmpty()) null else listOf(WordReading(text, -1, syl(*typed)))
        return WordCard.of(text, readings.of(text, ReadingStyle.PINYIN, words), readings.of(text, ReadingStyle.ZHUYIN, words), CharInfoTest.info)
    }

    private fun WordCard.both() = line(ReadingStyle.PINYIN) to line(ReadingStyle.ZHUYIN)

    @Test
    @Uat("PY-124", "PY-125")
    fun theWordsReadingAndEachCharactersAsTyped() = assertRows(
        "银行 yin hang" to {
            val c = card("银行", "yin", "hang")
            assertEquals("yín háng" to "ㄧㄣˊ ㄏㄤˊ", c.both())
            assertEquals(listOf("银", "行"), c.rows.map { it.text })
            assertEquals(WordCard.Row("行", "háng", "ㄏㄤˊ", "行", 6, CardReading(Syllables.id("hang"), 2)), c.rows[1])
        },
        "行走 xing zou" to { assertEquals("xíng zǒu" to "ㄒㄧㄥˊ ㄗㄡˇ", card("行走", "xing", "zou").both()) },
        "行走 by its text: the dictionary's" to { assertEquals("xíng zǒu", card("行走").line(ReadingStyle.PINYIN)) },
        "a word's exception (by its text, the word known): 爱好 ài hào, the row's reading that tone" to {
            val c = card("爱好")
            assertEquals("ài hào", c.line(ReadingStyle.PINYIN))
            assertEquals(CardReading(Syllables.id("hao"), 4), c.rows[1].reading)
        },
    )

    @Test
    @Uat("PY-128")
    fun whatIsNoHanCharacterIsWrittenAsItIsAndHasNoRow() = assertRows(
        "letters among them: 银A行 (as 卡拉OK's ka la O K)" to {
            val c = WordCard.of("银A行", listOf("yín", "", "háng"), listOf("ㄧㄣˊ", "", "ㄏㄤˊ"), CharInfoTest.info)
            assertEquals("yín A háng" to "ㄧㄣˊ A ㄏㄤˊ", c.both())
            assertEquals(listOf("银", "行"), c.rows.map { it.text })
        },
        "digits before: 2026年 2026 nián" to { assertEquals("2026 nián", WordCard.line("2026年", listOf("", "", "", "", "nián"))) },
        "a run of them between, its spaces trimmed" to { assertEquals("kǎ lā OK", WordCard.line("卡拉OK", listOf("kǎ", "lā", "", ""))) },
        "a character of no reading left out" to { assertEquals("yín", WordCard.line("银㐀", listOf("yín", ""))) },
        "none read: no line, not the text again" to { assertEquals("", WordCard.line("A股", listOf("", ""))) },
        "beyond the basic plane: a code point each, not a Char" to {
            val c = WordCard.of("𤝰行", listOf("x", "háng"), listOf("", "ㄏㄤˊ"), CharInfoTest.info)
            assertEquals(listOf("𤝰", "行"), c.rows.map { it.text })
            assertEquals("háng", c.rows[1].pinyin)
        },
    )

    @Test
    @Uat("PY-130")
    fun aToneNotKnownAndErhua() = assertRows(
        "toneless pinyin, no zhuyin: the syllable, no tone" to { assertEquals(CardReading(Syllables.id("chun"), null), WordCard.readingOf("chun", "")) },
        "the neutral tone: zhuyin's ˙ says so" to { assertEquals(CardReading(Syllables.id("men"), ToneMark.NEUTRAL), WordCard.readingOf("men", "˙ㄇㄣ")) },
        "marked" to { assertEquals(CardReading(Syllables.id("lve"), 4), WordCard.readingOf("lüè", "ㄌㄩㄝˋ")) },
        "ü toneless: its diaeresis no tone mark" to { assertEquals(CardReading(Syllables.id("lv"), null), WordCard.readingOf("lü", "")) },
        "ǜ: marked" to { assertEquals(CardReading(Syllables.id("lv"), 4), WordCard.readingOf("lǜ", "ㄌㄩˋ")) },
        "儿化's r: none, 儿's card as it is" to { assertNull(WordCard.readingOf("r", "ㄦ")) },
        "nothing read" to { assertNull(WordCard.readingOf("", "")) },
        "no syllable" to { assertNull(WordCard.readingOf("OK", "")) },
    )

    @Test
    fun countedByHanCharacters() {
        assertEquals(2, WordCard.hanCount("卡拉OK"))
        assertEquals(1, WordCard.hanCount("𤝰a"))
        assertEquals(0, WordCard.hanCount("iPhone😀"))
    }

    @Test
    fun radicalsAndStrokesAsTheCharactersCardCountsThem() {
        val c = card("行江", "xing", "jiang")
        assertEquals(listOf("行" to 6, "氵" to 6), c.rows.map { it.radical to it.strokes })
        // of the mainland's order where there is one, not Unihan's count
        val order = StrokeShape.parse("P P S H H SG")
        assertEquals(order.size, c.rows[0].strokes)
    }

    @Test
    @Uat("AX-16")
    fun spoken() {
        val words = CharCard.Words("读音 ", "部首 ", "拆字 ", "笔顺 ", "、", "，", { "$it 画" }) { it.name }
        val c = card("银行", "yin", "hang")
        assertEquals("银行，读音 yín háng", c.spoken(words))
        assertEquals("行，háng，部首 行，6 画", c.rows[1].spoken(words))
        assertEquals("江", WordCard.Row("江", "", "", null, null, null).spoken(words))
    }

    @Test
    @Uat("PY-126")
    fun aCharactersLineItsReadingThenItsOthers() = assertRows(
        "the candidate's first, then the card's order, two in all, … for more" to { assertEquals("háng / xíng…", readings.lineOf("行", "háng", ReadingStyle.PINYIN)) },
        "its commonest first: not twice" to { assertEquals("xíng / háng…", readings.lineOf("行", "xíng", ReadingStyle.PINYIN)) },
        "zhuyin" to { assertEquals("ㄏㄤˊ / ㄒㄧㄥˊ…", readings.lineOf("行", "ㄏㄤˊ", ReadingStyle.ZHUYIN)) },
        "one reading alone" to { assertEquals("jiāng", readings.lineOf("江", "jiāng", ReadingStyle.PINYIN)) },
        "none known: nothing, as the bar has" to { assertEquals("", readings.lineOf("江", "", ReadingStyle.ZHUYIN)) },
    )
}
