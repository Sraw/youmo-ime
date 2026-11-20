/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

import org.fcitx.fcitx5.android.input.UnicodePunctuation

/**
 * Brackets and quotes typed in pairs, as a code editor does: the opening one adds its closer, typing the closer
 * steps over it, Backspace in an empty pair takes both. Only before a space, punctuation or the end; an ASCII `"`
 * after a word closes, `'` never pairs. Closers put in are remembered till the cursor goes elsewhere ([forget]).
 * fcitx turns `"` into “ or ” by the quotes it saw, so either steps over a ” put in; it then counts that “ open,
 * so [quoteOpen] holds till the next cursor report for the service to reset fcitx.
 */
class AutoPairs internal constructor(
    private val session: EditingSession,
    private val editor: InputEditor,
    private val checkThread: () -> Unit,
) {

    private class OpenPair(val closer: Char, val opened: Int)

    private val pairs = ArrayList<OpenPair>()

    /** Its cursor report still to come; see [cursorReported]. */
    private var steppedOverWithOpener = false

    /** One put in here till its closer is typed, or one typed to step over a closer till the next report. */
    val quoteOpen: Boolean get() = steppedOverWithOpener || pairs.any { it.closer in QUOTE_CLOSERS }

    /** True if handled here as a pair or a step over (of a longer text, over its first, the rest committed after). */
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

    // English's `) `: the closer stepped over, the rest committed. Not “ for ”: a phrase starting with “ is no quote key
    private fun stepsOverFirst(text: String): Boolean {
        val top = pairs.lastOrNull()?.closer ?: return false
        if (text[0] != top) return false
        if (editor.textAfterCursor(1)?.firstOrNull() != top) return false
        pairs.removeAt(pairs.lastIndex)
        session.applySelectionOffset(1, 1)
        session.commitText(text.substring(1))
        return true
    }

    /** A pair fcitx put in itself (成对输入), remembered as one put in here. */
    fun committed(text: String, cursor: Int, start: Int) {
        checkThread()
        if (cursor == 1 && text.length == 2 && PAIRS[text[0]] == text[1]) pairs += OpenPair(text[1], start + 1)
    }

    /** Before the caller's Backspace: an empty pair's closer is deleted first. */
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

    fun forget() {
        checkThread()
        pairs.clear()
    }

    /** The pairs stay where all of it is inside the innermost (English's space taken back before a `)`). */
    internal fun deletedBefore(cursor: Int, units: Int) {
        checkThread()
        val top = pairs.lastOrNull() ?: return
        if (cursor - units < top.opened) pairs.clear()
    }

    /** A step over with “ stops counting as [quoteOpen]: the service, seeing that fall, resets fcitx's count. */
    internal fun cursorReported() {
        checkThread()
        steppedOverWithOpener = false
    }

    // nothing composing (a preedit is fcitx's to commit) and no selection (typing replaces it)
    private fun plainCursor() = editor.isAvailable && session.composing.isEmpty() && session.selection.latest.isEmpty()

    // ❛❜❝❞ are symbols to Unicode, not punctuation
    private fun punctuation(c: Char) = c in UnicodePunctuation || c in PAIRS || c in OPENERS

    // a word (`say "hi"`), or what ends one (`"Stop."`)
    private fun quoteCloses(before: CharSequence?): Boolean {
        if (before.isNullOrEmpty()) return false
        val last = Character.codePointBefore(before, before.length)
        return Character.isLetterOrDigit(last) || Character.getType(last).toByte() in ENDING ||
            (Character.isBmpCodePoint(last) && last.toChar() in OPENERS)
    }

    companion object {
        val PAIRS = mapOf(
            '(' to ')', '[' to ']', '{' to '}', '"' to '"',
            '（' to '）', '【' to '】', '〔' to '〕', '［' to '］', '｛' to '｝',
            '「' to '」', '『' to '』', '《' to '》', '〈' to '〉',
            '“' to '”', '‘' to '’',
            // « and ‹ taken as opening, as in French and Chinese text
            '〖' to '〗', '〘' to '〙', '❨' to '❩', '❲' to '❳', '❴' to '❵', '⟨' to '⟩',
            '❛' to '❜', '❝' to '❞', '«' to '»', '‹' to '›',
            // as `"`: opening or closing by what is before it
            '＂' to '＂',
        )
        private val OPENERS = PAIRS.entries.associate { (open, close) -> close to open }
        private val QUOTE_CLOSERS = setOf('”', '’')

        // what fcitx may make of the one quote key, either way
        private val QUOTES = mapOf('“' to '”', '‘' to '’')

        private val ENDING = setOf(Character.END_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION)
    }
}
