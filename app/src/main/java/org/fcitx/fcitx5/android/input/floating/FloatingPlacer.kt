/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.floating

import android.graphics.Rect
import android.view.View
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.floating.FloatingKeyboard.Spot

/**
 * Where the floating keyboard [view] is in the input view [parent], which fills the screen: laid
 * out at its bottom, moved to its [Spot] by translation, so a drag lays nothing out again. The
 * spot is kept ([AppPrefs.Internal]) when a drag ends; [bottomInset], the navigation bar, it stays above.
 */
class FloatingPlacer(private val parent: View, private val view: View) {

    private val prefs = AppPrefs.getInstance().internal

    private var spot = Spot(prefs.floatingKeyboardX.getValue(), prefs.floatingKeyboardY.getValue())

    var bottomInset = 0
        set(value) {
            field = value
            if (view.isLaidOut) place()
        }

    private var moved = false

    private val location = IntArray(2)

    private val room get() = (parent.height - bottomInset).toFloat()

    /** At its spot, once laid out (and each time it is again). */
    fun place() {
        val (left, top) = FloatingKeyboard.place(spot, view.width.toFloat(), view.height.toFloat(), parent.width.toFloat(), room)
        view.translationX = left - view.left
        view.translationY = top - view.top
    }

    /** Moved by [dx], [dy], within the screen. */
    fun drag(dx: Float, dy: Float) {
        val left = view.left + view.translationX + dx
        val top = view.top + view.translationY + dy
        spot = FloatingKeyboard.spot(left, top, view.width.toFloat(), view.height.toFloat(), parent.width.toFloat(), room)
        moved = true
        // a new translation draws again, and the service's touchable region is asked anew with it
        place()
    }

    /** Let go: kept there, if it moved. */
    fun drop() {
        if (!moved) return
        moved = false
        prefs.floatingKeyboardX.setValue(spot.x)
        prefs.floatingKeyboardY.setValue(spot.y)
    }

    /** Where it is in the window, its translation too: all of it that takes touches. */
    fun region(out: Rect) {
        view.getLocationInWindow(location)
        out.set(location[0], location[1], location[0] + view.width, location[1] + view.height)
    }
}
