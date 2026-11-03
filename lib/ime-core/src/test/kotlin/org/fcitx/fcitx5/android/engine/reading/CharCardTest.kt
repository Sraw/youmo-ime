/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.reading

import org.fcitx.fcitx5.android.engine.data.CodeTable
import org.fcitx.fcitx5.android.engine.lattice.Predictor
import org.fcitx.fcitx5.android.engine.phrase.CharInfo
import org.fcitx.fcitx5.android.engine.phrase.CharInfoTest
import org.fcitx.fcitx5.android.engine.stroke.Strokes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

class CharCardTest {

    private val data = ReadingData.data
    private val readings = CandidateReadings(data, data.tones) { Predictor.charReadingsOf(data.dictionary, data.vocabulary) }
    private val strokes = Strokes(CodeTable.load(ByteBuffer.wrap(Strokes.read("...\n行\tppshhz\n行\tppshhs\n江\tnnhhsh\n".reader().buffered(), "stroke").build().toByteArray()))) { 0f }

    private fun card(text: String, before: String = "", info: CharInfo = CharInfoTest.info, strokes: Strokes? = this.strokes) =
        CharCard.of(text, before, readings, data.charReadings, info, strokes)

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
        // the first order the stroke table has: 竖钩 a 竖, as the mainland's five classes count it
        assertEquals("丿丿丨一一丨", card.strokeOrder)
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
        assertEquals(11, qian.strokes)
        // no word of either: as 通用规范 lists them
        assertEquals(listOf("gān", "qián"), qian.readings.map { it.pinyin })
        // no info file, no stroke table: the readings alone; strokes counted from the order where there is one
        val bare = card("江", info = CharInfo.EMPTY)!!
        assertEquals(listOf(CharCard.Row("jiāng", "ㄐㄧㄤ", emptyList())), bare.readings)
        assertNull(bare.radical)
        assertEquals(6, bare.strokes)
        assertEquals("丶丶一一丨一", bare.strokeOrder)
        assertEquals(CharCard("江", listOf(CharCard.Row("jiāng", "ㄐㄧㄤ", emptyList())), null, null, null, null), card("江", info = CharInfo.EMPTY, strokes = null))
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
    fun spokenAsOneSentence() {
        val words = CharCard.Words("读音 ", "部首 ", "拆字 ", "、", "，") { "$it 画" }
        assertEquals("行，读音 xíng、háng、héng、hàng，部首 行，6 画，拆字 彳 亍", card("行")!!.spoken(words))
        assertEquals("江，读音 jiāng", card("江", info = CharInfo.EMPTY, strokes = null)!!.spoken(words))
    }
}
