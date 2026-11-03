/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.InputMethodCatalog.HANDWRITING
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.PINYIN
import org.fcitx.fcitx5.android.engine.handwriting.InkReading
import org.fcitx.fcitx5.android.engine.phrase.Chaizi
import org.fcitx.fcitx5.android.engine.phrase.CharInfoTest
import org.fcitx.fcitx5.android.engine.reading.ReadingData
import org.fcitx.fcitx5.android.engine.reading.ReadingStyle
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.stroke.Strokes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Handwriting's characters with all their readings, and the card of one looked up (UAT HW-30, HW-32). */
class EnginesHandwritingReadingsTest : EnginesFixture() {

    init {
        pinyin = ReadingData.bytes
        charInfo = CharInfoTest.builder().build().toByteArray()
        strokes = Strokes.read("...\n行\tppshhz\n江\tnnhhsh\n".reader().buffered(), "stroke").build().toByteArray()
        chaizi = Chaizi.format(mapOf("shuigong" to listOf("江"))).toByteArray()
    }

    private fun engines(style: ReadingStyle) = Engines(::load, null, warmUps = inPlace).apply { settings = settings.copy(candidateReading = style) }

    private fun Engines.write(piece: Int, vararg texts: String) =
        onEvent(HANDWRITING, EngineEvent.CHAR, Keyboard.INK, payload = Action.Ink(piece, InkReading(texts.toList(), DoubleArray(texts.size))))

    @Test
    fun aWrittenCharacterShowsItsReadingsTheWordsFirst() {
        val e = engines(ReadingStyle.PINYIN)
        val one = e.write(0, "行", "江")
        assertEquals(listOf("行", "江"), one.candidates)
        assertEquals(listOf(listOf("xíng/háng…"), listOf("jiāng")), one.readings)
        assertEquals(listOf("xíng/háng…"), e.candidates(HANDWRITING, 0, 1).single().readings)
        // after 银: 银行's háng first
        val e2 = engines(ReadingStyle.ZHUYIN)
        e2.write(0, "银")
        val two = e2.write(1, "行")
        assertEquals("银行", two.preedit)
        assertEquals(listOf(listOf("ㄏㄤˊ/ㄒㄧㄥˊ…")), two.readings)
    }

    @Test
    fun whatMayFollowAndOtherInputMethodsShowOneReading() {
        val e = engines(ReadingStyle.PINYIN)
        e.write(0, "行")
        val next = e.onEvent(HANDWRITING, EngineEvent.PICK, 0)
        assertEquals("行", next.commit)
        // offered after it, nothing written: one reading each
        assertTrue(next.toString(), next.readings.all { it.size <= 1 && it.none { r -> '/' in r } })
        val typed = engines(ReadingStyle.PINYIN).type(PINYIN, "xing")
        assertEquals(listOf("xíng"), typed.readings[typed.candidates.indexOf("行")])
    }

    @Test
    fun offShowsNoneAndReadsNoCharactersReadings() {
        val e = engines(ReadingStyle.OFF)
        assertEquals(emptyList<List<String>>(), e.write(0, "行").readings)
        assertTrue(e.candidates(HANDWRITING, 0, 1).single().readings.isEmpty())
    }

    @Test
    fun turnedOnWhatIsWrittenStaysAndIsReadAgain() {
        val e = engines(ReadingStyle.OFF)
        e.write(0, "银")
        e.write(1, "行")
        e.settings = e.settings.copy(candidateReading = ReadingStyle.PINYIN)
        val again = e.onEvent(HANDWRITING, EngineEvent.CHAR, Keyboard.RECALL)
        assertEquals("银行", again.preedit)
        assertEquals(listOf(listOf("háng/xíng…")), again.readings)
        // another setting: what was typed goes
        e.settings = e.settings.copy(pageSize = e.settings.pageSize + 1)
        assertEquals("", e.onEvent(HANDWRITING, EngineEvent.CHAR, Keyboard.RECALL).preedit)
    }

    @Test
    fun theCardOfAWrittenCharacterOrOneLookedUp() {
        val e = engines(ReadingStyle.OFF)
        e.write(0, "银")
        e.write(1, "行", "江")
        val card = e.charCard(HANDWRITING, 0, "行")
        assertEquals(listOf("háng", "xíng", "héng", "hàng"), card?.readings?.map { it.pinyin })
        assertEquals("丿丿丨一一乛", card?.strokeOrder)
        assertEquals("氵", e.charCard(HANDWRITING, 1, "江")?.radical)
        // not what is there now, or no such candidate
        assertNull(e.charCard(HANDWRITING, 0, "江"))
        assertNull(e.charCard(HANDWRITING, 5, "行"))
        // what may follow: no card
        e.onEvent(HANDWRITING, EngineEvent.PICK, 0)
        assertNull(e.charCard(HANDWRITING, 0, e.candidates(HANDWRITING, 0, 1).firstOrNull()?.text ?: "行"))
        // pinyin's: by its parts or strokes, not as typed
        val p = engines(ReadingStyle.OFF)
        val parts = p.type(PINYIN, "ushuigong")
        assertEquals("ㄐㄧㄤ", p.charCard(PINYIN, parts.candidates.indexOf("江"), "江")?.readings?.single()?.zhuyin)
        val q = engines(ReadingStyle.OFF)
        val byStrokes = q.type(PINYIN, "upps")
        assertNotNull(byStrokes.toString(), q.charCard(PINYIN, byStrokes.candidates.indexOf("行"), "行"))
        val r = engines(ReadingStyle.OFF)
        val typed = r.type(PINYIN, "xing")
        assertNull(r.charCard(PINYIN, typed.candidates.indexOf("行"), "行"))
        assertNull(engines(ReadingStyle.OFF).charCard(HANDWRITING, 0, "行"))
    }

    @Test
    fun noCardFileNoRadical() {
        charInfo = null
        val e = engines(ReadingStyle.OFF)
        e.write(0, "江")
        assertEquals(CharCardFields(null, 6), e.charCard(HANDWRITING, 0, "江")?.let { CharCardFields(it.radical, it.strokes) })
    }

    private data class CharCardFields(val radical: String?, val strokes: Int?)
}
