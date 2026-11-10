/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.look

import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.engine.WindowLooks
import org.fcitx.fcitx5.android.scene.look.LookThemeTest.Companion.palette
import org.fcitx.fcitx5.android.scene.look.LookThemeTest.Companion.themeFile
import org.fcitx.fcitx5.android.scene.theme.Argb
import org.fcitx.fcitx5.android.scene.theme.Cascades
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 060-render-p3 T7a: a theme 3.0's window drawn by the engine's default rules ([WindowLooks]) as the app
 * worked it out before, from the look's keys: the floating keyboard's corners ime-core's
 * `FloatingKeyboard.cornerDp`'s ([cornerDp], as 0.5.2 had it), the handle's strip a shade of the bar,
 * its grip the function keys' text colour. On the JVM alone: the old shade was of Java's float
 * arithmetic, which JS has none of (its floats are doubles), and a tie rounds the other way there.
 */
class WindowDefaultsTest {

    private val base = LookDefaults.look(palette(themeFile("material-light.xml")))

    private fun looks(look: ThemeLook, dark: Boolean = false): WindowLooks {
        val xml = LookTheme.xml(look, "T", dark)
        val theme = when (val t = ThemeReader.theme(xml, maxStyles = LookTheme.MAX_STYLES)) {
            is Parsed.Ok -> t.value
            is Parsed.Failed -> error("${t.problems}\n$xml")
        }
        return WindowLooks(Cascades(theme))
    }

    // the frame's radius is of the keys' alone, the keyboard's width not in it: at every radius and margin, flat
    // or not, upright and turned (the margins of the screen turned theirs), compact or not (the rows' margin
    // there Gap.vCompact, as the keys' @compact insets have it)
    @Test
    fun theFloatingCornersAreCornerDps() {
        val radii = listOf(0, 1, 4, 7, 12, Keys.MAX_RADIUS_DP)
        val gaps = listOf(Gap(3, 7, 3, 4), Gap(0, 0, 0, 0), Gap(5, 2, 9, 1), Gap(1, 9, 6, 0), Gap(24, 24, 24, 24), Gap(0, 24, 24, 0))
        for (r in radii) for (gap in gaps) for (blocks in listOf(true, false)) upAndTurned(r, gap, blocks)
    }

    // the keys [r] round, [gap] apart, [blocks]: the frame cornerDp's upright and turned
    private fun upAndTurned(r: Int, gap: Gap, blocks: Boolean) {
        val l = looks(base.copy(keys = base.keys.copy(radiusDp = r, gap = gap, blocks = blocks)))
        for (landscape in listOf(false, true)) for (compact in listOf(false, true)) {
            val scenes = buildSet {
                add("floating")
                if (landscape) add("landscape")
                if (compact) add("compact")
                if (!blocks) add("flat")
            }
            val h = if (landscape) gap.hLand else gap.h
            val v = when {
                landscape && compact -> gap.vLandCompact
                landscape -> gap.vLand
                compact -> gap.vCompact
                else -> gap.v
            }
            assertEquals(cornerDp(r, h, v, blocks).toDouble(), l.frame(scenes).radiusDp, "r=$r $gap blocks=$blocks $scenes")
        }
    }

    @Test
    fun theRestOfTheFrameIsTheAppsOfBefore() {
        val f = looks(base).frame(emptySet())
        // FloatingCorners' elevation, the system's shadow, no border; a corner of one radius
        assertEquals(WindowLooks.ELEVATION_DP, f.elevationDp)
        assertNull(f.shadowColor)
        assertNull(f.border)
        assertEquals(false, f.round)
    }

    @Test
    fun theHandleAndTheSideButtonsAreTheAppsOfBefore() {
        for (dark in listOf(false, true)) {
            val palette = palette(themeFile(if (dark) "material-dark.xml" else "material-light.xml"))
            val l = looks(LookDefaults.look(palette), dark)
            val scenes = if (dark) setOf("dark") else emptySet()
            // FloatingHandle's: the strip at the top the bar's with 10% of the keys' text, its alpha the bar's
            val top = l.handle(scenes, top = true)
            assertEquals(Argb(mix(palette.barColor, palette.keyTextColor)), top.fill)
            assertNull(l.handle(scenes, top = false).fill)
            assertEquals(Argb(palette.altKeyTextColor), top.color)
            assertEquals(Argb(palette.altKeyTextColor), top.iconColor)
            assertEquals(36.0 to 4.0, top.widthDp to top.heightDp)
            // InputView's side buttons: the function keys' text colour on nothing, a round ripple of the pressed colour
            val side = l.sideButton(scenes + "one-handed")
            assertEquals(WindowLooks.SideButton(null, Argb(palette.altKeyTextColor), true, 0.0, false, Argb(palette.keyPressHighlightColor)), side)
            assertNull(l.navbar(scenes))
        }
    }

    @Test
    fun theHandlesShadeIsTheOldOnesToTheLastChannel() {
        // every pair of a channel's values, each channel in turn, the alpha one of a few
        for (b in 0..255) for (i in 0..255) for (shift in listOf(0, 8, 16)) {
            val alpha = listOf(0xFF, 0x80, 0x00)[(b + i) % 3] shl 24
            val base = alpha or (b shl shift) or (0x33 shl ((shift + 8) % 24))
            val ink = (0xC0 shl 24) or (i shl shift)
            assertEquals(mix(base, ink), WindowLooks.handleColor(Argb(base), Argb(ink)).value, "$b $i $shift")
        }
    }

    // FloatingKeyboard.cornerDp (ime-core), theme 3.0's corners: the keys' radius (square keys, square
    // corners; flat keys drawn square, none of it) and their least margin
    private fun cornerDp(keyRadius: Int, hMargin: Int, vMargin: Int, bordered: Boolean): Int =
        if (keyRadius == 0) 0 else (if (bordered) keyRadius else 0) + minOf(hMargin, vMargin)

    // FloatingKeyboard.handleColor as it was (0.5.2): each of r, g, b 10% of the way to the ink, rounded; the base's alpha
    private fun mix(base: Int, ink: Int): Int {
        fun ch(shift: Int): Int {
            val b = base ushr shift and 0xFF
            val i = ink ushr shift and 0xFF
            return Math.round(b + (i - b) * 0.1f) shl shift
        }
        return (base and 0xFF000000.toInt()) or ch(16) or ch(8) or ch(0)
    }
}
