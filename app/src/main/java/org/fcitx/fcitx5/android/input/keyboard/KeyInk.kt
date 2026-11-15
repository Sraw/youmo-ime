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

/**
 * Writing on the letter keys (the toolbar's 手写, 061-handwriting-toolbar §3): the pad the strokes go on, in
 * [view]'s pixels ([InkPad], read by [recognition]), each touch down on the letters a stroke ([GlideTouch]),
 * the keys typed beside it in order ([keys]), and the ink drawn over the keys, which stay shown: the piece's
 * strokes and the one under the pen, faded out once the piece is done, in [color] [widthDp] wide. What is
 * read goes to [deliver], a piece done to [finished]; the pause is the user's (HandwritingPause, [pause]),
 * read at each stroke.
 */
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

    // a new width (the floating keyboard resized, 061-floating-resize §2.5): the pad's strokes are in the old size's
    // pixels and would not go with the next ones, so the piece written is done, as a pause would have it (what it
    // read stays typed)
    init {
        view.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (oldRight > oldLeft && right - left != oldRight - oldLeft) hidden()
        }
    }

    /** The keys typed while the pad is read, in order with it. */
    val keys: InkKeys by lazy { InkKeys(reads.value, this, deliver, finished) }

    private val reads = lazy(recognition)

    /** Whether a stroke was ever put on the pad: then the keys go in order with it ([keys]); else straight on. */
    var writtenOn = false
        private set

    /**
     * Whether strokes can be read now: the recognizer not given up (no stroke starts else, the touch a
     * tap as ever). Asked as a touch may start one: the recognizer is [warmUp] then, if 手写 was not
     * turned on here.
     */
    val loadable: Boolean get() {
        warmUp()
        return reads.value.loadable
    }
    private var warmed = false

    /** The recognizer made and loaded on its worker, as 手写 is turned on: the first stroke does not wait for it. */
    fun warmUp() {
        if (warmed) return
        warmed = true
        reads.value.warmUp()
    }

    /** Another input started: what was written dropped (InkKeys.restart); nothing to drop if never written on. */
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
        writtenOn = true
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

    /** The engine ended pieces up to [latest]: the one written wiped if among them, faded out. */
    fun ended(latest: Int) {
        val done = pad.finished.toList()
        if (!pad.ended(latest)) return
        view.removeCallbacks(finishCheck)
        fade(done)
    }

    private var scope: CoroutineScope? = null

    /** On the window: the engine's pieces ended watched ([ended]). */
    fun attached() {
        if (scope != null) return
        scope = CoroutineScope(Dispatchers.Main.immediate + Job()).also { s ->
            s.launch { EngineState.handwritingEnded.collect(::ended) }
        }
    }

    /** Off the window, hidden or another layout shown: no longer watched, and [hidden]. */
    fun detached() {
        scope?.cancel()
        scope = null
        hidden()
    }

    /** Hidden, or 手写 turned off: a piece written is done, as a pause would have it (what it read stays typed); the pad afresh. */
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

    companion object {
        /** The ink's width where the theme says none (`handwriting/pad`'s ink-width). */
        const val INK_DP = 3.5f
        private const val FADE_MILLIS = 150L
        private const val DOT = 0.1f
    }
}
