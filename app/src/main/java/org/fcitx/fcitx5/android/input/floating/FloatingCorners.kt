/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.floating

import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider

/**
 * The floating keyboard's frame, as users asked: [view] and all it holds, the theme's picture too,
 * clipped to a rectangle with corners [radius] pixels round (read at each layout: a turn of the
 * screen changes the keys' margins it follows, [FloatingKeyboard.cornerDp]), and a shadow
 * [elevation] deep, so it stands apart from the app under it.
 */
object FloatingCorners {

    const val ELEVATION_DP = 6

    fun frame(view: View, radius: () -> Float, elevation: Float) {
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius())
            }
        }
        view.clipToOutline = true
        view.elevation = elevation
    }
}
