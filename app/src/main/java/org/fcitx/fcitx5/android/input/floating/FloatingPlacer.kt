/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.floating

import android.graphics.Rect
import android.view.View
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.floating.FloatingKeyboard.Spot
import splitties.dimensions.dp

/**
 * Where the floating keyboard [view] is in the input view [parent], which fills the screen: laid
 * out at its bottom, moved to its [Spot] by translation, so a drag lays nothing out again. The
 * spot is kept ([AppPrefs.Internal]) when a drag ends; [bottomInset], the navigation bar, it stays above,
 * and [topInset], the status bar or a cutout, below, and the docking zone's mark ([DOCK_MARK_DP])
 * above the bottom, as Gboard's. Dragged to the bottom it is in the docking zone ([FloatingDrag]):
 * [onZone] is told as it goes in and out, and [drop] says to dock.
 */
class FloatingPlacer(private val parent: View, private val view: View) {

    private val prefs = AppPrefs.getInstance().internal

    private var spot = Spot(prefs.floatingKeyboardX.getValue(), prefs.floatingKeyboardY.getValue())

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

    private val dock = FloatingDrag(view.context.dp(DOCK_SLOP_DP).toFloat())

    // the strip above the bottom the keyboard never goes into: the docking zone's mark shows there
    private val markHeight = view.context.dp(DOCK_MARK_DP)

    /** Told true as a drag goes into the docking zone, false as it leaves it or ends. */
    var onZone: (Boolean) -> Unit = {}

    private val location = IntArray(2)

    private val room get() = (parent.height - bottomInset - topInset - markHeight).toFloat()

    /** At its spot, once laid out (and each time it is again). */
    fun place() {
        val (left, top) = FloatingKeyboard.place(spot, view.width.toFloat(), view.height.toFloat(), parent.width.toFloat(), room)
        view.translationX = left - view.left
        view.translationY = top + topInset - view.top
    }

    /** Moved by [dx], [dy], within the screen. */
    fun drag(dx: Float, dy: Float) {
        val left = view.left + view.translationX + dx
        val was = dock.inZone
        val top = dock.move(view.top + view.translationY - topInset, dy, (room - view.height).coerceAtLeast(0f))
        spot = FloatingKeyboard.spot(left, top, view.width.toFloat(), view.height.toFloat(), parent.width.toFloat(), room)
        moved = true
        // a new translation draws again, and the service's touchable region is asked anew with it
        place()
        if (dock.inZone != was) onZone(dock.inZone)
    }

    /**
     * Let go of by the docked keyboard ([FloatingHandoff]): its grip, [gripX], [gripY] in it, under
     * the finger at [fingerX], [fingerY] in the parent, within the screen. Laid out first: its size
     * is not known before. Kept as any drag is, as it ends ([drop]): not if it docks, the place
     * kept the last it floated at; [keep] the finger lifted before it was laid out, kept now.
     */
    fun grabbed(fingerX: Float, fingerY: Float, gripX: Float, gripY: Float, keep: Boolean) {
        spot = FloatingKeyboard.underFinger(fingerX, fingerY - topInset, gripX, gripY, view.width.toFloat(), view.height.toFloat(), parent.width.toFloat(), room)
        place()
        // the finger below where it can go (the docked grip is lower): taken up first as it comes up,
        // the grip then under it (MN-11 on redroid: kept 150 px above the finger all the way up).
        // Kept, no finger is down: the next drag starts afresh (F3470: else it stuck for the slack)
        dock.start(slack = if (keep) 0f else fingerY - (view.top + view.translationY + gripY))
        moved = !keep
        if (keep) {
            prefs.floatingKeyboardX.setValue(spot.x)
            prefs.floatingKeyboardY.setValue(spot.y)
        }
    }

    /**
     * The drag ended: kept there, if it moved; true if [lifted] in the docking zone, to dock it. A
     * cancelled drag or a second finger never docks: the zone is just above the gesture bar, whose
     * swipe the system takes. Docked, the place kept is the last it floated at, not the zone's.
     */
    fun drop(lifted: Boolean): Boolean {
        val inZone = dock.inZone
        val docks = lifted && inZone
        dock.start()
        if (inZone) onZone(false)
        if (moved && !docks) {
            prefs.floatingKeyboardX.setValue(spot.x)
            prefs.floatingKeyboardY.setValue(spot.y)
        }
        moved = false
        return docks
    }

    companion object {
        /** At the bottom when a drag began, how far on down it goes into the docking zone, in dp. */
        const val DOCK_SLOP_DP = 8

        /** The zone's mark across the bottom of the screen, in dp: its height and its line's width. */
        const val DOCK_MARK_DP = 32
        const val DOCK_MARK_STROKE_DP = 1.5f
    }

    /** Where it is in the window, its translation too: all of it that takes touches. */
    fun region(out: Rect) {
        view.getLocationInWindow(location)
        out.set(location[0], location[1], location[0] + view.width, location[1] + view.height)
    }
}
