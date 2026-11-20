/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

/** Tells [changed] before each text or cursor change, but those made [quietly] (a Backspace's own, the backspace swipe's selection, a copy); finishing a composition is not told. */
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

    /** A change made past it (a menu action, a key event). */
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
