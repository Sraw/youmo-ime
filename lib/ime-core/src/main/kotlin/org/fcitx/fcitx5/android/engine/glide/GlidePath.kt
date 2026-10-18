/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.glide

import kotlin.math.sqrt

/**
 * A finger's glide across the letters as a decoder walks its words' keys ([step], then [cost]
 * where a word ends): a key goes on only where the path passes near it ([Weights.near]), in order
 * from where the one before was, the first near where the path starts and the last near where it
 * ends ([Weights.ends]); a letter doubled is passed once (hello). What fitting a word cost is
 * SHARK²'s location channel (the mean distance between the path and the word's keys joined, each
 * taken at [SAMPLES] points along it, squared, as a Gaussian's log) and how near its keys come.
 *
 * [path] is x, y pairs over [layout] (key widths, rows [GlideLayout.ROW_HEIGHT] apart), in the
 * order the finger went; words of at most [maxKeys] keys.
 */
class GlidePath(path: FloatArray, private val layout: GlideLayout, private val weights: Weights, maxKeys: Int) {

    /**
     * How a word's keys are held to the path: [near], how close (key widths) the path comes to each
     * key; [ends], to the first and last; [shape], [proximity] and [edges] weigh the shape's mean
     * distance, the keys' mean distance from the path and the first and last keys' from where it
     * starts and ends against a model's log10 score: a finger starts and lifts on a word's ends far
     * more surely than it turns on its keys between. [corner] is what a turn of the path costs, by
     * how far it turns (1 - its cosine), that no key of the word is at: a finger turns on a key it
     * means (ni-h-ao is no niao); [turn] is the cosine below which a bend is a turn, not a wobble.
     */
    data class Weights(
        val near: Float = 1.0f,
        val ends: Float = 1.2f,
        val shape: Float = 4f,
        val proximity: Float = 5f,
        val edges: Float = 2f,
        val corner: Float = 2f,
        val turn: Float = 0.8f,
    )

    private val resampled = dense(thin(path))

    /** Points of the path as searched: none for a path of one point, no glide. */
    val points = if (path.size < 4) 0 else resampled.size / 2

    // each point's distance from each letter's nearest key, worked out once for the thousands of prefixes
    private val distances = FloatArray(points * GlideLayout.LETTERS) { layout.distance('a' + it % GlideLayout.LETTERS, resampled[2 * (it / GlideLayout.LETTERS)], resampled[2 * (it / GlideLayout.LETTERS) + 1]) }

    private val sampled = resample(resampled, resampled.size / 2, SAMPLES, FloatArray(SAMPLES * 2))

    // the points the path turns sharply at, in order
    private val corners = Corners(resampled, points, weights.turn)
    private val at = IntArray(maxKeys + 1)
    private val cost = FloatArray(maxKeys + 1)
    private val distinct = IntArray(maxKeys + 1)
    private val keys = CharArray(maxKeys + 1)
    private val line = FloatArray((maxKeys + 1) * 2)
    private val ideal = FloatArray(SAMPLES * 2)

    /**
     * Keys stepped so far: a long scribble fits so many prefixes that the search, on the fcitx
     * thread, would hold the keyboard for seconds; past [MAX_STEPS] none goes on, the best found kept.
     */
    var steps = 0
        private set

    private fun distance(i: Int, c: Char) = distances[i * GlideLayout.LETTERS + (c - 'a')]

