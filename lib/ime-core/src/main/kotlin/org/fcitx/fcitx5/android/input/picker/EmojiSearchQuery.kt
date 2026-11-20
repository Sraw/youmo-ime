/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

/** The emoji search's pinyin: letters build it; backspace takes a letter, or with none deletes in the field, but not as a repeat of the query's held key. 清空 and 恢复 act on the query. */
class EmojiSearchQuery {

    enum class Outcome {
        /** Search again. */
        CHANGED,

        SWALLOWED,

        /** As with the keyboard. */
        PASSED,
    }

    private val typed = StringBuilder()

    val text: String get() = typed.toString()

    // what the last clearAll took, for one restore
    private var cleared: String? = null

    /** Nothing typed, nothing to restore. */
    fun clear() {
        typed.clear()
        cleared = null
    }

    /** With nothing typed, nothing (the field is not the query). */
    fun clearAll(): Outcome {
        if (typed.isEmpty()) return Outcome.SWALLOWED
        cleared = typed.toString()
        typed.clear()
        return Outcome.CHANGED
    }

    /** Once, in place of what is typed now. */
    fun restore(): Outcome {
        val back = cleared ?: return Outcome.SWALLOWED
        cleared = null
        typed.clear()
        typed.append(back)
        return Outcome.CHANGED
    }

    fun type(letters: String): Outcome {
        typed.append(letters)
        return Outcome.CHANGED
    }

    /** [repeat]: one of a held key's. */
    fun backspace(repeat: Boolean): Outcome = when {
        typed.isNotEmpty() -> {
            typed.deleteCharAt(typed.length - 1)
            Outcome.CHANGED
        }
        repeat -> Outcome.SWALLOWED
        else -> Outcome.PASSED
    }
}
