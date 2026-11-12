/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.keys

import org.fcitx.fcitx5.android.scene.Fixtures.ok
import org.fcitx.fcitx5.android.scene.Forms
import org.fcitx.fcitx5.android.scene.TEST_DATA
import org.fcitx.fcitx5.android.scene.engine.MarkContent
import org.fcitx.fcitx5.android.scene.json.EnvJson
import org.fcitx.fcitx5.android.scene.readText
import org.fcitx.fcitx5.android.scene.recording
import org.fcitx.fcitx5.android.scene.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Each board's default marks as the theme maker gets them, one file a board (data/default-marks), the same on JVM and JS. */
class DefaultMarksTest {
    private val boards = listOf("qwerty", "nine", "k14", "k18", "zhuyin", "zhuyin-nine", "number", "handwriting")

    private fun marks(board: String) = DefaultMarks.shortcuts(Forms.layout(board), Forms.fragments)

    // contract: data/default-marks/*.json, which the theme maker reads for its preview and export check
    @Test
    fun everyBoardsMarksAreAsRecorded() {
        val failed = boards.mapNotNull { board ->
            val text = DefaultMarks.json(marks(board))
            val path = "$TEST_DATA/default-marks/$board.json"
            val committed = readText(path)
            when {
                recording() -> null.also { writeText(path, text) }
                committed == null -> "no $path: run the JVM tests with -Psnapshots.record=true"
                committed != text -> "$path differs: review, then record anew"
                else -> null
            }
        }
        assertTrue(failed.isEmpty(), failed.joinToString("\n"))
    }

    @Test
    fun theMarksAreAsTheAppDrewThem() {
        val qwerty = marks("qwerty")
        assertEquals(MarkContent.Symbol("1"), qwerty["full/q:up"])
        assertEquals(MarkContent.Icon("content-copy"), qwerty["full/c:down"])
        assertEquals(MarkContent.Empty, qwerty["full/q:down"])
        assertEquals(MarkContent.Icon("cursor-move"), qwerty["fn/space:up"])
        assertEquals(MarkContent.Icon("delete-sweep"), qwerty["fn/backspace:up"])
        val nine = marks("nine")
        assertEquals(MarkContent.Symbol("2"), nine["t9/2:up"])
        assertEquals(MarkContent.Symbol("1"), nine["t9/1:up"])
        assertEquals(MarkContent.Icon("content-copy"), nine["t9/2:down"])
        val k14 = marks("k14")
        assertEquals(MarkContent.Symbol("6"), k14["g14/as:up"])
        assertEquals(MarkContent.Symbol("~"), k14["g14/zx:up"])
    }

    @Test
    fun aMarkTypedAsAKeyIsShownAsThePunctuationMapsIt() {
        val mapped = DefaultMarks.shortcuts(Forms.layout("qwerty"), Forms.fragments, punctuation = mapOf("@" to "＠"))
        assertEquals(MarkContent.Symbol("＠"), mapped["full/a:up"])
        // a keypad's digit is no punctuation's
        val nine = DefaultMarks.shortcuts(Forms.layout("nine"), Forms.fragments, punctuation = mapOf("2" to "x"))
        assertEquals(MarkContent.Symbol("2"), nine["t9/2:up"])
    }

    @Test
    fun theJsonIsWhatEnvJsonReads() {
        val marks = marks("k18")
        val env = EnvJson.read("""{"width": 1080, "height": 700, "density": 2.625, "marks": ${DefaultMarks.json(marks)}}""").ok()
        assertEquals(marks, env.marks.shortcuts)
    }

    @Test
    fun aFunctionKeysBareNameIsReadAsItsOneName() {
        val env = EnvJson.read("""{"width": 1080, "height": 700, "density": 2.625, "marks": {"shortcuts": {"space:up": {"icon": "x"}, "full/q:down": {}}}}""").ok()
        assertEquals(mapOf("fn/space:up" to MarkContent.Icon("x"), "full/q:down" to MarkContent.Empty), env.marks.shortcuts)
    }
}
