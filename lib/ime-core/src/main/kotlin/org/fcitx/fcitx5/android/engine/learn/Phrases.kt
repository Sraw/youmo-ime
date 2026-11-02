/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn

/**
 * The whole phrases 联想 learns (U-73): a commit's whole text, of letters alone (Han, Latin), of
 * [MIN] to [MAX] of them. Digits, punctuation, emoji and spaces keep a phone number, an address or a
 * code out; longer text is split by its punctuation into commits of its own.
 */
object Phrases {
    const val MIN = 3

    /** D-C1: a chat's whole sentences fit, and the bar has room for one. */
    const val MAX = 12

    fun learnable(text: String): Boolean {
        val n = text.codePointCount(0, text.length)
        if (n !in MIN..MAX) return false
        var i = 0
        while (i < text.length) {
            val c = text.codePointAt(i)
            if (!Character.isLetter(c)) return false
            i += Character.charCount(c)
        }
        return true
    }
}
