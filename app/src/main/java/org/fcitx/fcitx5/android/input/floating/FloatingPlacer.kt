/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.floating

import android.graphics.Rect
import android.view.View
import org.fcitx.fcitx5.android.input.floating.FloatingKeyboard.Spot
import splitties.dimensions.dp

/** Laid out at the bottom and moved by translation, so a drag lays nothing out again. */
class FloatingPlacer(private val parent: View, private val view: View, private val landscape: Boolean) {

    private var spot = FloatingStore.spot(landscape)

    var bottomInset = 0
        set(value) {
            field = value
            if (view.isLaidOut) place()
        }

    var topInset = 0
        set(value) {
            field = value
            if (view.isLaidOut) place()
        }

    private var moved = false

    val dragging get() = moved

    private val dock = FloatingDrag(view.context.dp(DOCK_SLOP_DP).toFloat())

    // the docking zone's mark shows there
    private val markHeight = view.context.dp(DOCK_MARK_DP)

    var onZone: (Boolean) -> Unit = {}

    var onPlaced: () -> Unit = {}

    private val location = IntArray(2)

    /** Px, from [topInset] down. */
    val room get() = (parent.height - bottomInset - topInset - markHeight).toFloat()

    fun place() {
        val (left, top) = FloatingKeyboard.place(spot, view.width.toFloat(), view.height.toFloat(), parent.width.toFloat(), room)
        view.translationX = left - view.left
        view.translationY = top + topInset - view.top
        onPlaced()
    }

    fun drag(dx: Float, dy: Float) {
        val left = view.left + view.translationX + dx
        val was = dock.inZone
        val top = dock.move(view.top + view.translationY - topInset, dy, (room - view.height).coerceAtLeast(0f))
        spot = FloatingKeyboard.spot(left, top, view.width.toFloat(), view.height.toFloat(), parent.width.toFloat(), room)
        moved = true
        // the service's touchable region is asked anew with it
        place()
        if (dock.inZone != was) onZone(dock.inZone)
    }

    /** Laid out first: its size is not known before. [keep]: the finger lifted before it was laid out. */
    fun grabbed(fingerX: Float, fingerY: Float, gripX: Float, gripY: Float, keep: Boolean) {
        spot = FloatingKeyboard.underFinger(fingerX, fingerY - topInset, gripX, gripY, view.width.toFloat(), view.height.toFloat(), parent.width.toFloat(), room)
        place()
        // the finger below where it can go (the docked grip is lower): taken up first, the grip then under it.
        // Kept, no finger is down: the next drag starts afresh, else it stuck for the slack
        dock.start(slack = if (keep) 0f else fingerY - (view.top + view.translationY + gripY))
        moved = !keep
        if (keep) FloatingStore.keepSpot(landscape, spot)
    }

    /**
     * True if [lifted] in the docking zone. A cancelled drag or a second finger never docks: the zone is just
     * above the gesture bar, whose swipe the system takes. Docked, the place kept is the last it floated at.
     */
    fun drop(lifted: Boolean): Boolean {
        val inZone = dock.inZone
        val docks = lifted && inZone
        dock.start()
        if (inZone) onZone(false)
        if (moved && !docks) FloatingStore.keepSpot(landscape, spot)
        moved = false
        // the floating code strip, hidden while dragged, shows again on one (InputView)
        view.invalidate()
        return docks
    }

    /** In the room: from [topInset]. */
    fun resized(left: Float, top: Float, w: Float, h: Float) {
        spot = FloatingKeyboard.spot(left, top, w, h, parent.width.toFloat(), room)
    }

    fun keep() = FloatingStore.keepSpot(landscape, spot)

    companion object {
        /** At the bottom when a drag began, how far on down it goes into the docking zone. */
        const val DOCK_SLOP_DP = 8

        /** Its height and its line's width. */
        const val DOCK_MARK_DP = 32
        const val DOCK_MARK_STROKE_DP = 1.5f
    }

    /** Its translation too: all of it that takes touches. */
    fun region(out: Rect) {
        view.getLocationInWindow(location)
        out.set(location[0], location[1], location[0] + view.width, location[1] + view.height)
    }
}
