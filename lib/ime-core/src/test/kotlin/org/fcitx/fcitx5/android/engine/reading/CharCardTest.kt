/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.reading

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.engine.data.CodeTable
import org.fcitx.fcitx5.android.engine.lattice.Predictor
import org.fcitx.fcitx5.android.engine.phrase.CharInfo
import org.fcitx.fcitx5.android.engine.phrase.CharInfoTest
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.stroke.StrokeShape
import org.fcitx.fcitx5.android.engine.stroke.Strokes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

class CharCardTest {

    private val data = ReadingData.data
    private val readings = CandidateReadings(data, data.tones) { Predictor.charReadingsOf(data.dictionary, data.vocabulary) }
    private val strokes = Strokes(CodeTable.load(ByteBuffer.wrap(Strokes.read("...\n行\tppshhz\n江\tnnhhsh\n".reader().buffered(), "stroke").build().toByteArray()))) { 0f }

    private fun card(text: String, before: String = "", info: CharInfo = CharInfoTest.info, strokes: Strokes? = this.strokes, first: CardReading? = null) =
        CharCard.of(text, before, readings, data.charReadings, info, { strokes }, first)

    @Test
    fun everyReadingWithItsWordsAndTheCharactersMakeUp() {
        val card = card("行")!!
        assertEquals(
            listOf(
                CharCard.Row("xíng", "ㄒㄧㄥˊ", listOf("进行", "行走")),
                CharCard.Row("háng", "ㄏㄤˊ", listOf("银行")),
                CharCard.Row("héng", "ㄏㄥˊ", listOf("道行")),
                CharCard.Row("hàng", "ㄏㄤˋ", listOf("树行子")),
            ),
            card.readings,
        )
        assertEquals("行", card.radical)
        assertEquals(6, card.strokes)
        assertEquals(listOf(CharCard.Part("彳", "chì"), CharCard.Part("亍", "chù")), card.parts)
        // the mainland's order (charinfo.data's), each stroke's shape: its last a 竖钩; not the stroke
        // table's (rime-stroke's, the five kinds only: a 折), which is only for a character charinfo.data has no order of
        assertEquals("丿丿丨一一亅", card.orderShown())
        assertEquals(StrokeShape.parse("P P S H H SG"), card.strokeOrder)
        assertEquals("丿丿丨一一乛", card("行", info = CharInfo.EMPTY)!!.orderShown())
    }

    @Test
    fun theStrokeTableIsReadOnlyForACharacterWithoutItsOrder() {
        var asked = 0
        val counted = { asked++; strokes }
        // charinfo.data has 行's order: the table (a pack's file, read on first use) not asked for
        CharCard.of("行", "", readings, data.charReadings, CharInfoTest.info, counted)
        assertEquals(0, asked)
        CharCard.of("行", "", readings, data.charReadings, CharInfo.EMPTY, counted)
        assertEquals(1, asked)
    }

    @Test
    fun theReadingsInTheCandidatesOrder() {
        assertEquals(listOf("háng", "xíng", "héng", "hàng"), card("行", "银")!!.readings.map { it.pinyin })
        assertEquals(listOf("银行"), card("行", "银")!!.readings.first().examples)
    }

    @Test
    fun whatIsNotKnownIsLeftOut() {
        // 乾 not taken apart, no stroke order
        val qian = card("乾")!!
        assertNull(qian.parts)
        assertNull(qian.strokeOrder)
        assertNull(qian.orderShown())
        assertEquals(11, qian.strokes)
        // no word of either: as 通用规范 lists them
        assertEquals(listOf("gān", "qián"), qian.readings.map { it.pinyin })
        // no info file, no stroke table: the readings alone; strokes counted from the order where there is one
        val bare = card("江", info = CharInfo.EMPTY)!!
        assertEquals(listOf(CharCard.Row("jiāng", "ㄐㄧㄤ", emptyList())), bare.readings)
        assertNull(bare.radical)
        assertEquals(6, bare.strokes)
        assertEquals("丶丶一一丨一", bare.orderShown())
        assertEquals(CharCard("江", listOf(CharCard.Row("jiāng", "ㄐㄧㄤ", emptyList())), null, null, null, null), card("江", info = CharInfo.EMPTY, strokes = null))
        // the table's order of Unihan's count (打 5) where it has one, whichever it lists first; else its first,
        // the strokes counted as that order is
        fun table(text: String) = Strokes(CodeTable.load(ByteBuffer.wrap(Strokes.read(text.reader().buffered(), "stroke").build().toByteArray()))) { 0f }
        assertEquals("一丨一丨乛" to 5, card("打", strokes = table("...\n打\thsh\n打\thshsz\n"))!!.let { it.orderShown() to it.strokes })
        val da = card("打", strokes = table("...\n打\thsh\n"))!!
        assertEquals("一丨一" to 3, da.orderShown() to da.strokes)
        assertEquals(5, card("打", strokes = null)!!.strokes)
        // a part with no reading
        assertEquals(listOf(CharCard.Part("氵", "shuǐ"), CharCard.Part("工", "gōng")), card("江")!!.parts)
    }

