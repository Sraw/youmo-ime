/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import kotlin.math.hypot

/**
 * A touch on 展开候选's list while 笔画筛选 draws there (dev/design/061-stroke-on-candidate-page.md, the user's
 * decision): the whole list is the strokes', none of its own drag scrolling; a tap still picks and a long press still
 * opens the candidate's menu, the cell's own. The list's view passes each event of a finger here and does as the
 * [Route] says. Places in the list's pixels, the same both ways: [stroke] is read from them as they are.
 *
 * - Down: the list's (the cell pressed, its long press timed, 长按延迟's).
 * - Moving, short of [start] from its down: held from the list. Its long press due first: the list's from then on.
 * - Past [start] before that: a stroke, the list told a cancel; its trail [shown] once past the lift distance.
 * - Lifted never past [start]: within the cell's touch slop a tap ([Route.TAP]), the cell under the down tapped;
 *   past the slop nothing. A stroke lifted short of the lift distance (笔画筛选灵敏度) is nothing either (the user's:
 *   no candidate picked by a stroke that fell short); past it [Route.STROKE] with [stroke] read (null: none).
 * - Another finger: swallowed till every finger is up, the first going on; the first lifted first ends it there.
 */
class PageStrokeTouch {

    /**
     * What becomes of an event: the list's ([LIST]), kept from it ([HOLD]), a cancel to it as a stroke starts
     * ([CANCEL_LIST]); at the lift, a cancel to it and then the cell under the down tapped ([TAP]), the stroke
     * [stroke] sent ([STROKE]) or nothing ([NONE]).
     */
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

    /** The finger drawing, [GlideTouch.NO_POINTER] if none. */
    var pointer = GlideTouch.NO_POINTER
        private set

    /** Where the finger came down, for the cell to tap there. */
    val downX: Float get() = x0
    val downY: Float get() = y0

    /** The places so far, x and y in turn, [count] of them used. */
    var points = FloatArray(INITIAL_POINTS)
        private set
    var count = 0
        private set

    /** The stroke the last [Route.STROKE] read, `Strokes.KEYS`' `h s p n z`; null for none. */
    var stroke: Char? = null
        private set

    /** Whether the trail is drawn now: a stroke past its lift distance. */
    val shown: Boolean get() = state == State.DRAWING && far >= lift

    /** Whether a finger of this touch is still down (a stroke drawn, held, or the list's). */
    val active: Boolean get() = state != State.IDLE

    /**
     * The first finger down, [pointerId] at [x], [y] at [time]: a stroke starts [start] pixels off, any way; one
     * lifted short of [lift] was nothing; a lift within [slop] never started is a tap. A move after [longPressMillis]
     * with no stroke started is the cell's long press's.
     */
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

    /** A place the finger passed (a move's history). */
    fun add(x: Float, y: Float) {
        if (state != State.PENDING && state != State.DRAWING) return
        if (count + 2 > points.size) points = points.copyOf(points.size * 2)
        points[count++] = x
        points[count++] = y
        far = maxOf(far, hypot(x - x0, y - y0))
    }

    /** The finger at [x], [y] at [time] (its history [add]ed first). */
    fun move(x: Float, y: Float, time: Long): Route = when (state) {
        State.PENDING -> {
            if (time - t0 > longPressMillis) {
                // the long press came first: the cell's, as a key's is (GlideTouch.move: past the delay, not at it)
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

    /** Another finger down: swallowed, whatever the first does. */
    fun otherDown(): Route = Route.HOLD

    /** A finger lifted, [pointerId] at [x], [y] at [time], another still down: the first's ends the touch, the rest swallowed. */
    fun pointerUp(pointerId: Int, x: Float, y: Float, time: Long): Route {
        if (pointerId != pointer || state == State.IDLE || state == State.SWALLOW) return Route.HOLD
        val route = lifted(x, y, time)
        state = State.SWALLOW
        return route
    }

    /** The last finger lifted at [x], [y] at [time]. */
    fun up(x: Float, y: Float, time: Long): Route {
        val route = if (state == State.SWALLOW || state == State.IDLE) Route.HOLD else lifted(x, y, time)
        state = State.IDLE
        pointer = GlideTouch.NO_POINTER
        return route
    }

    /** The touch cancelled: nothing of it. */
    fun cancel() {
        state = State.IDLE
        pointer = GlideTouch.NO_POINTER
        count = 0
    }

    private fun lifted(x: Float, y: Float, time: Long): Route = when (state) {
        State.PENDING -> {
            far = maxOf(far, hypot(x - x0, y - y0))
            when {
                // held still past the long press: it came up, the lift is the cell's (its menu's)
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
     * Whether a finger draws 笔画筛选's strokes now, on the keys ([keys], BaseKeyboard.glideInput's) and on 展开候选's
     * list ([on]), one rule for both: 在键上划 is 笔画筛选 ([chosen]), the field one where words are typed (not a
     * [password]'s, not an [address]'s), pinyin typed ([composing], not a [tempMode]'s), an input method that filters
     * ([im], InputMethodCatalog.filtersByStrokes) on a keyboard that draws them ([filters]: 注音's draws none, its keys
     * BaseKeyboard.strokesDrawn). The list's besides: the toolbar's 手写 not writing on the keys
     * ([HandwritingMode.writes]: [handwritingOpen], [textPack] there, the recognizer [recognizerLoadable]; on the keys
     * GlideInput's inking decides it, the ink's own), and no screen reader's touch exploration ([exploring]: its
     * scrolling is a finger dragged, which would draw; on the keys it comes as hovering, not a touch).
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
            /** The keys' [keys] (BaseKeyboard.drawsStrokes), given each value the rule reads. */
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

    /**
     * The notice 「这里划笔画，翻页用右边 ↑↓」 (the user's): said as the page opens drawing strokes ([Draws.on]), the
     * first [TIMES] times so, for [MILLIS]; no setting. [shown]: how often it was, kept; counted only when it shows.
     */
    object Hint {
        const val TIMES = 3
        const val MILLIS = 2000L

        fun shows(draws: Boolean, shown: Int): Boolean = draws && shown < TIMES
    }

    private companion object {
        const val INITIAL_POINTS = 128
    }
}
