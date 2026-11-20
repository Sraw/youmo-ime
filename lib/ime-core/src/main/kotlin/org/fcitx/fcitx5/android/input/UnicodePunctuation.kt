/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

/** Unicode's punctuation categories (`，` `“` `-` `%`, not `+`). Shared by AutoPairs and VoiceRerank; a rule of one goes in that one. */
object UnicodePunctuation {

    private val CATEGORIES = setOf(
        Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
        Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
        Character.OTHER_PUNCTUATION,
    )

    operator fun contains(c: Char): Boolean = Character.getType(c).toByte() in CATEGORIES
}
