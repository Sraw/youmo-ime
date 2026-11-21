/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.shortcut

import org.fcitx.fcitx5.android.input.shortcut.InputMethodMenu.Cell.Method
import org.fcitx.fcitx5.android.input.shortcut.InputMethodMenu.Cell.More
import org.junit.Assert.assertEquals
import org.junit.Test

class InputMethodMenuTest {

    private val four = listOf("pinyin", "shuangpin", "wubi", "english")

    private fun names(menu: InputMethodMenu) = menu.cells.map { if (it is Method) it.uniqueName else "more" }

    // the smallest move is 🌐's tap: the finger starts on the next one, the current one greyed
    @Test
    fun allFitInTheGlobesOrderTheNextOneUnderTheFinger() {
        val menu = InputMethodMenu.of(four, "shuangpin", fit = 8)
        assertEquals(four + "more", names(menu))
        assertEquals(2, menu.start)
        assertEquals(setOf(1), menu.disabled)
    }

    @Test
    fun theLastOnesNextIsTheFirst() {
        val menu = InputMethodMenu.of(four, "english", fit = 8)
        assertEquals(0, menu.start)
        assertEquals(setOf(3), menu.disabled)
    }

    // the current one and those after it as 🌐 goes, round to the start; the rest from 更多…
    @Test
    fun tooManyShowTheCurrentAndThoseAfterIt() {
        val menu = InputMethodMenu.of(four, "wubi", fit = 4)
        assertEquals(listOf("wubi", "english", "pinyin", "more"), names(menu))
        assertEquals(1, menu.start)
        assertEquals(setOf(0), menu.disabled)
    }

    @Test
    fun exactlyFittingShowsThemAll() {
        assertEquals(four + "more", names(InputMethodMenu.of(four, "pinyin", fit = 5)))
    }

    @Test
    fun oneInputMethodIsItGreyedAndMoreUnderTheFinger() {
        val menu = InputMethodMenu.of(listOf("pinyin"), "pinyin", fit = 4)
        assertEquals(listOf(Method("pinyin"), More), menu.cells)
        assertEquals(1, menu.start)
        assertEquals(setOf(0), menu.disabled)
    }

    // the host not up yet: nothing greyed, the finger on 更多…
    @Test
    fun aCurrentNotInTheListGreysNothing() {
        val menu = InputMethodMenu.of(four, "", fit = 3)
        assertEquals(listOf("pinyin", "shuangpin", "more"), names(menu))
        assertEquals(2, menu.start)
        assertEquals(emptySet<Int>(), menu.disabled)
        assertEquals(listOf(More), InputMethodMenu.of(emptyList(), "", fit = 4).cells)
    }

    @Test
    fun aRowNarrowerThanTwoCellsStillHasTwo() {
        assertEquals(listOf("shuangpin", "more"), names(InputMethodMenu.of(four, "shuangpin", fit = 0)))
    }
}
