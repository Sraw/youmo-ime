/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.data.syl
import org.fcitx.fcitx5.android.engine.handwriting.InkReading
import org.fcitx.fcitx5.android.engine.session.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/** Handwriting and the tables share pinyin's coarse words, made again when an added word drops the sessions. */
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
        val engines = Engines({ if (it == InputMethodCatalog.PINYIN_DATA) ByteBuffer.wrap(coarsePinyin) else load(it) }, null, warmUps = inPlace)
        engines.settings = EngineSettings(modelLevel = ModelLevel.LOW)
        assertEquals(listOf("小学"), engines.handwritten("好"))
        assertTrue(engines.addWord("小穴", "xiao xue"))
        assertEquals(listOf("小穴", "小学"), engines.handwritten("好"))
        engines.type(InputMethodCatalog.WUBI, "vbg")
        assertEquals(listOf("小穴", "小学"), engines.onEvent(InputMethodCatalog.WUBI, EngineEvent.PICK, 0).candidates)
    }

    // the pad numbers its pieces on through the process, past a session dropped as a word is added
    private var piece = 0

    private fun Engines.handwritten(text: String): List<String> {
        onEvent(InputMethodCatalog.PINYIN, EngineEvent.CHAR, Keyboard.INK, payload = Action.Ink(piece++, InkReading(listOf(text), doubleArrayOf(0.0))))
        return onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, 0).candidates
    }
}
