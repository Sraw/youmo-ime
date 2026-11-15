/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.PINYIN
import org.fcitx.fcitx5.android.engine.handwriting.InkReading
import org.fcitx.fcitx5.android.engine.phrase.Chaizi
import org.fcitx.fcitx5.android.engine.phrase.CharInfoTest
import org.fcitx.fcitx5.android.engine.reading.CandidateCard
import org.fcitx.fcitx5.android.engine.reading.ReadingData
import org.fcitx.fcitx5.android.engine.reading.ReadingStyle
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.stroke.Strokes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Handwriting's characters with all their readings, and the card of one looked up (UAT HW-27, HW-28, HW-29). */
class EnginesHandwritingReadingsTest : EnginesFixture() {

    init {
        pinyin = ReadingData.bytes
        charInfo = CharInfoTest.builder().build().toByteArray()
        strokes = Strokes.read("...\n行\tppshhz\n江\tnnhhsh\n".reader().buffered(), "stroke").build().toByteArray()
        chaizi = Chaizi.format(mapOf("shuigong" to listOf("江"))).toByteArray()
    }

    private fun engines(style: ReadingStyle) = Engines(::load, null, warmUps = inPlace).apply { settings = settings.copy(candidateReading = style) }

    private fun Engines.write(piece: Int, vararg texts: String) =
        onEvent(PINYIN, EngineEvent.CHAR, Keyboard.INK, payload = Action.Ink(piece, InkReading(texts.toList(), DoubleArray(texts.size))))

    @Test
    @Uat("HW-27", "HW-51")
    fun aWrittenCharacterShowsItsReadingsTheWordsFirst() {
        val e = engines(ReadingStyle.PINYIN)
        val one = e.write(0, "行", "江")
        assertEquals(listOf("行", "江"), one.candidates)
        assertEquals(listOf(listOf("xíng/háng…"), listOf("jiāng")), one.readings)
        assertEquals(listOf("xíng/háng…"), e.candidates(PINYIN, 0, 1).single().readings)
        // after 银: 银行's háng first
        val e2 = engines(ReadingStyle.ZHUYIN)
        e2.write(0, "银")
        val two = e2.write(1, "行")
        assertEquals("银行", two.preedit)
        assertEquals(listOf(listOf("ㄏㄤˊ/ㄒㄧㄥˊ…")), two.readings)
    }

    // 061-handwriting-toolbar §3.5: all the readings are handwriting's while it shows, not pinyin's own looked up
    @Test
    @Uat("HW-51")
    fun aCharacterPinyinLooksUpAfterWritingShowsOneReading() {
        val e = engines(ReadingStyle.PINYIN)
        e.write(0, "行")
        assertEquals("行", e.onEvent(PINYIN, EngineEvent.PICK, 0).commit)
        e.onEvent(PINYIN, EngineEvent.RESET, 0)
        val s = e.type(PINYIN, "uppshhz")
        val at = s.candidates.indexOf("行")
        assertTrue("$s", at >= 0)
        assertEquals(listOf("xíng"), s.readings[at])
    }

    @Test
    fun whatMayFollowAndOtherInputMethodsShowOneReading() {
        val e = engines(ReadingStyle.PINYIN)
        e.write(0, "行")
        val next = e.onEvent(PINYIN, EngineEvent.PICK, 0)
        assertEquals("行", next.commit)
        // offered after it, nothing written: one reading each
        assertTrue(next.toString(), next.candidates.isNotEmpty())
        assertTrue(next.toString(), next.readings.all { it.size <= 1 && it.none { r -> '/' in r } })
        val typed = engines(ReadingStyle.PINYIN).type(PINYIN, "xing")
        assertEquals(listOf("xíng"), typed.readings[typed.candidates.indexOf("行")])
    }

    @Test
    fun offShowsNoneAndReadsNoCharactersReadings() {
        val e = engines(ReadingStyle.OFF)
        assertEquals(emptyList<List<String>>(), e.write(0, "行").readings)
        assertTrue(e.candidates(PINYIN, 0, 1).single().readings.isEmpty())
    }

    @Test
    @Uat("HW-27")
    fun turnedOnWhatIsWrittenStaysAndIsReadAgain() {
        val e = engines(ReadingStyle.OFF)
        e.write(0, "银")
        e.write(1, "行")
        e.settings = e.settings.copy(candidateReading = ReadingStyle.PINYIN)
        val again = e.onEvent(PINYIN, EngineEvent.CHAR, Keyboard.RECALL)
        assertEquals("银行", again.preedit)
        assertEquals(listOf(listOf("háng/xíng…")), again.readings)
        // another setting: what was typed goes
        e.settings = e.settings.copy(pageSize = e.settings.pageSize + 1)
        assertEquals("", e.onEvent(PINYIN, EngineEvent.CHAR, Keyboard.RECALL).preedit)
    }

