/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.input.keyboard.KeyboardArea.NavBar
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The screens upright and turned the input method has on a display ([KeyboardArea.screensOf]), as measured on the
 * API 29, 30, 33 and 36 emulators: the bar as held now, the other way's, the cutout across the short side.
 */
class KeyboardScreensTest {

    private fun KeyboardArea.Screen.px() = "${widthPx}x$heightPx/$widthDp"

    private fun screens(w: Int, h: Int, now: NavBar, other: NavBar, cutout: Int = 0, density: Float = D, round: Boolean = true) =
        KeyboardArea.screensOf(w, h, density, now, other, cutout, round).let { (up, turned) -> up.px() to turned.px() }

    // API 33, pixel_6, gestures, a 128 px camera cutout: upright 1080x2209, turned 2272x1017 (the IME's own)
    @Test
    fun gesturesAndACutoutUprightNow() =
        assertEquals("1080x2209/411" to "2272x1017/866", screens(1080, 2400, NavBar(63, 0), NavBar(63, 0), cutout = 128))

    @Test
    fun gesturesAndACutoutTurnedNow() =
        assertEquals("1080x2209/411" to "2272x1017/866", screens(2400, 1080, NavBar(63, 0), NavBar(63, 0), cutout = 128))

    // three buttons on a phone: at the bottom upright, at the side turned
    @Test
    fun buttonsUprightNow() =
        assertEquals("1080x2274/411" to "2274x1080/866", screens(1080, 2400, NavBar(126, 0), NavBar(0, 126)))

    @Test
    fun buttonsAtTheSideNow() =
        assertEquals("1080x2274/411" to "2274x1080/866", screens(2400, 1080, NavBar(0, 126), NavBar(126, 0)))

    @Test
    fun buttonsAndACutout() =
        assertEquals("1080x2146/411" to "2146x1080/818", screens(1080, 2400, NavBar(126, 0), NavBar(0, 126), cutout = 128))

    // no bar, no cutout (edge to edge from API 35): the display whole
    @Test
    fun edgeToEdge() =
        assertEquals("1080x2340/360" to "2340x1080/780", screens(1080, 2340, NavBar.NONE, NavBar.NONE, density = 3f))

    // API 29 (youmo29, 2.75): the bar at the bottom upright, at the side turned, the width truncated
    @Test
    fun api29Truncates() =
        assertEquals("1080x2208/392" to "2208x1080/802", screens(1080, 2340, NavBar(132, 0), NavBar(0, 132), density = 2.75f, round = false))

    private companion object {
        const val D = 2.625f
    }
}
