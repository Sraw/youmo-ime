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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.engine.handwriting.InkReading
import org.fcitx.fcitx5.android.input.handwriting.HandwritingRecognition
import org.fcitx.fcitx5.android.input.handwriting.InkKeys
import org.fcitx.fcitx5.android.input.handwriting.InkPad
import splitties.dimensions.dp

/** The keys stay shown under the ink; keys typed beside it go in order ([keys]). The pause is read at each stroke. */
class KeyInk(
    private val view: View,
    color: Int,
    widthDp: Float,
    private val pause: () -> Long,
    private val deliver: (InkReading, piece: Int) -> Unit,
    private val finished: (piece: Int) -> Unit,
    // asked for once written on: a keyboard of one who never writes makes no recognizer (its thread)
    recognition: () -> HandwritingRecognition = { HandwritingRecognition.shared },
) : InkKeys.Pad {

    override val pad = InkPad()

    // a new width: the pad's strokes are in the old size's pixels, so the piece written is done, as a pause would have it
    init {
        view.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (oldRight > oldLeft && right - left != oldRight - oldLeft) hidden()
        }
    }

    val keys: InkKeys by lazy { InkKeys(reads.value, this, deliver, finished) }

    private val reads = lazy(recognition)

    var writtenOn = false
        private set

    /** Asked as a touch may start one: the recognizer is [warmUp] then, if 手写 was not turned on here. */
    val loadable: Boolean get() {
        warmUp()
        return reads.value.loadable
    }
    private var warmed = false

    /** The first stroke does not wait for it. */
    fun warmUp() {
        if (warmed) return
        warmed = true
        reads.value.warmUp()
    }

    fun restart() {
        if (reads.isInitialized()) keys.restart() else next()
    }

    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = view.dp(widthDp)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

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

    @VisibleForTesting
    val shown: Boolean get() = pad.finished.isNotEmpty() || pad.drawing || fading.isNotEmpty()

    fun down(x: Float, y: Float) {
        view.removeCallbacks(finishCheck)
        pad.finishMillis = pause()
        writtenOn = true
        pad.down(x, y)
    }

    fun move(x: Float, y: Float) = pad.move(x, y)

    fun up(time: Long) {
        pad.up(time)?.let(keys::read)
        checkLater()
    }

    /** The strokes before still end their piece. */
    fun cancel() {
        pad.cancel()
        if (pad.finished.isNotEmpty()) checkLater()
    }

    override fun undo(): Boolean {
        val now = SystemClock.uptimeMillis()
        val read = pad.undo(now) ?: return false
        // timed again from here, else the strokes left end the piece before the one taken back is rewritten
        if (read.strokes.isEmpty()) {
            view.removeCallbacks(finishCheck)
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
        view.invalidate()
    }

    fun ended(latest: Int) {
        val done = pad.finished.toList()
        if (!pad.ended(latest)) return
        view.removeCallbacks(finishCheck)
        fade(done)
    }

    private var scope: CoroutineScope? = null

    fun attached() {
        if (scope != null) return
        scope = CoroutineScope(Dispatchers.Main.immediate + Job()).also { s ->
            s.launch { EngineState.handwritingEnded.collect(::ended) }
        }
    }

    fun detached() {
        scope?.cancel()
        scope = null
        hidden()
    }

    /** What it read stays typed. */
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

    // a tap a dot
    private fun trace(xy: FloatArray, n: Int) {
        path.moveTo(xy[0], xy[1])
        if (n == 2) path.lineTo(xy[0] + DOT, xy[1])
        for (j in 2 until n step 2) path.lineTo(xy[j], xy[j + 1])
    }

    companion object {
        /** Where the theme says none. */
        const val INK_DP = 3.5f
        private const val FADE_MILLIS = 150L
        private const val DOT = 0.1f
    }
}
