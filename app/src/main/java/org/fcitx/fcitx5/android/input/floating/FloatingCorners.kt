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
 * clipped to a rectangle with corners [radius] pixels round (read as the outline is made again, as the
 * view's size changes: a turn of the screen changes the keys' margins it follows,
 * [FloatingKeyboard.cornerDp]), and a shadow
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

    /**
     * [layer] (the popups: a key's bubble, a long press's keys) drawn over [view], the keyboard
     * [frame]d: once a child has a Z its parent draws its children by Z, and the keyboard lifted
     * covered the popups added after it.
     */
    fun keepAbove(layer: View, view: View) {
        layer.translationZ = view.elevation + 1f
    }
}
