/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.pinyin.Near
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.Touch
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Whether a word's reading goes over an edge read otherwise than typed ([Reading.otherwise]): HabitOrder walks the
 * code read whole again only for those, so one read over no 简拼 nor slip must say so, and one over either too.
 */
class ReadingOtherwiseTest {

    private val data = pinyinDataOf(Triple("下面", "xia mian", -3f), Triple("他", "ta", -2f), Triple("呀", "ya", -2f))

    private val ziranma = ShuangpinSegmenter(ShuangpinScheme.ZIRANMA, neighbours = true)

    private fun otherwise(keys: String, word: String, touches: Array<Touch?>? = null): Boolean? {
        val graph = ziranma.segment(keys, null, touches)
        val id = (0 until data.vocabulary.size).first { data.vocabulary.word(it) == word }
        return reading(graph, graph.start, graph.end, id, data.dictionary, null) { _, _ -> "" }?.otherwise
    }

    @Test
    fun aWordReadByItsWholeSyllablesIsReadAsTyped() {
        // 自然码 xw xia, mm mian
        assertEquals(false, otherwise("xwmm", "下面"))
    }

    @Test
    fun aWordReadByItsInitialsAloneIsReadOtherwise() {
        assertEquals(true, otherwise("xm", "下面"))
    }

    @Test
    fun aWordReadByAKeyTappedTowardsItsNeighbourIsReadOtherwise() {
        // 他 typed ta, and ya with y tapped at its left edge, towards t
        val t = Touch(-0.45f, 0f, 40f, 56f, listOf(Near('r', -1f, 0f), Near('y', 1f, 0f)))
        val y = Touch(-0.45f, 0f, 40f, 56f, listOf(Near('t', -1f, 0f), Near('u', 1f, 0f)))
        assertEquals(false, otherwise("ta", "他", arrayOf(t, null)))
        assertEquals(true, otherwise("ya", "他", arrayOf(y, null)))
    }
}
