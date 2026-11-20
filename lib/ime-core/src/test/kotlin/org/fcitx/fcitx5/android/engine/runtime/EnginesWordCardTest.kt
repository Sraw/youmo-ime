/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.ENGLISH
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.PINYIN
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.SHUANGPIN
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.WUBI
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.ZHUYIN
import org.fcitx.fcitx5.android.engine.glide.Glide
import org.fcitx.fcitx5.android.engine.glide.GlideLayout
import org.fcitx.fcitx5.android.engine.phrase.CharInfoTest
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.pinyin.ZhuyinKeys
import org.fcitx.fcitx5.android.engine.reading.CandidateCard
import org.fcitx.fcitx5.android.engine.reading.CardReading
import org.fcitx.fcitx5.android.engine.reading.ReadingData
import org.fcitx.fcitx5.android.engine.reading.ReadingStyle
import org.fcitx.fcitx5.android.engine.reading.WordCard
import org.fcitx.fcitx5.android.engine.user.CharGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A held candidate's card as [Engines.card] works it out: its reading the bar's own, whatever was typed. */
class EnginesWordCardTest : EnginesFixture() {

    // the fixture's own: 角色 under its misreading too
    private val misread = pinyin

    init {
        pinyin = ReadingData.bytes
        charInfo = CharInfoTest.builder().build().toByteArray()
    }

    private var phrases = ""

    private fun engines(style: ReadingStyle = ReadingStyle.OFF, settings: EngineSettings = EngineSettings()) =
        Engines(::load, null, warmUps = inPlace, additions = { Engines.Additions(phrases, "", dictionary = { null }) })
            .apply { this.settings = settings.copy(candidateReading = style) }

    private fun Engines.at(im: String, text: String): Int =
        candidates(im, 0, 30).indexOfFirst { it.text == text }.also { check(it >= 0) { "$text not in ${candidates(im, 0, 30)}" } }

    // [text]'s card once [keys] are typed in [im], with 候选词注音 [style]
    private fun cardOf(im: String, keys: String, text: String, style: ReadingStyle = ReadingStyle.OFF, typing: (Engines) -> Unit = { it.type(im, keys) }): CandidateCard? {
        val e = engines(style)
        typing(e)
        return e.card(im, e.at(im, text), text)
    }

    // the card's reading of [text] in [style] is the bar's, 候选词注音 [style]: each code point's
    private fun assertTheBars(im: String, text: String, typing: (Engines) -> Unit) {
        for (style in listOf(ReadingStyle.PINYIN, ReadingStyle.ZHUYIN)) {
            val e = engines(style)
            typing(e)
            val at = e.at(im, text)
            val bar = e.candidates(im, at, 1).single().readings
            val card = e.card(im, at, text)
            val read = when (card) {
                is CandidateCard.Word -> if (style == ReadingStyle.PINYIN) card.card.pinyin else card.card.zhuyin
                is CandidateCard.Char -> card.card.readings.first().let { listOf(if (style == ReadingStyle.PINYIN) it.pinyin else it.zhuyin) }
                else -> error("$card")
            }
            assertEquals("$im $text $style", bar, read)
        }
    }

    @Test
    @Uat("PY-124")
    fun aWordsCardReadsItAsTheBarDoes() {
        val card = cardOf(PINYIN, "yinhang", "银行") as CandidateCard.Word
        assertEquals("yín háng", card.line)
        assertEquals(listOf("银", "行"), card.card.rows.map { it.text })
        assertEquals(listOf("yín", "háng"), card.card.rows.map { it.pinyin })
        assertEquals(CardReading(Syllables.id("hang"), 2), card.card.rows[1].reading)
        assertTheBars(PINYIN, "银行") { it.type(PINYIN, "yinhang") }
        assertEquals("ㄧㄣˊ ㄏㄤˊ", (cardOf(PINYIN, "yinhang", "银行", ReadingStyle.ZHUYIN) as CandidateCard.Word).line)
    }

