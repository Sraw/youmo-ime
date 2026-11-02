/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.InputMethodCatalog.ENGLISH
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.PINYIN
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.WUBI
import org.fcitx.fcitx5.android.engine.phrase.Chaizi
import org.fcitx.fcitx5.android.engine.reading.ReadingStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 候选词注音 as [Engines] puts it on the candidates: off by default, each Chinese input method's when on. */
class EnginesReadingsTest : EnginesFixture() {

    init {
        chaizi = Chaizi.format(mapOf("nvzi" to listOf("好"))).toByteArray()
    }

    private fun engines(style: ReadingStyle, settings: EngineSettings = EngineSettings()) =
        Engines(::load, null, warmUps = inPlace).apply { this.settings = settings.copy(candidateReading = style) }

    @Test
    fun offShowsNoneAndKeepsTheHints() {
        val e = engines(ReadingStyle.OFF)
        assertEquals(emptyList<List<String>>(), e.type(PINYIN, "ni").readings)
        assertTrue(e.candidates(PINYIN, 0, 2).all { it.readings.isEmpty() })
        // the stroke lookup shows each character's reading as its hint
        val strokes = engines(ReadingStyle.OFF).type(PINYIN, "upsnp")
        assertTrue(strokes.toString(), strokes.hints.any { it.isNotEmpty() })
    }

    @Test
    fun pinyinOnEachCandidateAsTyped() {
        val e = engines(ReadingStyle.PINYIN)
        val s = e.type(PINYIN, "ni")
        assertEquals(s.candidates.map { listOf("nǐ") }, s.readings)
        assertEquals(listOf("nǐ"), e.candidates(PINYIN, 0, 1).single().readings)
    }

    @Test
    fun zhuyin() {
        assertEquals(listOf("ㄏㄠˇ"), engines(ReadingStyle.ZHUYIN).type(PINYIN, "hao").readings.first())
    }

    @Test
    fun pinyinsHintsGoWhileOnATablesStay() {
        val e = engines(ReadingStyle.PINYIN, wubiShown)
        assertTrue(engines(ReadingStyle.PINYIN).type(PINYIN, "upsnp").hints.isEmpty())
        val off = engines(ReadingStyle.OFF, wubiShown).type(WUBI, "vbg")
        val on = e.type(WUBI, "vbg")
        assertEquals(off.hints, on.hints)
        // a table's candidate read by its text
        assertEquals(listOf("hǎo"), on.readings[on.candidates.indexOf("好")])
    }

    @Test
    fun englishShowsNone() {
        assertEquals(emptyList<List<String>>(), engines(ReadingStyle.PINYIN).type(ENGLISH, "th").readings)
    }
}
