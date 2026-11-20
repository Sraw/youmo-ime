/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.engine.glide.GlideLayout
import kotlin.math.hypot

/** A keyboard touch as glide typing (滑行输入) sees it before the keys: which finger glides, when keys are cancelled, which events are the glide's. */
class GlideTouch(private val downKeys: Float = GlideGesture.DOWN) {

    /** [TAP_KEY]: a stroke lifted short of its lift distance, its key tapped there (its touch long cancelled). */
    enum class Route { KEYS, CANCEL_KEYS, GLIDE, TAP_KEY }

    private var gesture: GlideGesture? = null
    // made anew only as the keys or the long press change
    private var made: GlideGesture? = null
    private var t0 = 0L
    private var longPressMillis = 0L
    private var keyWidth = 0f
    private var keyHeight = 0f
    private var stroke = false
    private var strokeDistance = 0f
    // a stroke lifted short of this (px from its down, the farthest it went) was a tap; 0 for none
    private var lift = 0f
    private var onLetterDown = false
    private var x0 = 0f
    private var y0 = 0f
    private var far = 0f
    private var swallowing = false
    private var glided: FloatArray? = null

    private var writing = false

    // the writing finger lifted with another resting: a finger down again on the letters writes the next stroke
    private var writeAgain = false

    val writesAgain: Boolean get() = swallowing && writeAgain

    var pointer = NO_POINTER
        private set

    /** x and y in turn, [count] used; kept only while a glide may still come. */
    var points = FloatArray(INITIAL_POINTS)
        private set
    var count = 0
        private set

    val gliding: Boolean get() = writing || gesture?.gliding == true

    /** Not till past the lift distance: a tap's wobble shows nothing. */
    val shown: Boolean get() = gliding && far >= lift

    /** The glide ended with another finger down, whose touch is none of the keys' till all lift. */
    fun swallows(first: Boolean, last: Boolean): Boolean {
        if (first) swallowing = false
        if (!swallowing) return false
        if (last) swallowing = false
        return true
    }

    /** With another finger resting since the last stroke: the next stroke if [onLetter], else still swallowed. */
    fun downAgain(pointerId: Int, x: Float, y: Float, time: Long, onLetter: Boolean): Boolean {
        if (!writesAgain || !onLetter) return false
        swallowing = false
        writing = true
        pointer = pointerId
        t0 = time
        gesture = made?.apply { down(x, y, time, onLetter = true) }
        add(x, y)
        return true
    }

    /**
     * The first finger down: it may glide if [onLetter]. A stroke may start [inGap] of a split keyboard (no key to
     * tap). Handwriting on ([writes]): a stroke from its down, [gliding] at once, so the caller presses no key.
     * A stroke starts [strokeDistance] px off, cancelling its keys; lifted never [liftDistance] away, it was a tap ([Route.TAP_KEY]).
     */
    fun down(
        pointerId: Int, x: Float, y: Float, time: Long, onLetter: Boolean, keyWidth: Float, keyHeight: Float, longPressMillis: Long,
        stroke: Boolean = false, inGap: Boolean = false, writes: Boolean = false, strokeDistance: Float = 0f, liftDistance: Float = 0f,
    ) {
        reset()
        glided = null
        writeAgain = false
        onLetterDown = onLetter
        val starts = onLetter || (stroke || writes) && inGap
        if (!starts || keyWidth <= 0f || keyHeight <= 0f) return
        writing = writes
        make(keyWidth, keyHeight, longPressMillis, stroke, if (stroke && !writes) strokeDistance else 0f)
        lift = if (stroke && !writes) liftDistance else 0f
        pointer = pointerId
        t0 = time
        x0 = x
        y0 = y
        far = 0f
        gesture = made?.apply { down(x, y, time, onLetter = true) }
        add(x, y)
    }

    // the one made before if they are the same
    private fun make(keyWidth: Float, keyHeight: Float, longPressMillis: Long, drawn: Boolean, distance: Float) {
        val same = made != null && keyWidth == this.keyWidth && keyHeight == this.keyHeight && longPressMillis == this.longPressMillis &&
            drawn == this.stroke && distance == strokeDistance
        if (!same) made = GlideGesture(keyWidth, keyHeight, longPressMillis, downKeys, drawn, distance)
        strokeDistance = distance
        this.keyWidth = keyWidth
        this.keyHeight = keyHeight
        this.longPressMillis = longPressMillis
        stroke = drawn
    }

    /** The glide's if it glides; a stroke not past its lift distance is the first finger's tap (two thumbs typing fast). */
    fun otherDown(): Route {
        if (gliding && lift > 0f && far < lift) {
            val letter = onLetterDown
            reset()
            return if (letter) Route.TAP_KEY else Route.KEYS
        }
        if (gliding) return Route.GLIDE
        reset()
        return Route.KEYS
    }

    /** A move's history, kept while a glide may come. */
    fun add(x: Float, y: Float) {
        if (gesture == null) return
        if (count + 2 > points.size) points = points.copyOf(points.size * 2)
        points[count++] = x
        points[count++] = y
        far = maxOf(far, hypot(x - x0, y - y0))
    }

    fun move(x: Float, y: Float, time: Long): Route {
        val g = gesture ?: return Route.KEYS
        if (writing) {
            add(x, y)
            return Route.GLIDE
        }
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

    fun missing(): Route = if (gliding) Route.GLIDE else Route.KEYS

    fun pointerUp(pointerId: Int, x: Float, y: Float): Route {
        if (!gliding) return Route.KEYS
        if (pointerId == pointer) {
            writeAgain = writing
            // never short of the lift here: another finger down before it was past it made the tap ([otherDown])
            finish(x, y)
            swallowing = true
        }
        return Route.GLIDE
    }

    fun up(x: Float, y: Float, cancelled: Boolean): Route {
        if (!gliding) {
            reset()
            return Route.KEYS
        }
        when {
            cancelled -> reset()
            // the key's tap where there is one, none in a split keyboard's gap
            short(x, y) -> return if (onLetterDown) Route.TAP_KEY else Route.GLIDE
            else -> finish(x, y)
        }
        return Route.GLIDE
    }

    private fun short(x: Float, y: Float): Boolean {
        if (lift <= 0f || maxOf(far, hypot(x - x0, y - y0)) >= lift) return false
        reset()
        return true
    }

    /** Once: across in key widths, down in [GlideLayout.ROW_HEIGHT]s. */
    fun takeGlided(): FloatArray? = glided.also { glided = null }

    private fun finish(x: Float, y: Float) {
        add(x, y)
        glided = path(points, count, keyWidth, keyHeight)
        reset()
    }

    private fun reset() {
        gesture?.up()
        gesture = null
        writing = false
        pointer = NO_POINTER
        count = 0
    }

    companion object {
        /** MotionEvent.INVALID_POINTER_ID's value. */
        const val NO_POINTER = -1
        private const val INITIAL_POINTS = 256

        /** Rows of the height the decoder's radii are for, whatever the keys' shape (wide and short in landscape). */
        fun unitY(keyHeight: Float) = keyHeight / GlideLayout.ROW_HEIGHT

        fun path(points: FloatArray, count: Int, keyWidth: Float, keyHeight: Float): FloatArray {
            val unitY = unitY(keyHeight)
            return FloatArray(count) { if (it % 2 == 0) points[it] / keyWidth else points[it] / unitY }
        }
    }
}
