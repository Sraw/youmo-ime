/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.View
import androidx.annotation.VisibleForTesting
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.engine.handwriting.InkReading
import org.fcitx.fcitx5.android.input.handwriting.HandwritingRecognition
import org.fcitx.fcitx5.android.input.handwriting.InkKeys
import org.fcitx.fcitx5.android.input.handwriting.InkPad
import splitties.dimensions.dp

/**
 * Writing on the letter keys (键盘手写, design 060-kbd-handwriting §2, §6): the pad the strokes go on, in
 * [view]'s pixels ([InkPad], read by [recognition] as the handwriting keyboard's are), when a touch is a
 * stroke ([gesture], told by [GlideTouch]), the keys typed beside it in order ([keys]), and the ink drawn
 * over the keys, which stay shown: the piece's strokes and the one under the pen, faded out once the piece
 * is done. What is read goes to [deliver], a piece done to [finished]; the pause is the user's
 * (HandwritingPause, [pause]), read at each stroke.
 */
class KeyInk(
    private val view: View,
    theme: Theme,
    private val pause: () -> Long,
    deliver: (InkReading, piece: Int) -> Unit,
    finished: (piece: Int) -> Unit,
    private val recognition: HandwritingRecognition = HandwritingRecognition.shared,
) : InkKeys.Pad {

    override val pad = InkPad()

    /** When a touch on the letters is a stroke. */
    val gesture = KeyInkGesture()

    /** The keys typed while the pad is read, in order with it. */
    val keys = InkKeys(recognition, this, deliver, finished)

    /** Whether strokes can be read now: the recognizer not given up (no stroke starts else, the touch a tap as ever). */
    val loadable: Boolean get() = recognition.loadable

    // the theme's ink as the handwriting pad's when its theme says none (HandwritingPadView)
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.keyTextColor
        style = Paint.Style.STROKE
        strokeWidth = view.dp(INK_DP)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    // a piece done: its strokes fading out from [fadeFrom], drawn till FADE_MILLIS after
    private var fading: List<FloatArray> = emptyList()
    private var fadeFrom = 0L

    private val finishCheck = Runnable {
        val piece = pad.piece
        val done = pad.finished.toList()
        if (pad.finish(SystemClock.uptimeMillis())) {
            fade(done)
            keys.finish(piece)
        }
    }

    /** Whether ink is drawn now: the piece's strokes, or those of one done fading. */
    @VisibleForTesting
    val shown: Boolean get() = pad.finished.isNotEmpty() || pad.drawing || fading.isNotEmpty()

    /** A stroke starts at [x], [y]: the piece is not done while it is drawn. */
    fun down(x: Float, y: Float) {
        view.removeCallbacks(finishCheck)
        pad.finishMillis = pause()
        gesture.pauseMillis = pad.finishMillis
        pad.down(x, y)
    }

    fun move(x: Float, y: Float) = pad.move(x, y)

    /** The stroke lifted at [time]: read, and the piece checked for done once the pen stayed up the pause. */
    fun up(time: Long) {
        pad.up(time)?.let(keys::read)
        checkLater()
    }

    /** The stroke given up (the touch cancelled): the strokes before still end their piece. */
    fun cancel() {
        pad.cancel()
        if (pad.finished.isNotEmpty()) checkLater()
    }

    /** A tap's [points] (x and y in turn) put on the pad as a stroke lifted at [time]: a first dot taken back. */
    fun dot(points: FloatArray, time: Long) {
        down(points[0], points[1])
        for (k in 2 until points.size step 2) pad.move(points[k], points[k + 1])
        pad.up(time)?.let(keys::read)
    }

    /** The pause set again for a touch down: the writing goes on as long as the pad's piece would. */
    fun pauseNow() {
        gesture.pauseMillis = pause()
    }

    override fun undo(): Boolean {
        val now = SystemClock.uptimeMillis()
        val read = pad.undo(now) ?: return false
        gesture.undone(now)
        // timed again from here, else the strokes left end the piece before the one taken back is rewritten
        if (read.strokes.isEmpty()) {
            view.removeCallbacks(finishCheck)
            gesture.ended()
        } else {
            checkLater()
        }
        view.invalidate()
        keys.read(read)
        return true
    }

    override fun next() {
        view.removeCallbacks(finishCheck)
        pad.next()
        gesture.ended()
        view.invalidate()
    }

    /** The engine ended pieces up to [latest]: the one written wiped if among them, faded out. */
    fun ended(latest: Int) {
        val done = pad.finished.toList()
        if (!pad.ended(latest)) return
        view.removeCallbacks(finishCheck)
        gesture.ended()
        fade(done)
    }

    /** Hidden: a piece written is done, as a pause would have it; the pad afresh. */
    fun hidden() {
        if (pad.finished.isNotEmpty()) keys.finish(pad.piece)
        next()
        fading = emptyList()
    }

    private fun checkLater() {
        view.removeCallbacks(finishCheck)
        pad.finishMillis = pause()
        view.postDelayed(finishCheck, pad.finishMillis)
        view.invalidate()
    }

    private fun fade(strokes: List<FloatArray>) {
        fading = strokes
        fadeFrom = SystemClock.uptimeMillis()
        view.invalidate()
    }

    /** The ink over the keys. */
    fun draw(canvas: Canvas) {
        val alpha = ink.alpha
        if (fading.isNotEmpty()) {
            val t = SystemClock.uptimeMillis() - fadeFrom
            if (t >= FADE_MILLIS) {
                fading = emptyList()
            } else {
                path.rewind()
                for (s in fading) trace(s, s.size)
                ink.alpha = (alpha * (FADE_MILLIS - t) / FADE_MILLIS).toInt()
                canvas.drawPath(path, ink)
                ink.alpha = alpha
                view.postInvalidateOnAnimation()
            }
        }
        if (pad.finished.isEmpty() && !pad.drawing) return
        path.rewind()
        for (s in pad.finished) trace(s, s.size)
        pad.underPen { xy, n -> trace(xy, n) }
        canvas.drawPath(path, ink)
    }

    // a stroke's first [n] values of [xy], x, y pairs; a tap a dot
    private fun trace(xy: FloatArray, n: Int) {
        path.moveTo(xy[0], xy[1])
        if (n == 2) path.lineTo(xy[0] + DOT, xy[1])
        for (j in 2 until n step 2) path.lineTo(xy[j], xy[j + 1])
    }

    private companion object {
        const val INK_DP = 3.5f
        const val FADE_MILLIS = 150L
        const val DOT = 0.1f
    }
}
