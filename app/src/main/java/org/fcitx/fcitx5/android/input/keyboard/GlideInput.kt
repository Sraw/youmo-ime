/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.util.Log
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.annotation.VisibleForTesting
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.engine.glide.GlideLayout
import splitties.dimensions.dp

/**
 * Glide typing on [keyboard]'s letters ([GlideGesture]): while it glides the keys have none of the touch,
 * cancelled as it starts. Where [strokes] says so the touch is a stroke (笔画筛选) instead, started far
 * sooner; lifted short of [strokeLift] it was a tap ([tapKey]). Where [writes] says so, a touch down in the
 * letter area ([writingArea]) is ink written there, before any glide or stroke filter.
 *
 * In debug builds ([trace]) each touch down on a letter is logged as `YoumoStrokeTap`, for the stroke threshold.
 */
class GlideInput(
    private val keyboard: ViewGroup,
    theme: Theme,
    // an input method that reads one, the setting on, a field where words are typed
    private val enabled: () -> Boolean,
    // where the scene draws it: the nine keys' several on one
    private val letters: () -> List<Pair<Char, KeyPlace>>,
    private val longPressMillis: () -> Long,
    // in key heights
    down: Float = GlideGesture.DOWN,
    private val strokes: () -> Boolean = { false },
    private val stroked: (path: FloatArray) -> Unit = {},
    // in dp (笔画筛选灵敏度)
    private val strokeLift: () -> Float = { 0f },
    // in the keyboard's pixels: a stroke lifted short of [strokeLift]
    private val tapKey: (x: Float, y: Float) -> Unit = { _, _ -> },
    // besides the letters (九键's 1), by their cells: the gap between one and a letter goes as between two letters
    private val strokeKeys: () -> List<KeyPlace> = { emptyList() },
    private val ink: KeyInk? = null,
    private val writes: () -> Boolean = { false },
    // the letters' hit areas and Shift's
    private val writingArea: (x: Float, y: Float) -> Boolean = { _, _ -> false },
    private val glided: (path: FloatArray, layout: GlideLayout) -> Unit,
) {
    private val touch = GlideTouch(down)
    private val trail = Path()
    private val rect = Rect()
    private val strokeStart = keyboard.dp(STROKE_START_DP)
    // debug builds alone; its gap= is from the last lift on this keyboard, a switch starts it anew
    private val trace = if (BuildConfig.DEBUG) {
        StrokeTapTrace(keyboard.resources.displayMetrics.density, ViewConfiguration.get(keyboard.context).scaledTouchSlop.toFloat())
    } else {
        null
    }
    private val traced = keyboard.javaClass.simpleName

    private var stroking = false
    private var downX = 0f
    private var downY = 0f

    // [fed] of its places on the pad so far, -1 while it is none
    private var inking: KeyInk? = null
    private var fed = -1

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = keyboard.dp(TRAIL_DP)
        color = theme.accentKeyBackgroundColor
        alpha = TRAIL_ALPHA
    }

    /** True where it is the glide's; [toKeys] told a cancel as a glide starts. */
    fun dispatch(event: MotionEvent, toKeys: (MotionEvent) -> Boolean): Boolean {
        val masked = event.actionMasked
        val last = masked == MotionEvent.ACTION_UP || masked == MotionEvent.ACTION_CANCEL
        if (masked == MotionEvent.ACTION_POINTER_DOWN && writesAgain()) return downAgain(event)
        if (swallowed(event, last)) return true
        val i = event.actionIndex
        val route = when (masked) {
            MotionEvent.ACTION_DOWN -> down(event)
            MotionEvent.ACTION_POINTER_DOWN -> pointerDown(event).also { trace?.otherFinger() }
            MotionEvent.ACTION_MOVE -> move(event)
            MotionEvent.ACTION_POINTER_UP -> pointerUp(event, i)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> up(event, i, cancelled = masked == MotionEvent.ACTION_CANCEL)
            else -> GlideTouch.Route.KEYS
        }
        routed(route, event.eventTime)
        // a stroke written is the pad's, not a path read
        touch.takeGlided()?.let { if (inking == null) send(it) }
        if (last) traceUp(event)
        if (keys(route, masked)) {
            // a stroke may start where no key takes the down (the split gap): kept all the same, else its moves never
            // come here (the parent finds no target)
            return toKeys(event) || masked == MotionEvent.ACTION_DOWN && touch.pointer != GlideTouch.NO_POINTER
        }
        if (route == GlideTouch.Route.CANCEL_KEYS) {
            val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
            toKeys(cancel)
            cancel.recycle()
        }
        keyboard.invalidate()
        return true
    }

    // a stroke short of its lift as another finger lands: the first's tap made, that finger the keys'
    private fun keys(route: GlideTouch.Route, masked: Int) =
        route == GlideTouch.Route.KEYS || route == GlideTouch.Route.TAP_KEY && masked == MotionEvent.ACTION_POINTER_DOWN

    // a stroke's other fingers are swallowed to the last lift, traced all the same
    private fun swallowed(event: MotionEvent, last: Boolean): Boolean {
        if (!touch.swallows(first = event.actionMasked == MotionEvent.ACTION_DOWN, last = last)) return false
        if (last) traceUp(event)
        return true
    }

    private fun traceUp(event: MotionEvent) {
        trace?.up(event.eventTime, cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL)?.let { Log.i(TRACE_TAG, it) }
    }

    private fun routed(route: GlideTouch.Route, time: Long) {
        if (route == GlideTouch.Route.CANCEL_KEYS) trace?.started(time)
        if (route == GlideTouch.Route.TAP_KEY) {
            trace?.tapped()
            tapKey(downX, downY)
        }
    }

    private fun down(event: MotionEvent): GlideTouch.Route {
        downX = event.x
        downY = event.y
        // before a glide or a stroke filter, whatever 在键上划 is
        inking = ink?.takeIf { writes() && it.loadable }
        stroking = inking == null && strokes()
        fed = -1
        val drawn = stroking || inking != null
        val keys = if (drawn || enabled()) letters() else emptyList()
        // read before the one rect is used for the others
        val first = keys.firstOrNull()?.let { bounds(it.second) }
        val keyWidth = first?.width()?.toFloat() ?: 0f
        val keyHeight = first?.height()?.toFloat() ?: 0f
        val x = event.x.toInt()
        val y = event.y.toInt()
        // written on: the letter area as the hit areas share it out, Shift too; else on a letter as drawn,
        // or a stroke on a key of its own area (九键's 1)
        val onLetter = if (inking != null) {
            writingArea(event.x, event.y)
        } else {
            keys.any { (_, v) -> bounds(v).contains(x, y) } || stroking && strokeKeys().any { bounds(it).contains(x, y) }
        }
        val inGap = drawn && !onLetter && inGap(keys, event.x, event.y, keyWidth)
        if (trace != null && inking == null) traceDown(event)
        touch.down(
            event.getPointerId(0), event.x, event.y, event.eventTime, onLetter,
            keyWidth, keyHeight, longPressMillis(), stroke = stroking, inGap = inGap, writes = inking != null,
            strokeDistance = strokeStart, liftDistance = if (stroking) keyboard.dp(strokeLift()) else 0f,
        )
        if (inking == null || !touch.gliding) return GlideTouch.Route.KEYS
        feed()
        return GlideTouch.Route.GLIDE
    }

    private fun traceDown(event: MotionEvent) {
        val x = event.x.toInt()
        val y = event.y.toInt()
        val cell = letters().firstOrNull { bounds(it.second).contains(x, y) } ?: return
        val b = bounds(cell.second)
        tracedPointer = event.getPointerId(0)
        trace?.down(
            event.x, event.y, event.eventTime, b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
            traced, stroking, longPressMillis(),
        )
    }

    /** Logged after the touches traced: a stroke taken back is a misread. */
    fun backspaced(time: Long) {
        trace?.let { Log.i(TRACE_TAG, it.backspace(time)) }
    }

    private var tracedPointer = -1

    // written on with a key held (a thumb on space or ⌫), a finger down on the letters is a stroke all the
    // same, the held key let go of
    private fun pointerDown(event: MotionEvent): GlideTouch.Route {
        if (touch.gliding || inking == null || !writes()) return touch.otherDown()
        val i = event.actionIndex
        val x = event.getX(i)
        val y = event.getY(i)
        val first = letters().firstOrNull()?.let { bounds(it.second) }
        if (first == null || !writingArea(x, y)) return touch.otherDown()
        touch.down(
            event.getPointerId(i), x, y, event.eventTime, onLetter = true,
            first.width().toFloat(), first.height().toFloat(), longPressMillis(), writes = true,
        )
        fed = -1
        feed()
        return GlideTouch.Route.CANCEL_KEYS
    }

    private fun writesAgain() = touch.writesAgain && inking != null && writes()

    // as from a down, else swallowed
    private fun downAgain(event: MotionEvent): Boolean {
        val i = event.actionIndex
        val x = event.getX(i)
        val y = event.getY(i)
        if (touch.downAgain(event.getPointerId(i), x, y, event.eventTime, writingArea(x, y))) {
            fed = -1
            feed()
            keyboard.invalidate()
        }
        return true
    }

    private fun feed() {
        val k = inking ?: return
        val p = touch.points
        if (fed < 0) {
            k.down(p[0], p[1])
            fed = 2
        }
        while (fed + 1 < touch.count) {
            k.move(p[fed], p[fed + 1])
            fed += 2
        }
    }

    private fun lifted(event: MotionEvent, i: Int, cancelled: Boolean, end: () -> GlideTouch.Route): GlideTouch.Route {
        val k = inking
        val writing = k != null && fed >= 0 && touch.gliding
        if (writing && !cancelled) feed()
        val route = end()
        if (writing) {
            if (cancelled) {
                k!!.cancel()
            } else {
                k!!.move(event.getX(i), event.getY(i))
                k.up(event.eventTime)
            }
            fed = -1
        }
        return route
    }

    private fun pointerUp(event: MotionEvent, i: Int): GlideTouch.Route {
        if (event.getPointerId(i) != touch.pointer) return touch.pointerUp(event.getPointerId(i), event.getX(i), event.getY(i))
        return lifted(event, i, cancelled = false) { touch.pointerUp(event.getPointerId(i), event.getX(i), event.getY(i)) }
    }

    private fun up(event: MotionEvent, i: Int, cancelled: Boolean): GlideTouch.Route =
        lifted(event, i, cancelled) { touch.up(event.getX(i), event.getY(i), cancelled = cancelled) }

    @VisibleForTesting
    internal fun inGap(x: Float, y: Float): Boolean {
        val keys = letters()
        val keyWidth = keys.firstOrNull()?.let { bounds(it.second).width().toFloat() } ?: 0f
        return inGap(keys, x, y, keyWidth)
    }

    private fun inGap(keys: List<Pair<Char, KeyPlace>>, x: Float, y: Float, keyWidth: Float) =
        keys.isNotEmpty() && SplitGap.contains(boxes(keys), x, y, keyWidth)

    @VisibleForTesting
    internal val trailShown: Boolean get() = touch.shown

    private fun move(event: MotionEvent): GlideTouch.Route {
        // whether or not it may glide (the keys' own touch, glide typing off)
        trace?.let { t ->
            val j = event.findPointerIndex(tracedPointer).takeIf { it >= 0 } ?: return@let
            for (h in 0 until event.historySize) t.move(event.getHistoricalX(j, h), event.getHistoricalY(j, h))
            t.move(event.getX(j), event.getY(j))
        }
        val i = event.findPointerIndex(touch.pointer).takeIf { it >= 0 } ?: return touch.missing()
        for (h in 0 until event.historySize) touch.add(event.getHistoricalX(i, h), event.getHistoricalY(i, h))
        val route = touch.move(event.getX(i), event.getY(i), event.eventTime)
        if (route != GlideTouch.Route.KEYS) feed()
        return route
    }

    private fun send(path: FloatArray) {
        val keys = letters()
        if (keys.isEmpty()) return
        bounds(keys.first().second)
        val unitX = rect.width().toFloat()
        val unitY = GlideTouch.unitY(rect.height().toFloat())
        if (unitX <= 0f || unitY <= 0f) return
        // a stroke's shape is read as drawn: down in key widths too
        if (stroking) {
            val drawn = FloatArray(path.size) { if (it % 2 == 0) path[it] else path[it] * unitY / unitX }
            trace?.read(StrokeShape.classify(drawn))
            return stroked(drawn)
        }
        // every key a letter has: the split keyboard's G and V are on both halves
        val centres = keys.map { (c, v) -> bounds(v).let { b -> c to (b.exactCenterX() / unitX to b.exactCenterY() / unitY) } }
        glided(path, GlideLayout(centres))
    }

    private fun boxes(keys: List<Pair<Char, KeyPlace>>): FloatArray {
        val out = FloatArray(keys.size * 4)
        keys.forEachIndexed { i, (_, v) ->
            val b = bounds(v)
            out[i * 4] = b.left.toFloat()
            out[i * 4 + 1] = b.top.toFloat()
            out[i * 4 + 2] = b.right.toFloat()
            out[i * 4 + 3] = b.bottom.toFloat()
        }
        return out
    }

    private fun bounds(key: KeyPlace): Rect {
        key.place(keyboard, rect)
        return rect
    }

    fun draw(canvas: Canvas) {
        ink?.draw(canvas)
        if (!touch.shown || inking != null) return
        val p = touch.points
        trail.rewind()
        trail.moveTo(p[0], p[1])
        for (k in 2 until touch.count step 2) trail.lineTo(p[k], p[k + 1])
        canvas.drawPath(trail, paint)
    }

    companion object {
        private const val TRAIL_DP = 6f

        /** Its keys cancelled before their swipe (36dp) or long press. */
        const val STROKE_START_DP = 12f
        private const val TRAIL_ALPHA = 0xB0
        private const val TRACE_TAG = "YoumoStrokeTap"
    }
}
