/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import kotlin.math.abs
import kotlin.math.hypot

/**
 * 滑行输入 starts when a finger down alone on a letter moves [ACROSS] of a key's width or [downKeys] of its height
 * before its long press; a letter's swipe is shorter and up or down. A [stroke] (笔画筛选) starts at [strokeDistance]
 * any way (the caller's 12dp: a key's share was ~19dp down, ~10dp across), so a 竖 or 点 is a stroke before the key's
 * swipe or long press is; whether it was a tap after all is told at the lift ([GlideTouch]).
 */
class GlideGesture(
    private val keyWidth: Float,
    private val keyHeight: Float,
    private val longPressMillis: Long,
    private val downKeys: Float = DOWN,
    private val stroke: Boolean = false,
    // 0: [STROKE] of a key instead
    private val strokeDistance: Float = 0f,
) {

    private var x0 = 0f
    private var y0 = 0f
    private var t0 = 0L
    private var possible = false

    /** Once it is, it is till it lifts. */
    var gliding = false
        private set

    fun down(x: Float, y: Float, time: Long, onLetter: Boolean) {
        x0 = x
        y0 = y
        t0 = time
        possible = onLetter
        gliding = false
    }

    /** Two fingers do not glide: a fast typist's taps overlap. */
    fun otherDown() {
        possible = false
    }

    /** Whether it glides, from now if not before. */
    fun move(x: Float, y: Float, time: Long): Boolean {
        if (gliding || !possible) return gliding
        if (time - t0 > longPressMillis) {
            possible = false
            return false
        }
        gliding = if (stroke && strokeDistance > 0f) {
            hypot(x - x0, y - y0) >= strokeDistance
        } else if (stroke) {
            hypot((x - x0) / keyWidth, (y - y0) / keyHeight) >= STROKE
        } else {
            abs(x - x0) >= ACROSS * keyWidth || abs(y - y0) >= downKeys * keyHeight
        }
        return gliding
    }

    /** Or the touch cancelled. */
    fun up() {
        possible = false
        gliding = false
    }

    companion object {
        const val ACROSS = 0.8f
        const val DOWN = 1.6f

        /** Their keys as tall as the next row is far, a digit key's swipe shorter still. */
        const val NINE_KEYS_DOWN = 0.8f

        /** In keys (width across, height down): on a phone about 19dp down, 10dp across. */
        const val STROKE = 0.3f
    }
}
