/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.floating

import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.floating.FloatingKeyboard.Spot

/** No view needed, so the keyboard's size is known before its view is (KeyboardArea.floatingSize). */
object FloatingStore {

    private val prefs get() = AppPrefs.getInstance().internal

    // read as kept while the finger is down, written as it lifts
    private var resizing: Pair<Boolean, Int>? = null

    /** Else the one place 0.6.0 kept. */
    fun spot(landscape: Boolean): Spot = prefs.run {
        val old = Spot(floatingKeyboardX.getValue(), floatingKeyboardY.getValue())
        if (landscape) FloatingKeyboard.keptSpot(floatingLandscapeX.getValue(), floatingLandscapeY.getValue(), old)
        else FloatingKeyboard.keptSpot(floatingPortraitX.getValue(), floatingPortraitY.getValue(), old)
    }

    fun keepSpot(landscape: Boolean, spot: Spot) = prefs.run {
        (if (landscape) floatingLandscapeX else floatingPortraitX).setValue(spot.x)
        (if (landscape) floatingLandscapeY else floatingPortraitY).setValue(spot.y)
    }

    /** Negative: never set (its default then). */
    fun widthDp(landscape: Boolean): Float =
        resizing?.takeIf { it.first == landscape }?.second?.toFloat() ?: width(landscape).getValue()

    /** Not kept till [keepWidth]. */
    fun resizing(landscape: Boolean, widthDp: Int?) {
        resizing = widthDp?.let { landscape to it }
    }

    fun keepWidth(landscape: Boolean, widthDp: Int) {
        resizing = null
        width(landscape).setValue(widthDp.toFloat())
    }

    private fun width(landscape: Boolean) = if (landscape) prefs.floatingLandscapeWidthDp else prefs.floatingPortraitWidthDp
}
