/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.fcitx.fcitx5.android.engine.assertRows
import org.junit.Assert.assertEquals
import org.junit.Test

/** uat/05-toolbar.md's TB-44: the model's words when they come within a second, else the characters stay. */
class ClipboardChunksWindowTest {

    private val words = mapOf("北京大学" to intArrayOf(0, 2, 4))

    // the virtual clock of runTest: no real wait. A split slower than a second gives no model's pieces (the
    // first split, kept shown, is ClipboardChunksTest's); a text with no Chinese never asks the model
    @Test
    fun theModelsWordsOnlyWithinASecondAndForChinese() {
        class Row(val why: String, val text: String, val asks: Boolean, val split: suspend () -> Map<String, IntArray>, val pieces: List<String>?)
        assertRows(
            listOf(
                Row("within a second", "去北京大学", true, { delay(900); mapOf("去北京大学" to intArrayOf(0, 1, 3, 5)) }, listOf("去", "北京", "大学")),
                Row("slower than a second", "北京大学", true, { delay(1500); words }, null),
                Row("no Chinese", "li@x.com", false, { error("not asked") }, null),
            ),
            { it.why },
        ) { row ->
            runTest {
                var asked = false
                val pieces = ClipboardChunksWindow.modelChunks(row.text) { asked = true; row.split() }
                assertEquals(row.pieces, pieces?.map { it.text })
                assertEquals(row.asks, asked)
            }
        }
    }

    @Test
    fun aPickIsThePieceTheModelsSplitPutsItIn() {
        val text = "去北京大学"
        val first = ClipboardChunks.chunks(text)
        val model = ClipboardChunks.chunks(text, mapOf(text to intArrayOf(0, 1, 3, 5)))
        fun of(list: List<ClipboardChunks.Chunk>, vararg t: String) = t.map { w -> list.first { it.text == w } }
        // 北 → 北京, the order of the picks kept; 京 too gives 北京 once
        assertEquals(of(model, "大学", "北京"), ClipboardChunksWindow.remap(of(first, "大", "北"), model))
        assertEquals(of(model, "北京"), ClipboardChunksWindow.remap(of(first, "北", "京"), model))
        assertEquals(of(model, "去"), ClipboardChunksWindow.remap(of(first, "去"), model))
    }
}
