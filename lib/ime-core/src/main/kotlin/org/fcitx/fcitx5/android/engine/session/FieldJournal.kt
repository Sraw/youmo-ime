/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.learn.Habits
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Commit

/**
 * A session's [CommitJournal] as the learning record has it (U-03): pinyin's, handwriting's and a
 * table's, one copy (F2655). Dropped once the record of [habits] was rewritten (review-design-1c
 * M2; LearnLog.rewritten) or learning stopped; what was deleted whole taken back there, the text
 * the host reads compared as the commits were written ([simplify], review-design-1c S9). [cleared]:
 * told each time it drops all, for what a session keeps beside it (handwriting's candidates).
 */
internal class FieldJournal(
    private val habits: () -> Habits,
    private val simplify: (String) -> String,
    private val cleared: () -> Unit = {},
) {
    val journal = CommitJournal()

    // the writing of the record the journal goes with: another, and what it holds may be no longer the record's
    private var rewrites = 0L

    // the journal as of the record: dropped once the record was rewritten, or learning stopped
    private fun now(): CommitJournal {
        val h = habits()
        val now = h.rewrites
        if (now != rewrites || !h.learns) clear()
        rewrites = now
        return journal
    }

    fun clear() {
        journal.clear()
        cleared()
    }

    /** The commits deleted whole where the next commit goes: see CommitJournal.pending. */
    val pending: List<Commit> get() = now().pending

    /** What the journal deleted whole, taken back now: an action came that does not say otherwise (CommitJournal.settle). */
    fun settle() = now().settle(::undo)

    /**
     * What [action] tells before the session acts on it: a context checks the field; anything else
     * settles what was deleted, a Reset (another field, a panel) leaving the field unknown till a context says.
     */
    fun before(action: Action, learning: Boolean) {
        if (action is Action.Context) return told(action, learning)
        settle()
        if (action == Action.Reset) unverify()
    }

    /** A Backspace the app took, nothing typed: see CommitJournal.backspace. */
    fun backspace() = now().backspace()

    /** Something went into the field this session did not write. */
    fun unverify() = journal.unverify()

    /** A key went to the app: what was deleted stands, and what the key does there is the app's (U-03). */
    fun passedOn() {
        settle()
        unverify()
    }

    /**
     * [c] told the session: the host's checks what was written; the engine's own (null flags) is what it wrote (review-design-1c S10).
     * Where nothing is [learning] (a password) no text of the field is kept, as none committed is.
     */
    fun told(c: Action.Context, learning: Boolean) = when {
        !learning -> clear()
        c.learnFlags == null -> ownContext(c.before)
        // a field cleared as it is, empty, not the text remembered of it (review-1c S1)
        else -> now().context(if (c.cleared) "" else simplify(c.before), c.after?.let(simplify), c.started, c.cleared, c.selected, c.said)
    }

    /** A context the session's engine gave itself, [before] the cursor: see CommitJournal.own. */
    fun ownContext(before: String) = now().own(simplify(before))

    /** [text] committed by a session over this one (a character looked up), as [commit]: kept but where nothing is [learning]. */
    fun followed(text: String, learning: Boolean, commit: Commit? = null) {
        settle()
        if (learning) now().committed(simplify(text), commit) else clear()
    }

    /**
     * [text] committed, as [commit]; [again] what it typed again of [pending] (see CommitJournal.committed).
     * Where nothing is [learning] there is nothing to take back, and no text to keep. Written as the
     * host's text is compared, [simplify]d (a traditional table's).
     */
    fun committed(text: String, commit: Commit?, learning: Boolean, again: Retype.Match? = null) {
        if (learning) journal.committed(simplify(text), commit, again?.typedAgain ?: 0, again?.goesOn == true) else clear()
    }

    /** [text] the session wrote on its own (a glide's word it held back), as [commit]: in the journal as any commit. */
    fun own(text: String, commit: Commit?) = now().committed(simplify(text), commit)

    // a fault of the record is learning's, never the commit's (see Habits.failed)
    private fun undo(c: Commit): Boolean {
        val h = habits()
        if (!h.learns) return false
        return try {
            h.undo(c)
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            h.failed(e)
            false
        }
    }
}