    /**
     * The prefix of [depth] keys with [c] after it: the path's next point near c's key, the nearest
     * of those that follow; false where it does not pass near it.
     */
    fun step(depth: Int, c: Char): Boolean {
        // a lowercase letter only: the layout finds 'A' too, but a dictionary's capitals (A股) are no keys
        if (c !in 'a'..'z' || c !in layout) return false
        if (points == 0 || ++steps > MAX_STEPS) return false
        keys[depth] = c
        if (depth == 0) {
            val d = distance(0, c)
            if (d > weights.ends) return false
            at[0] = 0
            cost[0] = d
            distinct[0] = 1
            return true
        }
        val from = at[depth - 1]
        // a letter doubled, or another on the same key (the nine keys'): where the path was
        if (c == keys[depth - 1] || layout.sameKey(c, keys[depth - 1])) {
            at[depth] = from
            cost[depth] = cost[depth - 1]
            distinct[depth] = distinct[depth - 1]
            return true
        }
        var j = from + 1
        while (j < points && distance(j, c) > weights.near) j++
        if (j >= points) {
            // not passed near: the word's last letter may still be where the path ends, as its first
            // where it starts, a finger lifted short of a key more often than it turns short of one
            if (from == points - 1 || distance(points - 1, c) > weights.ends) return false
            j = points - 1
        }
        while (j + 1 < points && distance(j + 1, c) <= distance(j, c)) j++
        at[depth] = j
        cost[depth] = cost[depth - 1] + distance(j, c)
        distinct[depth] = distinct[depth - 1] + 1
        return true
    }

    /** What a word of the first [length] keys stepped costs to be the path; null if the path does not end near it. */
    fun cost(length: Int): Float? {
        val end = distance(points - 1, keys[length - 1])
        if (end > weights.ends) return null
        // the keys after the first all on the path's first point: a tap, not a glide
        if (length > 1 && at[length - 1] == 0) return null
        val near = (cost[length - 1] + end) / (distinct[length - 1] + 1)
        val shape = shapeDistance(length)
        return weights.shape * shape * shape + weights.proximity * near + weights.edges * (cost[0] + end) + weights.corner * unexplained(length)
    }

    // the path's sharp turns none of the first [length] keys was passed at
    private fun unexplained(length: Int): Float {
        var sum = 0f
        var i = 0
        for (c in corners.at.indices) {
            val k = corners.at[c]
            // the keys in order, as the corners: the first not before this corner's reach
            while (i < length && at[i] < k - CORNER_REACH) i++
            if (i >= length || at[i] > k + CORNER_REACH) sum += corners.turn[c]
        }
        return sum
    }

    /** Where [p]'s first [n] points turn by more than [cutoff] (a cosine): each the sharpest of its run, and by how much (1 - its cosine). */
    internal class Corners(p: FloatArray, n: Int, cutoff: Float) {
        val at: IntArray
        val turn: FloatArray

        init {
            val ats = ArrayList<Int>()
            val turns = ArrayList<Float>()
            var best = -1
            var bestCos = 1f
            for (k in ARM until n - ARM) {
                val cos = cosine(p, k)
                if (cos < cutoff) {
                    if (cos < bestCos) {
                        best = k
                        bestCos = cos
                    }
                } else if (best >= 0) {
                    ats += best
                    turns += 1 - bestCos
                    best = -1
                    bestCos = 1f
                }
            }
            if (best >= 0) {
                ats += best
                turns += 1 - bestCos
            }
            at = ats.toIntArray()
            turn = turns.toFloatArray()
        }

        // the cosine of the turn at [k], between the [ARM] points before and after
        private fun cosine(p: FloatArray, k: Int): Float {
            val ax = p[2 * k] - p[2 * (k - ARM)]
            val ay = p[2 * k + 1] - p[2 * (k - ARM) + 1]
            val bx = p[2 * (k + ARM)] - p[2 * k]
            val by = p[2 * (k + ARM) + 1] - p[2 * k + 1]
            val norm = sqrt((ax * ax + ay * ay) * (bx * bx + by * by))
            return if (norm == 0f) 1f else (ax * bx + ay * by) / norm
        }
    }

    // the mean distance between the path and the word's keys joined up (each at its key nearest where
    // the path passed it), each taken at the same [SAMPLES] points along its length
    private fun shapeDistance(length: Int): Float {
        var k = 0
        for (i in 0 until length) {
            if (i > 0 && keys[i] == keys[i - 1]) continue
            layout.nearest(keys[i], resampled[2 * at[i]], resampled[2 * at[i] + 1], line, k)
            k += 2
        }
        resample(line, k / 2, SAMPLES, ideal)
        var sum = 0f
        for (i in 0 until SAMPLES) {
            val dx = ideal[2 * i] - sampled[2 * i]
            val dy = ideal[2 * i + 1] - sampled[2 * i + 1]
            sum += sqrt(dx * dx + dy * dy)
        }
        return sum / SAMPLES
    }

