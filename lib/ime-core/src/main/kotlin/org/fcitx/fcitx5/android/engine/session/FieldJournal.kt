/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.learn.Habits
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Commit

/** A session's [CommitJournal] tied to the learning record (pinyin's, handwriting's and tables', one copy): dropped once [habits]' record was rewritten or learning stopped; host text compared [simplify]d. [cleared] is told whenever it drops all. */
internal class FieldJournal(
    private val habits: () -> Habits,
    private val simplify: (String) -> String,
    private val cleared: () -> Unit = {},
) {
    val journal = CommitJournal()

    // the record writing the journal goes with: another means its entries may no longer be the record's
    private var rewrites = 0L

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

    /** See CommitJournal.pending. */
    val pending: List<Commit> get() = now().pending

    /** See CommitJournal.settle. */
    fun settle() = now().settle(::undo)

    /** Before the session acts: a context checks the field; anything else settles deletions, a Reset leaving the field unknown till a context. */
    fun before(action: Action, learning: Boolean) {
        if (action is Action.Context) return told(action, learning)
        settle()
        if (action == Action.Reset) unverify()
    }

    /** See CommitJournal.backspace. */
    fun backspace() = now().backspace()

    /** Something this session did not write went into the field. */
    fun unverify() = journal.unverify()

    /** A key went to the app: deletions stand, and what the key does is the app's. */
    fun passedOn() {
        settle()
        unverify()
    }

    /** The host's context checks what was written; the engine's own (null flags) is what it wrote. Without [learning] (a password) no field text is kept. */
    fun told(c: Action.Context, learning: Boolean) = when {
        !learning -> clear()
        c.learnFlags == null -> ownContext(c.before)
        // a cleared field is empty, not the remembered text
        else -> now().context(if (c.cleared) "" else simplify(c.before), c.after?.let(simplify), c.started, c.cleared, c.selected, c.said)
    }

    /** See CommitJournal.own. */
    fun ownContext(before: String) = now().own(simplify(before))

    /** Committed by a session over this one (a looked-up character); kept only when [learning]. */
    fun followed(text: String, learning: Boolean, commit: Commit? = null) {
        settle()
        if (learning) now().committed(simplify(text), commit) else clear()
    }

    /** [again]: what it retyped of [pending]. Without [learning] nothing to take back and no text kept. Written [simplify]d, as host text is compared. */
    fun committed(text: String, commit: Commit?, learning: Boolean, again: Retype.Match? = null) {
        if (learning) journal.committed(simplify(text), commit, again?.typedAgain ?: 0, again?.goesOn == true) else clear()
    }

    /** Written by the session on its own (a held glide word): journaled as any commit. */
    fun own(text: String, commit: Commit?) = now().committed(simplify(text), commit)

    // a record fault is learning's, never the commit's (Habits.failed)
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
