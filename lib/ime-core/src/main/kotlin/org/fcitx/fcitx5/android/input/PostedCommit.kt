/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import org.fcitx.fcitx5.android.input.cursor.CursorTracker

/**
 * Text the app puts in over the engine (a paste, a long-press word, 九键's 1 again), asked for as a key is pressed
 * and put in once fcitx has committed: by then input may be in another field. [text] replaces [replacing] before
 * the cursor, if there.
 */
class PostedCommit(private val askedIn: Any?, private val replacing: String, internal val text: String) {

    private var committedAt: Int? = null

    /** The field it was asked for in, restarted or not (the screen turned); not another, nor none. */
    fun lands(now: Any?): Boolean = askedIn != null && askedIn === now

    /** [cursor] predicting where it left the cursor: what is asked next may take its place. */
    fun committed(cursor: CursorTracker) {
        committedAt = cursor.changes
    }

    /**
     * Null: not there, refused, or the cursor moved. Read to know, deleted in code points as fcitx counts. A [sensitive]
     * field is not read: it is there if [last] put it and the editor since reported just what [cursor] predicted, nothing
     * [composing]. Not reported yet, [replacing] stays: 1 pressed again before the first ， reported types ，。.
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

    /** [inCodePoints] false: UTF-16 units. */
    data class Deletion(val count: Int, val inCodePoints: Boolean)
}

/** [PostedCommit.takeBack]'s `last`: [putIn] takes back, commits, records, in that order; forgotten with its field. */
class LastPosted {

    /** The service's. */
    interface Field {
        /** Before the cursor is not read. */
        val sensitive: Boolean

        val composing: Boolean

        fun textBefore(length: Int): CharSequence?

        fun delete(deletion: PostedCommit.Deletion)

        fun commit(text: String)
    }

    private var last: PostedCommit? = null

    fun putIn(asked: PostedCommit, field: Field, cursor: CursorTracker) {
        asked.takeBack(field.sensitive, field.composing, cursor, last, field::textBefore)?.let(field::delete)
        field.commit(asked.text)
        asked.committed(cursor)
        last = asked
    }

    /** A paste into a password maybe: not kept past its field. */
    fun forget() {
        last = null
    }
}
