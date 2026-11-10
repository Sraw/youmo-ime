/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.ENGLISH
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.PINYIN
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.WUBI
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.phrase.Chaizi
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.reading.ReadingData
import org.fcitx.fcitx5.android.engine.reading.ReadingStyle
import org.fcitx.fcitx5.android.engine.session.Choice
import org.fcitx.fcitx5.android.engine.stroke.Strokes
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
    @Uat("PY-111")
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
    @Uat("PY-113")
    fun eachCandidateIsReadAsTyped() = assertRows(
        "pinyin: the typed syllable, its tone" to { readAsTyped(ReadingStyle.PINYIN, "nǐ") },
        "zhuyin: the same syllable in 注音" to { readAsTyped(ReadingStyle.ZHUYIN, "ㄋㄧˇ") },
    )

    private fun readAsTyped(style: ReadingStyle, want: String) {
        val e = engines(style)
        val s = e.type(PINYIN, "ni")
        assertEquals(s.candidates.map { listOf(want) }, s.readings)
        assertEquals(listOf(want), e.candidates(PINYIN, 0, 1).single().readings)
    }

    @Test
    fun pinyinsHintsGoWhileOnATablesStay() {
        val e = engines(ReadingStyle.PINYIN, wubiShown)
        assertTrue(engines(ReadingStyle.PINYIN).type(PINYIN, "upsnp").hints.isEmpty())
        val off = engines(ReadingStyle.OFF, wubiShown).type(WUBI, "vbg")
        val on = e.type(WUBI, "vbg")
        // readings off: none read, the table's hints shown all the same
        assertTrue(off.readings.all { it.isEmpty() })
        assertTrue("$off", off.hints.any { it.isNotEmpty() })
        assertEquals(off.hints, on.hints)
        // a table's candidate read by its text
        assertEquals(listOf("hǎo"), on.readings[on.candidates.indexOf("好")])
    }

    @Test
    fun englishShowsNone() {
        assertEquals(emptyList<List<String>>(), engines(ReadingStyle.PINYIN).type(ENGLISH, "th").readings)
    }

    // full pinyin's chain (stroke filter, glide, table forgetting, stroke lookup) hands on the reading typed:
    // 行 typed hang reads háng, not its text's likelier xíng
    @Test
    @Uat("PY-113")
    fun aCharacterOfTwoReadingsIsReadAsTypedThroughFullPinyinsWrappers() {
        pinyin = PinyinDataBuilder()
            .unigram("<unk>", -7f, 0f).unigram("行", -3f, 0f)
            .entry("行", syl("xing")).entry("行", syl("hang"), weight = -1f)
            .charTone('行'.code, Syllables.id("xing"), 2).charTone('行'.code, Syllables.id("hang"), 2)
            .build().toByteArray()
        val e = engines(ReadingStyle.PINYIN)
        val s = e.type(PINYIN, "hang")
        assertEquals(s.toString(), listOf("háng"), s.readings[s.candidates.indexOf("行")])
        assertEquals(listOf("háng"), e.candidates(PINYIN, 0, 10).single { it.text == "行" }.readings)
    }

    // a table's pinyin lookup (五笔's z) hands on the reading typed as full pinyin does: 行 looked up
    // by hang reads háng, not its text's likelier xíng; the table's own candidates stay read by their text
    @Test
    fun aTablesPinyinLookupIsReadAsTyped() {
        pinyin = PinyinDataBuilder()
            .unigram("<unk>", -7f, 0f).unigram("行", -3f, 0f).unigram("好", -2.5f, 0f)
            .entry("行", syl("xing")).entry("行", syl("hang"), weight = -1f).entry("好", syl("hao"))
            .charTone('行'.code, Syllables.id("xing"), 2).charTone('行'.code, Syllables.id("hang"), 2)
            .charTone('好'.code, Syllables.id("hao"), 3)
            .build().toByteArray()
        val e = engines(ReadingStyle.PINYIN, wubiShown)
        val s = e.type(WUBI, "zhang")
        val at = s.candidates.indexOf("行")
        assertTrue(s.toString(), at >= 0)
        assertEquals(s.toString(), listOf("háng"), s.readings[at])
        assertEquals(listOf("háng"), e.candidates(WUBI, 0, 10).single { it.text == "行" }.readings)
        val coded = engines(ReadingStyle.PINYIN, wubiShown).type(WUBI, "vbg")
        assertEquals(listOf("hǎo"), coded.readings[coded.candidates.indexOf("好")])
    }

    // a polyphone found by its parts or strokes is hinted all its readings ("xing/hang/heng"): drawn above as
    // one, toned, as any candidate is (A5, the user, 2026-11-06), the one it has after what was put before it
    // (银 then 行: háng), else its commonest (xíng); no hint left beside it, 候选词注音 on or off
    @Test
    @Uat("PY-111", "PY-118")
    fun aPolyphoneFoundByItsPartsOrStrokesIsReadOnceAbove() {
        pinyin = ReadingData.bytes
        chaizi = Chaizi.format(mapOf("chichu" to listOf("行"))).toByteArray()
        strokes = Strokes.read("...\n行\tppshhs\n".reader().buffered(), "stroke").build().toByteArray()
        for ((style, alone, after) in listOf(
            Triple(ReadingStyle.PINYIN, "xíng", "háng"),
            Triple(ReadingStyle.OFF, "xíng", "háng"),
            Triple(ReadingStyle.ZHUYIN, "ㄒㄧㄥˊ", "ㄏㄤˊ"),
        )) {
            for (keys in listOf("uchichu", "uppshhs")) {
                val e = engines(style)
                val s = e.type(PINYIN, keys)
                val at = s.candidates.indexOf("行")
                assertTrue("$style $keys $s", at >= 0)
                assertEquals("$style $keys $s", listOf(alone), s.readings[at])
                assertEquals("$style $keys", "", s.hints.getOrElse(at) { "" })
                assertEquals(Choice("行", "", listOf(alone)), e.candidates(PINYIN, 0, 10).single { it.text == "行" })
            }
            // 银 picked, then 行 by its parts after it
            val e = engines(style)
            val typed = e.type(PINYIN, "yinuchichu")
            val yin = typed.candidates.indexOf("银")
            assertTrue("$style $typed", yin >= 0)
            val s = e.onEvent(PINYIN, EngineEvent.PICK, yin)
            val at = s.candidates.indexOf("行")
            assertTrue("$style $s", at >= 0)
            assertEquals("$style $s", listOf(after), s.readings[at])
            assertEquals("", s.hints.getOrElse(at) { "" })
            assertEquals(listOf(after), e.candidates(PINYIN, 0, 10).single { it.text == "行" }.readings)
        }
    }
}
