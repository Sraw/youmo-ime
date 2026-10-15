/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.handwriting

import java.util.concurrent.atomic.AtomicInteger

/**
 * The handwriting pad's strokes and when they are read: each stroke ended asks for the character
 * being written (a piece) to be read ([Read]); the pen up for [finishMillis] ends the piece
 * ([finish]), its ink wiped while its reading stays on show; the next stroke starts a new piece. Backspace takes the last stroke back ([undo]). Times in milliseconds, points in any one
 * unit.
 *
 * Pieces are numbered, later ones higher, across pads: what a reading does to what is shown is
 * the engine's to decide, in order with the keys (HandwritingSession). The engine says which
 * pieces it ended (a reading picked, space, the cursor moved): the pad wipes the one being written
 * if it is among them ([ended]).
 */
class InkPad {

    /** Read [strokes], of [piece]; [id] numbers the reads asked for, later ones higher. */
    class Read(val strokes: List<FloatArray>, val piece: Int, val id: Int)

    private val done = ArrayList<FloatArray>()
    private var current: Points? = null
    private var lastUp = 0L
    private var reads = 0

    /** How long the pen stays up before the piece counts as done: the user's to set, the hand's pace. */
    var finishMillis = FINISH_MILLIS
        set(value) {
            require(value > 0) { "finish after $value ms" }
            field = value
        }

    /** The piece being written. */
    var piece = pieces.getAndIncrement()
        private set

    /** The strokes of the piece being written, the one under the pen last. */
    val strokes: List<FloatArray> get() = done + listOfNotNull(current?.toArray())

    /** Whether a stroke is being drawn. */
    val drawing: Boolean get() = current != null

    /** The pen down at [x], [y]: a stroke starts. */
    fun down(x: Float, y: Float) {
        current = Points().apply { add(x, y) }
    }

    fun move(x: Float, y: Float) {
        current?.add(x, y)
    }

    /** The pen up at [time]: the stroke ends, the piece to be read. */
    fun up(time: Long): Read? {
        val stroke = current ?: return null
        current = null
        done.add(stroke.toArray())
        lastUp = time
        return read()
    }

    /** The stroke drawn given up (the touch cancelled). */
    fun cancel() {
        current = null
    }

    /**
     * At [time], whether the piece written is now done: the pen up long enough since the last
     * stroke. Its ink is then wiped, its reading left on show, and the next stroke starts another.
     */
    fun finish(time: Long): Boolean {
        if (done.isEmpty() || current != null || time - lastUp < finishMillis) return false
        next()
        return true
    }

    /** The piece ended as it is (the pad hidden): wiped, the next stroke another's. */
    fun next() {
        done.clear()
        current = null
        piece = pieces.getAndIncrement()
    }

    /** The engine ended the pieces up to [latest]: whether the one being written was among them, now wiped. */
    fun ended(latest: Int): Boolean {
        if (latest < piece) return false
        next()
        return true
    }

    /**
     * Backspace: the last stroke taken back and what is left read, nothing left read as nothing;
     * null where the pad has no stroke to take back, backspace then the engine's.
     */
    fun undo(): Read? {
        if (current != null || done.isEmpty()) return null
        done.removeAt(done.size - 1)
        return read()
    }

    private fun read(): Read = Read(done.toList(), piece, ++reads)

    private class Points {
        private var xy = FloatArray(INITIAL)
        private var n = 0

        fun add(x: Float, y: Float) {
            if (n + 2 > xy.size) xy = xy.copyOf(xy.size * 2)
            xy[n++] = x
            xy[n++] = y
        }

        fun toArray(): FloatArray = xy.copyOf(n)
    }

    companion object {
        /** How long the pen stays up before the character written counts as done, unless set: [finishMillis]. */
        const val FINISH_MILLIS = 700L

        private const val INITIAL = 64

        // across pads: a keyboard made again goes on from the last's pieces
        private val pieces = AtomicInteger()
    }
}
