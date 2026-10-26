/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Commit

/**
 * The field as a session wrote it (U-03, impl/design-1c.md §2): its last commits, what is left of
 * each on either side of the cursor, and those deleted whole, taken back in the learning record and
 * waiting to be typed again ([pending]). Only what the session saw, and only as long as it is sure
 * of it: a commit taken back wrongly is learning the user wanted lost for good, one missed costs
 * little, so when in doubt nothing is taken back (review-design-1c M1, review-1c M1/M2):
 *
 * - A Backspace the app took takes a character before the cursor, while the journal is
 *   [verified]: nothing else went into the field since the session last knew it. Text the session
 *   did not write (a key passed on, a panel's, another input method's), a commit of several code
 *   points to a character (an emoji sequence) leave it unverified, and a Backspace then drops all.
 * - A context the host read checks it. One it explains exactly (the cursor among the commits, some
 *   deleted whole as a cut or a selection's Backspace deletes them) verifies it again; any other
 *   drops all, as a field started anew or cleared does. What was read in full is all there is: the
 *   text before the cursor longer or shorter than written is no fit. A selection's sides look like
 *   a cut and are none ([Action.Context.selected]); an empty text before the cursor is not known
 *   (an editor that would not say). An unverified journal reads no deletion from a context at all.
 * - A commit deleted whole is taken back only at the next action that is no context ([settle]):
 *   a context after it that does not fit it drops it with the rest.
 */
internal class CommitJournal {

    /** A commit as written: its [text], and the record of it ([commit], null where nothing is to be taken back). */
    class Entry(val text: String, var commit: Commit?, val order: Long) {
        val length = text.codePointCount(0, text.length)

        /** Whether a Backspace takes a character a code point: else how many it takes is the editor's to say. */
        val countable = countable(text)

        /** How much of [text] is left, in code points: deleted from its end. */
        var left = length

        // deleted where the cursor was, before it went elsewhere: taken back, but typed again there no more
        var retype = true

        val shown get() = if (left == length) text else text.substring(0, text.offsetByCodePoints(0, left))
    }

    private val entries = ArrayList<Entry>()

    // what each entry was written as: the order deleted ones are typed again in, however deleted
    private var written = 0L

    private val empty get() = entries.isEmpty() && deleting.isEmpty() && deleted.isEmpty()

    // how many of [entries] are before the cursor: the rest after it, as the user went back among them
    private var cursor = 0

    // the text before all of [entries] and after them as the host read it (HOST_CHARS of each at
    // most); null, not known: without both, no commit is known deleted but by a Backspace. [headWhole]:
    // the head was read to the field's start, so nothing is before it
    private var head: String? = null
    private var headWhole = false
    private var tail: String? = null

    /** Whether the field is as the journal has it, so a Backspace's character is known. */
    var verified = true
        private set

    // text is selected, as the last context told: a commit goes over it
    private var selected = false

    // the last Backspace's: a context right after it may tell it deleted a selection instead (review-1c S2)
    private class Charge(val entry: Entry, val removed: Boolean)

    private var charge: Charge? = null

    // the commits deleted whole at the cursor, in the order written, each taken back
    private val deleted = ArrayList<Entry>()

    // those deleted whole not yet taken back: see settle
    private val deleting = ArrayList<Entry>()

    private val partlyBefore get() = cursor > 0 && entries[cursor - 1].left < entries[cursor - 1].length

    /** The commits deleted whole where the next commit goes, in the order written: none if the cursor is in what is left of one. */
    val pending: List<Commit> get() = if (partlyBefore) emptyList() else deleted.mapNotNull { it.commit }

    fun clear() {
        entries.clear()
        cursor = 0
        head = null
        headWhole = false
        tail = null
        deleted.clear()
        deleting.clear()
        verified = true
        selected = false
        charge = null
    }

    /** Something went into the field the journal did not see (a key passed on, a panel, another input method): see the class. */
    fun unverify() {
        verified = false
    }

    /**
     * Takes back what was deleted whole since the last action, the last written first, by [undo]:
     * whether it was (in the tail, one of the last few). Those taken back wait to be typed again; one
     * not taken back ends the run (review-design-1c M2: a commit the record no longer has is never learned again).
     */
    fun settle(undo: (Commit) -> Boolean) {
        charge = null
        if (deleting.isEmpty()) return
        deleting.sortByDescending { it.order }
        for (e in deleting) {
            val commit = e.commit
            if (commit == null || !undo(commit)) deleted.removeAll { it.order > e.order } else if (e.retype) {
                // in the order written, those deleted after the cursor too (a forward delete) after those before (review-1c S7)
                val at = deleted.indexOfFirst { it.order > e.order }
                deleted.add(if (at < 0) deleted.size else at, e)
            }
        }
        deleting.clear()
    }

