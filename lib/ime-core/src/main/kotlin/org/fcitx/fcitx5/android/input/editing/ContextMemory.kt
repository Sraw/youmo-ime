/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

import org.fcitx.fcitx5.android.engine.session.Action

/**
 * The end of what was written in each field, the engine's context where the field has none (a chat's box cleared
 * after sending): kept while [fresh], for the last [apps] fields, in memory only, never of a sensitive field. Kept as
 * read before the cursor plus commits less Backspace, not read back each key (a round trip each).
 */
class ContextMemory(
    private val keep: Int = KEEP,
    private val fresh: Long = FRESH_MILLIS,
    private val apps: Int = APPS,
) {
    private class Written(val text: StringBuilder, var at: Long)

    // in order of use; past `apps`, the first goes
    private val written = object : LinkedHashMap<String, Written>(apps, LOAD, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Written>?) = size > apps
    }
    private var app: String? = null

    /** Null: one whose text is not to be kept. */
    fun focus(app: String?) {
        this.app = app
    }

    /** Else no need to ask if it is empty. */
    fun remembers(now: Long): Boolean = written[app ?: return false]?.let { now - it.at <= fresh } == true

    /** [before] itself, or when empty what was written last, still fresh; [after] asked only then (a round trip). Null: kept for next time. */
    fun context(before: String, now: Long, after: () -> CharSequence?): String {
        val app = app ?: return before
        if (before.isNotEmpty()) {
            remember(app, now).apply { text.setLength(0) }.text.append(before.takeLast(keep))
            return before
        }
        if (remembers(now)) {
            val following = after() ?: return before
            if (following.isEmpty()) return written.getValue(app).text.toString()
        }
        written.remove(app)
        return before
    }

    /** The cursor after its first [cursor] units ([text]'s end for -1). */
    fun committed(text: String, cursor: Int = -1, now: Long) {
        val app = app ?: return
        val typed = if (cursor in 0 until text.length) text.substring(0, cursor) else text
        if (typed.isEmpty()) return
        val last = remember(app, now).text
        last.append(typed)
        if (last.length > keep) last.delete(0, last.length - keep)
    }

    /** Of unknown length: what was kept no longer ends the text. */
    fun cut() {
        written.remove(app ?: return)
    }

    fun deleted() {
        val last = written[app ?: return]?.text ?: return
        if (last.isEmpty()) return
        last.setLength(last.length - Character.charCount(Character.codePointBefore(last, last.length)))
    }

    /** The selection, or the code point before the cursor. */
    fun backspace(start: Int, end: Int) {
        if (start != end) cut() else if (start > 0) deleted()
    }

    private fun remember(app: String, now: Long): Written {
        val last = written.getOrPut(app) { Written(StringBuilder(), now) }
        last.at = now
        return last
    }

    companion object {
        /** As much as the service reads: the context is as long as a field read whole, so English takes it as no field's start. */
        const val KEEP = Action.Context.HOST_CHARS

        /** A message answered within half an hour is still the conversation. */
        const val FRESH_MILLIS = 30 * 60 * 1000L

        const val APPS = 8
        private const val LOAD = 0.75f

        /** What it says of itself tells apart the fields one view holds (Compose, a browser). */
        fun field(app: String?, id: Int, type: Int, name: String?, hint: CharSequence?): String =
            "$app#$id#$type#${name.orEmpty()}#${hint ?: ""}"
    }
}
