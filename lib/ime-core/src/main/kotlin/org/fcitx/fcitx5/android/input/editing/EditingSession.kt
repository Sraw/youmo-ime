/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.input.cursor.CursorRange
import org.fcitx.fcitx5.android.input.cursor.CursorTracker
import kotlin.math.max

/**
 * Where the IME's idea of the cursor, the selection and the composing region lives.
 *
 * The IME runs ahead of the editor: it decides where the cursor *will* be, tells the editor,
 * and later reconciles that prediction against what the editor reports
 * (see [CursorTracker]). Getting that wrong shows up as the cursor jumping, text landing in
 * the wrong place, or a composition that will not clear -- all of which are miserable to
 * reproduce by hand.
 *
 * Everything here is arithmetic over an [InputEditor], with no Android types involved.
 */
class EditingSession(private val editor: InputEditor) {

    /** Where the IME believes the cursor is, reconciled against the editor's reports. */
    val selection = CursorTracker()

    /** The range currently held as composing text, in editor coordinates. */
    val composing = CursorRange()

    /** The content of that range, as fcitx formatted it. */
    var composingText: FormattedText = FormattedText.Empty
        private set

    fun setComposingText(text: FormattedText) {
        composingText = text
    }

    fun resetComposingState() {
        composing.clear()
        composingText = FormattedText.Empty
    }

    /**
     * Commits [text], replacing the composing region if there is one, the selection if there
     * is one, or simply inserting before the cursor.
     *
     * @param cursor where to leave the cursor within [text]; `-1` means after its last
     * character, which is the common case and lets the editor place the cursor itself.
     */
    fun commitText(text: String, cursor: Int = -1) {
        if (!editor.isAvailable) return
        // When the commit is exactly what is already composing, there is nothing to replace:
        // finish the composition as-is and only move the cursor if it is not already there.
        // Re-committing identical text would make the editor redraw and can drop spans.
        if (composing.isNotEmpty() && composingText.toString() == text) {
            val c = if (cursor == -1) text.length else cursor
            val target = composing.start + c
            resetComposingState()
            editor.batchEdit {
                if (selection.current.start != target) {
                    selection.predict(target)
                    editor.setSelection(target, target)
                }
                editor.finishComposingText()
            }
            return
        }
        val start = if (composing.isEmpty()) selection.latest.start else composing.start
        resetComposingState()
        if (cursor == -1) {
            selection.predict(start + text.length)
            editor.commitText(text, 1)
        } else {
            val target = start + cursor
            selection.predict(target)
            editor.batchEdit {
                editor.commitText(text, 1)
                editor.setSelection(target, target)
            }
        }
    }

    /**
     * Deletes the current selection, if any. A collapsed cursor is left alone.
     *
     * Unlike [commitText] this does not check [InputEditor.isAvailable]: the original service
     * method did not either, and this refactor keeps behaviour identical.
     */
    fun deleteSelection() {
        val lastSelection = selection.latest
        if (lastSelection.isEmpty()) return
        selection.predict(lastSelection.start)
        editor.commitText("", 1)
    }

    /**
     * Moves each end of the selection by the given offset, clamped at the start of the buffer.
     * An offset that would invert the range is ignored rather than applied backwards.
     */
    fun applySelectionOffset(offsetStart: Int, offsetEnd: Int = 0) {
        if (!editor.isAvailable) return
        val lastSelection = selection.latest
        val start = max(lastSelection.start + offsetStart, 0)
        val end = max(lastSelection.end + offsetEnd, 0)
        if (start > end) return
        selection.predict(start, end)
        editor.setSelection(start, end)
    }

    /** Collapses a selection to its end, leaving a plain cursor. */
    fun cancelSelection() {
        val lastSelection = selection.latest
        if (lastSelection.isEmpty()) return
        val end = lastSelection.end
        selection.predict(end)
        editor.setSelection(end, end)
    }

    /**
     * Deletes text around the cursor at fcitx's request.
     *
     * Only [before] shifts the cursor, so only it is predicted -- in UTF-16 units, which is
     * what the editor will report back, even when the deletion itself counts code points.
     * Measuring that costs one synchronous `getTextBeforeCursor`; fcitx asks for surrounding
     * deletions rarely, so unlike Backspace this path can afford it.
     */
    fun deleteSurrounding(before: Int, after: Int, inCodePoints: Boolean) {
        if (!editor.isAvailable) return
        if (before > 0) {
            selection.predictOffset(-(if (inCodePoints) unitsBeforeCursor(before) else before))
        }
        editor.deleteSurroundingText(before, after, inCodePoints)
    }

    /**
     * Handles Backspace from the virtual keyboard.
     *
     * Most editors get a Backspace key event, because that is the only thing every editor
     * understands. Editors that opted in ([EditorTraits.acceptsDeleteSurrounding]) are edited
     * directly instead: the selection is deleted, or else one code point before the cursor.
     *
     * @return true when handled here; false when the caller must send a Backspace key event
     */
    fun backspace(traits: EditorTraits): Boolean {
        val lastSelection = selection.latest
        val direct = editor.isAvailable && traits.acceptsDeleteSurrounding &&
            !traits.isRawKeyInput && (lastSelection.isNotEmpty() || lastSelection.start > 0)
        if (lastSelection.isNotEmpty()) {
            selection.predict(lastSelection.start)
        } else if (lastSelection.start > 0) {
            // A key event deletes whatever the editor thinks one character is, which can only
            // be guessed at; asking the editor would add a round trip to every press, so the
            // guess stays at one unit. A direct deletion is a known code point, so it is exact.
            selection.predictOffset(-(if (direct) unitsBeforeCursor(1) else 1))
        }
        if (!direct) return false
        if (lastSelection.isEmpty()) {
            editor.deleteSurroundingText(1, 0, inCodePoints = true)
        } else {
            editor.commitText("", 0)
        }
        return true
    }

    /**
     * How many UTF-16 units the last [codePoints] code points before the cursor take up.
     * Falls back to assuming one unit each when the editor will not show enough text to tell.
     */
    private fun unitsBeforeCursor(codePoints: Int): Int {
        val wanted = codePoints * 2 // enough for any code point to be a surrogate pair
        val text = editor.textBeforeCursor(wanted) ?: return codePoints
        // Shorter than the cursor position allows means the editor held text back, not that
        // the text starts there; counting what came back would under-predict.
        if (text.length < minOf(wanted, selection.latest.start)) return codePoints
        return CodePoints.lengthOfLast(text, codePoints)
    }

    /**
     * Asks the editor to end its composing region, leaving the text in place. Does not touch
     * this session's composing state -- callers that want that call [resetComposingState].
     */
    fun finishEditorComposing() {
        editor.finishComposingText()
    }

    /**
     * Restores the composing region after an editor reported no composing span while this
     * session still believes there is one -- some editors drop it through an `InputFilter`.
     *
     * @return whether a restore was issued
     */
    fun restoreComposingRegionIfDropped(reportedStart: Int, reportedEnd: Int): Boolean {
        if (reportedStart != -1 || reportedEnd != -1) return false
        if (composing.isEmpty()) return false
        editor.setComposingRegion(composing.start, composing.end)
        return true
    }
}