    companion object {
        /** Points the path and a word's line are each taken at to compare their shapes. */
        const val SAMPLES = 32

        /**
         * Keys a glide's search steps at most, each a walk along the path's points: a bound on a
         * scribble's time, not a word's. A clean path steps far fewer, a key going on only where the
         * path passes it: ime-eval's test sets (2026-10-17) took at most 36k (English), 122k (full
         * pinyin), 319k (the nine keys, the most) and 16k (五笔) a word, 99% of them under 136k.
         */
        const val MAX_STEPS = 1_000_000

        // about this far apart (key widths), the path's points: none too far for a key passed to
        // be missed, none so close that a finger's many touch points slow the search
        private const val STEP = 0.25f

        // how many points (of [STEP]) a key may be passed from a corner it explains
        private const val CORNER_REACH = 3

        // points (of [STEP]) either side of one that its turn is measured over
        private const val ARM = 2

        /** [path] without the points nearer than half a [STEP] to the one kept before them; its last kept. */
        internal fun thin(path: FloatArray): FloatArray {
            val points = path.size / 2
            if (points <= 2) return path
            val out = ArrayList<Float>(path.size)
            for (i in 0 until points) {
                val last = i == points - 1
                if (out.isNotEmpty() && !last) {
                    val dx = path[2 * i] - out[out.size - 2]
                    val dy = path[2 * i + 1] - out[out.size - 1]
                    if (dx * dx + dy * dy < STEP * STEP / 4) continue
                }
                out += path[2 * i]
                out += path[2 * i + 1]
            }
            return out.toFloatArray()
        }

        /** [path] with points put in where two are further apart than [STEP]. */
        internal fun dense(path: FloatArray): FloatArray {
            val out = ArrayList<Float>(path.size * 2)
            for (i in 0 until path.size / 2) {
                if (i > 0) {
                    val px = path[2 * i - 2]
                    val py = path[2 * i - 1]
                    val dx = path[2 * i] - px
                    val dy = path[2 * i + 1] - py
                    val parts = (sqrt(dx * dx + dy * dy) / STEP).toInt()
                    for (k in 1..parts) {
                        val t = k / (parts + 1f)
                        out += px + dx * t
                        out += py + dy * t
                    }
                }
                out += path[2 * i]
                out += path[2 * i + 1]
            }
            return out.toFloatArray()
        }

        /**
         * The first [points] of [line] (x, y pairs) taken at [count] points evenly along its length
         * into [out]; a point alone, [count] times.
         */
        internal fun resample(line: FloatArray, points: Int, count: Int, out: FloatArray): FloatArray {
            if (points == 0) return out
            var total = 0f
            for (i in 1 until points) total += segment(line, i)
            var seg = 1
            var before = 0f // the line's length up to the start of [seg]
            for (k in 0 until count) {
                val target = if (count == 1) 0f else total * k / (count - 1)
                if (points == 1 || total == 0f) {
                    out[2 * k] = line[0]
                    out[2 * k + 1] = line[1]
                    continue
                }
                while (seg < points - 1 && before + segment(line, seg) < target) before += segment(line, seg++)
                val span = segment(line, seg)
                val t = if (span == 0f) 0f else ((target - before) / span).coerceIn(0f, 1f)
                out[2 * k] = line[2 * seg - 2] + (line[2 * seg] - line[2 * seg - 2]) * t
                out[2 * k + 1] = line[2 * seg - 1] + (line[2 * seg + 1] - line[2 * seg - 1]) * t
            }
            return out
        }

        // the length of [line]'s segment ending at point [i]
        private fun segment(line: FloatArray, i: Int): Float {
            val dx = line[2 * i] - line[2 * i - 2]
            val dy = line[2 * i + 1] - line[2 * i - 1]
            return sqrt(dx * dx + dy * dy)
        }
    }
}
