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
    // whether a glide is taken now: an input method that reads one, the setting on, a field where words are typed
    private val enabled: () -> Boolean,
    // each letter and its key: the nine keys' several on one
    private val letters: () -> List<Pair<Char, TextKeyView>>,
    private val longPressMillis: () -> Long,
    // how far up or down a glide starts, in key heights (GlideGesture)
    down: Float = GlideGesture.DOWN,
    private val glided: (path: FloatArray, layout: GlideLayout) -> Unit,
) {
    // which finger may glide, which events are the keys', the path in the decoder's units
    private val touch = GlideTouch(down)
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
        val masked = event.actionMasked
        val last = masked == MotionEvent.ACTION_UP || masked == MotionEvent.ACTION_CANCEL
        if (touch.swallows(first = masked == MotionEvent.ACTION_DOWN, last = last)) return true
        val i = event.actionIndex
        val route = when (masked) {
            MotionEvent.ACTION_DOWN -> down(event)
            MotionEvent.ACTION_POINTER_DOWN -> touch.otherDown()
            MotionEvent.ACTION_MOVE -> move(event)
            MotionEvent.ACTION_POINTER_UP -> touch.pointerUp(event.getPointerId(i), event.getX(i), event.getY(i))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touch.up(event.getX(i), event.getY(i), cancelled = masked == MotionEvent.ACTION_CANCEL)
            else -> GlideTouch.Route.KEYS
        }
        touch.takeGlided()?.let { send(it) }
        if (route == GlideTouch.Route.KEYS) return toKeys(event)
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
        val keys = if (enabled()) letters() else emptyList()
        val x = event.x.toInt()
        val y = event.y.toInt()
        val onLetter = keys.any { (_, v) -> bounds(v).contains(x, y) }
        // the keys' size from any letter: all the same; nothing measured on a tap off the letters
        if (onLetter) bounds(keys.first().second)
        touch.down(
            event.getPointerId(0), event.x, event.y, event.eventTime, onLetter,
            rect.width().toFloat(), rect.height().toFloat(), longPressMillis(),
        )
        return GlideTouch.Route.KEYS
    }

    private fun move(event: MotionEvent): GlideTouch.Route {
        val i = event.findPointerIndex(touch.pointer).takeIf { it >= 0 } ?: return touch.missing()
        for (h in 0 until event.historySize) touch.add(event.getHistoricalX(i, h), event.getHistoricalY(i, h))
        return touch.move(event.getX(i), event.getY(i), event.eventTime)
    }

    // [path] in key widths over where the letters are
    private fun send(path: FloatArray) {
        val keys = letters()
        if (keys.isEmpty()) return
        bounds(keys.first().second)
        val unitX = rect.width().toFloat()
        val unitY = GlideTouch.unitY(rect.height().toFloat())
        if (unitX <= 0f || unitY <= 0f) return
        // every key a letter has: the split keyboard's G and V are on both halves
        val centres = keys.map { (c, v) -> bounds(v).let { b -> c to (b.exactCenterX() / unitX to b.exactCenterY() / unitY) } }
        glided(path, GlideLayout(centres))
    }

    // where [view] is in the keyboard, in the one rect kept for it
    private fun bounds(view: TextKeyView): Rect {
        rect.set(0, 0, view.width, view.height)
        keyboard.offsetDescendantRectToMyCoords(view, rect)
        return rect
    }

    /** The path so far, over the keys. */
    fun draw(canvas: Canvas) {
        if (!touch.gliding) return
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
