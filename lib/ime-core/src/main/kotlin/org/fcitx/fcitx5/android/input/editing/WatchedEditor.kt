/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

/**
 * [inner], each change it makes to the field's text or cursor told to [changed] first, but those
 * made [quietly] (a Backspace's own deletion, the backspace swipe's selection, a copy's). Finishing or
 * marking a composition leaves both as they are, and is not told. A change made past it (a menu
 * action, a key event) is told by [changing].
 */
class WatchedEditor(private val inner: InputEditor, private val changed: () -> Unit) : InputEditor by inner {

    private var quiet = 0

    fun <T> quietly(block: () -> T): T {
        quiet++
        try {
            return block()
        } finally {
            quiet--
        }
    }

    /** A change about to be made to the field some other way: told unless made [quietly]. */
    fun changing() {
        if (quiet == 0) changed()
    }

    override fun commitText(text: String, newCursorPosition: Int): Boolean {
        changing()
        return inner.commitText(text, newCursorPosition)
    }

    override fun setComposingText(text: CharSequence, newCursorPosition: Int) {
        changing()
        inner.setComposingText(text, newCursorPosition)
    }

    override fun setSelection(start: Int, end: Int) {
        changing()
        inner.setSelection(start, end)
    }

    override fun deleteSurroundingText(before: Int, after: Int, inCodePoints: Boolean) {
        changing()
        inner.deleteSurroundingText(before, after, inCodePoints)
    }
}
