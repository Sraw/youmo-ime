/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.data.Misreadings
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinSegmenter
import org.fcitx.fcitx5.android.engine.session.Action.Key
import org.fcitx.fcitx5.android.engine.session.Action.Select
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class PinyinSessionMisreadingTest {

    private val data = data(hints = true)

    private fun data(hints: Boolean) = PinyinData.load(
        ByteBuffer.wrap(
            PinyinDataBuilder()
                .unigram("<unk>", -7f, 0f)
                .unigram("般若", -4f, 0f)
                .unigram("般", -4f, 0f)
                .entry("般若", syl("bo", "re"))
                .entry("般若", syl("ban", "ruo"), Misreadings.WEIGHT)
                .entry("般", syl("ban"))
                .unigram("莘莘学子", -4f, 0f)
                .entry("莘莘学子", syl("shen", "shen", "xue", "zi"))
                .entry("莘莘学子", syl("xin", "xin", "xue", "zi"), Misreadings.WEIGHT)
                .unigram("说服", -4f, 0f)
                .entry("说服", syl("shuo", "fu"))
                .entry("说服", syl("shui", "fu"), Misreadings.WEIGHT)
                .build(
                    if (!hints) emptyMap() else mapOf(
                        Misreadings.META_PREFIX + "般若" to Misreadings.meta(Misreadings("般若", "bō rě", listOf("ban'ruo"))),
                        Misreadings.META_PREFIX + "莘莘学子" to
                            Misreadings.meta(Misreadings("莘莘学子", "shēn shēn xué zǐ", listOf("xin'xin'xue'zi"))),
                        Misreadings.META_PREFIX + "说服" to Misreadings.meta(Misreadings("说服", "shuō fú", listOf("shui'fu"))),
                    ),
                )
                .toByteArray(),
        ),
    )

    private fun Session.type(keys: String): Snapshot = keys.map { apply(Key(it)) }.last()

    @Test
    fun typedByItsMisreadingAWordShowsItsReading() {
        val session = PinyinSession(data, PinyinSegmenter(), prediction = false)
        val s = session.type("banruo")
        assertEquals("般若", s.candidates[0])
        assertEquals("bō rě", s.hints[0])
        assertEquals("bō rě", session.candidates(0, 1).single().hint)
        session.apply(Action.Reset)
        // by its reading, separated or not: nothing to show
        assertEquals(emptyList<String>(), session.type("bore").hints)
        session.apply(Action.Reset)
        session.type("bo're")
        assertEquals(Choice("般若"), session.candidates(0, 1).single())
        // a start the reading shares (br is b'r of bo're too), or letters fitting neither: no misreading
        for (keys in listOf("br", "bor", "bx")) {
            session.apply(Action.Reset)
            session.type(keys)
            session.candidates(0, 50).firstOrNull { it.text == "般若" }?.let { assertEquals(keys, Choice("般若"), it) }
        }
    }

    @Test
    fun eachSyllableMayBeTypedByItsStart() {
        val session = PinyinSession(data, PinyinSegmenter(), prediction = false)
        // the whole misreading, a syllable half typed, 简拼 and mixed, the last syllables untyped
        // offered, with its hint; xinxinxue (its last syllable untyped) is not offered by the fixture,
        // and the hint never lifts a word into the candidates: there neither word nor hint
        val rows = listOf("xinxinxuezi" to true, "xinxinxuez" to true, "xxxuez" to true, "xxxz" to true, "xinxinxue" to false, "banr" to true)
        assertRows(rows, { it.first }) { (keys, offered) ->
            session.apply(Action.Reset)
            session.type(keys)
            val word = if (keys.startsWith("b")) "般若" else "莘莘学子"
            val hint = if (word == "般若") "bō rě" else "shēn shēn xué zǐ"
            val shown = session.candidates(0, 50)
            if (offered) {
                assertEquals(Choice(word, hint = hint), shown.first { it.text == word })
            } else {
                assertEquals(null, shown.firstOrNull { it.text == word })
                assertEquals(emptyList<Choice>(), shown.filter { it.hint.isNotEmpty() })
            }
        }
    }

    @Test
    fun theHintRanksNothing() {
        val with = PinyinSession(data, PinyinSegmenter(), prediction = false)
        val without = PinyinSession(data(hints = false), PinyinSegmenter(), prediction = false)
        for (keys in listOf("xxxz", "xxxuez", "xinxinxuez")) {
            with.apply(Action.Reset)
            without.apply(Action.Reset)
            assertEquals(keys, without.type(keys).candidates, with.type(keys).candidates)
            assertEquals(keys, without.candidates(0, 50).map { it.text }, with.candidates(0, 50).map { it.text })
        }
    }

    @Test
    fun aStartBothReadingsShareClaimsNoMisreading() {
        val session = PinyinSession(data, PinyinSegmenter(), prediction = false)
        assertEquals(Choice("说服", hint = "shuō fú"), session.type("shuifu").let { session.candidates(0, 5).first { it.text == "说服" } })
        for (keys in listOf("shu", "sf", "shf", "shuof")) {
            session.apply(Action.Reset)
            session.type(keys)
            assertEquals(keys, null, session.candidates(0, 5).firstOrNull { it.text == "说服" }?.hint?.ifEmpty { null })
        }
    }

    @Test
    fun afterAPieceTheRestIsWhatIsCompared() {
        val session = PinyinSession(data, PinyinSegmenter(), prediction = false)
        val s = session.type("banbanruo")
        val piece = session.apply(Select(s.candidates.indexOf("般")))
        assertEquals("般若", piece.candidates[0])
        assertEquals("bō rě", piece.hints[0])
    }

    @Test
    fun shuangpinKeysAreNoReadingToCompare() {
        val session = PinyinSession(data, ShuangpinSegmenter(ShuangpinScheme.ZIRANMA), spell = true, prediction = false)
        assertEquals(emptyList<String>(), session.type("bjro").hints)
    }
}
