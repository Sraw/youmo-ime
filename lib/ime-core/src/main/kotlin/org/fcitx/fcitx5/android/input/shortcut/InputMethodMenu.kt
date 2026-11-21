/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.shortcut

/**
 * 选输入法: the enabled input methods in the list's order, 🌐's, then 更多…, placed and picked as the edit menu
 * ([EditMenuLayout]). The current one is [disabled]; the finger starts on the one 🌐 would switch to, so a lift straight away is 🌐's tap.
 */
class InputMethodMenu private constructor(val cells: List<Cell>, val start: Int, val disabled: Set<Int>) {

    sealed interface Cell {
        data class Method(val uniqueName: String) : Cell

        /** 🌐's long press: other apps' keyboards too. */
        data object More : Cell
    }

    companion object {
        /**
         * At most [fit] cells, two at least. Too many: the current one and those 🌐 reaches after it, wrapping, so the
         * next few are a step away; the rest through 更多….
         */
        fun of(enabled: List<String>, current: String, fit: Int): InputMethodMenu {
            val room = fit.coerceAtLeast(2) - 1
            val at = enabled.indexOf(current)
            val shown = when {
                enabled.size <= room -> enabled
                at < 0 -> enabled.take(room)
                else -> List(room) { enabled[(at + it) % enabled.size] }
            }
            val next = if (at < 0 || enabled.size < 2) null else enabled[(at + 1) % enabled.size]
            val cells = shown.map(Cell::Method) + Cell.More
            val start = shown.indexOf(next).takeIf { it >= 0 } ?: cells.lastIndex
            return InputMethodMenu(cells, start, setOfNotNull(shown.indexOf(current).takeIf { it >= 0 }))
        }
    }
}
