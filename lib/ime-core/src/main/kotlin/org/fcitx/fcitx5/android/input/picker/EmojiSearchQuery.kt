/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

/**
 * The pinyin typed in the emoji panel's search, and where its keys go: the letters build it; a
 * backspace takes its last letter, or with none typed deletes in the field as elsewhere, but not
 * one held on from the query's letters, which would go on into the user's text.
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

    fun clear() {
        typed.clear()
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
