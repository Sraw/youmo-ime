/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.floating

import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.floating.FloatingKeyboard.Spot

/**
 * Where the floating keyboard is kept and how wide, 竖屏 and 横屏 ([landscape], KeyboardArea.Screen's) each their own
 * (061-floating-resize §2.6, the user's ②): read and written here only, no view needed, so the keyboard's size is known
 * before its view is (KeyboardArea.floatingSize). What either falls back to is FloatingKeyboard's.
 */
object FloatingStore {

    private val prefs get() = AppPrefs.getInstance().internal

    // a resize under way, its shape and width, dp: read as kept while the finger is down, written as it lifts
    private var resizing: Pair<Boolean, Int>? = null

    /** Its place on a screen held [landscape]: its own once kept, else the one place 0.6.0 kept. */
    fun spot(landscape: Boolean): Spot = prefs.run {
        val old = Spot(floatingKeyboardX.getValue(), floatingKeyboardY.getValue())
        if (landscape) FloatingKeyboard.keptSpot(floatingLandscapeX.getValue(), floatingLandscapeY.getValue(), old)
        else FloatingKeyboard.keptSpot(floatingPortraitX.getValue(), floatingPortraitY.getValue(), old)
    }

    /** Its place kept, dragged or resized there on a screen held [landscape]. */
    fun keepSpot(landscape: Boolean, spot: Spot) = prefs.run {
        (if (landscape) floatingLandscapeX else floatingPortraitX).setValue(spot.x)
        (if (landscape) floatingLandscapeY else floatingPortraitY).setValue(spot.y)
    }

    /** The width the user resized it to on a screen held [landscape], dp; negative, never (its default then). */
    fun widthDp(landscape: Boolean): Float =
        resizing?.takeIf { it.first == landscape }?.second?.toFloat() ?: width(landscape).getValue()

    /** A resize under way at [widthDp] ([widthDp] null: over), not kept till [keepWidth]. */
    fun resizing(landscape: Boolean, widthDp: Int?) {
        resizing = widthDp?.let { landscape to it }
    }

    /** The width resized to kept. */
    fun keepWidth(landscape: Boolean, widthDp: Int) {
        resizing = null
        width(landscape).setValue(widthDp.toFloat())
    }

    private fun width(landscape: Boolean) = if (landscape) prefs.floatingLandscapeWidthDp else prefs.floatingPortraitWidthDp
}
