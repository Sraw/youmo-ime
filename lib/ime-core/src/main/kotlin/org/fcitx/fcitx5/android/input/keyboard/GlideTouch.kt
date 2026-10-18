/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.engine.glide.GlideLayout

/**
 * A touch on the keyboard as glide typing (滑行输入) has it, before the keys: which finger may glide
 * ([GlideGesture]), when the keys are cancelled, which events are the glide's alone (the other
 * fingers' once it glides, and theirs till all lift if the gliding one lifts first), and the path
 * it leaves in the decoder's units ([path]). Places in the keyboard's pixels; [downKeys] as [GlideGesture]'s.
 */
class GlideTouch(private val downKeys: Float = GlideGesture.DOWN) {

    /** What becomes of an event: the keys', a cancel to the keys as a glide starts, or the glide's alone. */
    enum class Route { KEYS, CANCEL_KEYS, GLIDE }

    private var gesture: GlideGesture? = null
    // the one made, kept for the next touch: made anew only as the keys or the long press change
    private var made: GlideGesture? = null
    private var t0 = 0L
    private var longPressMillis = 0L
    private var keyWidth = 0f
    private var keyHeight = 0f
    private var swallowing = false
    private var glided: FloatArray? = null

    /** The finger that may glide, [NO_POINTER] if none. */
    var pointer = NO_POINTER
        private set

    /** The places so far, x and y in turn, [count] of them used: kept only while a glide may still come. */
    var points = FloatArray(INITIAL_POINTS)
        private set
    var count = 0
        private set

    val gliding: Boolean get() = gesture?.gliding == true

    /**
     * Whether an event is swallowed: the glide ended with another finger down, whose touch is none of
     * the keys' till all lift. [first] an ACTION_DOWN, [last] an ACTION_UP or ACTION_CANCEL.
     */
    fun swallows(first: Boolean, last: Boolean): Boolean {
        if (first) swallowing = false
        if (!swallowing) return false
        if (last) swallowing = false
        return true
    }

    /**
     * The first finger down, [pointerId] at [x], [y] at [time]: it may glide if [onLetter] (a glide
     * taken now, and on a letter), keys [keyWidth] by [keyHeight].
     */
    fun down(pointerId: Int, x: Float, y: Float, time: Long, onLetter: Boolean, keyWidth: Float, keyHeight: Float, longPressMillis: Long) {
        reset()
        glided = null
        if (!onLetter || keyWidth <= 0f || keyHeight <= 0f) return
        val same = made != null && keyWidth == this.keyWidth && keyHeight == this.keyHeight && longPressMillis == this.longPressMillis
        if (!same) made = GlideGesture(keyWidth, keyHeight, longPressMillis, downKeys)
        pointer = pointerId
        t0 = time
        this.keyWidth = keyWidth
        this.keyHeight = keyHeight
        this.longPressMillis = longPressMillis
        gesture = made?.apply { down(x, y, time, onLetter = true) }
        add(x, y)
    }

    /** Another finger down: the glide's if it glides; else no glide comes of this touch. */
    fun otherDown(): Route {
        if (gliding) return Route.GLIDE
        reset()
        return Route.KEYS
    }

    /** A place the finger passed (a move's history), kept while a glide may come. */
    fun add(x: Float, y: Float) {
        if (gesture == null) return
        if (count + 2 > points.size) points = points.copyOf(points.size * 2)
        points[count++] = x
        points[count++] = y
    }

    /** The finger at [x], [y] at [time]. */
    fun move(x: Float, y: Float, time: Long): Route {
        val g = gesture ?: return Route.KEYS
        val was = g.gliding
        // its long press came up: the key's from now on, and nothing more kept
        if (!was && time - t0 > longPressMillis) {
            reset()
            return Route.KEYS
        }
        add(x, y)
        return when {
            !g.move(x, y, time) -> Route.KEYS
            was -> Route.GLIDE
            else -> Route.CANCEL_KEYS
        }
    }

    /** A move without the finger that may glide in it. */
    fun missing(): Route = if (gliding) Route.GLIDE else Route.KEYS

    /** A finger lifted, [pointerId] at [x], [y], another still down. */
    fun pointerUp(pointerId: Int, x: Float, y: Float): Route {
        if (!gliding) return Route.KEYS
        if (pointerId == pointer) {
            finish(x, y)
            swallowing = true
        }
        return Route.GLIDE
    }

    /** The last finger lifted at [x], [y], or the touch [cancelled]. */
    fun up(x: Float, y: Float, cancelled: Boolean): Route {
        if (!gliding) {
            reset()
            return Route.KEYS
        }
        if (cancelled) reset() else finish(x, y)
        return Route.GLIDE
    }

    /** The path of the glide just ended, once: across in key widths, down in [GlideLayout.ROW_HEIGHT]s of a key's height. */
    fun takeGlided(): FloatArray? = glided.also { glided = null }

    private fun finish(x: Float, y: Float) {
        add(x, y)
        glided = path(points, count, keyWidth, keyHeight)
        reset()
    }

    private fun reset() {
        gesture?.up()
        gesture = null
        pointer = NO_POINTER
        count = 0
    }

    companion object {
        /** MotionEvent.INVALID_POINTER_ID's value. */
        const val NO_POINTER = -1
        private const val INITIAL_POINTS = 256

        /** A height in pixels in the decoder's units: rows of the height its radii are for, whatever the keys' shape (wide and short in landscape). */
        fun unitY(keyHeight: Float) = keyHeight / GlideLayout.ROW_HEIGHT

        /** [count] of [points] (x and y in turn, pixels) in key widths across and [unitY] down. */
        fun path(points: FloatArray, count: Int, keyWidth: Float, keyHeight: Float): FloatArray {
            val unitY = unitY(keyHeight)
            return FloatArray(count) { if (it % 2 == 0) points[it] / keyWidth else points[it] / unitY }
        }
    }
}
