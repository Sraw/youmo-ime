/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.input.keyboard.KeyboardArea.NavBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [KeyboardArea.screensOf] as measured on the API 29, 30, 33 and 36 emulators. */
class KeyboardScreensTest {

    private fun KeyboardArea.Screen.px() = "${widthPx}x$heightPx/$widthDp"

    private fun screens(w: Int, h: Int, now: NavBar, other: NavBar, cutout: Int = 0, density: Float = D, round: Boolean = true) =
        KeyboardArea.screensOf(w, h, density, now, other, cutout, round).let { (up, turned) -> up.px() to turned.px() }

    @Test
    fun theScreensUprightAndTurned() = assertRows(
        // API 33, pixel_6, gestures, a 128 px camera cutout: upright 1080x2209, turned 2272x1017
        "gesturesAndACutoutUprightNow" to {
            assertEquals("1080x2209/411" to "2272x1017/866", screens(1080, 2400, NavBar(63, 0), NavBar(63, 0), cutout = 128))
        },
        "gesturesAndACutoutTurnedNow" to {
            assertEquals("1080x2209/411" to "2272x1017/866", screens(2400, 1080, NavBar(63, 0), NavBar(63, 0), cutout = 128))
        },
        "buttonsUprightNow" to {
            assertEquals("1080x2274/411" to "2274x1080/866", screens(1080, 2400, NavBar(126, 0), NavBar(0, 126)))
        },
        "buttonsAtTheSideNow" to {
            assertEquals("1080x2274/411" to "2274x1080/866", screens(2400, 1080, NavBar(0, 126), NavBar(126, 0)))
        },
        "buttonsAndACutout" to {
            assertEquals("1080x2146/411" to "2146x1080/818", screens(1080, 2400, NavBar(126, 0), NavBar(0, 126), cutout = 128))
        },
        "edgeToEdge" to {
            assertEquals("1080x2340/360" to "2340x1080/780", screens(1080, 2340, NavBar.NONE, NavBar.NONE, density = 3f))
        },
        "api29Truncates" to {
            assertEquals("1080x2208/392" to "2208x1080/802", screens(1080, 2340, NavBar(132, 0), NavBar(0, 132), density = 2.75f, round = false))
        },
    )

    @Test
    @Uat("LS-49")
    fun aLargeScreenIsTheDisplaysShortSideNotTheWindows() {
        // a 601 × 962 dp tablet at 2x, its bar at the bottom both ways (API 29–34 leave it out of the window): turned
        // the window is 553 dp high, yet the display's short side says large
        val (up, turned) = KeyboardArea.screensOf(1202, 1924, 2f, NavBar(96, 0), NavBar(96, 0), 0, roundDp = true)
        assertEquals(1202 - 96, turned.heightPx)
        assertEquals(601 to 601, up.shortSideDp to turned.shortSideDp)
        assertTrue(up.large && turned.large)
        val (pu, pt) = KeyboardArea.screensOf(1080, 2340, 2.75f, NavBar(132, 0), NavBar(0, 132), 0, roundDp = false,
            statusBar = { if (it) 66 else 132 }, imeNav = { if (it) 0 else 132 })
        assertFalse(pu.large || pt.large)
        assertEquals(listOf(132, 132, 66, 0), listOf(pu.statusBarPx, pu.imeNavPx, pt.statusBarPx, pt.imeNavPx))
    }

    private companion object {
        const val D = 2.625f
    }

    private fun shapes(w: Int, h: Int, density: Float = D, round: Boolean = true, nowDp: Int? = null) =
        KeyboardArea.screensOf(w, h, density, NavBar.NONE, NavBar.NONE, 0, round, nowDp).let { (up, turned) ->
            "${up.widthDp}/${up.landscape}" to "${turned.widthDp}/${turned.landscape}"
        }

    @Test
    @Uat("LS-37")
    fun eachScreenIsLandscapeAsWideAsItIs() = assertRows(
        "aPhone" to { assertEquals("393/false" to "851/true", shapes(1080, 2340, density = 2.75f)) },
        "aFoldableUnfolded" to { assertEquals("673/true" to "841/true", shapes(1767, 2208)) },
        "aFoldableUnfoldedTurnedNow" to { assertEquals("673/true" to "841/true", shapes(2208, 1767)) },
        "aFoldableFolded" to { assertEquals("412/false" to "891/true", shapes(1082, 2340)) },
        "aTablet" to { assertEquals("800/true" to "1280/true", shapes(1600, 2560, density = 2f)) },
    )

    @Test
    fun theScreenHeldNowIsAsTheConfigurationSaysOnTheEdge() = assertRows(
        // 1574 px at 2.625 is 599.6 dp: 599 truncated (API 29), the configuration's 600 for the one held now
        "truncatedAlone" to { assertEquals("599/false" to "841/true", shapes(1574, 2208, round = false)) },
        "theConfigurationsWidth" to { assertEquals("600/true" to "841/true", shapes(1574, 2208, round = false, nowDp = 600)) },
        "roundedTheSame" to { assertEquals("599/false" to "841/true", shapes(1574, 2208, round = false, nowDp = 599)) },
        // held turned: the configuration's is the turned one's (842 of 841.1), the upright one's worked out
        "notTheOneHeldNow" to { assertEquals("599/false" to "842/true", shapes(2208, 1574, round = false, nowDp = 842)) },
        // an activity's half of a split screen: the display's own width kept
        "farOffIsNoRounding" to { assertEquals("599/false" to "841/true", shapes(1574, 2208, round = false, nowDp = 300)) },
    )

    private val buttons = NavBar(0, 126)
    private fun other(now: NavBar, landscape: Boolean, mode: Int = 0) = KeyboardArea.otherBar(now, landscape, D, mode) { buttons }

    @Test
    fun theOtherWaysBar() = assertRows(
        "gestureModeCopiesTheStrip" to { assertEquals(NavBar(63, 0), other(NavBar(63, 0), false, KeyboardArea.GESTURES)) },
        "aBottomBarWhileLandscapeStaysAtTheBottom" to { assertEquals(NavBar(84, 0), other(NavBar(84, 0), true)) },
        "noBarNowNoneTurned" to { assertEquals(NavBar.NONE, other(NavBar.NONE, false, KeyboardArea.GESTURES)) },
        // a ROM not saying mode 2: 63 px at 2.625 is 24 dp, a strip, not buttons
        "aShortStripUprightIsGestures" to { assertEquals(NavBar(63, 0), other(NavBar(63, 0), false)) },
        "threeButtonsUprightMoveToTheSide" to { assertEquals(buttons, other(NavBar(126, 0), false)) },
        "threeButtonsAtTheSideComeBackFromTheResources" to {
            assertEquals(NavBar(126, 0), KeyboardArea.otherBar(NavBar(0, 126), true, D, 0) { NavBar(126, 0) })
        },
    )
}
