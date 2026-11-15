/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

import org.fcitx.fcitx5.android.core.CoreLog

/**
 * Whether what was committed went into the field, and the text that did not, kept for the user to
 * write again ([kept]; specs/pitfalls.md, "A field that takes a commit and drops it").
 *
 * An editor can stay the served one, take every write and do nothing with it: a WebView whose view
 * lost the focus while the keyboard stayed up (the screen turned with the keyboard over most of the
 * page) answers reads and reports nothing, and the keyboard is told nothing either. So a commit is
 * [committed] here with the text before the cursor read just before it, and it has landed when the
 * editor reports the cursor where it was to leave it ([reported]). With no such report by the time the service's wait is up
 * ([judge]), the text before the cursor is read again: the same as before, and not already ending in
 * what was committed, nothing went in. Anything else is taken as landed: an editor that will not be
 * read, one that changed the text on its way in (upper-cased, cut short), one that never reports.
 *
 * Only asked of a field that says where its cursor is ([EditingSession.commitText]; and [on]: the
 * service leaves out passwords and fields of no type): an editor of no text of its own (a terminal,
 * a game: `BaseInputConnection` with no `Editable`) reads back empty before and after and would look
 * the same. A field that refuses the text (full to its length, digits only) and reports nothing is
 * said not to have taken it, which is so.
 *
 * The read before a commit is a binder round trip that waits for the app's main thread (7 ms at the
 * median on a phone, a page busy with its own script; dev/design/061-lc24-commit-read.md). So it is
 * not made where the text before the cursor is known: a commit that landed where it was to leave the
 * cursor ([reported]) leaves what was read before it and what it committed ([known]), and the next
 * commit made at that cursor, with nothing composing, takes that instead. Anything else done to the
 * field ([moved], [forget]) and a commit that is not a plain insertion at the cursor forget it.
 */
class CommitCheck(private val editor: InputEditor) {

    /** Whether commits in this field are checked; false, nothing is read and nothing kept. */
    var on = false
        set(value) {
            field = value
            if (!value) {
                known = null
                ended()
            }
        }

    /**
     * Told as [kept] changes, as a commit starts to wait ([waiting] true), and as a wait ends while [toWrite]:
     * the service shows the first, times the second and writes what is kept at the third.
     */
    var changed: (() -> Unit)? = null

    /** The clock a wait is timed by, in ms: the service's own, its handler's ([waitLeft]). */
    var clock: () -> Long = { System.nanoTime() / NANOS_PER_MS }

    /** What did not go in, in the order it was committed; empty when all did. */
    var kept = ""
        private set(value) {
            if (field == value) return
            field = value
            changed?.invoke()
        }

    /**
     * The user asked for what is kept to be written while a commit waited (written then, it would have
     * joined that commit and been judged after it, out of the order typed): it is written as that wait ends, and is not let go meanwhile by
     * that commit landing. Cleared by [take] and [forget]; the service clears it where it will not write.
     */
    var toWrite = false

    // commits no report has come after: the text before the cursor read before the first, when ([clock]),
    // and what they committed since
    // and the cursor they were to leave, where the text before it is then known ([known]); -1 where not
    private class Waiting(val before: String, var text: String, val since: Long, var at: Int)

    private var waiting: Waiting? = null

    // the text before the cursor at [at], as the last commit that landed there left it; null where unknown
    private class Known(val at: Int, val text: String)

    private var known: Known? = null

    /** Whether a commit waits for the editor's report: the service calls [judge] once its wait is up. */
    val isWaiting: Boolean get() = waiting != null

    /**
     * How much longer, in ms, the commit waiting has to wait for its report ([WAIT_MS] from its own
     * commit); 0 with its wait up or nothing waiting. The service's timer may have been set for one
     * before it (that one reported, or another field's forgotten): a commit just made is not judged
     * at that one's time, while an editor whose reads lag a frame has not shown it yet.
     */
    fun waitLeft(): Long = waiting?.let { (it.since + WAIT_MS - clock()).coerceAtLeast(0) } ?: 0

