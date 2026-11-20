/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import kotlin.math.hypot

/**
 * A touch on 展开候选's list while 笔画筛选 draws there: the list does not drag-scroll; a tap still picks, a long press
 * still opens the menu. A move past [start] before the long press is due is a stroke (the list told a cancel); a lift
 * never past it within the slop is a tap; a stroke short of the lift distance picks nothing. Other fingers are swallowed.
 */
class PageStrokeTouch {

    enum class Route { LIST, HOLD, CANCEL_LIST, TAP, STROKE, NONE }

    private enum class State { IDLE, PENDING, DRAWING, LIST, SWALLOW }

    private var state = State.IDLE
    private var t0 = 0L
    private var x0 = 0f
    private var y0 = 0f
    private var far = 0f
    private var start = 0f
    private var lift = 0f
    private var slop = 0f
    private var longPressMillis = 0L

    var pointer = GlideTouch.NO_POINTER
        private set

    /** For the cell to tap there. */
    val downX: Float get() = x0
    val downY: Float get() = y0

    /** x and y in turn, [count] used. */
    var points = FloatArray(INITIAL_POINTS)
        private set
    var count = 0
        private set

    /** `Strokes.KEYS`' `h s p n z`; null for none. */
    var stroke: Char? = null
        private set

    val shown: Boolean get() = state == State.DRAWING && far >= lift

    val active: Boolean get() = state != State.IDLE

    /** A move after [longPressMillis] with no stroke started is the cell's long press's. */
    @Suppress("LongParameterList")
    fun down(pointerId: Int, x: Float, y: Float, time: Long, start: Float, lift: Float, slop: Float, longPressMillis: Long): Route {
        state = State.PENDING
        pointer = pointerId
        t0 = time
        x0 = x
        y0 = y
        far = 0f
        count = 0
        stroke = null
        this.start = start
        this.lift = lift
        this.slop = slop
        this.longPressMillis = longPressMillis
        add(x, y)
        return Route.LIST
    }

    fun add(x: Float, y: Float) {
        if (state != State.PENDING && state != State.DRAWING) return
        if (count + 2 > points.size) points = points.copyOf(points.size * 2)
        points[count++] = x
        points[count++] = y
        far = maxOf(far, hypot(x - x0, y - y0))
    }

    /** Its history [add]ed first. */
    fun move(x: Float, y: Float, time: Long): Route = when (state) {
        State.PENDING -> {
            if (time - t0 > longPressMillis) {
                // the cell's, as a key's is (GlideTouch.move: past the delay, not at it)
                state = State.LIST
                Route.LIST
            } else {
                add(x, y)
                if (far >= start) {
                    state = State.DRAWING
                    Route.CANCEL_LIST
                } else {
                    Route.HOLD
                }
            }
        }
        State.DRAWING -> {
            add(x, y)
            Route.HOLD
        }
        State.LIST -> Route.LIST
        State.IDLE, State.SWALLOW -> Route.HOLD
    }

    fun otherDown(): Route = Route.HOLD

    /** The first finger's lift ends the touch, the rest swallowed. */
    fun pointerUp(pointerId: Int, x: Float, y: Float, time: Long): Route {
        if (pointerId != pointer || state == State.IDLE || state == State.SWALLOW) return Route.HOLD
        val route = lifted(x, y, time)
        state = State.SWALLOW
        return route
    }

    fun up(x: Float, y: Float, time: Long): Route {
        val route = if (state == State.SWALLOW || state == State.IDLE) Route.HOLD else lifted(x, y, time)
        state = State.IDLE
        pointer = GlideTouch.NO_POINTER
        return route
    }

    fun cancel() {
        state = State.IDLE
        pointer = GlideTouch.NO_POINTER
        count = 0
    }

    private fun lifted(x: Float, y: Float, time: Long): Route = when (state) {
        State.PENDING -> {
            far = maxOf(far, hypot(x - x0, y - y0))
            when {
                time - t0 > longPressMillis -> Route.LIST
                far <= slop -> Route.TAP
                else -> Route.NONE
            }
        }
        State.DRAWING -> {
            add(x, y)
            if (far < lift) {
                Route.NONE
            } else {
                stroke = StrokeShape.classify(points, count)
                if (stroke == null) Route.NONE else Route.STROKE
            }
        }
        State.LIST -> Route.LIST
        State.IDLE, State.SWALLOW -> Route.HOLD
    }

    /**
     * One rule for the keys and 展开候选's list: 笔画筛选 chosen, a field where words are typed (no password or address),
     * pinyin composing (no temp mode), an input method and keyboard that filter. The list's besides: 手写 not writing on
     * the keys, and no screen reader exploring (its scrolling is a dragged finger, which would draw).
     */
    class Draws {
        var chosen = false
        var password = false
        var address = false
        var composing = false
        var tempMode = false
        var im = ""
        var filters = true
        var handwritingOpen = false
        var textPack = false
        var recognizerLoadable = false
        var exploring = false

        val keys: Boolean
            get() = chosen && !password && !address && composing && !tempMode && InputMethodCatalog.filtersByStrokes(im) && filters

        val on: Boolean
            get() = keys && !writes && !exploring

        private val writes: Boolean
            get() = textPack && HandwritingMode.writes(handwritingOpen, im, password, tempMode, recognizerLoadable, exploring)

        companion object {
            @Suppress("LongParameterList")
            fun keys(chosen: Boolean, password: Boolean, address: Boolean, composing: Boolean, tempMode: Boolean, im: String, filters: Boolean): Boolean =
                Draws().also {
                    it.chosen = chosen
                    it.password = password
                    it.address = address
                    it.composing = composing
                    it.tempMode = tempMode
                    it.im = im
                    it.filters = filters
                }.keys
        }
    }

    /** 「这里划笔画，翻页用右边 ↑↓」, the first [TIMES] times the page opens drawing strokes; counted only when it shows. */
    object Hint {
        const val TIMES = 3
        const val MILLIS = 2000L

        fun shows(draws: Boolean, shown: Int): Boolean = draws && shown < TIMES
    }

    private companion object {
        const val INITIAL_POINTS = 128
    }
}