    /**
     * [text] committed at the cursor, as [commit]; the first [typedAgain] of [pending] typed again
     * by it, and, if it ended before the next of them did ([goesOn]), those wait for the commit after it.
     */
    fun committed(text: String, commit: Commit?, typedAgain: Int = 0, goesOn: Boolean = false) {
        charge = null
        val waiting = if (partlyBefore) emptyList() else deleted.filter { it.commit != null }
        val rest = if (goesOn && typedAgain < waiting.size) waiting.subList(typedAgain, waiting.size).toList() else emptyList()
        deleted.clear()
        deleted += rest
        if (text.isEmpty()) return
        // put over a selection: what it took is not known
        if (selected) clear()
        // written after what is left of one: that one is a word of the user's now, not a slip to take back (review-1c N5)
        if (partlyBefore) entries[cursor - 1].commit = null
        entries.add(cursor++, Entry(text, commit, ++written))
        while (entries.size > KEPT) trim()
    }

    // one entry fewer, from the end farther from the cursor: the cursor back before all of them
    // writes its commit first, which the front's would drop at once (F2804)
    private fun trim() {
        if (cursor - 1 >= entries.size - cursor) {
            head = head?.let {
                val all = it + entries[0].shown
                headWhole = headWhole && all.length < Action.Context.HOST_CHARS
                all.takeLast(Action.Context.HOST_CHARS)
            }
            entries.removeAt(0)
            cursor--
        } else {
            // the tail read as far as the host reads: whole while shorter (follows)
            tail = tail?.let { (entries.last().shown + it).take(Action.Context.HOST_CHARS) }
            entries.removeAt(entries.size - 1)
        }
    }

    /**
     * A Backspace the app took: the character before the cursor gone, if the journal is sure of it,
     * else all dropped. A commit gone whole is taken back at the next action ([settle]); one past
     * what was written here ends the run. With text selected, the selection goes instead: the
     * context read as it goes says so, and the charge is given back ([placeUncharged]).
     */
    fun backspace() {
        charge = null
        if (!verified || cursor == 0) return clear()
        val e = entries[cursor - 1]
        if (!e.countable) {
            // how much of it the editor takes is its to say: the next context does
            unverify()
            return
        }
        val removed = --e.left == 0
        if (removed) {
            entries.removeAt(--cursor)
            deleting += e
        }
        charge = Charge(e, removed)
    }

    /**
     * What the host read about the cursor: [before] it, and [after] it if read. A field [started]
     * anew is another's; one [cleared] is empty. Text [selected] changes nothing yet. Else the cursor
     * is put after the commits [before] ends with, [after] following, some of those after it gone
     * whole (a selection, a cut) or none, those taken back at the next action ([settle]). At no such
     * place all is dropped.
     */
    fun context(before: String, after: String?, started: Boolean, cleared: Boolean, selected: Boolean, said: Boolean = false) {
        val charge = charge
        this.charge = null
        if (started) return known(before, after)
        if (cleared) return emptied()
        // the text either side of a selection is that of a cut: none yet (review-1c M2)
        this.selected = selected
        if (selected) return
        // an editor that would not say: not known what is there, so nothing is read from it (review-1c M1).
        // One that said empty is a field empty, checked as any text (原 HW-22/23: an empty field refocused after a Reset)
        if (before.isEmpty() && !said) return unverify()
        if (empty) return known(before, after)
        // as the Backspace left it (an editor that tells each); else the text either side of a selection
        // that Backspace deleted, read as it went: told as the field before it (review-1c S2, verify-1c)
        val at = place(before, after) ?: charge?.let { placeUncharged(it, before, after) } ?: return known(before, after)
        val (j, k) = at
        if (j != cursor || k != cursor) {
            // what is not as written may hold text never seen: only where the cursor is (review-1c M1)
            if (!verified) return known(before, after)
            // the cursor put elsewhere: no run of deletions goes on there, unless this is one
            if (j != cursor) {
                deleted.clear()
                deleting.forEach { it.retype = false }
            }
            for (i in j until k) deleting += entries[i]
            repeat(k - j) { entries.removeAt(j) }
            cursor = j
        }
        // what is around the commits, first read where the cursor is after them all
        if (cursor == entries.size) {
            if (tail == null) tail = after
            val written = shownBefore(cursor)
            if (head == null && before.endsWith(written)) {
                head = before.substring(0, before.length - written.length)
                headWhole = before.length < Action.Context.HOST_CHARS
            }
        }
        verified = true
    }

