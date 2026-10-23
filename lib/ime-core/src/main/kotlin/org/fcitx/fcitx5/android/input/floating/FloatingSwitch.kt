/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.floating

/**
 * The floating keyboard put in place of the docked one, and slid back down to dock: what its view
 * does with a drag, a drop and its first layout. The view is made anew each way (the keyboard's
 * prefs), so a floating one starts unlaid out: shrunk from the docked keyboard it replaced
 * ([shrinkFrom], any switch while shown), or let go of by that keyboard's handle with the finger
 * still down ([grab]), whose moves wait here till the first layout puts its grip under the finger.
 * Docking slides it down first ([dock]); till the slide is over its drag and drops are over too.
 */
class FloatingSwitch {

    /** A rectangle in the input view, as the docked keyboard was. */
    data class Bounds(val left: Float, val top: Float, val width: Float, val height: Float)

    /**
     * What the first layout places: shrunk from [from], at its kept spot, or with its grip under the
     * finger at [x], [y] ([underFinger]); [ended] the finger lifted before, its place kept at once.
     */
    data class Grab(val x: Float, val y: Float, val from: Bounds, val underFinger: Boolean, val ended: Boolean = false)

    /** What a layout does: the kept spot, a [Grab]'s place, or nothing while it slides to dock. */
    sealed interface Layout {
        data object Spot : Layout
        data object Sliding : Layout
        data class Placed(val grab: Grab) : Layout
    }

    /** How a dock goes: at once (no animation), slid down first, or already sliding. */
    enum class Dock { Now, Slide, Sliding }

    private var pending: Grab? = null

    /** Sliding down to dock. */
    var docking = false
        private set

    /** Made floating in place of the docked keyboard shown at [from]: shrunk from it, unless grabbed. */
    fun shrinkFrom(from: Bounds) {
        if (pending == null) pending = Grab(0f, 0f, from, underFinger = false)
    }

    /**
     * Let go of by the docked keyboard's handle, it at [from]: its grip under the finger at [x], [y];
     * [ended] no finger stays down (TalkBack's long press), its place kept as it is laid out.
     */
    fun grab(x: Float, y: Float, from: Bounds, ended: Boolean = false) {
        pending = Grab(x, y, from, underFinger = true, ended = ended)
    }

    /** The finger moved: true to move the keyboard now; false while it slides, or kept for the first layout. */
    fun drag(dx: Float, dy: Float): Boolean {
        if (docking) return false
        val p = pending
        if (p == null || !p.underFinger) return true
        pending = p.copy(x = p.x + dx, y = p.y + dy)
        return false
    }

    /** The drag ended: true for the keyboard to take it (and dock, let go in the zone); false while it slides, or before the first layout. */
    fun drop(): Boolean {
        if (docking) return false
        val p = pending
        if (p == null || !p.underFinger) return true
        pending = p.copy(ended = true)
        return false
    }

    /** Laid out, again or the first time. */
    fun laidOut(): Layout {
        if (docking) return Layout.Sliding
        val p = pending ?: return Layout.Spot
        pending = null
        return Layout.Placed(p)
    }

    /** Docking asked, [animates] if the slide would show. */
    fun dock(animates: Boolean): Dock = when {
        docking -> Dock.Sliding
        !animates -> Dock.Now
        else -> Dock.Slide.also { docking = true }
    }

    /** The slide over: ended, cancelled, or the view taken out under it. True once: dock now. */
    fun slideOver(): Boolean = docking.also { docking = false }

    /** Hidden, or its field left, not yet laid out: the docked keyboard it would shrink from is long gone. */
    fun inputEnded() {
        pending = null
    }
}
