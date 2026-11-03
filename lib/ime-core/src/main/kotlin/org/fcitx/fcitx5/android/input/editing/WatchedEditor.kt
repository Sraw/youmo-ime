/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

/**
 * [inner], each change it makes to the field's text or cursor told to [changed] first, but those
 * made [quietly] (a Backspace's own deletion, the backspace swipe's selection). Finishing or marking
 * a composition leaves both as they are, and is not told.
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

    private fun change() {
        if (quiet == 0) changed()
    }

    override fun commitText(text: String, newCursorPosition: Int) {
        change()
        inner.commitText(text, newCursorPosition)
    }

    override fun setComposingText(text: CharSequence, newCursorPosition: Int) {
        change()
        inner.setComposingText(text, newCursorPosition)
    }

    override fun setSelection(start: Int, end: Int) {
        change()
        inner.setSelection(start, end)
    }

    override fun deleteSurroundingText(before: Int, after: Int, inCodePoints: Boolean) {
        change()
        inner.deleteSurroundingText(before, after, inCodePoints)
    }
}