    // the field empty, as a chat's once a message is sent: the commits deleted by Backspace one by
    // one if nothing written is left, else all sent (review-1c S1)
    private fun emptied() {
        if (entries.isNotEmpty()) clear()
        head = ""
        headWhole = true
        tail = ""
        selected = false
        verified = true
    }

    // all dropped, the field as the host read it: what the next commits go between
    private fun known(before: String, after: String?) {
        clear()
        head = before
        headWhole = before.length < Action.Context.HOST_CHARS
        tail = after
    }

    /**
     * A context the engine gave itself, [before] the cursor: its own commit (a glide's word held
     * back, a character looked up), which it should have written here; else the field is not as known.
     */
    fun own(before: String) {
        if (!fits(before, cursor, read = false)) unverify()
    }

    // [place] as if [charge]'s Backspace had not been: kept so if it places, else the Backspace stands
    private fun placeUncharged(charge: Charge, before: String, after: String?): Pair<Int, Int>? {
        val e = charge.entry
        if (charge.removed) {
            deleting.remove(e)
            entries.add(cursor++, e)
        }
        e.left++
        place(before, after)?.let { return it }
        if (--e.left == 0) {
            entries.removeAt(--cursor)
            deleting += e
        }
        return null
    }

    // where [before] and [after] put the cursor: after entry j, the entries from k on after it (j..k-1
    // deleted whole); the cursor where it is first. Without the text after, only there and with none deleted
    private fun place(before: String, after: String?): Pair<Int, Int>? {
        if (fits(before, cursor) && (after == null || follows(after, cursor))) return cursor to cursor
        if (after == null || head == null || tail == null) return null
        for (j in entries.size downTo 0) {
            if (!fits(before, j)) continue
            for (k in j..entries.size) if (follows(after, k)) return j to k
        }
        return null
    }

    // whether [before] ends with the head and what is shown of the entries before [at]. What was
    // [read] by the host in full is all there is: shorter, something written is gone; longer than a
    // head read to the start, something else is there (review-1c M1)
    private fun fits(before: String, at: Int, read: Boolean = true): Boolean {
        val written = head.orEmpty() + shownBefore(at)
        if (read && before.length < Action.Context.HOST_CHARS && written.length > before.length) return false
        if (read && headWhole && before.length > written.length) return false
        val n = minOf(before.length, written.length)
        return before.regionMatches(before.length - n, written, written.length - n, n)
    }

    private fun shownBefore(at: Int) = buildString { for (i in 0 until at) append(entries[i].shown) }

    // whether [after] is what is shown of the entries from [at], then the tail: as far as both were read
    private fun follows(after: String, at: Int): Boolean {
        val written = StringBuilder()
        for (i in at until entries.size) written.append(entries[i].shown)
        written.append(tail.orEmpty())
        val n = minOf(after.length, written.length)
        if (!after.regionMatches(0, written, 0, n)) return false
        // what was read in full is all there is
        val tail = tail
        val writtenWhole = tail != null && tail.length < Action.Context.HOST_CHARS
        return !(after.length < Action.Context.HOST_CHARS && written.length > after.length) && !(writtenWhole && after.length > written.length)
    }

    companion object {
        /** The commits kept: as many as the learning record keeps to take back. */
        const val KEPT = LearnLog.UNDOABLE

        /**
         * Whether each code point of [text] is a character of its own, as an editor's Backspace takes
         * it: none joins (ZWJ), varies (a variation selector, a skin tone, a tag), combines (a mark)
         * or pairs (regional indicators, a flag) with another.
         */
        fun countable(text: String): Boolean {
            var i = 0
            while (i < text.length) {
                val c = text.codePointAt(i)
                if (joins(c)) return false
                i += Character.charCount(c)
            }
            return true
        }

        private fun joins(c: Int): Boolean = when (Character.getType(c)) {
            Character.NON_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt() -> true
            else -> c == ZWJ || c in 0xFE00..0xFE0F || c in 0x1F1E6..0x1F1FF || c in 0x1F3FB..0x1F3FF || c in 0xE0000..0xE01EF
        }

        private const val ZWJ = 0x200D
    }
}
