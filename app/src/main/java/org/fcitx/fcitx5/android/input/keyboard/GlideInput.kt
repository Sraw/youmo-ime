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
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.engine.glide.GlideLayout
import splitties.dimensions.dp

/**
 * Glide typing on [keyboard]'s letters ([GlideGesture]): the finger's path drawn as it goes, and
 * once it lifts, in key widths over where the [letters] are, given to [glided]. While it glides
 * the keys have none of the touch: they are cancelled as it starts, their preview and swipe with them.
 */
class GlideInput(
    private val keyboard: ViewGroup,
    theme: Theme,
    // whether a glide is taken now: English, its setting on, a field where words are typed
    private val enabled: () -> Boolean,
    private val letters: () -> List<Pair<Char, TextKeyView>>,
    private val longPressMillis: () -> Long,
    private val glided: (path: FloatArray, layout: GlideLayout) -> Unit,
) {
    private var gesture: GlideGesture? = null

    // the finger that may glide; once it does, the others are none of the keys' till all lift
    private var pointer = MotionEvent.INVALID_POINTER_ID
    private var swallowing = false
    private val points = ArrayList<Float>()
    private val trail = Path()
    private val rect = Rect()

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
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) swallowing = false
        if (swallowing) {
            // the glide ended with another finger still down: its touch was the glide's, as all lift
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) swallowing = false
            return true
        }
        val gliding = gesture?.gliding == true
        when (action) {
            MotionEvent.ACTION_DOWN -> down(event)
            MotionEvent.ACTION_POINTER_DOWN -> if (gliding) return true else gesture?.otherDown()
            MotionEvent.ACTION_MOVE -> if (move(event, toKeys)) return true
            MotionEvent.ACTION_POINTER_UP -> if (gliding) {
                // the gliding finger lifted, another still down: the glide ends; the other is swallowed
                if (event.getPointerId(event.actionIndex) == pointer) {
                    lift(event, event.actionIndex, up = true)
                    swallowing = true
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (lift(event, event.actionIndex, up = action == MotionEvent.ACTION_UP)) return true
        }
        return toKeys(event)
    }

    private fun down(event: MotionEvent) {
        reset()
        if (!enabled()) return
        val keys = letters()
        val key = keys.firstOrNull { (_, v) -> bounds(v).contains(event.x.toInt(), event.y.toInt()) }
        val size = bounds(keys.firstOrNull()?.second ?: return)
        pointer = event.getPointerId(0)
        gesture = GlideGesture(size.width().toFloat(), size.height().toFloat(), longPressMillis()).apply {
            down(event.x, event.y, event.eventTime, onLetter = key != null)
        }
        add(event.x, event.y)
    }

    // whether the finger glides, the keys cancelled as it starts
    private fun move(event: MotionEvent, toKeys: (MotionEvent) -> Boolean): Boolean {
        val g = gesture ?: return false
        val i = event.findPointerIndex(pointer).takeIf { it >= 0 } ?: return g.gliding
        val was = g.gliding
        for (h in 0 until event.historySize) add(event.getHistoricalX(i, h), event.getHistoricalY(i, h))
        val x = event.getX(i)
        val y = event.getY(i)
        add(x, y)
        if (!g.move(x, y, event.eventTime)) return false
        if (!was) {
            val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
            toKeys(cancel)
            cancel.recycle()
        }
        keyboard.invalidate()
        return true
    }

    // whether the glide ended: the path given in key widths over the letters
    private fun lift(event: MotionEvent, index: Int, up: Boolean): Boolean {
        val g = gesture ?: return false
        val was = g.gliding
        if (was && up) {
            add(event.getX(index), event.getY(index))
            send()
        }
        reset()
        return was
    }

    private fun send() {
        val keys = letters()
        val size = keys.firstOrNull()?.let { bounds(it.second) }?.takeIf { it.width() > 0 && it.height() > 0 } ?: return
        // across in key widths, down in rows of the height the decoder's radii are for, whatever
        // the keys' shape (wide and short in landscape)
        val unitX = size.width().toFloat()
        val unitY = size.height() / GlideLayout.ROW_HEIGHT
        // every key a letter has: the split keyboard's G and V are on both halves
        val centres = keys.map { (c, v) -> bounds(v).let { b -> c to (b.exactCenterX() / unitX to b.exactCenterY() / unitY) } }
        val path = FloatArray(points.size) { if (it % 2 == 0) points[it] / unitX else points[it] / unitY }
        glided(path, GlideLayout(centres))
    }

    private fun add(x: Float, y: Float) {
        if (points.isEmpty()) trail.moveTo(x, y) else trail.lineTo(x, y)
        points += x
        points += y
    }

    private fun reset() {
        gesture?.up()
        gesture = null
        if (!trail.isEmpty) keyboard.invalidate()
        points.clear()
        trail.rewind()
    }

    // where [view] is in the keyboard
    private fun bounds(view: TextKeyView): Rect {
        rect.set(0, 0, view.width, view.height)
        keyboard.offsetDescendantRectToMyCoords(view, rect)
        return Rect(rect)
    }

    /** The path so far, over the keys. */
    fun draw(canvas: Canvas) {
        if (gesture?.gliding == true) canvas.drawPath(trail, paint)
    }

    companion object {
        private const val TRAIL_DP = 6f
        private const val TRAIL_ALPHA = 0xB0
    }
}
