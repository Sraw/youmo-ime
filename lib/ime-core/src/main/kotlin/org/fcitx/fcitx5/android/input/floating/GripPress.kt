/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.floating

import kotlin.math.abs

/** The handle's grip held for the long-press time floats the keyboard: down within [gripWidth] of the middle (the bar is too thin to aim at), one finger, within [slop]. */
class GripPress(private val slop: Float, private val gripWidth: Float) {

    private var downX = 0f
    private var downY = 0f

    /** The long press counts if its time comes now. */
    var armed = false
        private set

    /** True if on the grip, to time the press. */
    fun down(x: Float, y: Float, stripWidth: Float): Boolean {
        downX = x
        downY = y
        armed = abs(x - stripWidth / 2) <= gripWidth / 2
        return armed
    }

    /** Past the slop, no longer a hold. */
    fun move(x: Float, y: Float) {
        if (armed && FloatingButtonPlacement.isDrag(x - downX, y - downY, slop)) armed = false
    }

    /** Or a second finger. */
    fun end() {
        armed = false
    }

    /** True, once, if the hold counts. */
    fun fire(): Boolean = armed.also { armed = false }

    companion object {
        /** dp. */
        const val GRIP_TOUCH_DP = 120
    }
}
