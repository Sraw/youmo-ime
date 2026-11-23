/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayouts
import org.fcitx.fcitx5.android.input.theme.KeyName
import org.fcitx.fcitx5.android.input.theme.KeyNames
import org.fcitx.fcitx5.android.scene.layout.Include
import org.fcitx.fcitx5.android.scene.layout.Key
import org.fcitx.fcitx5.android.scene.layout.Row
import org.fcitx.fcitx5.android.scene.layout.Slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * contract: the 3.0 theme format's key names, shared with the web page; a rename breaks theme files.
 * A key named here but not there (or twice on a board) is a key a theme cannot reach.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class ThemeKeyNamesTest {

    private val context get() = RuntimeEnvironment.getApplication()

    private val boards = mapOf(
        "qwerty" to listOf("qwerty"),
        "nine" to listOf("nine"),
        "k14" to listOf("k14"),
        "k18" to listOf("k18"),
        "number" to listOf("number"),
        "zhuyin-nine" to listOf("zhuyin-nine"),
        "zhuyin" to listOf("zhuyin"),
        "symbols" to listOf("symbols"),
        "emoji" to listOf("emoji", "emoticon"),
    )

    // with [slots] its slots' too (nine's side column, a place of the format)
    private fun ids(id: String, slots: Boolean = false): List<String> {
        val layout = SceneLayouts.of(context, id) ?: error("no layout $id")
        val rows = layout.layout.parts.flatMap { p ->
            when (p) {
                is Row -> listOf(p)
                is Include -> layout.fragments.getValue(p.src).rows
            }
        }
        return rows.flatMap { r -> r.cells.mapNotNull { (it as? Key)?.id ?: (it as? Slot)?.id?.takeIf { slots } } }
    }

    @Test
    fun everyKeyHasItsBoardsName() {
        for ((board, layouts) in boards) for (id in layouts) {
            // a theme's keys (k14/r1c1) are picked by the layout's id, not its file's name
            assertEquals(id, board, SceneLayouts.of(context, id)!!.layout.id)
            for (key in ids(id)) assertTrue("$board/$key is not a name of the format", KeyNames.isKnown(KeyName(board, key)))
        }
    }

    // a key's images are looked up by its id: two keys of one id would draw each other's; only keys
    // never shown together, each under a condition (number's pads), may share one
    @Test
    fun noTwoKeysOfALayoutShareAnId() {
        for (id in boards.values.flatten()) {
            val layout = SceneLayouts.of(context, id) ?: error("no layout $id")
            val keys = layout.layout.parts.flatMap { p ->
                when (p) {
                    is Row -> p.cells.map { it to (it.condition ?: p.condition) }
                    is Include -> layout.fragments.getValue(p.src).rows.flatMap { r -> r.cells.map { it to (it.condition ?: r.condition ?: p.condition) } }
                }
            }.filter { it.first is Key }
            for ((key, n) in keys.groupingBy { it.first.id }.eachCount()) if (n > 1) {
                assertTrue("$id: $key twice, not each under a condition", keys.filter { it.first.id == key }.all { it.second != null })
            }
        }
    }

    @Test
    fun everyPlaceOfABoardIsAKey() {
        for ((board, layouts) in boards) {
            val names = layouts.flatMap { ids(it, slots = true) }.toSet()
            val places = KeyNames.POSITIONS.getValue(board).toSet()
            assertEquals(board, places, places intersect names)
        }
    }

    @Test
    fun aBoardsKeysCarryTheirNames() {
        val keyboard = NumberKeyboard(context, TestThemes.PixelDark).laidOut()
        assertEquals(KeyNames.NUMBER, keyboard.themeBoard)
        val names = keyboard.shownKeys().mapNotNull { it.id }
        assertTrue(names.toString(), "backspace" in names && "1" in names)
    }
}