    @Test
    @Uat("PY-124", "SP-29", "T9-64")
    fun theSameWordTypedAnyWayIsReadTheSame() = assertRows(
        "简拼" to { assertTheBars(PINYIN, "银行") { it.type(PINYIN, "yh") } },
        "双拼" to { assertTheBars(SHUANGPIN, "银行") { it.type(SHUANGPIN, "ynhh") } },
        "the nine keys" to { assertTheBars(PINYIN, "银行") { e -> "9464264".forEach { e.onEvent(PINYIN, EngineEvent.CHAR, Keyboard.pinyinNineKey(it)) } } },
        "the 18 keys" to { assertTheBars(PINYIN, "银行") { e -> "yinhang".forEach { e.onEvent(PINYIN, EngineEvent.CHAR, Keyboard.pinyin18Key(NineKeyLetters.G18.key(it))) } } },
        "双拼's 14 keys" to { assertTheBars(SHUANGPIN, "银行") { e -> "ynhh".forEach { e.onEvent(SHUANGPIN, EngineEvent.CHAR, Keyboard.shuangpin14Key(NineKeyLetters.G14.key(it))) } } },
        "a glide" to {
            assertTheBars(PINYIN, "银行") { e ->
                val path = Glide("yinhang".flatMap { GlideLayout.QWERTY.centre(it).toList() }.toFloatArray(), GlideLayout.QWERTY)
                e.onEvent(PINYIN, EngineEvent.CHAR, Keyboard.GLIDE, payload = checkNotNull(e.glide(PINYIN, path)))
            }
        },
        "zhuyin's keys" to { assertTheBars(ZHUYIN, "银行") { it.type(ZHUYIN, "ㄧㄣㄏㄤ".map(ZhuyinKeys::symbolCode).joinToString("")) } },
        "each of them yín háng" to {
            assertEquals("yín háng", (cardOf(SHUANGPIN, "ynhh", "银行") as CandidateCard.Word).line)
            assertEquals("yín háng", (cardOf(PINYIN, "yh", "银行") as CandidateCard.Word).line)
        },
    )

    @Test
    @Uat("PY-125")
    fun aPolyphoneIsReadAsItsWordHasItAndItsCardPutsThatFirst() {
        val e = engines()
        e.type(PINYIN, "xingzou")
        val walk = e.card(PINYIN, e.at(PINYIN, "行走"), "行走") as CandidateCard.Word
        val row = walk.card.rows.first()
        assertEquals("xíng", row.pinyin)
        val card = checkNotNull(e.charCardOf(row.text, row.reading))
        assertEquals("xíng" to true, card.readings.first().let { it.pinyin to it.inWord })
        val bank = (cardOf(PINYIN, "yinhang", "银行") as CandidateCard.Word).card.rows[1]
        assertEquals("háng" to true, e.charCardOf(bank.text, bank.reading)!!.readings.first().let { it.pinyin to it.inWord })
    }

    @Test
    @Uat("PY-126")
    fun aCharacterHasItsLineThenItsCard() {
        val card = cardOf(PINYIN, "xing", "行") as CandidateCard.Char
        assertEquals("xíng / háng…", card.line)
        assertEquals("行", card.card.text)
        assertEquals("xíng" to true, card.card.readings.first().let { it.pinyin to it.inWord })
        assertTheBars(PINYIN, "行") { it.type(PINYIN, "xing") }
        assertEquals("háng / xíng…", (cardOf(PINYIN, "hang", "行") as CandidateCard.Char).line)
        assertEquals("ㄏㄤˊ / ㄒㄧㄥˊ…", (cardOf(ZHUYIN, "ㄏㄤ".map(ZhuyinKeys::symbolCode).joinToString(""), "行") as CandidateCard.Char).line)
    }