    @Test
    @Uat("HW-29")
    fun theCardOfAWrittenCharacterOrOneLookedUp() {
        val e = engines(ReadingStyle.OFF)
        e.write(0, "银")
        e.write(1, "行", "江")
        val card = e.charCard(PINYIN, 0, "行")
        assertEquals(listOf("háng", "xíng", "héng", "hàng"), card?.readings?.map { it.pinyin })
        // charinfo.data's, the mainland's (its last a 竖钩), over the stroke table's first (a 折)
        assertEquals("丿丿丨一一亅", card?.orderShown())
        assertEquals("氵", e.charCard(PINYIN, 1, "江")?.radical)
        // not what is there now, or no such candidate
        assertNull(e.charCard(PINYIN, 0, "江"))
        assertNull(e.charCard(PINYIN, 5, "行"))
        // what may follow: no card at once, its reading line (a word card's or a character's behind it)
        e.onEvent(PINYIN, EngineEvent.PICK, 0)
        val followed = e.candidates(PINYIN, 0, 1).single().text
        assertNull(e.charCard(PINYIN, 0, followed))
        assertTrue(e.card(PINYIN, 0, followed).toString(), e.card(PINYIN, 0, followed).let { it is CandidateCard.Char || it is CandidateCard.Word })
        // pinyin's: by its parts or strokes, not as typed
        val p = engines(ReadingStyle.OFF)
        val parts = p.type(PINYIN, "ushuigong")
        assertEquals("ㄐㄧㄤ", p.charCard(PINYIN, parts.candidates.indexOf("江"), "江")?.readings?.single()?.zhuyin)
        val q = engines(ReadingStyle.OFF)
        val byStrokes = q.type(PINYIN, "upps")
        assertNotNull(byStrokes.toString(), q.charCard(PINYIN, byStrokes.candidates.indexOf("行"), "行"))
        val r = engines(ReadingStyle.OFF)
        val typed = r.type(PINYIN, "xing")
        // as typed: its reading line, the card behind it
        assertNull(r.charCard(PINYIN, typed.candidates.indexOf("行"), "行"))
        assertTrue(r.card(PINYIN, typed.candidates.indexOf("行"), "行") is CandidateCard.Char)
        assertNull(engines(ReadingStyle.OFF).charCard(PINYIN, 0, "行"))
    }

    // the paged window's index, from the page shown (review 051-hw-reading M2)
    @Test
    @Uat("HW-29")
    fun aCardOnTheSecondPageIsOfItsCharacter() {
        val e = engines(ReadingStyle.OFF).apply { settings = settings.copy(pageSize = 1) }
        e.write(0, "行", "江")
        assertEquals(listOf("江"), e.onEvent(PINYIN, EngineEvent.PAGE_DOWN, 0).candidates)
        assertEquals("氵", e.charCard(PINYIN, 0, "江", paged = true)?.radical)
        assertNull(e.charCard(PINYIN, 0, "江"))
        assertNull(e.charCard(PINYIN, 0, "行", paged = true))
    }

    @Test
    @Uat("PY-131")
    fun traditionalOutputsWrittenCharacterHasItsCardAsShown() {
        val e = engines(ReadingStyle.OFF)
        e.write(0, "银")
        val shown: (String) -> String = { it.replace('银', '銀') }
        assertNull(e.card(PINYIN, 0, "銀"))
        val card = e.card(PINYIN, 0, "銀", shown = shown) as CandidateCard.Looked
        assertEquals("銀", card.card.text)
    }

    @Test
    fun aWrittenLetterHasNoCard() {
        val e = engines(ReadingStyle.PINYIN)
        e.write(0, "a")
        assertNull(e.card(PINYIN, 0, "a"))
    }

    @Test
    fun noCardFileNoRadical() {
        charInfo = null
        val e = engines(ReadingStyle.OFF)
        e.write(0, "江")
        assertEquals(CharCardFields(null, 6), e.charCard(PINYIN, 0, "江")?.let { CharCardFields(it.radical, it.strokes) })
    }

    // the card shown at once, of a character looked up
    private fun Engines.charCard(im: String, index: Int, text: String, paged: Boolean = false) =
        (card(im, index, text, paged) as? CandidateCard.Looked)?.card

    private data class CharCardFields(val radical: String?, val strokes: Int?)
}
