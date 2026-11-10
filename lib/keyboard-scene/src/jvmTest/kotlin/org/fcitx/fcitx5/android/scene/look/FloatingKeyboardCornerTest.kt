/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.look

import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.engine.WindowLooks
import org.fcitx.fcitx5.android.scene.look.LookThemeTest.Companion.palette
import org.fcitx.fcitx5.android.scene.look.LookThemeTest.Companion.themeFile
import org.fcitx.fcitx5.android.scene.theme.Cascades
import org.fcitx.fcitx5.android.scene.theme.Selector
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import org.fcitx.fcitx5.android.scene.theme.Value
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A theme 3.0's floating keyboard as round as the window's frame round it (WindowLooks.cornerDp): flat
 * or not, upright and turned, square keys square (R20 F5939, F6573).
 */
class FloatingKeyboardCornerTest {

    private val base = LookDefaults.look(palette(themeFile("material-light.xml")))

    @Test
    fun theFloatingKeyboardIsAsRoundAsItsFrame() {
        val radii = listOf(0, 1, 7, Keys.MAX_RADIUS_DP)
        val gaps = listOf(Gap(3, 7, 3, 4), Gap(0, 0, 0, 0), Gap(5, 2, 9, 1), Gap(24, 24, 24, 24))
        for (r in radii) for (gap in gaps) for (blocks in listOf(true, false)) {
            val xml = LookTheme.xml(base.copy(keys = base.keys.copy(radiusDp = r, gap = gap, blocks = blocks)), "T", false)
            val theme = when (val t = ThemeReader.theme(xml, maxStyles = LookTheme.MAX_STYLES)) {
                is Parsed.Ok -> t.value
                is Parsed.Failed -> error("${t.problems}\n$xml")
            }
            val cascades = Cascades(theme)
            for (landscape in listOf(false, true)) {
                val scenes = buildSet {
                    add("floating")
                    if (landscape) add("landscape")
                    if (!blocks) add("flat")
                }
                val keyboard = (cascades.of(scenes).of(Selector.Target("keyboard"))["radius"] as Value.OfRadius).radius
                // up to the most a theme's radius is read as (the frame, the app's outline, has no such limit)
                val frame = minOf(WindowLooks(cascades).frame(scenes).radiusDp, Keys.MAX_RADIUS_DP.toDouble())
                assertEquals(frame, keyboard.corners.max(), "r=$r $gap blocks=$blocks $scenes")
            }
        }
    }
}
