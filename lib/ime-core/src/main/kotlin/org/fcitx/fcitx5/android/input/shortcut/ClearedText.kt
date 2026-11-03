/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.shortcut

import java.text.BreakIterator

/**
 * What 恢复 puts back in the input started last, in memory only (a password's never): its clears
 * ([remember]) and its runs of deletions ([deleting]: Backspace pressed or held, a selection's
 * deletion, one after another with nothing else between), last first ([take]) while the field reads
 * as each left it and nothing is typed: once anything else changed it, nothing is put back.
 * Forgotten ([forget]) as input starts in another field or finishes, so nothing is put back in
 * another field that reads the same, nor kept once its field is left (review M1); at most [MAX] kept.
 */
class ClearedText {

    private sealed interface Entry {
        class Clear(val cleared: EditPlan.Cleared) : Entry

        /**
         * Deletions from [base], the field as read before the first since it (a selection's
         * deletion reads it again: [settled]), [presses] of them; [taken] what went before that
         * read, after [base]'s cursor. [open] while the next deletion is more of it.
         */
        class Run(var base: Read) : Entry {
            var presses = 1
            var taken = ""
            var open = true
        }
    }

    /**
     * The field as a run reads it: [field] around the cursor, the cursor (the selection's start) [at]
     * that far into the field, -1 where that is not known. Its text before the cursor is all there
     * is ([whole]) where the read gave less than asked and no less than [at]: an app may give less
     * than asked of a longer field (review M7), whose start is then not where the read starts.
     */
    class Read(val field: EditPlan.Field, val at: Int) {
        val whole = field.before.length < READ_BEFORE && (at < 0 || field.before.length >= at)
    }

    private var field: Any? = null
    private val kept = ArrayDeque<Entry>()

    /** All forgotten: input started anew or finished. */
    fun forget() {
        field = null
        kept.clear()
    }

    private fun into(field: Any) {
        if (field !== this.field) kept.clear()
        this.field = field
    }

    private fun push(entry: Entry) {
        kept.addLast(entry)
        if (kept.size > MAX) kept.removeFirst()
    }

    /**
     * [field] (the app's token for the input it started, told from another by identity alone)
     * cleared: [cleared] kept, or null when that clear could not be.
     */
    fun remember(field: Any, cleared: EditPlan.Cleared?) {
        into(field)
        // one not kept breaks the chain: those before it were left by a field since changed. What was
        // typed is typing since those before it, which the field, never showing it, cannot tell (review S1)
        if (cleared == null || cleared.typed != null) kept.clear()
        if (cleared != null) push(Entry.Clear(cleared))
    }

    /**
     * A deletion in [field] about to be made, of the selection ([selection]) or, with none, of what
     * one Backspace takes before the cursor. A run's first, or a selection's, reads the field first
     * ([read], each side at most so many chars, null where it cannot be read: nothing kept, those
     * before broken off); the rest of a run are counted alone, what they took told when it is put back.
     * A selection's deletion in a run tells what the run took so far; where that cannot be told, the
     * run is dropped and a new one starts.
     */
    fun deleting(field: Any, selection: Boolean, read: (before: Int, after: Int) -> Read?) {
        into(field)
        val run = (kept.lastOrNull() as? Entry.Run)?.takeIf { it.open }
        if (run != null && !selection) {
            run.presses++
            return
        }
        val now = read(READ_BEFORE, READ_AFTER) ?: return kept.clear()
        if (run != null) {
            val took = settled(run.base, run.presses, now.field.before + now.field.selected, now.field.after)
            if (took != null) {
                run.taken = took + run.taken
                run.base = now
                run.presses = 1
                return
            }
            kept.clear()
        } else if (now.field.before.isEmpty() && now.field.selected.isEmpty()) {
            // nothing to delete: the field stays as it is, a clear before still to be put back
            return
        }
        push(Entry.Run(now))
    }

    /**
     * The field changed otherwise than by a run's deletions (typed in, the cursor moved, what is typed
     * changed): a run's next deletion starts another, and the last run is not put back, nor anything
     * kept before it, all left by a field since changed. A clear since is told by the field alone
     * ([take]), as ever (the app's own changes are not told here either), so kept (review M1).
     */
    fun edited() {
        repeat(kept.indexOfLast { it is Entry.Run } + 1) { kept.removeFirst() }
    }

    /**
     * The user put the cursor somewhere in the field (a tap on it the app told): the next deletion
     * starts another run. The last is kept, put back where the field still reads as it left it.
     */
    fun moved() {
        (kept.lastOrNull() as? Entry.Run)?.open = false
    }