    /**
     * The text before the cursor before a commit of [text], for [committed]; null where not checked.
     * Not read where it is known: the cursor at [at], where the last commit that landed left it, and
     * nothing composing in the field ([plain]).
     */
    fun before(text: String, at: Int = -1, plain: Boolean = false): CharSequence? {
        if (!on || text.isEmpty() || waiting != null) return null
        known?.let { if (plain && it.at == at) return it.text }
        return editor.textBeforeCursor(READ)
    }

    /**
     * [text] was committed, [before] read before it ([before]'s answer), to leave the cursor at [at]
     * after it: a plain insertion at the cursor, nothing composing replaced; -1 for any other. A
     * commit while another waits is judged with it, from the first one's read.
     */
    fun committed(text: String, before: CharSequence?, at: Int = -1) {
        if (!on || text.isEmpty()) return
        waiting?.let {
            it.text += text
            it.at = if (it.at >= 0) at else -1
            return
        }
        known = null
        // an editor that would not say what is before the cursor is not one to judge
        waiting = Waiting(before?.toString() ?: return, text, clock(), at)
        changed?.invoke()
    }

    /**
     * The editor reported the cursor where the session's last write was to leave it: what waited went
     * in, and what was kept before is no longer asked for, the user having typed on.
     */
    fun reported() {
        val landed = waiting ?: return
        // an editor that gives less before the cursor than asked (a cap of its own) reads back shorter than
        // that would be, and a commit dropped would not read the same: not known there, read
        val whole = landed.before.length >= minOf(READ, landed.at - landed.text.length)
        known = if (landed.at >= 0 && whole) Known(landed.at, (landed.before + landed.text).takeLast(READ)) else null
        if (!toWrite) kept = ""
        ended()
    }

    /**
     * The editor reported a cursor that is not the last write's (the field tapped, the app's own change),
     * or something other than a commit was done to the field while one waits (a deletion, a key sent, the
     * editor's action, the cursor set, input started anew in it): the text before the cursor is another
     * now, and a read there tells nothing of what waits: that is not judged. What was kept before stays,
     * and the next commit waits on its own, read before it and timed from it. The service is told again
     * of a write asked for and not done yet ([toWrite]: input was not started when its post ran).
     */
    fun moved() {
        known = null
        ended()
    }

    // nothing waits any more: what the user asked to be written is written now (the service, told)
    private fun ended() {
        waiting = null
        if (toWrite) changed?.invoke()
    }

    /** The wait is up with no report: read again. True when nothing went in, the text now [kept]. */
    fun judge(): Boolean {
        val asked = waiting ?: return false
        waiting = null
        known = null
        val now = editor.textBeforeCursor(READ)?.toString()
        // the same text before the cursor, and not what was committed already there (a word finished
        // in place, 哈 after a row of 哈): nothing went in
        // an editor that will not be read now: nothing known of this commit, what was kept before stays
        if (now == null) return false
        if (now != asked.before || asked.before.endsWith(asked.text.takeLast(READ))) {
            if (!toWrite) kept = ""
            return false
        }
        CoreLog.d { "a commit of ${asked.text.length} chars read back not in the field: kept" }
        kept += asked.text
        return true
    }

    /** What is kept, to be committed again: no longer kept, unless that commit does not go in either. */
    fun take(): String = kept.also {
        toWrite = false
        kept = ""
    }

    /** Another field, or the keyboard put away: nothing waits and nothing is kept. */
    fun forget() {
        waiting = null
        known = null
        toWrite = false
        kept = ""
    }

    companion object {
        /** UTF-16 units read before the cursor, before a commit and after. */
        const val READ = 32

        /** How long the service waits for the editor's report before [judge], in ms. */
        const val WAIT_MS = 800L

        private const val NANOS_PER_MS = 1_000_000L
    }
}
