/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

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

    private val data = PinyinData.load(
        ByteBuffer.wrap(
            PinyinDataBuilder()
                .unigram("<unk>", -7f, 0f)
                .unigram("般若", -4f, 0f)
                .unigram("般", -4f, 0f)
                .entry("般若", syl("bo", "re"))
                .entry("般若", syl("ban", "ruo"), Misreadings.WEIGHT)
                .entry("般", syl("ban"))
                .build(mapOf(Misreadings.META_PREFIX + "般若" to Misreadings.meta(Misreadings("般若", "bō rě", listOf("ban'ruo")))))
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
        assertEquals("", session.type("bo're").let { session.candidates(0, 1).single().hint })
        // 简拼, or a syllable half typed: no misreading
        for (keys in listOf("br", "banr", "bor")) {
            session.apply(Action.Reset)
            session.type(keys)
            assertEquals(keys, "", session.candidates(0, 5).firstOrNull { it.text == "般若" }?.hint.orEmpty())
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
