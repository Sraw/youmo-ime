/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

import org.fcitx.fcitx5.android.input.UnicodePunctuation

/**
 * Brackets and quotes typed in pairs, as an editor for code does: an opening one typed puts its
 * closing one after it, the cursor between them. Typing the closing one there steps over it
 * rather than adding a second; a Backspace right after the opening one, the pair still empty,
 * takes the closing one too.
 *
 * Only where it reads as starting something: before the end of the text, a space or punctuation,
 * not before a word (`(` before `abc` is a bracket put round it, closed later). An ASCII `"`
 * after a word or punctuation is a closing one (`say "hi"`), `'` never pairs (`don't`); only
 * where the editor says what is after the cursor, or nothing put in could be stepped over.
 *
 * The closing ones put in are remembered, the innermost last, till the cursor goes anywhere this
 * did not put it ([EditingSession.onCursorUpdate] calls [forget]) or text is deleted other than by
 * Backspace or before the cursor inside the innermost pair ([deletedBefore]): only those step over
 * or go with their opening one, so a closing bracket typed before one the user wrote is typed.
 * [EditingSession.pairs] is the one of an editor.
 *
 * fcitx's punctuation turns `"` into “ or ” by the quotes it saw open, so either of them typed
 * alone at a ” put in steps over it: fcitx reset (the screen turned) may have taken the ” for an “.
 * It then counts that “ open, so [quoteOpen] holds till the next cursor report, for the service
 * to reset fcitx there as when a pair is left.
 */
