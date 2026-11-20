/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.floating

import kotlin.math.abs

/**
 * The lone floating keyboard button (a hardware keyboard, nothing typed): top-left in the parent, from its left edge in
 * any direction. Beside the cursor it follows it, a drag holding for this field only; with no cursor, top right (a
 * bottom corner covered apps' buttons) or where last dragged, kept as fractions of the parent.
 */
class FloatingButtonPlacement {

    data class Spot(val x: Float, val y: Float)

    /** [top]/[bottom]: the band clear of the status and navigation bars. */
    data class Area(val width: Float, val height: Float, val top: Float, val bottom: Float)

    // this field's drag, from where the cursor put the button
    private var offsetX = 0f
    private var offsetY = 0f

    // before the drag offset: what a drop is measured from
    private var beside: Spot? = null

    /** A position saved with no cursor is the caller's and stays. */
    fun newField() {
        offsetX = 0f
        offsetY = 0f
        beside = null
    }

    /** [saved]: fractions of the parent, or null. */
    fun noCursor(saved: Spot?, w: Float, h: Float, area: Area, margin: Float): Spot {
        beside = null
        if (saved != null) return clamp(saved.x * area.width, saved.y * area.height, w, h, area)
        return clamp(area.width - w - margin, area.top + margin, w, h, area)
    }

    /** Moved by this field's drag, if any. */
    fun besideCursor(x: Float, y: Float, w: Float, h: Float, area: Area): Spot {
        beside = Spot(x, y)
        return clamp(x + offsetX, y + offsetY, w, h, area)
    }

    /** Kept inside the parent. */
    fun clamp(x: Float, y: Float, w: Float, h: Float, area: Area): Spot {
        val maxX = (area.width - w).coerceAtLeast(0f)
        val bottom = (area.bottom - h).coerceAtLeast(0f)
        return Spot(x.coerceIn(0f, maxX), y.coerceIn(area.top.coerceAtMost(bottom), bottom))
    }

    /** Already clamped. Beside the cursor: this field's offset, null returned; else the fractions to save. */
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
        fun isDrag(dx: Float, dy: Float, slop: Float) = dx * dx + dy * dy > slop * slop

        /** In RTL it is laid out at the right edge. */
        fun toLeft(tX: Float, w: Float, parentWidth: Float, rtl: Boolean) = if (rtl) tX + parentWidth - w else tX

        fun toTranslation(left: Float, w: Float, parentWidth: Float, rtl: Boolean) =
            if (rtl) left - (parentWidth - w) else left

        /** Within its own size a scroll, which the window follows once the cursor rests; a jump moves it at once. */
        fun isScroll(dx: Int, dy: Int, w: Int, h: Int, sameSize: Boolean) = sameSize && abs(dx) <= w && abs(dy) <= h
    }
}
