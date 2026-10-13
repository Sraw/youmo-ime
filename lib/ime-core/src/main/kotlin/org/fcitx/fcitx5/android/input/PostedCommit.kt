/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import org.fcitx.fcitx5.android.input.cursor.CursorTracker

/**
 * Text the app puts in itself over the engine's input (the service's commitOverEngine: a paste, a
 * word from a long press, 九键's 1 pressed again), asked for as a key is pressed and put in once
 * fcitx has committed what it had: by then input may be in another field, and the editor may have
 * reported its cursor. Asked for in [askedIn], the EditorInfo input had last started with then:
 * [text], in place of [replacing] before the cursor, if anything.
 *
 * The service keeps the field and reads the editor; what that allows is decided here, and the order it goes in
 * ([LastPosted]), so each case is tested without an editor.
 */
class PostedCommit(private val askedIn: Any?, private val replacing: String, internal val text: String) {

    // the cursor's [CursorTracker.changes] as [text] went in, null till it did
    private var committedAt: Int? = null

    /**
     * Whether it goes in now that input last started with [now]: the field it was asked for in,
     * restarted with or not (the screen turned); not another, nor none once input finished, nor
     * anything where it was asked for with no field started.
     */
    fun lands(now: Any?): Boolean = askedIn != null && askedIn === now

    /** [text] went in, [cursor] predicting where it left the cursor: what is asked next may take its place. */
    fun committed(cursor: CursorTracker) {
        committedAt = cursor.changes
    }

    /**
     * What to delete before the cursor to take [replacing] back, null for nothing: it is not there,
     * the field refused it or the cursor moved. Read to know ([textBefore]), and deleted in code
     * points, as fcitx counts. In a [sensitive] field nothing is read: it is there if it is the text
     * put in [last], and since then the editor reported all that [cursor] predicted
     * ([CursorTracker.settled]) and nothing else changed it ([CursorTracker.changes]: no report none
     * expected, no edit of the IME's own), nothing [composing]; its units, known, are deleted. When
     * it was asked does not count: fcitx busy, 1 is pressed again before the first ， went in. Not
     * reported yet, [replacing] stays: 1 pressed again before the editor reported the first types
     * both, ，。, and a third time before it reported the second, 。？.
     */
    fun takeBack(sensitive: Boolean, composing: Boolean, cursor: CursorTracker, last: PostedCommit?, textBefore: (Int) -> CharSequence?): Deletion? {
        if (replacing.isEmpty()) return null
        if (!sensitive) {
            if (textBefore(replacing.length)?.toString() != replacing) return null
            return Deletion(replacing.codePointCount(0, replacing.length), inCodePoints = true)
        }
        if (composing || last == null || !cursor.settled) return null
        if (last.text != replacing || last.committedAt != cursor.changes) return null
        return Deletion(replacing.length, inCodePoints = false)
    }

    /** [count] characters before the cursor: code points, or ([inCodePoints] false) UTF-16 units. */
    data class Deletion(val count: Int, val inCodePoints: Boolean)
}

/**
 * What the service put in over the engine last, in the field input started with: [PostedCommit.takeBack]'s
 * `last`. Each [PostedCommit] goes in by [putIn], which takes back what it replaces, commits it and records it,
 * in that order; forgotten with its field ([forget]).
 */
class LastPosted {

    /** The editor as [putIn] puts text in it: the service's. */
    interface Field {
        /** A password, or a field that asks not to be learned from: what is before the cursor is not read. */
        val sensitive: Boolean

        /** Whether something is composed there. */
        val composing: Boolean

        fun textBefore(length: Int): CharSequence?

        fun delete(deletion: PostedCommit.Deletion)

        fun commit(text: String)
    }

    private var last: PostedCommit? = null

    /**
     * [asked] put in [field]: what it replaces taken back ([PostedCommit.takeBack], told what was put in last),
     * its text committed, [cursor] predicting where that left the cursor; then it is what was put in last.
     */
    fun putIn(asked: PostedCommit, field: Field, cursor: CursorTracker) {
        asked.takeBack(field.sensitive, field.composing, cursor, last, field::textBefore)?.let(field::delete)
        field.commit(asked.text)
        asked.committed(cursor)
        last = asked
    }

    /** Input started anew or finished: what went in, a paste into a password maybe, is not kept past its field. */
    fun forget() {
        last = null
    }
}