    @Test
    @Uat("PY-127")
    fun aWordTypedByItsMisreadingIsReadItsTrueWay() {
        pinyin = misread
        for (style in listOf(ReadingStyle.OFF, ReadingStyle.PINYIN)) {
            assertEquals("$style", "jué sè", (cardOf(PINYIN, "jiaose", "角色", style) as CandidateCard.Word).line)
        }
        assertEquals("ㄐㄩㄝˊ ㄙㄜˋ", (cardOf(PINYIN, "jiaose", "角色", ReadingStyle.ZHUYIN) as CandidateCard.Word).line)
        assertTheBars(PINYIN, "角色") { it.type(PINYIN, "jiaose") }
        val jue = (cardOf(PINYIN, "jiaose", "角色") as CandidateCard.Word).card.rows.first()
        assertEquals(CardReading(Syllables.id("jue"), 2), jue.reading)
    }

    @Test
    @Uat("TBL-53")
    fun aTablesCandidateIsReadByItsText() {
        val e = engines(settings = wubiShown)
        e.type(WUBI, "vbg")
        val good = e.card(WUBI, e.at(WUBI, "好"), "好") as CandidateCard.Char
        assertTrue(good.line, good.line.startsWith("hǎo"))
    }

    @Test
    @Uat("PY-131")
    fun traditionalOutputHasItsCardItsCharactersAsShown() {
        val e = engines()
        e.type(PINYIN, "yinhang")
        val at = e.at(PINYIN, "银行")
        val shown: (String) -> String = { it.replace('银', '銀') }
        // the text as shown, not the engine's: none unless converted to check
        assertNull(e.card(PINYIN, at, "銀行"))
        val card = e.card(PINYIN, at, "銀行", shown = shown) as CandidateCard.Word
        assertEquals(listOf("銀", "行"), card.card.rows.map { it.text })
        assertEquals("yín háng", card.line)
        assertEquals("銀行", card.card.text)
    }

    @Test
    @Uat("PY-128", "EN-29")
    fun noHanCharacterNoCard() {
        val e = engines()
        e.type(PINYIN, "yinhang")
        assertNull(e.card(PINYIN, e.at(PINYIN, "银行"), "江"))
        assertNull(e.card(PINYIN, 99, "银行"))
        val en = engines()
        en.type(ENGLISH, "the")
        assertNull(en.card(ENGLISH, 0, en.candidates(ENGLISH, 0, 1).single().text))
        assertNull(engines().card(PINYIN, 0, "银行"))
    }

    @Test
    @Uat("PY-133")
    fun moreThanEightHanCharactersHaveTheirLineAlone() {
        phrases = "nine,1=银行银行银行银行银\neight,1=银行银行银行银行\nmix,1=银行OK银行银行银行\n"
        val e = engines(ReadingStyle.PINYIN)
        e.type(PINYIN, "nine")
        val at = e.at(PINYIN, "银行银行银行银行银")
        val line = e.card(PINYIN, at, "银行银行银行银行银") as CandidateCard.Line
        assertEquals("银行银行银行银行银", line.text)
        assertEquals(WordCard.line("银行银行银行银行银", e.candidates(PINYIN, at, 1).single().readings), line.line)
        assertTrue(line.line, line.line.startsWith("yín háng"))
        val eight = engines()
        eight.type(PINYIN, "eight")
        assertTrue(eight.card(PINYIN, eight.at(PINYIN, "银行银行银行银行"), "银行银行银行银行") is CandidateCard.Word)
        val mix = engines()
        mix.type(PINYIN, "mix")
        assertTrue(mix.card(PINYIN, mix.at(PINYIN, "银行OK银行银行银行"), "银行OK银行银行银行") is CandidateCard.Word)
        phrases = "para,1=" + "银行".repeat(100) + "\n"
        val para = engines(ReadingStyle.PINYIN)
        para.type(PINYIN, "para")
        val long = para.card(PINYIN, para.at(PINYIN, "银行".repeat(100)), "银行".repeat(100)) as CandidateCard.Line
        assertEquals("银行".repeat(100), long.text)
        assertEquals(List(WordCard.LINE_CHARS / 2) { "yín háng" }.joinToString(" ") + " …", long.line)
    }

