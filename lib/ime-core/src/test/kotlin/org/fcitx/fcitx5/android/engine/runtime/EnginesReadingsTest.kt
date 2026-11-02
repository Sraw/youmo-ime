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
import org.fcitx.fcitx5.android.engine.session.Choice
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
    fun offShowsNoneButTheHintsAboveTheCandidates() {
        val e = engines(ReadingStyle.OFF)
        assertEquals(emptyList<List<String>>(), e.type(PINYIN, "ni").readings)
        assertTrue(e.candidates(PINYIN, 0, 2).all { it.readings.isEmpty() })
        // the stroke lookup's hint of each character's reading: a reading above it, no hint beside
        val strokes = engines(ReadingStyle.OFF).type(PINYIN, "upsnp")
        val at = strokes.candidates.indexOf("你")
        assertEquals(strokes.toString(), listOf("nǐ"), strokes.readings[at])
        assertEquals("", strokes.hints[at])
        val listed = engines(ReadingStyle.OFF).run { type(PINYIN, "upsnp"); candidates(PINYIN, 0, 10) }.single { it.text == "你" }
        assertEquals(Choice("你", "", listOf("nǐ")), listed)
        // a part's
        val parts = engines(ReadingStyle.OFF).type(PINYIN, "unvzi")
        assertEquals(parts.toString(), listOf("hǎo"), parts.readings[parts.candidates.indexOf("好")])
    }

    @Test
    fun aWordTypedByItsMisreadingIsAnnotatedWithItsTrueReading() {
        for ((style, want) in listOf(ReadingStyle.OFF to listOf("jué", "sè"), ReadingStyle.PINYIN to listOf("jué", "sè"), ReadingStyle.ZHUYIN to listOf("ㄐㄩㄝˊ", "ㄙㄜˋ"))) {
            val e = engines(style)
            val s = e.type(PINYIN, "jiaose")
            val at = s.candidates.indexOf("角色")
            assertEquals("$style $s", want, s.readings[at])
            assertEquals("", s.hints.getOrElse(at) { "" })
            assertEquals(want, e.candidates(PINYIN, 0, 10).single { it.text == "角色" }.readings)
        }
        // typed by its true reading: as typed, no hint
        assertEquals(listOf("jué", "sè"), engines(ReadingStyle.PINYIN).type(PINYIN, "juese").let { it.readings[it.candidates.indexOf("角色")] })
        assertTrue(engines(ReadingStyle.OFF).type(PINYIN, "juese").readings.all { it.isEmpty() })
    }

    @Test
    fun offATablesHintsStay() {
        val off = engines(ReadingStyle.OFF, wubiShown).type(WUBI, "vbg")
        assertTrue(off.readings.all { it.isEmpty() })
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
