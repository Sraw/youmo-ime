/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.floating

import kotlin.math.abs

/**
 * Where the keyboard button goes when it floats alone (a hardware keyboard in use, nothing typed),
 * and what a drag of it leaves behind. Places are the button's top-left corner in the parent, from
 * its left edge whatever the layout direction.
 *
 * Beside the cursor it follows the cursor; a drag there moves it for this field only, as the next
 * field's cursor is elsewhere. Where the editor tells no cursor it stands at the top right, in every
 * layout direction (in a bottom corner it covered an app's own button), or where the user last
 * dragged it with no cursor: that is kept, as fractions of the parent, so a rotation or another
 * screen still finds it.
 */
class FloatingButtonPlacement {

    data class Spot(val x: Float, val y: Float)

    /** The parent's size and the band of it clear of the status bar ([top]) and navigation bar ([bottom]). */
    data class Area(val width: Float, val height: Float, val top: Float, val bottom: Float)

    // this field's drag, from where the cursor put the button
    private var offsetX = 0f
    private var offsetY = 0f

    // where the cursor last put it, before the drag offset: what a drop is measured from
    private var beside: Spot? = null

    /** A new field: the cursor leads again. A position saved with no cursor is the caller's and stays. */
    fun newField() {
        offsetX = 0f
        offsetY = 0f
        beside = null
    }

    /** With no cursor: [saved] (fractions of the parent, or null for none), else the top right. */
    fun noCursor(saved: Spot?, w: Float, h: Float, area: Area, margin: Float): Spot {
        beside = null
        if (saved != null) return clamp(saved.x * area.width, saved.y * area.height, w, h, area)
        return clamp(area.width - w - margin, area.top + margin, w, h, area)
    }

    /** [x], [y]: where the cursor puts the button; moved by this field's drag, if any. */
    fun besideCursor(x: Float, y: Float, w: Float, h: Float, area: Area): Spot {
        beside = Spot(x, y)
        return clamp(x + offsetX, y + offsetY, w, h, area)
    }

    /** Where a drag puts it: the finger's place kept inside the parent. */
    fun clamp(x: Float, y: Float, w: Float, h: Float, area: Area): Spot {
        val maxX = (area.width - w).coerceAtLeast(0f)
        val bottom = (area.bottom - h).coerceAtLeast(0f)
        return Spot(x.coerceIn(0f, maxX), y.coerceIn(area.top.coerceAtMost(bottom), bottom))
    }

    /**
     * Dropped at [at] (already clamped). Beside the cursor it becomes this field's offset and null is
     * returned; with no cursor, the fractions of the parent to save are returned.
     */
    fun drop(at: Spot, area: Area): Spot? {
        val base = beside
        if (base != null) {
            offsetX = at.x - base.x
            offsetY = at.y - base.y
            return null
        }
        if (area.width <= 0f || area.height <= 0f) return null
        return Spot(at.x / area.width, at.y / area.height)
    }

    companion object {
        /** A finger moved past the touch slop: a drag, not a tap. */
        fun isDrag(dx: Float, dy: Float, slop: Float) = dx * dx + dy * dy > slop * slop

        /** A view's translationX ([tX]) to its left edge: in RTL it is laid out at the right edge. */
        fun toLeft(tX: Float, w: Float, parentWidth: Float, rtl: Boolean) = if (rtl) tX + parentWidth - w else tX

        /** The inverse of [toLeft]. */
        fun toTranslation(left: Float, w: Float, parentWidth: Float, rtl: Boolean) =
            if (rtl) left - (parentWidth - w) else left

        /**
         * Moved by [dx], [dy] from where its touch window is, the same size as it: within its own size
         * a scroll, which the window follows once the cursor rests. A jump past it (a tap elsewhere in
         * the text) moves the window at once.
         */
        fun isScroll(dx: Int, dy: Int, w: Int, h: Int, sameSize: Boolean) = sameSize && abs(dx) <= w && abs(dy) <= h
    }
}