    // the bar's reading (jiǎo sè as typed), not the word's true jué sè
    @Test
    @Uat("PY-133")
    fun aLongTypedSentencesLineIsTheBarsAsFarAsItGoes() {
        pinyin = misread
        val text = "角色".repeat(33)
        for (style in listOf(ReadingStyle.PINYIN, ReadingStyle.ZHUYIN)) {
            val e = engines(style)
            e.type(PINYIN, "jiaose".repeat(33))
            val at = e.at(PINYIN, text)
            val bar = e.candidates(PINYIN, at, 1).single().readings
            val line = (e.card(PINYIN, at, text) as CandidateCard.Line).line
            assertEquals("$style", bar.take(WordCard.LINE_CHARS).joinToString(" ") + " …", line)
        }
    }

    @Test
    @Uat("PY-128")
    fun aPhrasesCharacterHeldBackStillHasItsRow() {
        phrases = "hj,1=行江\n"
        val e = engines()
        e.rareGate = CharGate.of { it == '江'.code }
        e.reload()
        e.type(PINYIN, "hj")
        val card = e.card(PINYIN, e.at(PINYIN, "行江"), "行江") as CandidateCard.Word
        assertEquals(listOf("行", "江"), card.card.rows.map { it.text })
        assertEquals("氵", card.card.rows[1].radical)
    }

    @Test
    @Uat("PY-129")
    fun aPredictionHasItsLineAsTheBarReadsIt() {
        for (style in listOf(ReadingStyle.PINYIN, ReadingStyle.ZHUYIN)) {
            val e = engines(style)
            e.type(PINYIN, "yinhang")
            e.onEvent(PINYIN, EngineEvent.PICK, e.at(PINYIN, "银行"))
            // what may follow 银行 (the fixture's bigram): 江; after 江, 行, of several readings
            e.onEvent(PINYIN, EngineEvent.PICK, e.at(PINYIN, "江"))
            val at = e.at(PINYIN, "行")
            val card = e.card(PINYIN, at, "行") as CandidateCard.Char
            val bar = e.candidates(PINYIN, at, 1).single().readings
            assertEquals("$style", bar, card.card.readings.first().let { listOf(if (style == ReadingStyle.PINYIN) it.pinyin else it.zhuyin) })
            assertTrue("$style ${card.line}", card.line.startsWith(bar.single()))
        }
    }

    @Test
    fun theZhuyinNineKeysLineIsInZhuyin() {
        val e = engines()
        "ㄏㄤ".forEach { c -> e.onEvent(PINYIN, EngineEvent.CHAR, Keyboard.zhuyinNineKey(ZhuyinKeys.GROUP_SYMBOLS.indexOfFirst { c in it })) }
        val card = e.card(PINYIN, e.at(PINYIN, "行"), "行") as CandidateCard.Char
        assertTrue(card.line, card.line.startsWith("ㄏㄤˊ"))
    }

    @Test
    fun aCandidateOnThePagedWindowsSecondPageIsItself() {
        val e = engines(settings = EngineSettings(pageSize = 1))
        e.type(PINYIN, "yinhang")
        val second = e.candidates(PINYIN, 1, 1).single().text
        e.onEvent(PINYIN, EngineEvent.PAGE_DOWN, 0)
        // index 0 of the page shown: the second candidate's card, not the first's
        assertNull(e.card(PINYIN, 0, second))
        assertEquals(second, e.card(PINYIN, 0, second, paged = true).let { (it as? CandidateCard.Word)?.card?.text ?: (it as CandidateCard.Char).card.text })
    }

    @Test
    fun aCharactersCardOfTheDataAlone() {
        val e = engines()
        val card = e.charCardOf("行", CardReading(Syllables.id("hang"), 2))!!
        assertEquals(listOf("háng", "xíng", "héng", "hàng"), card.readings.map { it.pinyin })
        assertEquals("行", card.radical)
        assertNull(e.charCardOf("银行", null))
        assertEquals(listOf("xíng", "háng", "héng", "hàng"), e.charCardOf("行", null)!!.readings.map { it.pinyin })
    }
}
