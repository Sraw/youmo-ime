/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.engine.glide.GlideLayout
import kotlin.math.hypot

/**
 * A touch on the keyboard as glide typing (滑行输入) has it, before the keys: which finger may glide
 * ([GlideGesture]), when the keys are cancelled, which events are the glide's alone (the other
 * fingers' once it glides, and theirs till all lift if the gliding one lifts first), and the path
 * it leaves in the decoder's units ([path]). Places in the keyboard's pixels; [downKeys] as [GlideGesture]'s.
 */
class GlideTouch(private val downKeys: Float = GlideGesture.DOWN) {

    /**
     * What becomes of an event: the keys', a cancel to the keys as a glide starts, or the glide's alone;
     * or, as a stroke drawn lifts short of its lift distance, the key it came down on tapped there (its touch long cancelled).
     */
    enum class Route { KEYS, CANCEL_KEYS, GLIDE, TAP_KEY }

    private var gesture: GlideGesture? = null
    // the one made, kept for the next touch: made anew only as the keys or the long press change
    private var made: GlideGesture? = null
    private var t0 = 0L
    private var longPressMillis = 0L
    private var keyWidth = 0f
    private var keyHeight = 0f
    private var stroke = false
    private var strokeDistance = 0f
    // a stroke drawn lifted short of this (pixels from its down, the farthest it went) was the key's tap; 0 for none
    private var lift = 0f
    private var onLetterDown = false
    private var x0 = 0f
    private var y0 = 0f
    private var far = 0f
    private var swallowing = false
    private var glided: FloatArray? = null

    // handwriting on: the touch a stroke written from its down
    private var writing = false

    // the writing finger lifted with another down (a thumb resting): a finger down again on the letters writes the
    // next stroke ([downAgain]), as 0.6.0's pad had it, the touch still none of the keys'
    private var writeAgain = false

    /** Whether a finger down now, another resting since a stroke, writes the next one ([downAgain]). */
    val writesAgain: Boolean get() = swallowing && writeAgain

    /** The finger that may glide, [NO_POINTER] if none. */
    var pointer = NO_POINTER
        private set

    /** The places so far, x and y in turn, [count] of them used: kept only while a glide may still come. */
    var points = FloatArray(INITIAL_POINTS)
        private set
    var count = 0
        private set

    val gliding: Boolean get() = writing || gesture?.gliding == true

    /** Whether the path is drawn now: a stroke drawn not till it goes past its lift distance, a tap's wobble shows nothing. */
    val shown: Boolean get() = gliding && far >= lift

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
     * A finger down, [pointerId] at [x], [y] at [time], while another rests since the stroke written last lifted
     * ([writesAgain]): the next stroke if [onLetter] (the letter area), [gliding] as at [down]; else still swallowed.
     */
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
     * The first finger down, [pointerId] at [x], [y] at [time]: it may glide if [onLetter] (a glide
     * taken now, and on a letter), keys [keyWidth] by [keyHeight]; a [stroke] drawn if so (GlideGesture's).
     * A stroke may start [inGap] too, between a split keyboard's halves ([SplitGap]): no key is there to
     * tap, and a stroke's shape needs no key under it; a glide's path is read from the letters it starts on, so it starts on one.
     *
     * Handwriting on ([writes], the toolbar's 手写): a touch there is a stroke written from its down, [gliding]
     * at once, so the caller presses no key (it asks right after this); [onLetter] then is the whole letter
     * area, Shift too, a split keyboard's gap with it. Its places are the caller's to put on the pad from
     * the events, in pixels: [takeGlided] is in key units, scaled apart across and down, for the glide decoder alone.
     *
     * A stroke drawn starts [strokeDistance] pixels off, any way ([GlideGesture]): its keys cancelled then, before
     * their swipe or long press. Lifted with the finger never [liftDistance] from its down, it was a tap after all:
     * [up] says [Route.TAP_KEY], the key it came down on typed by the caller as a tap there; past it, a stroke,
     * however far back the finger came.
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

    // the gesture for these keys and strokes: the one made before if they are the same
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

    /**
     * Another finger down: the glide's if it glides; else no glide comes of this touch. A stroke not yet past
     * its lift distance is the first finger's tap after all ([Route.TAP_KEY], [Route.KEYS] from no letter),
     * the other finger the keys': two thumbs typing fast, the first drifting 12 dp or so as the second lands
     * (0.6.0 started a stroke at about 19 dp down, and typed both).
     */
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

    /** A place the finger passed (a move's history), kept while a glide may come. */
    fun add(x: Float, y: Float) {
        if (gesture == null) return
        if (count + 2 > points.size) points = points.copyOf(points.size * 2)
        points[count++] = x
        points[count++] = y
        far = maxOf(far, hypot(x - x0, y - y0))
    }

    /** The finger at [x], [y] at [time]. */
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

    /** A move without the finger that may glide in it. */
    fun missing(): Route = if (gliding) Route.GLIDE else Route.KEYS

    /** A finger lifted, [pointerId] at [x], [y], another still down. */
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

    /** The last finger lifted at [x], [y], or the touch [cancelled]. */
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

    // a stroke drawn lifting at [x], [y] never its lift distance from its down: no path, nothing kept
    private fun short(x: Float, y: Float): Boolean {
        if (lift <= 0f || maxOf(far, hypot(x - x0, y - y0)) >= lift) return false
        reset()
        return true
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
        writing = false
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
