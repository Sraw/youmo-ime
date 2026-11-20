/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

import org.fcitx.fcitx5.android.input.cursor.CursorRange

/** An editor for testing [EditingSession]: AOSP's `BaseInputConnection` over an `Editable`, recording its calls; `InputEditorContractTest` in `:app` holds it to the real one. */
class FakeEditor(
    initial: String = "",
    cursor: Int = initial.length,
    /** `commitText`/`setComposingText` clamp the cursor into the old text and carry it; API 35 (not 29) then puts it back before the new text for `newCursorPosition == 0` at a collapsed cursor. */
    private val reselectsZeroAfterInsert: Boolean = true,
) : InputEditor {

    /** False models no connection: every call dropped, a read null, still recorded in [calls]. */
    override var isAvailable: Boolean = true

    /** Reads answer, writes are dropped and still return true: a WebView that lost focus while the keyboard stayed up. */
    var dropsWrites: Boolean = false

    /** How much text before the cursor this editor reveals; null reveals none. */
    var revealLimit: Int? = Int.MAX_VALUE

    private val buffer = StringBuilder(initial)

    // who put each character of [buffer] there: [APP] or [KEYBOARD]
    private val owner = StringBuilder(APP.toString().repeat(initial.length))

    /** Who put each character there, [APP] or [KEYBOARD], so a test can assert the keyboard deleted none of the app's. */
    val owners: String get() = owner.toString()

    /** Selection anchor and focus, as set; `start > end` is possible, as in `Selection`. */
    var selectionStart = cursor
        private set
    var selectionEnd = cursor
        private set

    /** Composing region, or `-1 to -1` when there is none. Always `start <= end`. */
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

    private val hasComposing get() = composingStart >= 0 && composingEnd >= 0

    private fun clearComposing() {
        composingStart = -1
        composingEnd = -1
    }

    /** The app sets the whole text (as `TextView.setText`), cursor at its end, no composing span; not recorded. */
    fun appSetsText(text: String) {
        buffer.setLength(0)
        buffer.append(text)
        owner.setLength(0)
        owner.append(APP.toString().repeat(text.length))
        selectionStart = text.length
        selectionEnd = text.length
        clearComposing()
    }

    /** The app's own edit of [from, to), moving spans as an `Editable`'s `replace` does (InputEditorContractTest.appEdits); not recorded. */
    fun appReplace(from: Int, to: Int, text: String) {
        val length = text.length
        buffer.replace(from, to, text)
        owner.replace(from, to, APP.toString().repeat(length))
        fun moved(p: Int) = if (p in from + 1..<to) from + (p - from) * length / (to - from) else shift(p, from, to, length)
        selectionStart = moved(selectionStart)
        selectionEnd = moved(selectionEnd)
        if (!hasComposing) return
        val a = when {
            composingStart < from -> composingStart
            composingStart <= to -> from + length
            else -> composingStart - (to - from) + length
        }
        val b = when {
            composingEnd <= from -> composingEnd
            composingEnd < to -> from
            else -> composingEnd - (to - from) + length
        }
        if (a < b) {
            composingStart = a
            composingEnd = b
        } else {
            clearComposing()
        }
    }

    /** The app's: the composing span dropped, the text kept (`finishComposingText` of its own, an `InputFilter` that drops spans). */
    fun appDropSpan() = clearComposing()

    /** The app's: [start]..[end] selected (a double tap, a drag, a select-all), as [reportSelection]. */
    fun appSelect(start: Int, end: Int = start) = reportSelection(start, end)

    /** The app's rewrite (an `InputFilter`) after each [commitText] and [setComposingText]; spans kept, cut to the new length. */
    var rewriter: ((String) -> String)? = null

    /** Whether the editor gives its composing region at once (Android 13's `takeSnapshot`). */
    var snapshots = false

    override fun composingRegion(): CursorRange? {
        if (!snapshots) return null
        calls += "composingRegion()"
        return CursorRange(composingStart, composingEnd)
    }

    /** Owners are kept by position, so this fits a rewriter that only changes the tail. */
    private fun rewrite() {
        val r = rewriter ?: return
        val new = r(buffer.toString())
        buffer.setLength(0)
        buffer.append(new)
        owner.setLength(minOf(owner.length, new.length))
        while (owner.length < new.length) owner.append(APP)
        selectionStart = selectionStart.coerceAtMost(new.length)
        selectionEnd = selectionEnd.coerceAtMost(new.length)
        if (hasComposing) {
            composingEnd = composingEnd.coerceAtMost(new.length)
            if (composingStart >= composingEnd) clearComposing()
        }
    }

    /** Where a cursor at [p] lands after [from, to) is replaced by [length] chars, as `Editable` spans move. */
    private fun shift(p: Int, from: Int, to: Int, length: Int): Int = when {
        p < from -> p
        p > to -> p - (to - from) + length
        p == from && from != to -> p // a point at the very start of a replaced range stays put
        else -> from + length
    }

    /** `BaseInputConnection.replaceText`, shared by commitText and setComposingText. */
    private fun replaceText(text: String, newCursorPosition: Int, composing: Boolean) {
        var a: Int
        var b: Int
        if (hasComposing) {
            a = composingStart
            b = composingEnd
            clearComposing()
        } else {
            a = selectionStart.coerceAtLeast(0)
            b = selectionEnd.coerceAtLeast(0)
            if (b < a) a = b.also { b = a }
        }
        val c = (if (newCursorPosition > 0) newCursorPosition + b - 1 else newCursorPosition + a)
            .coerceIn(0, buffer.length)
        var cursor = shift(c, a, b, text.length)
        buffer.replace(a, b, text)
        owner.replace(a, b, KEYBOARD.toString().repeat(text.length))
        if (reselectsZeroAfterInsert && newCursorPosition == 0 && a == b) cursor = c
        selectionStart = cursor
        selectionEnd = cursor
        if (composing && text.isNotEmpty()) {
            composingStart = a
            composingEnd = a + text.length
        }
    }

    /** Whether a [commitText] of this text fails, as a binder call too big for the editor's buffer; asked once a call. */
    var commitFails: (String) -> Boolean = { false }

    override fun commitText(text: String, newCursorPosition: Int): Boolean {
        calls += "commitText($text, $newCursorPosition)"
        if (!isAvailable || commitFails(text)) return false
        if (dropsWrites) return true
        replaceText(text, newCursorPosition, composing = false)
        rewrite()
        return true
    }

    /** Styling spans are not modelled: only the characters of [text] land in the buffer. */
    override fun setComposingText(text: CharSequence, newCursorPosition: Int) {
        calls += "setComposingText($text, $newCursorPosition)"
        if (!isAvailable || dropsWrites) return
        replaceText(text.toString(), newCursorPosition, composing = true)
        rewrite()
    }

    /** Test shorthand for the common `newCursorPosition = 1`. */
    fun setComposingText(text: String) = setComposingText(text, 1)

    /** Out-of-range positions are ignored, not clamped, as `BaseInputConnection` does. */
    override fun setSelection(start: Int, end: Int) {
        calls += "setSelection($start, $end)"
        if (!isAvailable || dropsWrites) return
        val length = buffer.length
        if (start !in 0..length || end !in 0..length) return
        selectionStart = start
        selectionEnd = end
    }

    override fun finishComposingText() {
        calls += "finishComposingText()"
        if (!isAvailable || dropsWrites) return
        clearComposing()
    }

    /** Clamped into the text and ordered; an empty region means no composing region. */
    override fun setComposingRegion(start: Int, end: Int) {
        calls += "setComposingRegion($start, $end)"
        if (!isAvailable || dropsWrites) return
        val length = buffer.length
        val a = minOf(start, end).coerceIn(0, length)
        val b = maxOf(start, end).coerceIn(0, length)
        clearComposing()
        if (a != b) {
            composingStart = a
            composingEnd = b
        }
    }

    override fun textAfterCursor(length: Int): CharSequence? {
        calls += "textAfterCursor($length)"
        if (!isAvailable) return null
        val limit = revealLimit ?: return null
        val b = maxOf(selectionStart, selectionEnd)
        val n = minOf(length, limit, buffer.length - b)
        return buffer.substring(b, b + maxOf(n, 0))
    }

    override fun textBeforeCursor(length: Int): CharSequence? {
        calls += "textBeforeCursor($length)"
        if (!isAvailable) return null
        val limit = revealLimit ?: return null
        val a = minOf(selectionStart, selectionEnd)
        if (a <= 0) return ""
        val n = minOf(length, limit, a)
        return buffer.substring(a - n, a)
    }

    /** Deletes around the selection widened to the composing region, so composing text is never deleted. */
    override fun deleteSurroundingText(before: Int, after: Int, inCodePoints: Boolean) {
        calls += "deleteSurroundingText($before, $after, inCodePoints=$inCodePoints)"
        if (!isAvailable || dropsWrites) return
        var a = minOf(selectionStart, selectionEnd)
        var b = maxOf(selectionStart, selectionEnd)
        if (hasComposing) {
            a = minOf(a, composingStart)
            b = maxOf(b, composingEnd)
        }
        val start: Int
        val end: Int
        if (inCodePoints) {
            // an unpaired surrogate in the way makes the real call delete nothing at all
            start = codePointsBackward(a, before) ?: return
            end = codePointsForward(b, after) ?: return
        } else {
            start = if (before > 0) (a - before).coerceAtLeast(0) else a
            end = if (after > 0) (b + after).coerceAtMost(buffer.length) else b
        }
        buffer.delete(b, end)
        buffer.delete(start, a)
        owner.delete(b, end)
        owner.delete(start, a)
        val removed = a - start
        fun move(p: Int) = if (p >= a) p - removed else p
        selectionStart = move(selectionStart)
        selectionEnd = move(selectionEnd)
        if (hasComposing) {
            composingStart = move(composingStart)
            composingEnd = move(composingEnd)
        }
    }

    private fun codePointsBackward(from: Int, count: Int): Int? {
        var i = from
        repeat(count) {
            if (i == 0) return i
            val c = buffer[i - 1]
            i -= if (Character.isLowSurrogate(c)) {
                if (i < 2 || !Character.isHighSurrogate(buffer[i - 2])) return null
                2
            } else {
                if (Character.isHighSurrogate(c)) return null
                1
            }
        }
        return i
    }

    private fun codePointsForward(from: Int, count: Int): Int? {
        var i = from
        repeat(count) {
            if (i == buffer.length) return i
            val c = buffer[i]
            i += if (Character.isHighSurrogate(c)) {
                if (i + 1 >= buffer.length || !Character.isLowSurrogate(buffer[i + 1])) return null
                2
            } else {
                if (Character.isLowSurrogate(c)) return null
                1
            }
        }
        return i
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

    /** Simulates the user or the app moving the selection, which the IME then hears about. */
    fun reportSelection(start: Int, end: Int = start) {
        selectionStart = start
        selectionEnd = end
    }

    companion object {
        /** [owners]: a character the app put there (the initial text too). */
        const val APP = 'a'

        /** [owners]: a character the input method's calls put there. */
        const val KEYBOARD = 'k'
    }
}