    /**
     * The last clear or run to put back in [field], read as [now]: a run's as its deletion left the
     * cursor, what it took put back before it (the cursor after it, where it was). Null, all
     * forgotten, when the field changed since or something is [typing] (put back, a clear would
     * drop it or go in under it: review S1).
     */
    fun take(field: Any, now: EditPlan.Field, typing: Boolean): EditPlan.Cleared? {
        val last = kept.lastOrNull()?.takeIf { field === this.field } ?: return null
        val back = if (typing) {
            null
        } else {
            when (last) {
                is Entry.Clear -> last.cleared.takeIf { it.left == now }
                is Entry.Run -> last.back(now)
            }
        }
        if (back == null) {
            kept.clear()
            return null
        }
        kept.removeLast()
        // putting back is no deletion: the next Backspace starts a run of its own
        (kept.lastOrNull() as? Entry.Run)?.open = false
        return back
    }

    private fun Entry.Run.back(now: EditPlan.Field): EditPlan.Cleared? {
        if (now.selected.isNotEmpty()) return null
        val took = (settled(base, presses, now.before, now.after) ?: return null) + taken
        if (took.isEmpty()) return null
        return EditPlan.Cleared(now, listOf(EditPlan.Step.Replace(took)))
    }

    companion object {
        /** The clears and runs kept at most: one cleared, typed and cleared again a field's whole life is no stack to grow without end. */
        const val MAX = 16

        /**
         * What a run reads before the cursor: a run longer is not put back. Long enough for a
         * Backspace held a while; read at a run's first press alone, so not the clear's whole field.
         */
        const val READ_BEFORE = 1_000

        /**
         * What a run reads after the cursor: only to tell it is still where the run left it, so
         * no more than the engine's context reads (a round trip carries it all the same).
         */
        const val READ_AFTER = 64

        /**
         * What [presses] deletions took from the field read as [base] to leave [before] the cursor
         * and [after] it: the selection (the first's, if there is one) and, before it, what the rest
         * took; null where that cannot be told for sure. A key event's Backspace takes what the editor
         * calls one character (a code point, an emoji sequence, a flag whole: never asked, a round
         * trip a press), so a run's text is told by what is left: [before], the base's read whole
         * ([Read.whole]), is how much of [base]'s before is left; cut short, the place where its end
         * meets [base]'s, the only one where the presses can have taken what it leaves, each at least
         * a code point and at most a character. Not where anything after the cursor changed (an
         * empty pair's closing one gone with it is too), nor where the field was changed some other
         * way that leaves it reading as the run would (the app emptied it on a send, say: one press
         * takes no more than one character).
         */
        internal fun settled(base: Read, presses: Int, before: String, after: String): String? {
            val field = base.field
            val afterKept = if (field.after.length < READ_AFTER) after == field.after else after.startsWith(field.after)
            if (!afterKept) return null
            val units = if (field.selected.isEmpty()) presses else presses - 1
            val b = field.before
            val took = if (base.whole) {
                (b.length - before.length).takeIf {
                    it >= 0 && b.startsWith(before) && fits(b.substring(before.length), units, atStart = before.isEmpty(), Characters())
                }
            } else {
                cutShort(b, units, before)
            }
            return took?.let { b.substring(b.length - it) + field.selected }
        }

        // the read cut short: how much of [b]'s end [units] presses took to leave [before], where only
        // one length can be it. A press's taking all of it leaves no end to meet. Shortest first, and
        // stopped once no longer one can fit: a few presses' search, not the read's every length
        // squared (review L6; 5000 哈's 1000 read, each a fit)
        private fun cutShort(b: String, units: Int, before: String): Int? {
            val characters = Characters()
            var found: Int? = null
            // each press takes a code point at least: no shorter end is theirs
            var k = minOf(units, b.length)
            while (k < b.length) {
                val tail = b.substring(b.length - k)
                val left = b.length - k
                if (before.length >= left && before.regionMatches(before.length - left, b, 0, left)) {
                    val count = characters.of(tail)
                    // a character more prepended can merge with the first (a ZWJ sequence, a surrogate
                    // pair): the count falls by one at most, and only after it rose. Two past the presses,
                    // no longer end fits
                    if (count >= units + 2) break
                    if (count <= units && units <= tail.codePointCount(0, tail.length)) {
                        if (found != null) return null
                        found = k
                    }
                }
                k++
            }
            return found
        }

        // [presses] Backspaces taking [text]: each at least a code point and at most a character;
        // at the field's start the rest took nothing
        private fun fits(text: String, presses: Int, atStart: Boolean, characters: Characters): Boolean {
            if (text.isEmpty()) return presses == 0 || atStart
            return characters.of(text) <= presses && (atStart || presses <= text.codePointCount(0, text.length))
        }

        // characters (grapheme clusters) counted, one BreakIterator for a whole search
        private class Characters {
            private val it = BreakIterator.getCharacterInstance()

            fun of(text: String): Int {
                it.setText(text)
                var count = 0
                while (it.next() != BreakIterator.DONE) count++
                return count
            }
        }
    }
}
