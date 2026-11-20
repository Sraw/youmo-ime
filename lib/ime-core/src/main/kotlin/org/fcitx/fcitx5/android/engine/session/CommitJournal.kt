/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Commit

/**
 * The field as the session wrote it: its last commits, what is left of each around the cursor, and those deleted
 * whole (taken back in the record, waiting to be retyped: [pending]). A wrong take-back loses learning for good and
 * a missed one costs little, so when in doubt nothing is taken back:
 *
 * - A Backspace takes a character only while [verified]: unseen text (keys passed on, panels, another input method)
 *   or multi-code-point characters leave it unverified, and a Backspace then drops all.
 * - A host context it explains exactly verifies it again; any other drops all. An empty before-text is unknown,
 *   and a selection's sides look like a cut but are none ([Action.Context.selected]).
 * - A commit deleted whole is taken back only at the next non-context action ([settle]).
 */
internal class CommitJournal {

    /** [commit] null where nothing is to be taken back. */
    class Entry(val text: String, var commit: Commit?, val order: Long) {
        val length = text.codePointCount(0, text.length)

        /** Whether a Backspace takes one code point; else how many is the editor's to say. */
        val countable = countable(text)

        /** Code points of [text] left, deleted from its end. */
        var left = length

        // deleted where the cursor was before it moved: taken back, but no longer retyped there
        var retype = true

        val shown get() = if (left == length) text else text.substring(0, text.offsetByCodePoints(0, left))
    }

    private val entries = ArrayList<Entry>()

    // write order: deleted entries are retyped in it, however deleted
    private var written = 0L

    private val empty get() = entries.isEmpty() && deleting.isEmpty() && deleted.isEmpty()

    // how many of [entries] are before the cursor
    private var cursor = 0

    // text before and after all [entries] as the host read it (HOST_CHARS each at most); null: unknown, so only a
    // Backspace can show a deletion. [headWhole]: the head reaches the field's start
    private var head: String? = null
    private var headWhole = false
    private var tail: String? = null

    /** Whether the field is as the journal has it, so a Backspace's character is known. */
    var verified = true
        private set

    // text is selected, as the last context told: a commit goes over it
    private var selected = false

    // the last Backspace's: a context right after it may show it deleted a selection instead
    private class Charge(val entry: Entry, val removed: Boolean)

    private var charge: Charge? = null

    // commits deleted whole at the cursor, in write order, each taken back
    private val deleted = ArrayList<Entry>()

    // those deleted whole not yet taken back: see settle
    private val deleting = ArrayList<Entry>()

    private val partlyBefore get() = cursor > 0 && entries[cursor - 1].left < entries[cursor - 1].length

    /** Commits deleted whole where the next commit goes, in write order; none if the cursor is inside one's remains. */
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

    /** Something unseen went into the field. */
    fun unverify() {
        verified = false
    }

    /** Takes back what was deleted whole since the last action, last written first, by [undo]; ones taken back wait to be retyped, and one not taken back ends the run (a commit the record lost is never relearned). */
    fun settle(undo: (Commit) -> Boolean) {
        charge = null
        if (deleting.isEmpty()) return
        deleting.sortByDescending { it.order }
        for (e in deleting) {
            val commit = e.commit
            if (commit == null || !undo(commit)) deleted.removeAll { it.order > e.order } else if (e.retype) {
                // in write order, forward-deleted ones after those before
                val at = deleted.indexOfFirst { it.order > e.order }
                deleted.add(if (at < 0) deleted.size else at, e)
            }
        }
        deleting.clear()
    }

    /** [text] committed at the cursor as [commit], retyping the first [typedAgain] of [pending]; if it ended before the next one did ([goesOn]), those wait for the following commit. */
    fun committed(text: String, commit: Commit?, typedAgain: Int = 0, goesOn: Boolean = false) {
        charge = null
        val waiting = if (partlyBefore) emptyList() else deleted.filter { it.commit != null }
        val rest = if (goesOn && typedAgain < waiting.size) waiting.subList(typedAgain, waiting.size).toList() else emptyList()
        deleted.clear()
        deleted += rest
        if (text.isEmpty()) return
        // over a selection: what it replaced is unknown
        if (selected) clear()
        // written after one's remains: that is the user's word now, not a slip to take back
        if (partlyBefore) entries[cursor - 1].commit = null
        entries.add(cursor++, Entry(text, commit, ++written))
        while (entries.size > KEPT) trim()
    }

    /**
     * The texts of the [n] entries right before the cursor, each whole; null if not so. Unverified too: a passed-on
     * comma leaves the field unseen, and the caller checks the field's text it read instead.
     */
    fun lastWhole(n: Int): List<String>? {
        if (n < 1 || cursor < n || selected) return null
        val last = entries.subList(cursor - n, cursor)
        return if (last.all { it.left == it.length }) last.map { it.text } else null
    }

