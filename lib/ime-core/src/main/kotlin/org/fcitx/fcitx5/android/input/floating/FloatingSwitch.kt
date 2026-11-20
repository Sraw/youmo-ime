/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.floating

/**
 * The floating keyboard swapped for the docked one, and back. The view is made anew, so it starts unlaid out: shrunk
 * from the docked one ([shrinkFrom]) or grabbed by its handle ([grab]), whose moves wait till the first layout puts the
 * grip under the finger. Docking slides down first ([dock]); drags and drops wait till the slide is over.
 */
class FloatingSwitch {

    /** In the input view. */
    data class Bounds(val left: Float, val top: Float, val width: Float, val height: Float)

    /** [ended]: the finger lifted before, its place kept at once. */
    data class Grab(val x: Float, val y: Float, val from: Bounds, val underFinger: Boolean, val ended: Boolean = false)

    sealed interface Layout {
        data object Spot : Layout
        data object Sliding : Layout
        data class Placed(val grab: Grab) : Layout
    }

    enum class Dock { Now, Slide, Sliding }

    private var pending: Grab? = null

    var docking = false
        private set

    /** Shrunk from it, unless grabbed. */
    fun shrinkFrom(from: Bounds) {
        if (pending == null) pending = Grab(0f, 0f, from, underFinger = false)
    }

    /** [ended]: no finger stays down (TalkBack's long press). */
    fun grab(x: Float, y: Float, from: Bounds, ended: Boolean = false) {
        pending = Grab(x, y, from, underFinger = true, ended = ended)
    }

    /** False while it slides, or kept for the first layout. */
    fun drag(dx: Float, dy: Float): Boolean {
        if (docking) return false
        val p = pending
        if (p == null || !p.underFinger) return true
        pending = p.copy(x = p.x + dx, y = p.y + dy)
        return false
    }

    /** True: the keyboard takes it (docks in the zone); false while it slides, or before the first layout. */
    fun drop(): Boolean {
        if (docking) return false
        val p = pending
        if (p == null || !p.underFinger) return true
        pending = p.copy(ended = true)
        return false
    }

    fun laidOut(): Layout {
        if (docking) return Layout.Sliding
        val p = pending ?: return Layout.Spot
        pending = null
        return Layout.Placed(p)
    }

    /** [animates]: the slide would show. */
    fun dock(animates: Boolean): Dock = when {
        docking -> Dock.Sliding
        !animates -> Dock.Now
        else -> Dock.Slide.also { docking = true }
    }

    /** Ended, cancelled, or the view taken out under it. True once: dock now. */
    fun slideOver(): Boolean = docking.also { docking = false }

    /** Not yet laid out: the docked keyboard it would shrink from is gone. */
    fun inputEnded() {
        pending = null
    }
}
