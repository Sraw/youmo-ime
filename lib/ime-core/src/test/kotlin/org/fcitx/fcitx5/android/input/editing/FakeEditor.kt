/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

/**
 * A text buffer that behaves like an editor, for testing [EditingSession].
 *
 * Deliberately simple: a [StringBuilder] plus a selection. It implements the parts of
 * `InputConnection` semantics that the editing logic depends on, and records the calls so a
 * test can assert on *how* the editor was driven, not only on the resulting text.
 */
class FakeEditor(initial: String = "", cursor: Int = initial.length) : InputEditor {

    /** Set to false to model the gap between input sessions, when there is no connection. */
    override var isAvailable: Boolean = true

    private val buffer = StringBuilder(initial)

    var selectionStart = cursor
        private set
    var selectionEnd = cursor
        private set

    /** Composing region, or `-1 to -1` when there is none. */
    var composingStart = -1
        private set
    var composingEnd = -1
        private set

    val text: String get() = buffer.toString()

    /** Every call made on this editor, in order, for asserting on the interaction. */
    val calls = mutableListOf<String>()

    var batchDepth = 0
        private set

    /** The deepest nesting reached, so a test can prove a change was batched. */
    var maxBatchDepth = 0
        private set

    override fun commitText(text: String, newCursorPosition: Int) {
        calls += "commitText($text, $newCursorPosition)"
        // Matches InputConnection: a commit replaces the composing region when there is one,
        // and the selection otherwise.
        val (from, to) = if (composingStart >= 0) {
            composingStart to composingEnd
        } else {
            selectionStart to selectionEnd
        }
        buffer.replace(from, to, text)
        val cursor = from + text.length
        selectionStart = cursor
        selectionEnd = cursor
        composingStart = -1
        composingEnd = -1
    }

    override fun setSelection(start: Int, end: Int) {
        calls += "setSelection($start, $end)"
        selectionStart = start
        selectionEnd = end
    }

    override fun finishComposingText() {
        calls += "finishComposingText()"
        composingStart = -1
        composingEnd = -1
    }

    override fun setComposingRegion(start: Int, end: Int) {
        calls += "setComposingRegion($start, $end)"
        composingStart = start
        composingEnd = end
    }

    override fun deleteSurroundingText(before: Int, after: Int, inCodePoints: Boolean) {
        calls += "deleteSurroundingText($before, $after, inCodePoints=$inCodePoints)"
        val start = if (inCodePoints) {
            buffer.offsetByCodePoints(selectionStart, -before.coerceAtMost(
                buffer.codePointCount(0, selectionStart)
            ))
        } else {
            (selectionStart - before).coerceAtLeast(0)
        }
        val end = if (inCodePoints) {
            buffer.offsetByCodePoints(selectionEnd, after.coerceAtMost(
                buffer.codePointCount(selectionEnd, buffer.length)
            ))
        } else {
            (selectionEnd + after).coerceAtMost(buffer.length)
        }
        buffer.delete(selectionEnd, end)
        buffer.delete(start, selectionStart)
        selectionStart = start
        selectionEnd = start
    }

    override fun batchEdit(block: () -> Unit) {
        batchDepth++
        maxBatchDepth = maxOf(maxBatchDepth, batchDepth)
        calls += "beginBatchEdit()"
        try {
            block()
        } finally {
            calls += "endBatchEdit()"
            batchDepth--
        }
    }

    /**
     * Models `InputConnection.setComposingText`, which the service calls outside the logic
     * under test: the text is placed in the buffer and marked as the composing region.
     */
    fun showComposingText(text: String, start: Int) {
        buffer.replace(start, maxOf(start, composingEnd.coerceAtLeast(start)), text)
        composingStart = start
        composingEnd = start + text.length
        selectionStart = composingEnd
        selectionEnd = composingEnd
    }

    /** Simulates the editor reporting its own selection back to the IME. */
    fun reportSelection(start: Int, end: Int = start) {
        selectionStart = start
        selectionEnd = end
    }
}
