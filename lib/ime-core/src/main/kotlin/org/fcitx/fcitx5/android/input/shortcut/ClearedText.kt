/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.shortcut

import java.text.BreakIterator

/**
 * What 恢复 puts back in the last input, memory only (never a password's): its clears and runs of deletions,
 * last first, while the field reads as each left it and nothing is typed. Forgotten when another input starts.
 */
class ClearedText {

    private sealed interface Entry {
        class Clear(val cleared: EditPlan.Cleared) : Entry

        /** [base]: the field before the run's first deletion; [taken] what went before that read. [open] while the next deletion extends it. */
        class Run(var base: Read) : Entry {
            var presses = 1
            var taken = ""
            var open = true
        }
    }

    /** [at] -1 where unknown. [whole] where the read gave less than asked but not less than [at]: an app may give less of a longer field. */
    class Read(val field: EditPlan.Field, val at: Int) {
        val whole = field.before.length < READ_BEFORE && (at < 0 || field.before.length >= at)
    }

    private var field: Any? = null
    private val kept = ArrayDeque<Entry>()

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

    /** [field] is told apart by identity alone; [cleared] null when that clear could not be kept. */
    fun remember(field: Any, cleared: EditPlan.Cleared?) {
        into(field)
        // one not kept breaks the chain; typing since those before cannot be told by the field
        if (cleared == null || cleared.typed != null) kept.clear()
        if (cleared != null) push(Entry.Clear(cleared))
    }

    /**
     * A run's first deletion, or a selection's, reads the field ([read] null: nothing kept, the chain broken);
     * the rest are counted. Where a selection's deletion cannot tell what the run took, the run is dropped.
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
     * Changed otherwise than by a run: the last run and all before it are not put back. A clear is still checked by
     * the field alone ([take]), unless it never read all before the cursor ([EditPlan.Cleared.whole] false).
     */
    fun edited() {
        repeat(kept.indexOfLast { it is Entry.Run || it is Entry.Clear && !it.cleared.whole } + 1) { kept.removeFirst() }
    }

    /** A tap moved the cursor: the next deletion starts another run; the last is kept. */
    fun moved() {
        (kept.lastOrNull() as? Entry.Run)?.open = false
    }

    /** Null, all forgotten, when the field changed since or something is [typing] (a clear would drop it). */
    fun take(field: Any, now: EditPlan.Field, typing: Boolean): EditPlan.Cleared? {
        val last = kept.lastOrNull()?.takeIf { field === this.field } ?: return null
        val back = if (typing) {
            null
        } else {
            when (last) {
                is Entry.Clear -> last.cleared.takeIf { it.leaves(now) }
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
        /** A field cleared and retyped all its life is no stack to grow without end. */
        const val MAX = 16

        /** A run longer is not put back; read at a run's first press alone. */
        const val READ_BEFORE = 1_000

        /** Only to tell the cursor is where the run left it. */
        const val READ_AFTER = 64

        /**
         * What [presses] deletions took, null where unsure. A key Backspace takes what the editor calls a character
         * (never asked: a round trip a press), so it is told by what is left: the only place [before]'s end meets
         * [base]'s with each press a code point to a character. Not where anything after the cursor changed.
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

        // the only length that fits; shortest first, stopped once none longer can (not every length squared)
        internal fun cutShort(b: String, units: Int, before: String, characters: Characters = Characters()): Int? {
            var found: Int? = null
            // each press takes a code point at least: no shorter end is theirs
            var k = minOf(units, b.length)
            while (k < b.length) {
                val tail = b.substring(b.length - k)
                val left = b.length - k
                if (before.length >= left && before.regionMatches(before.length - left, b, 0, left)) {
                    val count = characters.of(tail)
                    // a prepended character can merge with the first (a ZWJ, a surrogate): the count falls by one at most
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

        // at the field's start the rest took nothing
        private fun fits(text: String, presses: Int, atStart: Boolean, characters: Characters): Boolean {
            if (text.isEmpty()) return presses == 0 || atStart
            return characters.of(text) <= presses && (atStart || presses <= text.codePointCount(0, text.length))
        }

        // grapheme clusters, one BreakIterator for a whole search
        internal class Characters {
            private val it = BreakIterator.getCharacterInstance()

            var counted = 0
                private set

            fun of(text: String): Int {
                counted++
                it.setText(text)
                var count = 0
                while (it.next() != BreakIterator.DONE) count++
                return count
            }
        }
    }
}
