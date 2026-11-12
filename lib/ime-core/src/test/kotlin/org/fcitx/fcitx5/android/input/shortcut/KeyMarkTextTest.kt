/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.shortcut

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyMarkTextTest {
    // a text mark's step down where it has no room: its first character (a whole grapheme) and 「…」; none where it is one already
    @Test
    fun shorterIsTheFirstCharacterAndAnEllipsis() {
        val rows = listOf(
            // one character: nothing shorter to draw
            "a" to null,
            // two: the first and 「…」
            "ab" to "a…",
            // longer, as of() cut it to two already: still the first
            "abc" to "a…",
            // a letter and its combining accent are one character, never cut apart
            "é" to null,
            "éx" to "é…",
            // of() already short to one and 「…」: the same, so null
            "a…" to null,
        )
        for ((text, want) in rows) assertEquals(text, want, KeyMarkText.shorter(text))
    }
}
