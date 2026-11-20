/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.handwriting

import java.util.concurrent.atomic.AtomicInteger

/**
 * Handwriting strokes: each stroke asks for the piece to be read; the pen up for [finishMillis] ends the piece (ink
 * wiped, reading kept). Pieces are numbered across pads; the engine says which it ended, and the pad wipes the
 * current one if among them ([ended]).
 */
class InkPad {

    /** [id]: later reads higher. */
    class Read(val strokes: List<FloatArray>, val piece: Int, val id: Int)

    private val done = ArrayList<FloatArray>()
    private var current: Points? = null
    private var lastUp = 0L
    private var reads = 0

    /** The user's to set, the hand's pace. */
    var finishMillis = FINISH_MILLIS
        set(value) {
            require(value > 0) { "finish after $value ms" }
            field = value
        }

    var piece = pieces.getAndIncrement()
        private set

    /** The one under the pen last. */
    val strokes: List<FloatArray> get() = done + listOfNotNull(current?.toArray())

    /** Not copied, so not to be kept. */
    val finished: List<FloatArray> get() = done

    /** What was drawn of [finished] holds while this is the same. */
    var edits = 0
        private set

    val drawing: Boolean get() = current != null

    /** [each] gets x, y pairs as the first n values of xy, not copied; not called where none is drawn. */
    fun underPen(each: (xy: FloatArray, n: Int) -> Unit) {
        current?.let { each(it.xy, it.n) }
    }

    fun down(x: Float, y: Float) {
        current = Points().apply { add(x, y) }
    }

    fun move(x: Float, y: Float) {
        current?.add(x, y)
    }

    fun up(time: Long): Read? {
        val stroke = current ?: return null
        current = null
        done.add(stroke.toArray())
        edits++
        lastUp = time
        return read()
    }

    /** The touch cancelled. */
    fun cancel() {
        current = null
    }

    /** The pen up long enough since the last stroke or [undo]: ink wiped, reading left, the next stroke another piece. */
    fun finish(time: Long): Boolean {
        if (done.isEmpty() || current != null || time - lastUp < finishMillis) return false
        next()
        return true
    }

    /** The pad hidden: wiped, the next stroke another's. */
    fun next() {
        done.clear()
        edits++
        current = null
        piece = pieces.getAndIncrement()
    }

    /** Whether the one being written was among them, now wiped. */
    fun ended(latest: Int): Boolean {
        if (latest < piece) return false
        next()
        return true
    }

    /** Null with no stroke, backspace then the engine's; the finishing pause counts from [time] (the stroke is often rewritten). */
    fun undo(time: Long): Read? {
        if (current != null || done.isEmpty()) return null
        done.removeAt(done.size - 1)
        edits++
        lastUp = time
        return read()
    }

    private fun read(): Read = Read(done.toList(), piece, ++reads)

    private class Points {
        var xy = FloatArray(INITIAL)
            private set
        var n = 0
            private set

        fun add(x: Float, y: Float) {
            if (n + 2 > xy.size) xy = xy.copyOf(xy.size * 2)
            xy[n++] = x
            xy[n++] = y
        }

        fun toArray(): FloatArray = xy.copyOf(n)
    }

    companion object {
        /** Unless set: [finishMillis]. */
        const val FINISH_MILLIS = 700L

        private const val INITIAL = 64

        // across pads: a keyboard made again goes on from the last's pieces
        private val pieces = AtomicInteger()
    }
}