    @Test
    fun noCardButOfOneHanCharacter() {
        assertNull(card("银行"))
        assertNull(card("a"))
        assertNull(card(""))
        assertEquals(emptyList<CharCard.Row>(), card("㐀")!!.readings)
    }

    @Test
    fun theDictionariesNeutralToneLastAndATraditionalCharactersWordsItsSimplifiedOnes() {
        assertEquals(listOf("tóu", "tou"), card("头")!!.readings.map { it.pinyin })
        val traditional = CandidateReadings(data, data.tones, { it.replace('還', '还') }) { Predictor.charReadingsOf(data.dictionary, data.vocabulary) }
        val huan = CharCard.of("還", "", traditional, data.charReadings, CharInfoTest.info, { strokes })!!
        assertEquals(listOf("hái", "huán"), huan.readings.map { it.pinyin })
        assertEquals(listOf("还是"), huan.readings.first().examples)
    }

    @Test
    fun thePartsWithTheirReadings() {
        assertEquals("彳 亍（chì chù）", card("行")!!.partsShown())
        assertNull(card("乾")!!.partsShown())
        val some = CharCard("x", emptyList(), null, null, listOf(CharCard.Part("丅", null), CharCard.Part("口", "kǒu")), null)
        assertEquals("丅 口（— kǒu）", some.partsShown())
        assertEquals("丅", some.copy(parts = listOf(CharCard.Part("丅", null))).partsShown())
    }

    @Test
    fun spokenAsOneSentence() {
        val names = mapOf(StrokeShape.P to "撇", StrokeShape.S to "竖", StrokeShape.H to "横", StrokeShape.SG to "竖钩", StrokeShape.ZHE to "折")
        val words = CharCard.Words("读音 ", "部首 ", "拆字 ", "笔顺 ", "、", "，", { "$it 画" }) { names.getValue(it) }
        assertEquals("行，读音 xíng、háng、héng、hàng，部首 行，6 画，拆字 彳 亍，笔顺 撇、撇、竖、横、横、竖钩", card("行")!!.spoken(words))
        // a stroke known by its kind only: the kind's name
        assertEquals("行，读音 xíng、háng、héng、hàng，6 画，笔顺 撇、撇、竖、横、横、折", card("行", info = CharInfo.EMPTY)!!.spoken(words))
        assertEquals("江，读音 jiāng", card("江", info = CharInfo.EMPTY, strokes = null)!!.spoken(words))
    }

    // opened from a word's row, or a held character: the reading it has there first, so the two never read it apart
    @Test
    @Uat("PY-125")
    fun theReadingACandidateHasItInFirst() {
        fun first(syllable: String, tone: Int?) = card("行", first = CardReading(Syllables.id(syllable), tone))!!.readings
        assertRows(
            "of its syllable and tone: that row first, marked, its words kept" to {
                val r = first("hang", 2)
                assertEquals(listOf("háng", "xíng", "héng", "hàng"), r.map { it.pinyin })
                assertEquals(CharCard.Row("háng", "ㄏㄤˊ", listOf("银行"), inWord = true), r.first())
                assertEquals(listOf(true, false, false, false), r.map { it.inWord })
            },
            "its syllable in another tone (a word's exception): a row of it put first, no words" to {
                val r = first("xing", 4)
                assertEquals(CharCard.Row("xìng", "ㄒㄧㄥˋ", emptyList(), inWord = true), r.first())
                assertEquals(listOf("xíng", "háng", "héng", "hàng"), r.drop(1).map { it.pinyin })
            },
            "a syllable the card lacks: the same" to { assertEquals(listOf("jiāng", "xíng"), first("jiang", 1).take(2).map { it.pinyin }) },
            "a tone not known: toneless, no zhuyin" to { assertEquals(CharCard.Row("chun", "", emptyList(), inWord = true), first("chun", null).first()) },
            "none: the card as it is" to { assertEquals(card("行")!!.readings, card("行", first = null)!!.readings) },
        )
    }
}
