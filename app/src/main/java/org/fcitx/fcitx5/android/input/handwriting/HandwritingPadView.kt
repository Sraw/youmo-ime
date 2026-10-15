/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.handwriting

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import splitties.dimensions.dp

/**
 * The pad written on: draws [pad]'s strokes and feeds it the first finger's moves, [onRead] told
 * of each read a stroke ended asks for. The piece is checked for done [InkPad.FINISH_MILLIS]
 * after the pen goes up.
 */
@SuppressLint("ViewConstructor")
class HandwritingPadView(
    context: Context,
    theme: Theme,
    private val onRead: (InkPad.Read) -> Unit,
) : View(context) {

    val pad = InkPad()

    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.keyTextColor
        style = Paint.Style.STROKE
        strokeWidth = dp(3.5f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    private val finishCheck = Runnable {
        if (pad.finish(SystemClock.uptimeMillis())) invalidate()
    }

    init {
        contentDescription = context.getString(R.string.a11y_handwriting_pad)
    }

    // the finger writing: another's touches are not strokes
    private var pointer = MotionEvent.INVALID_POINTER_ID

    /** The piece ended as it is: InkPad.next. */
    fun next() {
        removeCallbacks(finishCheck)
        pointer = MotionEvent.INVALID_POINTER_ID
        pad.next()
        invalidate()
    }

    /** The engine ended pieces up to [latest]: InkPad.ended. */
    fun ended(latest: Int) {
        if (!pad.ended(latest)) return
        removeCallbacks(finishCheck)
        pointer = MotionEvent.INVALID_POINTER_ID
        invalidate()
    }

    /** Backspace: the last stroke taken back, true; false with none to take back. */
    fun undo(): Boolean {
        val read = pad.undo() ?: return false
        invalidate()
        onRead(read)
        return true
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val i = if (pointer == MotionEvent.INVALID_POINTER_ID) -1 else event.findPointerIndex(pointer)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                removeCallbacks(finishCheck)
                pointer = event.getPointerId(0)
                pad.down(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> {
                if (i < 0) return true
                // the writing finger's points, those batched since the last event first
                for (h in 0 until event.historySize) pad.move(event.getHistoricalX(i, h), event.getHistoricalY(i, h))
                pad.move(event.getX(i), event.getY(i))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (i < 0 || event.actionIndex != i) return true
                pad.move(event.getX(i), event.getY(i))
                pointer = MotionEvent.INVALID_POINTER_ID
                pad.up(SystemClock.uptimeMillis())?.let(onRead)
                postDelayed(finishCheck, InkPad.FINISH_MILLIS)
            }
            MotionEvent.ACTION_CANCEL -> {
                pointer = MotionEvent.INVALID_POINTER_ID
                pad.cancel()
                // the strokes before still end their piece
                postDelayed(finishCheck, InkPad.FINISH_MILLIS)
            }
            else -> return true
        }
        invalidate()
        return true
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(finishCheck)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        for (s in pad.strokes) {
            path.rewind()
            path.moveTo(s[0], s[1])
            // a tap: a dot
            if (s.size == 2) path.lineTo(s[0] + DOT, s[1])
            for (j in 2 until s.size step 2) path.lineTo(s[j], s[j + 1])
            canvas.drawPath(path, ink)
        }
    }

    private companion object {
        const val DOT = 0.1f
    }
}
