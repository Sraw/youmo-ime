/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import kotlin.math.abs
import kotlin.math.hypot

/**
 * When a finger on the letters glides (滑行输入) rather than taps, swipes or holds a key: it went
 * down on a letter, alone, and before its long press came up moved [ACROSS] of a key's width
 * sideways or [downKeys] of its height up or down ([DOWN]; the nine keys', a row away from the next, [NINE_KEYS_DOWN]).
 * A letter's swipe is up or down and shorter (the keys' swipe threshold); its long press, once up,
 * is the popup's, the finger choosing in it. [keyWidth] and [keyHeight] in the units of the places
 * given, [longPressMillis] the keys'.
 *
 * A [stroke] drawn (笔画筛选) starts much sooner: once the finger is [strokeDistance] from where it went down,
 * any way alike, in the places' units (the caller's 12dp, whatever the keys' shape: a key's share was ~19dp down
 * on a phone's tall keys, ~10dp across). A 竖 from the bottom row has a key's height at most to go, a short 点 less
 * still, and both must be strokes before the key's swipe (its 角标, 36dp) or long press are. Whether a touch
 * started so is a stroke or the key's tap after all is told as it lifts ([GlideTouch]'s lift distance).
 * Without a distance it starts [STROKE] of a key off, keys counted as they are wide across and tall down.
 */
class GlideGesture(
    private val keyWidth: Float,
    private val keyHeight: Float,
    private val longPressMillis: Long,
    private val downKeys: Float = DOWN,
    private val stroke: Boolean = false,
    // how far a stroke drawn goes before it is one, any way, in the places' units; 0: [STROKE] of a key instead
    private val strokeDistance: Float = 0f,
) {

    private var x0 = 0f
    private var y0 = 0f
    private var t0 = 0L
    private var possible = false

    /** Whether the finger down glides: once it is, it is till it lifts. */
    var gliding = false
        private set

    /** A finger down at [x], [y] at [time], on a letter or not. */
    fun down(x: Float, y: Float, time: Long, onLetter: Boolean) {
        x0 = x
        y0 = y
        t0 = time
        possible = onLetter
        gliding = false
    }

    /** Another finger down: two fingers do not glide, a fast typist's taps overlapping. */
    fun otherDown() {
        possible = false
    }

    /** The finger at [x], [y] at [time]: whether it glides, from now if not before. */
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

    /** The finger lifted, or the touch cancelled. */
    fun up() {
        possible = false
        gliding = false
    }

    companion object {
        const val ACROSS = 0.8f
        const val DOWN = 1.6f

        /** The nine keys' up or down: their keys as tall as the next row is far, a digit key's swipe shorter still. */
        const val NINE_KEYS_DOWN = 0.8f

        /** How far a stroke goes before it is one where no distance is given, in keys (a key's width across, its height down): on a phone's keys about 19dp down, 10dp across. */
        const val STROKE = 0.3f
    }
}
