/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.floating

import kotlin.math.abs

/**
 * The docked keyboard's handle (始终显示悬浮条): its grip, the short bar in the middle, held for the
 * system's long-press time lets the keyboard go floating under the finger. A press counts that went
 * down on the grip ([gripWidth] wide about the strip's middle: the bar itself is too thin to aim at)
 * and stayed there, one finger, within [slop]: a finger sliding along the strip, or a second one,
 * is not a hold. The caller times it and asks [fire] once the time is up.
 */
class GripPress(private val slop: Float, private val gripWidth: Float) {

    private var downX = 0f
    private var downY = 0f

    /** Down and still: the long press counts if its time comes now. */
    var armed = false
        private set

    /** A finger down at [x], [y] on a strip [stripWidth] wide: true if on the grip, to time the press. */
    fun down(x: Float, y: Float, stripWidth: Float): Boolean {
        downX = x
        downY = y
        armed = abs(x - stripWidth / 2) <= gripWidth / 2
        return armed
    }

    /** The finger at [x], [y]: past the slop, no longer a hold. */
    fun move(x: Float, y: Float) {
        if (armed && FloatingButtonPlacement.isDrag(x - downX, y - downY, slop)) armed = false
    }

    /** Lifted, cancelled, or a second finger: no hold. */
    fun end() {
        armed = false
    }

    /** The long-press time came: true, once, if the hold counts. */
    fun fire(): Boolean = armed.also { armed = false }

    companion object {
        /** The grip's touch width, in dp, about the strip's middle. */
        const val GRIP_TOUCH_DP = 120
    }
}
