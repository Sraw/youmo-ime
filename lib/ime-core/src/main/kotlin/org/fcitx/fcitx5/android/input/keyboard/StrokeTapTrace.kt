/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import java.util.Locale
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * One touch on a letter, measured for the debug build's `YoumoStrokeTap` log (dev/design/061-swipe-eval.md,
 * 真机核对): how far a real finger drifts in a tap, where it lands in the key, how fast a stroke is drawn.
 * The synthetic taps the stroke threshold was chosen on are swapped for these. Geometry and times only:
 * never which letter, nothing typed. Places in px, written out in dp. What came of it (res): L a tap typed, T a stroke
 * lifted short typed as a tap, S and the stroke read, U a stroke unread, P a long press, O off the key, G a glide, C cancelled.
 */
class StrokeTapTrace(private val density: Float, private val touchSlop: Float) {

    private var active = false
    private var keyboard = ""
    private var strokeMode = false
    private var longPress = 0L
    private var cellW = 0f
    private var cellH = 0f
    private var atX = 0f
    private var atY = 0f
    private var x0 = 0f
    private var y0 = 0f
    private var t0 = 0L
    private var gap = -1L
    private var lastUp = -1L
    private var far = 0f
    private var farX = 0f
    private var farY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var outside = false
    private var multi = false
    private var startedAt = -1L
    private var startedFar = 0f
    private var stroke: Char? = null
    private var read = false
    private var tappedBack = false

    // the cell's edges, touch slop outside each, as KeyGestureRecognizer.pointInView has them
    private var left = 0f
    private var top = 0f
    private var right = 0f
    private var bottom = 0f

    /** A finger down at [x], [y] at [time] in the cell [left]..[right], [top]..[bottom] (all the keyboard's px). */
    fun down(
        x: Float, y: Float, time: Long, left: Float, top: Float, right: Float, bottom: Float,
        keyboard: String, strokeMode: Boolean, longPress: Long,
    ) {
        active = true
        this.keyboard = keyboard
        this.strokeMode = strokeMode
        this.longPress = longPress
        cellW = right - left
        cellH = bottom - top
        atX = x - (left + right) / 2
        atY = y - (top + bottom) / 2
        this.left = left - touchSlop
        this.top = top - touchSlop
        this.right = right + touchSlop
        this.bottom = bottom + touchSlop
        x0 = x
        y0 = y
        t0 = time
        gap = if (lastUp < 0) -1 else time - lastUp
        far = 0f
        farX = 0f
        farY = 0f
        lastX = x
        lastY = y
        outside = false
        multi = false
        startedAt = -1
        startedFar = 0f
        stroke = null
        read = false
        tappedBack = false
    }

    /** The finger at [x], [y]. */
    fun move(x: Float, y: Float) {
        if (!active) return
        lastX = x
        lastY = y
        val d = hypot(x - x0, y - y0)
        if (d > far) {
            far = d
            farX = x - x0
            farY = y - y0
        }
        if (x !in left..<right || y !in top..<bottom) outside = true
    }

    /** The touch became a stroke or a glide at [time], its keys cancelled. */
    fun started(time: Long) {
        if (!active || startedAt >= 0) return
        startedAt = time - t0
        startedFar = far
    }

    /** Another finger went down meanwhile. */
    fun otherFinger() {
        if (active) multi = true
    }

    /** The stroke the path was read as, null where it was none. */
    fun read(kind: Char?) {
        stroke = kind
        read = true
    }

    /** The stroke lifted short: the key's tap after all (GlideTouch.Route.TAP_KEY). */
    fun tapped() {
        tappedBack = true
    }

    /**
     * The finger lifted at [time] (or the touch [cancelled]): the line for the log, null where no touch was traced.
     * [time] is the touch's last lift: with another finger down (multi=1) the last finger's, so dur= runs to it
     * (a stroke short of its lift as another lands is tapped then, T, its own lift not told apart).
     */
    fun up(time: Long, cancelled: Boolean): String? {
        if (!active) return null
        active = false
        lastUp = time
        val dur = time - t0
        // what came of it: the keys' own say (a tap typed, off the key no tap, a long press) where no stroke started
        val result = when {
            cancelled -> "C"
            startedAt >= 0 && !strokeMode -> "G"
            tappedBack -> "T"
            startedAt >= 0 -> if (!read) "U" else stroke?.let { "S$it" } ?: "U"
            dur >= longPress -> "P"
            outside -> "O"
            else -> "L"
        }
        // up positive, as a stroke is drawn
        val dir = if (far == 0f) 0 else Math.toDegrees(atan2(-farY.toDouble(), farX.toDouble())).roundToInt()
        return String.format(
            Locale.ROOT,
            "kb=%s mode=%d lp=%d cell=%.1fx%.1f at=%.1f,%.1f dur=%d gap=%d far=%.1f dir=%d lift=%.1f,%.1f start=%s out=%d multi=%d res=%s",
            keyboard, if (strokeMode) 1 else 0, longPress, dp(cellW), dp(cellH), dp(atX), dp(atY), dur, gap,
            dp(far), dir, dp(lastX - x0), dp(lastY - y0),
            if (startedAt < 0) "-" else String.format(Locale.ROOT, "%d@%.1f", startedAt, dp(startedFar)),
            if (outside) 1 else 0, if (multi) 1 else 0, result,
        )
    }

    /** A backspace pressed at [time]: its line, so a stroke taken back right after it is seen as a misread. */
    fun backspace(time: Long): String = "bksp since=${if (lastUp < 0) -1 else time - lastUp}"

    private fun dp(px: Float) = px / density
}
