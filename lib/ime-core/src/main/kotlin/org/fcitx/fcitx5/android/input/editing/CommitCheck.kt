/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

import org.fcitx.fcitx5.android.core.CoreLog

/**
 * Whether a commit went into the field, keeping what did not for the user to write again ([kept]).
 * A WebView that lost focus with the keyboard still up takes every write and does nothing, telling no one.
 * So a commit has landed when the editor reports the cursor where it was to leave it; else at [judge] the
 * text before the cursor is read again, and unchanged means nothing went in. Anything unclear counts as landed.
 *
 * Asked only of fields that say where their cursor is (one with no Editable reads back empty either way).
 * The read before a commit is a binder round trip to the app's main thread, so it is skipped where a landed
 * commit left the text known ([known]). A field that uses the text and restores its buffer in the same batch
 * (a terminal) would look dropped, so a read right after the commit tells it apart.
 */
class CommitCheck(private val editor: InputEditor) {

    /** False: nothing is read and nothing kept. */
    var on = false
        set(value) {
            field = value
            if (!value) {
                known = null
                ended()
            }
        }

    /** Told as [kept] changes, as a commit starts to wait, and as a wait ends while [toWrite]. */
    var changed: (() -> Unit)? = null

    /** In ms, the service handler's clock ([waitLeft]). */
    var clock: () -> Long = { System.nanoTime() / NANOS_PER_MS }

    var kept = ""
        private set(value) {
            if (field == value) return
            field = value
            changed?.invoke()
        }

    /** Asked to write the kept text while a commit waited: written as that wait ends, to keep the typing order. */
    var toWrite = false

    // commits no report has come after: the text before the cursor before the first, when, what they committed,
    // the cursor they were to leave (-1 if not known) and whether a read right after showed it ([seen])
    private class Waiting(val before: String, var text: String, val since: Long, var at: Int, val seen: Boolean)

    private var waiting: Waiting? = null

    private class Known(val at: Int, val text: String)

    private var known: Known? = null

    // read from the editor, not [known]: the commit after it is read right after too
    private var read = false

    val isWaiting: Boolean get() = waiting != null

    /** Timed from the waiting commit itself: the service's timer may have been set for an earlier one. */
    fun waitLeft(): Long = waiting?.let { (it.since + WAIT_MS - clock()).coerceAtLeast(0) } ?: 0

    /** Null where not checked; not read where it is known (the cursor at [at], nothing composing: [plain]). */
    fun before(text: String, at: Int = -1, plain: Boolean = false): CharSequence? {
        read = false
        if (!on || text.isEmpty() || waiting != null) return null
        known?.let { if (plain && it.at == at) return it.text }
        read = true
        return editor.textBeforeCursor(READ)
    }

    /** [at]: the cursor after a plain insertion, -1 otherwise. A commit while another waits is judged with it. */
    fun committed(text: String, before: CharSequence?, at: Int = -1) {
        if (!on || text.isEmpty()) return
        waiting?.let {
            it.text += text
            it.at = if (it.at >= 0) at else -1
            return
        }
        known = null
        // an editor that would not say what is before the cursor is not one to judge
        val was = before?.toString() ?: return
        // where it was known, the field has shown before that it takes what is written: no read
        val seen = read && editor.textBeforeCursor(READ)?.toString().let { it != null && it != was }
        read = false
        waiting = Waiting(was, text, clock(), at, seen)
        changed?.invoke()
    }

    /** What waited went in, and what was kept before is no longer asked for: the user typed on. */
    fun reported() {
        val landed = waiting ?: return
        // an editor capping the read shorter than asked: not known there, read next time
        val whole = landed.before.length >= minOf(READ, landed.at - landed.text.length)
        known = if (landed.at >= 0 && whole) Known(landed.at, (landed.before + landed.text).takeLast(READ)) else null
        if (!toWrite) kept = ""
        ended()
    }

    /** Something other than this commit changed the field or cursor: what waits is not judged; the next commit waits on its own. */
    fun moved() {
        known = null
        ended()
    }

    private fun ended() {
        waiting = null
        if (toWrite) changed?.invoke()
    }

    /** The wait is up with no report: read again. True when nothing went in, the text now [kept]. */
    fun judge(): Boolean {
        val asked = waiting ?: return false
        waiting = null
        known = null
        // in the field right after the write: used, though the field may since have put its text back as it was
        if (asked.seen) {
            if (!toWrite) kept = ""
            return false
        }
        val now = editor.textBeforeCursor(READ)?.toString()
        // the same text, and not already ending in what was committed (哈 after a row of 哈): nothing went in.
        // Unreadable now: nothing known of this commit
        if (now == null) return false
        if (now != asked.before || asked.before.endsWith(asked.text.takeLast(READ))) {
            if (!toWrite) kept = ""
            return false
        }
        CoreLog.d { "a commit of ${asked.text.length} chars read back not in the field: kept" }
        kept = (kept + asked.text).let { all ->
            var from = maxOf(0, all.length - KEPT_MAX)
            if (from > 0 && Character.isLowSurrogate(all[from])) from++
            all.substring(from)
        }
        return true
    }

    /** No longer kept, unless that commit does not go in either. */
    fun take(): String = kept.also {
        toWrite = false
        kept = ""
    }

    fun forget() {
        waiting = null
        known = null
        toWrite = false
        kept = ""
    }

    companion object {
        const val READ = 32

        const val WAIT_MS = 800L

        /** A few sentences typed into a dead field, while a misjudged one is not given back more than this. */
        const val KEPT_MAX = 256

        private const val NANOS_PER_MS = 1_000_000L
    }
}
