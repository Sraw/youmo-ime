/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.floating

/**
 * Dragging the floating keyboard, and docking at the bottom (as Gboard): its lowest place leaves room for the zone's
 * mark; let go in the zone, it docks. Starting at the bottom, only a further push of [slop] enters it. Grabbed from the
 * docked keyboard the finger may start lower than it goes: [start]'s slack is taken up first and is no push.
 */
class FloatingDrag(private val slop: Float) {

    // how far the finger has gone on down past where the keyboard stopped
    private var pushed = 0f

    // the keyboard was above its lowest place in this drag
    private var above = false

    // of [pushed], what the finger began below its lowest place: given back, but no push into the zone
    private var slack = 0f

    var inZone = false
        private set

    fun start(slack: Float = 0f) {
        this.slack = slack.coerceAtLeast(0f)
        pushed = this.slack
        above = false
        inZone = false
    }

    /** A push past [maxTop] is counted, given back first as the finger comes up. The caller compares [inZone] with before. */
    fun move(top: Float, dy: Float, maxTop: Float): Float {
        var at = top
        if (pushed > 0f) {
            pushed += dy
            if (pushed < 0f) {
                at = maxTop + pushed
                pushed = 0f
            }
            slack = slack.coerceAtMost(pushed)
        } else {
            at = top + dy
        }
        if (at > maxTop) {
            pushed += at - maxTop
            at = maxTop
        }
        // by the slop, not a pixel: raw moves wobble along the bottom (a saved share also comes back a fraction off)
        if (at.coerceAtLeast(0f) < maxTop - slop) above = true
        inZone = at >= maxTop && (above || pushed - slack >= slop)
        return at.coerceAtLeast(0f)
    }
}
