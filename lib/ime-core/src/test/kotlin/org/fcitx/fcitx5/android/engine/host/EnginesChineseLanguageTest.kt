/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.handwriting.InkReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * What handwriting and a table read by as pinyin does, the coarse words held back one list for them
 * all: made again once an added word drops the sessions, so a coarse word the user adds is theirs in each.
 */
class EnginesChineseLanguageTest : EnginesFixture() {

    private val coarsePinyin = PinyinDataBuilder()
        .unigram("<unk>", -7f, 0f)
        .unigram("好", -2f, 0f)
        .unigram("小穴", -3f, 0f)
        .unigram("小学", -3.5f, 0f)
        .bigram("好", "小穴", -0.5f, 0f)
        .bigram("好", "小学", -1f, 0f)
        .entry("好", syl("hao"))
        .entry("小穴", syl("xiao", "xue"))
        .entry("小学", syl("xiao", "xue"))
        .build()
        .toByteArray()

    @Test
    fun aCoarseWordTheUserAddsIsPredictedInHandwritingAndATableMadeAfter() {
        val engines = Engines({ if (it == Engines.PINYIN_DATA) ByteBuffer.wrap(coarsePinyin) else load(it) }, null, warmUps = inPlace)
        // the decoder's reading alone
        engines.settings = EngineSettings(modelLevel = ModelLevel.LOW)
        // 小穴 never offered unasked
        assertEquals(listOf("小学"), engines.handwritten("好"))
        assertTrue(engines.addWord("小穴", "xiao xue"))
        assertEquals(listOf("小穴", "小学"), engines.handwritten("好"))
        // a table's session, first made now, reads the same list
        engines.type(Engines.WUBI, "vbg")
        assertEquals(listOf("小穴", "小学"), engines.onEvent(Engines.WUBI, EngineEvent.PICK, 0).candidates)
    }

    // the pad numbers its pieces on through the process, past a session dropped as a word is added
    private var piece = 0

    private fun Engines.handwritten(text: String): List<String> {
        offerInk(InkReading(listOf(text), doubleArrayOf(0.0)), piece++)
        onEvent(Engines.HANDWRITING, EngineEvent.CHAR, Keyboard.INK)
        return onEvent(Engines.HANDWRITING, EngineEvent.PICK, 0).candidates
    }
}
