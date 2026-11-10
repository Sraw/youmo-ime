/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.annotation.VisibleForTesting
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.engine.glide.GlideLayout
import splitties.dimensions.dp

/**
 * Glide typing on [keyboard]'s letters ([GlideGesture]): the finger's path drawn as it goes, and
 * once it lifts, in key widths over where the [letters] are, given to [glided]. While it glides
 * the keys have none of the touch: they are cancelled as it starts, their preview and swipe with them.
 *
 * Where [strokes] says so as the finger goes down, the same touch is a stroke (笔画筛选) instead,
 * started far sooner ([GlideGesture.STROKE]): its path, in key widths both ways, given to [stroked]. The setting has one or the other, never both.
 * A stroke may also start between a split keyboard's halves ([SplitGap]); a glide starts on a letter only.
 *
 * Where [writes] says so as the finger goes down, a stroke is written on the keys instead (键盘手写, [ink]):
 * put on its pad and drawn there in ink; while the writing goes on every touch on the letters is one
 * ([KeyInkGesture]). [typed] says whether the input method has its own input typed (no writing starts
 * then); [tookBack] deletes the letter of a tap taken back as a character's first dot.
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
    private val ink: KeyInk? = null,
    private val writes: () -> Boolean = { false },
    private val typed: () -> Boolean = { false },
    private val tookBack: () -> Unit = {},
    private val glided: (path: FloatArray, layout: GlideLayout) -> Unit,
) {
    // which finger may glide, which events are the keys', the path in the decoder's units
    private val touch = GlideTouch(down)
    private val trail = Path()
    private val rect = Rect()

    // the touch, as it went down, a stroke's
    private var stroking = false

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
        if (touch.swallows(first = masked == MotionEvent.ACTION_DOWN, last = last)) return true
        val i = event.actionIndex
        val route = when (masked) {
            MotionEvent.ACTION_DOWN -> down(event)
            MotionEvent.ACTION_POINTER_DOWN -> touch.otherDown()
            MotionEvent.ACTION_MOVE -> move(event)
            MotionEvent.ACTION_POINTER_UP -> pointerUp(event, i)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> up(event, i, cancelled = masked == MotionEvent.ACTION_CANCEL)
            else -> GlideTouch.Route.KEYS
        }
        // a stroke written is the pad's, not a path read
        touch.takeGlided()?.let { if (inking == null) send(it) }
        if (route == GlideTouch.Route.KEYS) {
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

    private fun down(event: MotionEvent): GlideTouch.Route {
        stroking = strokes()
        // writing on the keys: not over a tap or a long press the recognizer cannot read
        inking = ink?.takeIf { !stroking && it.loadable && writes() }
        fed = -1
        val drawn = stroking || inking != null
        val keys = if (drawn || enabled()) letters() else emptyList()
        // the keys' size from any letter, all the same: read before the one rect is used for the others
        val first = keys.firstOrNull()?.let { bounds(it.second) }
        val keyWidth = first?.width()?.toFloat() ?: 0f
        val keyHeight = first?.height()?.toFloat() ?: 0f
        val x = event.x.toInt()
        val y = event.y.toInt()
        val onLetter = keys.any { (_, v) -> bounds(v).contains(x, y) }
        // a stroke may start between a split keyboard's halves too: no key there to tap
        val inGap = drawn && !onLetter && inGap(keys, event.x, event.y, keyWidth)
        inking?.pauseNow()
        touch.down(
            event.getPointerId(0), event.x, event.y, event.eventTime, onLetter,
            keyWidth, keyHeight, longPressMillis(), stroke = stroking, inGap = inGap,
            ink = inking?.gesture, typed = inking != null && typed(),
        )
        // the writing goes on: a stroke from its down, no key pressed
        if (inking == null || !touch.gliding) return GlideTouch.Route.KEYS
        feed(event.eventTime)
        return GlideTouch.Route.GLIDE
    }

    // the touch's places not yet on the pad put there, a stroke started with the first; a tap before it
    // taken back as its first dot (KeyInkGesture.takeTapped): its letter deleted, its places the first stroke
    private fun feed(time: Long) {
        val k = inking ?: return
        val p = touch.points
        if (fed < 0) {
            k.gesture.takeTapped()?.let {
                tookBack()
                k.dot(it, time)
            }
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
        if (writing && !cancelled) feed(event.eventTime)
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
        if (event.getPointerId(i) != touch.pointer) return touch.pointerUp(event.getPointerId(i), event.getX(i), event.getY(i), event.eventTime)
        return lifted(event, i, cancelled = false) { touch.pointerUp(event.getPointerId(i), event.getX(i), event.getY(i), event.eventTime) }
    }

    private fun up(event: MotionEvent, i: Int, cancelled: Boolean): GlideTouch.Route =
        lifted(event, i, cancelled) { touch.up(event.getX(i), event.getY(i), cancelled = cancelled, time = event.eventTime) }

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
    internal val trailShown: Boolean get() = touch.gliding

    private fun move(event: MotionEvent): GlideTouch.Route {
        val i = event.findPointerIndex(touch.pointer).takeIf { it >= 0 } ?: return touch.missing()
        for (h in 0 until event.historySize) touch.add(event.getHistoricalX(i, h), event.getHistoricalY(i, h))
        val route = touch.move(event.getX(i), event.getY(i), event.eventTime)
        if (route != GlideTouch.Route.KEYS) feed(event.eventTime)
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
        if (stroking) return stroked(FloatArray(path.size) { if (it % 2 == 0) path[it] else path[it] * unitY / unitX })
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
        if (!touch.gliding || inking != null) return
        val p = touch.points
        trail.rewind()
        trail.moveTo(p[0], p[1])
        for (k in 2 until touch.count step 2) trail.lineTo(p[k], p[k + 1])
        canvas.drawPath(trail, paint)
    }

    companion object {
        private const val TRAIL_DP = 6f
        private const val TRAIL_ALPHA = 0xB0
    }
}
