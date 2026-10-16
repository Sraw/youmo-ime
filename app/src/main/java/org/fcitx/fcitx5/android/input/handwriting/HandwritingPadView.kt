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
 * The pad written on: draws [pad]'s strokes and feeds it the moves of one finger, the first down
 * while none writes (another resting on the pad, a palm or a thumb, does not stop the strokes after
 * it), [onRead] told of each read a stroke ended asks for. The piece is checked for done [pause]
 * after the pen goes up or backspace takes a stroke back, read each time: the setting may change
 * while the keyboard is up; [onFinish] is told of a piece done so.
 */
@SuppressLint("ViewConstructor")
class HandwritingPadView(
    context: Context,
    theme: Theme,
    private val onRead: (InkPad.Read) -> Unit,
    private val pause: () -> Long = { InkPad.FINISH_MILLIS },
    private val onFinish: (piece: Int) -> Unit = {},
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

    // the finished strokes, traced again only when they change (InkPad.edits)
    private val finishedInk = Path()
    private var traced = -1

    // made once: a lambda written in onDraw, holding the view, would be allocated at every frame
    private val traceUnderPen: (FloatArray, Int) -> Unit = { xy, n -> trace(path, xy, n) }

    private val finishCheck = Runnable {
        val piece = pad.piece
        if (pad.finish(SystemClock.uptimeMillis())) {
            invalidate()
            onFinish(piece)
        }
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
        val read = pad.undo(SystemClock.uptimeMillis()) ?: return false
        // timed again from here, else the strokes left end the piece before the one taken back is rewritten
        if (read.strokes.isEmpty()) removeCallbacks(finishCheck) else checkLater()
        invalidate()
        onRead(read)
        return true
    }

    // the piece checked for done once the pen has stayed up the pause, read now
    private fun checkLater() {
        removeCallbacks(finishCheck)
        pad.finishMillis = pause()
        postDelayed(finishCheck, pad.finishMillis)
    }

    private fun penDown(event: MotionEvent, index: Int) {
        parent?.requestDisallowInterceptTouchEvent(true)
        removeCallbacks(finishCheck)
        pointer = event.getPointerId(index)
        pad.down(event.getX(index), event.getY(index))
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val i = if (pointer == MotionEvent.INVALID_POINTER_ID) -1 else event.findPointerIndex(pointer)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> penDown(event, 0)
            // with a finger resting on the pad, the writing one comes down as another pointer
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (pointer != MotionEvent.INVALID_POINTER_ID) return true
                penDown(event, event.actionIndex)
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
                checkLater()
            }
            MotionEvent.ACTION_CANCEL -> {
                pointer = MotionEvent.INVALID_POINTER_ID
                pad.cancel()
                // the strokes before still end their piece
                postDelayed(finishCheck, pad.finishMillis)
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
        if (traced != pad.edits) {
            traced = pad.edits
            finishedInk.rewind()
            for (s in pad.finished) trace(finishedInk, s, s.size)
        }
        canvas.drawPath(finishedInk, ink)
        path.rewind()
        pad.underPen(traceUnderPen)
        canvas.drawPath(path, ink)
    }

    /** A stroke's first [n] values of [xy], x, y pairs, traced into [into]. */
    private fun trace(into: Path, xy: FloatArray, n: Int) {
        into.moveTo(xy[0], xy[1])
        // a tap: a dot
        if (n == 2) into.lineTo(xy[0] + DOT, xy[1])
        for (j in 2 until n step 2) into.lineTo(xy[j], xy[j + 1])
    }

    private companion object {
        const val DOT = 0.1f
    }
}
