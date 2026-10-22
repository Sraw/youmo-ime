/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.floating

/**
 * The floating keyboard dragged up and down, and brought to the bottom to dock it, as Gboard's: its
 * lowest place is above a strip kept for the docking zone's mark, and a drag that takes it there is
 * in the zone; let go there, it docks ([FloatingKeyboard]), moved back up first, it floats on. Where
 * it already was at the bottom when the drag began (it first floats there), only a push on down of
 * [slop] takes it in: a drag along the bottom only moves it across.
 */
class FloatingDrag(private val slop: Float) {

    // how far the finger has gone on down past where the keyboard stopped
    private var pushed = 0f

    // the keyboard was above its lowest place in this drag
    private var above = false

    /** In the docking zone: let go now, it docks. */
    var inZone = false
        private set

    /** A new drag, nothing pushed yet. */
    fun start() {
        pushed = 0f
        above = false
        inZone = false
    }

    /**
     * The keyboard's top after the finger moved [dy] with it at [top], [maxTop] the lowest it goes:
     * a push past it counted, and given back first as the finger comes up again. [inZone] then says
     * whether it is in the zone; the caller compares it with its own reading before the move.
     */
    fun move(top: Float, dy: Float, maxTop: Float): Float {
        var at = top
        if (pushed > 0f) {
            pushed += dy
            if (pushed < 0f) {
                at = maxTop + pushed
                pushed = 0f
            }
        } else {
            at = top + dy
        }
        if (at > maxTop) {
            pushed += at - maxTop
            at = maxTop
        }
        // up by the slop, not a pixel: the handle passes raw moves, and a finger wobbling a few
        // pixels while dragging along the bottom would arm the dock (a place kept as a share also
        // comes back a fraction off). Where the keyboard is shown, not past the top: one as tall as
        // the room (maxTop 0) never goes above its lowest place
        if (at.coerceAtLeast(0f) < maxTop - slop) above = true
        inZone = at >= maxTop && (above || pushed >= slop)
        return at.coerceAtLeast(0f)
    }
}