class AutoPairs internal constructor(
    private val session: EditingSession,
    private val editor: InputEditor,
    private val checkThread: () -> Unit,
) {

    /** A closing one put in, and where the cursor was right after its opening one. */
    private class OpenPair(val closer: Char, val opened: Int)

    private val pairs = ArrayList<OpenPair>()

    /** A ” or ’ stepped over with “ or ‘, its cursor report still to come; see [cursorReported]. */
    private var steppedOverWithOpener = false

    /**
     * Whether fcitx counts a “ or ‘ open: one put in here, till its ” or ’ is typed, or one typed
     * to step over a ” or ’, till the cursor report after it.
     */
    val quoteOpen: Boolean get() = steppedOverWithOpener || pairs.any { it.closer in QUOTE_CLOSERS }

    /**
     * [text] typed, a character the user means to insert: true if it was handled here, as a
     * pair or a step over a closing one (of more than one character, over its first, the rest
     * committed after it); false if the caller commits it as usual.
     */
    fun type(text: String): Boolean {
        checkThread()
        if (text.isEmpty() || !plainCursor()) return false
        if (text.length > 1) return stepsOverFirst(text)
        val c = text[0]
        val top = pairs.lastOrNull()?.closer
        val closer = PAIRS[c]
        val closes = top != null && (c == top || QUOTES[c] == top)
        // neither steps over nor pairs, whatever follows: not worth a round trip to the editor
        if (!closes && closer == null) return false
        // null: the editor will not say, and what it cannot show could not be stepped over
        val after = (editor.textAfterCursor(1) ?: return false).firstOrNull()
        if (closes && after == top) {
            pairs.removeAt(pairs.lastIndex)
            if (c in QUOTES) steppedOverWithOpener = true
            session.applySelectionOffset(1, 1)
            return true
        }
        if (closer == null) return false
        if (after != null && !after.isWhitespace() && !punctuation(after)) return false
        // two units: a letter beyond the BMP is a surrogate pair
        if (c == closer && quoteCloses(editor.textBeforeCursor(2))) return false
        val at = session.selection.latest.start
        session.commitText("$c$closer", 1)
        pairs += OpenPair(closer, at + 1)
        return true
    }

    // [text], its first a closing one put in right after the cursor, and more (English's `) `, the
    // space it puts after it): the closing one stepped over, the rest committed after it. That one
    // itself, not “ for ”: what starts with “ (a phrase, a candidate) is no quote key fcitx turned
    private fun stepsOverFirst(text: String): Boolean {
        val top = pairs.lastOrNull()?.closer ?: return false
        if (text[0] != top) return false
        if (editor.textAfterCursor(1)?.firstOrNull() != top) return false
        pairs.removeAt(pairs.lastIndex)
        session.applySelectionOffset(1, 1)
        session.commitText(text.substring(1))
        return true
    }

    /**
     * [text] committed with the cursor after its first [cursor] units: a pair put in by fcitx
     * itself (its punctuation's 成对输入), remembered as one put in here.
     */
    fun committed(text: String, cursor: Int, start: Int) {
        checkThread()
        if (cursor == 1 && text.length == 2 && PAIRS[text[0]] == text[1]) pairs += OpenPair(text[1], start + 1)
    }

    /**
     * Backspace pressed, about to delete the character before the cursor: if that is the opening
     * one of an empty pair put in here, the closing one after the cursor is deleted first.
     * The caller then deletes as ever, the opening one, with a key event as Backspace is.
     */
    fun backspace() {
        checkThread()
        val top = pairs.lastOrNull() ?: return
        // without a round trip, only where the pair can still be empty
        if (!plainCursor() || session.selection.latest.start != top.opened) return
        val opener = OPENERS.getValue(top.closer)
        pairs.removeAt(pairs.lastIndex)
        if (editor.textBeforeCursor(1)?.singleOrNull() != opener || editor.textAfterCursor(1)?.firstOrNull() != top.closer) return
        // after the cursor: nothing moves, nothing to predict; and nothing composing
        editor.deleteSurroundingText(0, 1, inCodePoints = true)
    }

    /** The pairs put in are no longer where the cursor is: none steps over or goes with Backspace. */
    fun forget() {
        checkThread()
        pairs.clear()
    }

    /**
     * [units] deleted right before the cursor at [cursor], none after it: the pairs stay where all
     * of it is inside the innermost one (English's space taken back before a `)`); else forgotten.
     */
    internal fun deletedBefore(cursor: Int, units: Int) {
        checkThread()
        val top = pairs.lastOrNull() ?: return
        if (cursor - units < top.opened) pairs.clear()
    }

    /**
     * A cursor report reached [EditingSession.onCursorUpdate]. A step over with “ no longer
     * counts as [quoteOpen] after it: the service, seeing that fall, resets fcitx's count.
     */
    internal fun cursorReported() {
        checkThread()
        steppedOverWithOpener = false
    }

    // nothing composing (a preedit is fcitx's to commit) and no selection (typing replaces it)
    private fun plainCursor() = editor.isAvailable && session.composing.isEmpty() && session.selection.latest.isEmpty()

    private fun punctuation(c: Char) = c in UnicodePunctuation

    // what a quote after it closes: a word (`say "hi"`), or what ends one (`"Stop."`)
    private fun quoteCloses(before: CharSequence?): Boolean {
        if (before.isNullOrEmpty()) return false
        val last = Character.codePointBefore(before, before.length)
        return Character.isLetterOrDigit(last) || Character.getType(last).toByte() in ENDING
    }

    companion object {
        /** Opening ones to their closing ones. */
        val PAIRS = mapOf(
            '(' to ')', '[' to ']', '{' to '}', '"' to '"',
            '（' to '）', '【' to '】', '〔' to '〕', '［' to '］', '｛' to '｝',
            '「' to '」', '『' to '』', '《' to '》', '〈' to '〉',
            '“' to '”', '‘' to '’',
        )
        private val OPENERS = PAIRS.entries.associate { (open, close) -> close to open }
        private val QUOTE_CLOSERS = setOf('”', '’')

        // what fcitx may make of the one quote key, either way: to the closing one
        private val QUOTES = mapOf('“' to '”', '‘' to '’')

        private val ENDING = setOf(Character.END_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION)
    }
}
