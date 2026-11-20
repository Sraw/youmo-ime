/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.reading.CandidateCard
import org.fcitx.fcitx5.android.engine.reading.CandidateReadings
import org.fcitx.fcitx5.android.engine.reading.ReadingStyle
import org.fcitx.fcitx5.android.engine.reading.WordCard
import org.fcitx.fcitx5.android.engine.runtime.EngineSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** uat/06-pinyin.md: a held candidate's word card reads it as the bar does, whatever was typed to get it. */
class WordCardUatTest {

    private fun EngineReplay.cardOf(text: String): CandidateCard? = engines.card(im, candidates.indexOf(text).also { assertTrue("$text not in ${candidates.take(SHOWN)}", it >= 0) }, text)

    private fun EngineReplay.word(text: String): WordCard = (cardOf(text) as CandidateCard.Word).card

    private fun replay(style: ReadingStyle = ReadingStyle.PINYIN, im: String = InputMethodCatalog.PINYIN) =
        EngineReplay(settings(EngineSettings(candidateReading = style)), im = im)

    @Test
    @Uat("PY-124", "PY-125", "PY-127", "PY-128", "PY-130", "TBL-53")
    fun theExamplesReadAsTheDesignHasThem() = assertRows(
        "银行: yín háng, ㄧㄣˊ ㄏㄤˊ, a row each with its radical" to {
            with(replay()) {
                type("yinhang")
                val card = word("银行")
                assertEquals("yín háng", card.line(ReadingStyle.PINYIN))
                assertEquals("ㄧㄣˊ ㄏㄤˊ", card.line(ReadingStyle.ZHUYIN))
                assertEquals(listOf("银" to "钅", "行" to "行"), card.rows.map { it.text to it.radical })
            }
        },
        "行业 háng yè, 行走 xíng zǒu: the word says which" to {
            with(replay()) {
                type("hangye")
                assertEquals("háng yè", word("行业").line(ReadingStyle.PINYIN))
            }
            with(replay()) {
                type("xingzou")
                val card = word("行走")
                assertEquals("xíng zǒu", card.line(ReadingStyle.PINYIN))
                // 行's card, from the word, xíng first
                val hang = engines.charCardOf("行", card.rows[0].reading)!!
                assertEquals("xíng", hang.readings.first().pinyin)
                assertTrue(hang.readings.first().inWord)
            }
        },
        "角色 typed jiaose: jué sè, as the bar marks it" to {
            with(replay()) {
                type("jiaose")
                assertEquals("jué sè", word("角色").line(ReadingStyle.PINYIN))
                assertEquals(readingOf("角色"), word("角色").line(ReadingStyle.PINYIN))
            }
        },
        "䏝 under chun: toneless chun, no zhuyin, its card chun first" to {
            with(replay()) {
                type("chun")
                val card = cardOf("䏝") as CandidateCard.Char
                assertEquals("chun", card.line.substringBefore(CandidateReadings.LINE_SEPARATOR))
                assertEquals("chun", card.card.readings.first().pinyin)
            }
        },
        "五笔 长大: the dictionary's reading, the code hint as it was" to {
            with(replay(im = InputMethodCatalog.WUBI)) {
                type("tadd")
                val hint = hintOf("长大")
                assertEquals("zhǎng dà", word("长大").line(ReadingStyle.PINYIN))
                assertEquals(hint, hintOf("长大"))
            }
        },
    )

    /** The card's readings are the bar's for the first candidate of [rows], in each style. */
    private fun sameAsTheBar(rows: List<Pair<String, String?>>): Pair<Int, List<String>> {
        val misses = ArrayList<String>()
        var checked = 0
        val r = replay(ReadingStyle.OFF)
        for ((keys, target) in rows) {
            // typed with 候选词注音 off: each key's page not read (the bar's reading is what candidates() reads below)
            r.engines.settings = r.engines.settings.copy(candidateReading = ReadingStyle.OFF)
            r.focus("")
            r.type(keys)
            for (style in listOf(ReadingStyle.PINYIN, ReadingStyle.ZHUYIN)) {
                // the style alone changed: the session and what was typed kept (Engines.settings), nothing made anew
                r.engines.settings = r.engines.settings.copy(candidateReading = style)
                // the first page, or for a misreading the pages its target is on; its target missing is a miss
                val page = r.engines.candidates(r.im, 0, if (target == null) PAGE else MISREAD_PAGES * PAGE)
                val at = if (target == null) 0 else page.indexOfFirst { it.text == target }
                val miss = if (at < 0) "$style $keys: $target not in the first $MISREAD_PAGES pages"
                else page.getOrNull(at)?.let { sameAsTheBar(r, style, at, it.text, it.readings, keys) } ?: continue
                misses += miss
                checked++
            }
        }
        return checked to misses.filter { it.isNotEmpty() }
    }

    // "" where the card of [text] reads as [bar], what differs where not; null where there is nothing to read
    private fun sameAsTheBar(r: EngineReplay, style: ReadingStyle, at: Int, text: String, bar: List<String>, keys: String): String? {
        val shown = when (val card = r.engines.card(r.im, at, text)) {
            is CandidateCard.Word -> if (style == ReadingStyle.ZHUYIN) card.card.zhuyin else card.card.pinyin
            is CandidateCard.Char -> listOf(card.line.substringBefore(CandidateReadings.LINE_SEPARATOR).removeSuffix(CandidateReadings.MORE.toString()))
            is CandidateCard.Line -> return if (card.line == WordCard.line(text, bar)) "" else "$style $keys $text: ${card.line} ≠ $bar"
            else -> return if (WordCard.hanCount(text) > 0) "$style $keys $text: no card" else null
        }
        val expected = if (bar.isEmpty()) List(shown.size) { "" } else bar
        return if (shown == expected) "" else "$style $keys $text: $shown ≠ $bar"
    }

    private fun data(path: String) = generateSequence(File(".").absoluteFile) { it.parentFile }
        .map { File(it, path) }.first { it.exists() }
        .readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }

    @Test
    @Uat("PY-124", "PY-127")
    fun theCardReadsAsTheBarOnTheChatWordsAndTheMisreadings() {
        val chat = data("lib/ime-eval/data/pinyin-chat.tsv").take(CHAT_ROWS).map { it[0] to null }
        val misread = data("lexicon/misreadings.tsv").map { it[2].replace("'", "") to it[0] }
        val (checked, misses) = sameAsTheBar(chat + misread)
        assertTrue("${misses.size} of ${chat.size + misread.size}: ${misses.take(SHOWN)}", misses.isEmpty())
        // each row read in both styles, not passed over
        assertEquals(2 * (chat.size + misread.size), checked)
    }

    private companion object {
        const val CHAT_ROWS = 100
        const val SHOWN = 10
        const val PAGE = 20
        const val MISREAD_PAGES = 3
    }
}
