/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

/**
 * Where the undo chip's top goes, in the window's pixels (user decision 2026-10-22, PY-80: over
 * the bar it hid the second candidate). With no candidates shown it sits over the bar, near its
 * top ([barTop] + [gap]). With candidates it goes above the keyboard ([aboveTop] - [gap] - [chipHeight]):
 * over the app, under no key and no candidate the user is about to tap. Not the toolbar row: while
 * composing that row is the candidates. With no room above (a window at the screen's top), below
 * [belowBottom] when there is somewhere below and the whole chip fits there above [bottomLimit] (the lowest window y it may reach, the navigation bar left out)
 * (a floating keyboard as tall as a landscape screen has neither), else over the bar after all.
 */
object UndoChipPlacement {
    fun top(
        candidates: Boolean,
        barTop: Int,
        aboveTop: Int,
        chipHeight: Int,
        gap: Int,
        belowBottom: Int? = null,
        bottomLimit: Int = Int.MAX_VALUE,
    ): Int {
        if (!candidates) return barTop + gap
        val above = aboveTop - gap - chipHeight
        return when {
            above >= 0 -> above
            belowBottom != null && belowBottom + gap + chipHeight <= bottomLimit -> belowBottom + gap
            else -> barTop + gap
        }
    }
}
