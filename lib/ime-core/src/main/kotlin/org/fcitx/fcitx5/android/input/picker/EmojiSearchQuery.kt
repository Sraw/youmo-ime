/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

/**
 * The pinyin typed in the emoji panel's search, and where its keys go: the letters build it; a
 * backspace takes its last letter, or with none typed deletes in the field as elsewhere, but not
 * one held on from the query's letters, which would go on into the user's text. ⌫'s slots' 清空 and 恢复
 * are the query's too ([clearAll], [restore]; 060-keys-unify J1): it is what the user is editing.
 */
class EmojiSearchQuery {

    enum class Outcome {
        /** the query changed: search again */
        CHANGED,

        /** nothing to do */
        SWALLOWED,

        /** the key is the field's, as with the keyboard */
        PASSED,
    }

    private val typed = StringBuilder()

    val text: String get() = typed.toString()

    // what the last clearAll took, for one restore
    private var cleared: String? = null

    /** The search opened or closed: nothing typed, nothing to restore. */
    fun clear() {
        typed.clear()
        cleared = null
    }

    /** 清空: the query emptied, kept for [restore]; with nothing typed, nothing (the field is not the query). */
    fun clearAll(): Outcome {
        if (typed.isEmpty()) return Outcome.SWALLOWED
        cleared = typed.toString()
        typed.clear()
        return Outcome.CHANGED
    }

    /** 恢复: the query the last [clearAll] took, once, in place of what is typed now; none, nothing. */
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

    /** A backspace, [repeat] one of a held key's. */
    fun backspace(repeat: Boolean): Outcome = when {
        typed.isNotEmpty() -> {
            typed.deleteCharAt(typed.length - 1)
            Outcome.CHANGED
        }
        repeat -> Outcome.SWALLOWED
        else -> Outcome.PASSED
    }
}
