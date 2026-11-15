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
 * Glide typing on [keyboard]'s letters ([GlideGesture]): the finger's path drawn as it goes, and
 * once it lifts, in key widths over where the [letters] are, given to [glided]. While it glides
 * the keys have none of the touch: they are cancelled as it starts, their preview and swipe with them.
 *
 * Where [strokes] says so as the finger goes down, the same touch is a stroke (笔画筛选) instead,
 * started far sooner ([STROKE_START_DP] any way, the keys cancelled then): its path, in key widths both ways, given to [stroked].
 * Lifted never [strokeLift] dp from its down, it was a tap: the key it came down on is tapped there ([tapKey]); its
 * trail is drawn only past that. The setting has one or the other, never both.
 * A stroke may also start between a split keyboard's halves ([SplitGap]); a glide starts on a letter only.
 *
 * Where [writes] says so as the finger goes down (the toolbar's 手写, HandwritingMode.writes), a touch down in the
 * letter area ([writingArea]: the letters' hit areas, the gaps between them, Shift; a split keyboard's gap) is a
 * stroke written there instead, from its down ([ink]): put on its pad and drawn in ink, no key pressed, before
 * any glide or stroke filter (061-handwriting-toolbar §3.2). A touch down elsewhere is that key's as ever.
 *
 * In debug builds ([trace]), each touch down on a letter is logged as `YoumoStrokeTap`, a line of its geometry
 * ([StrokeTapTrace]): what a real finger does, for the stroke threshold (dev/design/061-swipe-eval.md). It changes nothing.
 */
class GlideInput(
    private val keyboard: ViewGroup,
    theme: Theme,
    // whether a glide is taken now: an input method that reads one, the setting on, a field where words are typed
    private val enabled: () -> Boolean,
    // each letter and its key, where the scene draws it: the nine keys' several on one
    private val letters: () -> List<Pair<Char, KeyPlace>>,
    private val longPressMillis: () -> Long,
    // how far up or down a glide starts, in key heights (GlideGesture)
    down: Float = GlideGesture.DOWN,
    // whether a touch now is a stroke drawn rather than a glide (StrokeShape reads it)
    private val strokes: () -> Boolean = { false },
    private val stroked: (path: FloatArray) -> Unit = {},
    // how far a stroke drawn goes, at its lift, to be one (笔画筛选灵敏度, StrokeLift), in dp
    private val strokeLift: () -> Float = { 0f },
    // the key at a place (the keyboard's pixels) tapped there: a stroke drawn lifted short of [strokeLift]
    private val tapKey: (x: Float, y: Float) -> Unit = { _, _ -> },
    private val ink: KeyInk? = null,
    private val writes: () -> Boolean = { false },
    // whether a place (the keyboard's) is in the letter area written on: the letters' hit areas and Shift's
    private val writingArea: (x: Float, y: Float) -> Boolean = { _, _ -> false },
    private val glided: (path: FloatArray, layout: GlideLayout) -> Unit,
) {
    // which finger may glide, which events are the keys', the path in the decoder's units
    private val touch = GlideTouch(down)
    private val trail = Path()
    private val rect = Rect()
    private val strokeStart = keyboard.dp(STROKE_START_DP)
    // debug builds alone; one a keyboard: its gap= is from the last lift on this keyboard, a switch starts it anew (enough for rhythm)
    private val trace = if (BuildConfig.DEBUG) {
        StrokeTapTrace(keyboard.resources.displayMetrics.density, ViewConfiguration.get(keyboard.context).scaledTouchSlop.toFloat())
    } else {
        null
    }
    // the keyboard's name in the trace's lines
    private val traced = keyboard.javaClass.simpleName

    // the touch, as it went down, a stroke's; where it went down
    private var stroking = false
    private var downX = 0f
    private var downY = 0f

    // the touch, as it went down, may be written ([ink] then); [fed] of its places on the pad so far, -1 while it is none
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

    /**
     * [event] as the keyboard has it, before its keys: true where it is the glide's, [toKeys] what
     * the keyboard does otherwise (its dispatch), told a cancel as a glide starts.
     */
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
            // a stroke may start where no key takes the down (the split keyboard's gap): the touch kept
            // all the same, else its moves never come here (the parent finds no target)
            return toKeys(event) || masked == MotionEvent.ACTION_DOWN && touch.pointer != GlideTouch.NO_POINTER
        }
        if (route == GlideTouch.Route.CANCEL_KEYS) {
            val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
            toKeys(cancel)
            cancel.recycle()
        }
        // the trail drawn anew, or gone as the glide ends
        keyboard.invalidate()
        return true
    }

    // the keys': and a stroke short of its lift as another finger lands, the first's tap made, that finger the keys'
    private fun keys(route: GlideTouch.Route, masked: Int) =
        route == GlideTouch.Route.KEYS || route == GlideTouch.Route.TAP_KEY && masked == MotionEvent.ACTION_POINTER_DOWN

    // swallowed (GlideTouch.swallows), the touch's trace line all the same: a stroke's other fingers are to the last lift
    private fun swallowed(event: MotionEvent, last: Boolean): Boolean {
        if (!touch.swallows(first = event.actionMasked == MotionEvent.ACTION_DOWN, last = last)) return false
        if (last) traceUp(event)
        return true
    }

    private fun traceUp(event: MotionEvent) {
        trace?.up(event.eventTime, cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL)?.let { Log.i(TRACE_TAG, it) }
    }

    // a stroke started (traced), or lifted short of its distance: the key under its down tapped after all
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
        // written on: before a glide or a stroke filter, whatever 在键上划 is (§3.2); the recognizer loadable (HandwritingMode)
        inking = ink?.takeIf { writes() && it.loadable }
        stroking = inking == null && strokes()
        fed = -1
        val drawn = stroking || inking != null
        val keys = if (drawn || enabled()) letters() else emptyList()
        // the keys' size from any letter, all the same: read before the one rect is used for the others
        val first = keys.firstOrNull()?.let { bounds(it.second) }
        val keyWidth = first?.width()?.toFloat() ?: 0f
        val keyHeight = first?.height()?.toFloat() ?: 0f
        val x = event.x.toInt()
        val y = event.y.toInt()
        // written on: the letter area as the keys' hit areas share it out, Shift too; else on a letter as drawn
        val onLetter = if (inking != null) writingArea(event.x, event.y) else keys.any { (_, v) -> bounds(v).contains(x, y) }
        // a stroke may start between a split keyboard's halves too: no key there to tap
        val inGap = drawn && !onLetter && inGap(keys, event.x, event.y, keyWidth)
        if (trace != null && inking == null) traceDown(event)
        touch.down(
            event.getPointerId(0), event.x, event.y, event.eventTime, onLetter,
            keyWidth, keyHeight, longPressMillis(), stroke = stroking, inGap = inGap, writes = inking != null,
            strokeDistance = strokeStart, liftDistance = if (stroking) keyboard.dp(strokeLift()) else 0f,
        )
        // the writing goes on: a stroke from its down, no key pressed
        if (inking == null || !touch.gliding) return GlideTouch.Route.KEYS
        feed()
        return GlideTouch.Route.GLIDE
    }

    // the touch traced from its down, where that is on a letter's cell
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

    /** A backspace pressed at [time] (uptime), logged after the touches traced: a stroke taken back is a misread. */
    fun backspaced(time: Long) {
        trace?.let { Log.i(TRACE_TAG, it.backspace(time)) }
    }

    private var tracedPointer = -1

    // another finger down: the glide's while one glides. Written on, with a key held (a thumb on space or ⌫), one
    // down on the letters is a stroke all the same (the letters only write), the held key let go of
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

    // a stroke lifted while a thumb rests, handwriting still on: a finger down again may write the next
    private fun writesAgain() = touch.writesAgain && inking != null && writes()

    // a finger down again on the letters while a thumb rests: the next stroke, as from a down (else swallowed)
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

    // the touch's places not yet on the pad put there, a stroke started with the first
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

    // the stroke's last place and its lift, as the touch's lift is told it
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
        // another finger's lift: nothing of the stroke
        if (event.getPointerId(i) != touch.pointer) return touch.pointerUp(event.getPointerId(i), event.getX(i), event.getY(i))
        return lifted(event, i, cancelled = false) { touch.pointerUp(event.getPointerId(i), event.getX(i), event.getY(i)) }
    }

    private fun up(event: MotionEvent, i: Int, cancelled: Boolean): GlideTouch.Route =
        lifted(event, i, cancelled) { touch.up(event.getX(i), event.getY(i), cancelled = cancelled) }

    /** Whether [x], [y] (the keyboard's) is between a split keyboard's halves ([SplitGap]), as a finger down there is told. */
    @VisibleForTesting
    internal fun inGap(x: Float, y: Float): Boolean {
        val keys = letters()
        val keyWidth = keys.firstOrNull()?.let { bounds(it.second).width().toFloat() } ?: 0f
        return inGap(keys, x, y, keyWidth)
    }

    private fun inGap(keys: List<Pair<Char, KeyPlace>>, x: Float, y: Float, keyWidth: Float) =
        keys.isNotEmpty() && SplitGap.contains(boxes(keys), x, y, keyWidth)

    /** Whether the path of a glide or stroke is drawn now (its trail). */
    @VisibleForTesting
    internal val trailShown: Boolean get() = touch.shown

    private fun move(event: MotionEvent): GlideTouch.Route {
        // the finger traced, whether or not it may glide (the keys' own touch, glide typing off)
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

    // [path] in key widths over where the letters are
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

    // where each of [keys] is, left, top, right and bottom in turn (SplitGap's)
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

    // where [key] is in the keyboard, in the one rect kept for it
    private fun bounds(key: KeyPlace): Rect {
        key.place(keyboard, rect)
        return rect
    }

    /** The path so far, over the keys; or the ink written there. */
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

        /** Where a stroke drawn starts, any way: its keys cancelled, before their swipe (36dp) or long press. */
        const val STROKE_START_DP = 12f
        private const val TRAIL_ALPHA = 0xB0
        private const val TRACE_TAG = "YoumoStrokeTap"
    }
}
