/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

/**
 * The end of what was written in each field, for the engine's context where the field has none: a
 * chat clears its box once a message is sent, and the reply typed next follows it more than it
 * follows nothing. Kept as long as it is [fresh], for the last [apps] fields, in memory only; the
 * service tells it nothing of a password or other sensitive field, and names a field by its app
 * and id, so a browser's sites do not share one.
 *
 * The text is what the editor showed before the cursor, the last time it was read, with what was
 * committed since and less what Backspace took: not read back each time, which would cost a round
 * trip a key.
 */
class ContextMemory(
    private val keep: Int = KEEP,
    private val fresh: Long = FRESH_MILLIS,
    private val apps: Int = APPS,
) {
    private class Written(val text: StringBuilder, var at: Long)

    // the app last written in last
    private val written = LinkedHashMap<String, Written>(apps, LOAD, true)
    private var app: String? = null

    /** The field [app] focused; null: one whose text is not to be kept. */
    fun focus(app: String?) {
        this.app = app
    }

    /** Whether there is anything fresh at [now] for the field focused: else no need to ask if it is empty. */
    fun remembers(now: Long): Boolean = written[app ?: return false]?.let { now - it.at <= fresh } == true

    /**
     * The context for the engine where the editor shows [before] before the cursor at [now]:
     * itself, or, when the field is [empty], what was written in it last, still fresh. Null: the
     * editor would not say, so what is kept stays for the next time.
     */
    fun context(before: String, empty: Boolean?, now: Long): String {
        val app = app ?: return before
        if (before.isNotEmpty()) {
            remember(app, now).apply { text.setLength(0) }.text.append(before.takeLast(keep))
            return before
        }
        if (empty == null) return before
        val last = written[app]
        if (!empty || last == null || now - last.at > fresh) {
            written.remove(app)
            return before
        }
        return last.text.toString()
    }

    /** [text] committed at [now], the cursor left after its first [cursor] units ([text]'s end for -1). */
    fun committed(text: String, cursor: Int = -1, now: Long) {
        val app = app ?: return
        val typed = if (cursor in 0 until text.length) text.substring(0, cursor) else text
        if (typed.isEmpty()) return
        val last = remember(app, now).text
        last.append(typed)
        if (last.length > keep) last.delete(0, last.length - keep)
    }

    /** A selection was deleted, of what length is not known: what was kept no longer ends the text. */
    fun cut() {
        written.remove(app ?: return)
    }

    /** Backspace took the code point before the cursor. */
    fun deleted() {
        val last = written[app ?: return]?.text ?: return
        if (last.isEmpty()) return
        last.setLength(last.length - Character.charCount(Character.codePointBefore(last, last.length)))
    }

    private fun remember(app: String, now: Long): Written {
        val last = written.getOrPut(app) { Written(StringBuilder(), now) }
        last.at = now
        if (written.size > apps) written.remove(written.keys.first())
        return last
    }

    companion object {
        /** As much as the service reads before the cursor. */
        const val KEEP = 64

        /** A message answered within half an hour is still the conversation. */
        const val FRESH_MILLIS = 30 * 60 * 1000L

        const val APPS = 8
        private const val LOAD = 0.75f
    }
}
