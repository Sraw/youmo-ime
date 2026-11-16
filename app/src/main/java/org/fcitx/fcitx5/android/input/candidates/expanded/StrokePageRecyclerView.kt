/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.candidates.expanded

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import org.fcitx.fcitx5.android.input.keyboard.GlideInput
import org.fcitx.fcitx5.android.input.keyboard.PageStrokeTouch
import org.fcitx.fcitx5.android.input.keyboard.PageStrokeTouch.Route
import splitties.dimensions.dp

/**
 * 展开候选's list, on which a finger draws 笔画筛选's strokes while [draws] says so (dev/design/061-stroke-on-candidate-page.md):
 * none of its own drag scrolling then, the page turned by the column's ↑↓ alone; a tap still picks, a long press still
 * opens the candidate's menu (the cell's own, 长按延迟's). What becomes of each event is [PageStrokeTouch]'s. A mouse,
 * a trackpad's two-finger swipe, a wheel and a touch while the list still moves are the list's as ever.
 */
class StrokePageRecyclerView(context: Context, attrs: AttributeSet?) : RecyclerView(context, attrs) {

    /** The trail's colour (GlideInput's). */
    var trailColor: Int
        get() = paint.color
        set(value) {
            paint.color = value
            paint.alpha = TRAIL_ALPHA
        }

    /** Whether a finger down now draws, asked at each down. */
    var draws: () -> Boolean = { false }

    /** 笔画筛选灵敏度's distance, pixels: a stroke lifted short of it is nothing. */
    var lift: () -> Float = { 0f }

    /** 长按延迟: a touch held so long first is the cell's long press. */
    var longPressMillis: () -> Long = { 0L }

    /** A stroke drawn: `h s p n z`. */
    var stroked: (Char) -> Unit = {}

    private val touch = PageStrokeTouch()

    // this touch is the strokes' (decided at its down), and the list told a cancel of it already
    private var drawing = false
    private var listCancelled = false

    // the cell's touch slop (CustomGestureView's KeyGesture), the keys' start of a stroke (GlideInput's)
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val start = dp(GlideInput.STROKE_START_DP)

    // the list's items changed under a finger (a stroke filtered them, a page loaded in the middle): its tap picks nothing,
    // the cell under it may be another candidate's now. Pages loaded at the end leave the cells as they were
    private var generation = 0
    private var downGeneration = 0
    private val changes = object : AdapterDataObserver() {
        override fun onChanged() { generation++ }
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int) { generation++ }
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) { generation++ }
        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) { generation++ }
        override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) { generation++ }
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
            if (positionStart + itemCount < (adapter?.itemCount ?: 0)) generation++
        }
    }

    // GlideInput's trail: its width, colour and alpha
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = dp(TRAIL_DP)
        alpha = TRAIL_ALPHA
    }
    private val path = Path()

    /** Whether the trail is drawn now, for a test. */
    val trailShown: Boolean get() = drawing && touch.shown

    override fun setAdapter(adapter: Adapter<*>?) {
        this.adapter?.unregisterAdapterDataObserver(changes)
        super.setAdapter(adapter)
        adapter?.registerAdapterDataObserver(changes)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            drawing = scrollState == SCROLL_STATE_IDLE && finger(ev) && draws()
            listCancelled = false
            if (drawing) {
                downGeneration = generation
                touch.down(ev.getPointerId(0), ev.x, ev.y, ev.eventTime, start, lift(), slop, longPressMillis())
            }
        }
        if (!drawing) return super.dispatchTouchEvent(ev)
        when (ev.actionMasked) {
            // the cell pressed, its long press timed
            MotionEvent.ACTION_DOWN -> super.dispatchTouchEvent(ev)
            MotionEvent.ACTION_MOVE -> moved(ev)
            MotionEvent.ACTION_POINTER_DOWN -> touch.otherDown()
            MotionEvent.ACTION_POINTER_UP -> {
                val i = ev.actionIndex
                lifted(ev, touch.pointerUp(ev.getPointerId(i), ev.getX(i), ev.getY(i), ev.eventTime))
            }
            MotionEvent.ACTION_UP -> lifted(ev, touch.up(ev.x, ev.y, ev.eventTime))
            MotionEvent.ACTION_CANCEL -> {
                touch.cancel()
                super.dispatchTouchEvent(ev)
                invalidate()
            }
        }
        if (!touch.active) drawing = false
        return true
    }

    private fun moved(ev: MotionEvent) {
        val i = ev.findPointerIndex(touch.pointer)
        if (i < 0) return
        for (h in 0 until ev.historySize) touch.add(ev.getHistoricalX(i, h), ev.getHistoricalY(i, h))
        when (touch.move(ev.getX(i), ev.getY(i), ev.eventTime)) {
            Route.LIST -> if (!listCancelled) super.dispatchTouchEvent(ev)
            Route.CANCEL_LIST -> cancelList(ev)
            else -> {}
        }
        if (touch.shown) invalidate()
    }

    private fun lifted(ev: MotionEvent, route: Route) {
        when (route) {
            // the cell's lift (a long press's), unless it was a second finger's
            Route.LIST -> if (ev.actionMasked == MotionEvent.ACTION_UP && !listCancelled) super.dispatchTouchEvent(ev) else cancelList(ev)
            Route.TAP -> {
                cancelList(ev)
                if (generation == downGeneration) tapCell(touch.downX, touch.downY)
            }
            Route.STROKE -> {
                cancelList(ev)
                touch.stroke?.let(stroked)
            }
            Route.NONE -> cancelList(ev)
            Route.HOLD, Route.CANCEL_LIST -> {}
        }
        invalidate()
    }

    // the cell under the down tapped there, as a tap on it clicks (KeyGesture.tap: the key's after a stroke lifted short)
    private fun tapCell(x: Float, y: Float) {
        val cell = findChildViewUnder(x, y) as? CustomGestureView ?: return
        cell.keyGesture.tap(x - cell.left - cell.translationX, y - cell.top - cell.translationY)
    }

    private fun cancelList(ev: MotionEvent) {
        if (listCancelled) return
        listCancelled = true
        val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
    }

    // no drag of the list's own while a finger draws: the cells alone have its events (a down on no cell, none)
    override fun onInterceptTouchEvent(e: MotionEvent): Boolean = if (drawing) false else super.onInterceptTouchEvent(e)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean = if (drawing) true else super.onTouchEvent(e)

    override fun draw(c: Canvas) {
        super.draw(c)
        if (!trailShown) return
        val p = touch.points
        path.rewind()
        path.moveTo(p[0], p[1])
        for (k in 2 until touch.count step 2) path.lineTo(p[k], p[k + 1])
        c.drawPath(path, paint)
    }

    private companion object {
        const val TRAIL_DP = 6f
        const val TRAIL_ALPHA = 0xB0

        // MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE (API 34; getClassification is 29's): a trackpad's scroll
        const val TWO_FINGER_SWIPE = 3

        // on the screen (a finger, a stylus): not a mouse's drag, not a trackpad's two-finger swipe (the list's
        // scrolling as ever)
        fun finger(ev: MotionEvent) =
            ev.getToolType(0) != MotionEvent.TOOL_TYPE_MOUSE && ev.classification != TWO_FINGER_SWIPE
    }
}