    /** The [n] entries before the cursor ([lastWhole]) become one of [text] as [commit], the field's text the same; returns their commits. */
    fun merge(n: Int, text: String, commit: Commit?): List<Commit?> {
        charge = null
        val merged = entries.subList(cursor - n, cursor).map { it.commit }
        repeat(n) { entries.removeAt(--cursor) }
        deleted.clear()
        entries.add(cursor++, Entry(text, commit, ++written))
        return merged
    }

    /** [commit], taken back elsewhere, is no longer taken back when its text is deleted. */
    fun forgo(commit: Commit) {
        for (e in entries) if (e.commit === commit) e.commit = null
        deleted.removeAll { it.commit === commit }
    }

    // drop the entry farther from the cursor: with the cursor before all of them, dropping the front would lose the new commit
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
            // the tail as far as the host reads: whole while shorter (follows)
            tail = tail?.let { (entries.last().shown + it).take(Action.Context.HOST_CHARS) }
            entries.removeAt(entries.size - 1)
        }
    }

    /** A Backspace the app took: the character before the cursor goes if known, else all are dropped. Whole deletions are taken back at [settle]. With a selection, the following context returns the charge ([placeUncharged]). */
    fun backspace() {
        charge = null
        if (!verified || cursor == 0) return clear()
        val e = entries[cursor - 1]
        if (!e.countable) {
            // how much the editor takes is its to say: the next context does
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

    /** What the host read around the cursor. [started]: another's field; [cleared]: empty. Else the cursor goes after the commits [before] ends with, with [after] following and any between deleted whole; no such place drops all. */
    fun context(before: String, after: String?, started: Boolean, cleared: Boolean, selected: Boolean, said: Boolean = false) {
        val charge = charge
        this.charge = null
        if (started) return known(before, after)
        if (cleared) return emptied()
        // a selection's sides look like a cut: none yet
        this.selected = selected
        if (selected) return
        // an editor that would not say: nothing is read. One that said empty is an empty field, checked as any text
        if (before.isEmpty() && !said) return unverify()
        if (empty) return known(before, after)
        // as the Backspace left it; else, the sides of a selection that Backspace deleted
        val at = place(before, after) ?: charge?.let { placeUncharged(it, before, after) } ?: return known(before, after)
        val (j, k) = at
        if (j != cursor || k != cursor) {
            // an unverified field may hold unseen text: only where the cursor is
            if (!verified) return known(before, after)
            // the cursor moved: no deletion run continues there, unless this is one
            if (j != cursor) {
                deleted.clear()
                deleting.forEach { it.retype = false }
            }
            for (i in j until k) deleting += entries[i]
            repeat(k - j) { entries.removeAt(j) }
            cursor = j
        }
        // what surrounds the commits, first read when the cursor is after them all
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

    // the field emptied (a chat sending): deleted one by one by Backspace if nothing written is left, else all sent
    private fun emptied() {
        if (entries.isNotEmpty()) clear()
        head = ""
        headWhole = true
        tail = ""
        selected = false
        verified = true
    }

    // all dropped: the field as the host read it is what the next commits go between
    private fun known(before: String, after: String?) {
        clear()
        head = before
        headWhole = before.length < Action.Context.HOST_CHARS
        tail = after
    }

    /** A context the engine gave itself (a held glide word, a looked-up character), which it should have written; else the field is unknown. */
    fun own(before: String) {
        if (!fits(before, cursor, read = false)) unverify()
    }

    // [place] as if [charge]'s Backspace had not happened: kept if it places, else the Backspace stands
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

    // where [before]/[after] put the cursor: after entry j, entries from k on after it (j..k-1 deleted whole). The
    // current place is tried first; without [after], only there and with none deleted
    private fun place(before: String, after: String?): Pair<Int, Int>? {
        if (fits(before, cursor) && (after == null || follows(after, cursor))) return cursor to cursor
        if (after == null || head == null || tail == null) return null
        for (j in entries.size downTo 0) {
            if (!fits(before, j)) continue
            for (k in j..entries.size) if (follows(after, k)) return j to k
        }
        return null
    }

    // whether [before] ends with the head and the entries before [at]. A full host read is all there is: shorter
    // means something written is gone, longer than a whole head means something else is there
    private fun fits(before: String, at: Int, read: Boolean = true): Boolean {
        val written = head.orEmpty() + shownBefore(at)
        if (read && before.length < Action.Context.HOST_CHARS && written.length > before.length) return false
        if (read && headWhole && before.length > written.length) return false
        val n = minOf(before.length, written.length)
        return before.regionMatches(before.length - n, written, written.length - n, n)
    }

    private fun shownBefore(at: Int) = buildString { for (i in 0 until at) append(entries[i].shown) }

    // whether [after] is the entries from [at] then the tail, as far as both were read
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
        /** As many as the record keeps to take back. */
        const val KEPT = LearnLog.UNDOABLE

        /** Whether each code point is its own character to Backspace: none joins (ZWJ), varies (selector, skin tone, tag), combines or pairs (flags). */
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
