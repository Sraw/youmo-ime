/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui

import android.graphics.Paint
import org.fcitx.fcitx5.android.data.pinyin.RareCharsManager
import org.fcitx.fcitx5.android.ui.rare.RareSamples
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The group cards' picked characters (dev/design/061-settings-cards.md §3.1), on a phone: each one its
 * group's by the APK's table, and drawn by the phone's fonts (on youmo and youmo29 both, API 36 and 29).
 * One not drawn would be left out of its card, a plain character in its place; one of another group
 * would show a group what it does not hold.
 */
class RareSamplesGlyphTest {

    @Test
    fun eachPickedOneIsItsGroupsByTheTable() {
        val levels = requireNotNull(RareCharsManager.levels) { "no levels table in the APK" }
        val wrong = RareSamples.PICKED.flatMap { (level, picked) ->
            picked.mapNotNull { c -> levels.of(c.codePointAt(0)).takeIf { it != level }?.let { "$c: $it, not $level" } }
        }
        assertEquals(emptyList<String>(), wrong)
    }

    @Test
    fun eachPickedOneIsDrawn() {
        val paint = Paint()
        assertEquals(emptyList<String>(), RareSamples.PICKED.values.flatten().filterNot { paint.hasGlyph(it) })
    }
}
