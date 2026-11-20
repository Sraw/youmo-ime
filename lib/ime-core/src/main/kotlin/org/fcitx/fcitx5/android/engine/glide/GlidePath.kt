/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.glide

import kotlin.math.sqrt

/**
 * A glide as a decoder walks its words' keys ([step], then [cost]): a key continues only where the path passes
 * near it, in order, the first and last near its ends; a doubled letter is passed once. The cost is SHARK²'s
 * location channel (mean squared distance at [SAMPLES] points, as a Gaussian log) plus how near the keys come.
 */
class GlidePath(path: FloatArray, private val layout: GlideLayout, private val weights: Weights, maxKeys: Int) {

    /**
     * Weights against model log10: [ends] and [edges] count more because a finger starts and lifts on a word's ends
     * far more surely than it turns on its middle keys; [corner] costs a turn at no key of the word (ni-h-ao is not niao).
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

    val points = if (path.size < 4) 0 else resampled.size / 2

    // each point's distance from each letter's nearest key, computed once for thousands of prefixes
    private val distances = FloatArray(points * GlideLayout.LETTERS) { layout.distance('a' + it % GlideLayout.LETTERS, resampled[2 * (it / GlideLayout.LETTERS)], resampled[2 * (it / GlideLayout.LETTERS) + 1]) }

    private val sampled = resample(resampled, resampled.size / 2, SAMPLES, FloatArray(SAMPLES * 2))

    private val corners = Corners(resampled, points, weights.turn)
    private val at = IntArray(maxKeys + 1)
    private val cost = FloatArray(maxKeys + 1)
    private val distinct = IntArray(maxKeys + 1)
    private val keys = CharArray(maxKeys + 1)
    private val line = FloatArray((maxKeys + 1) * 2)
    private val ideal = FloatArray(SAMPLES * 2)

    // a long scribble fits so many prefixes the search would hold the fcitx thread for seconds: capped at [MAX_STEPS]
    var steps = 0
        private set

    private fun distance(i: Int, c: Char) = distances[i * GlideLayout.LETTERS + (c - 'a')]

    fun step(depth: Int, c: Char): Boolean {
        // lowercase only: the layout finds 'A' too, but a dictionary's capitals (A股) are no keys
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
        // a doubled letter, or another on the same key (the nine keys): where the path was
        if (c == keys[depth - 1] || layout.sameKey(c, keys[depth - 1])) {
            at[depth] = from
            cost[depth] = cost[depth - 1]
            distinct[depth] = distinct[depth - 1]
            return true
        }
        var j = from + 1
        while (j < points && distance(j, c) > weights.near) j++
        if (j >= points) {
            // not passed near: the last letter may still be where the path ends, as a finger lifts short of a key
            // more often than it turns short of one
            if (from == points - 1 || distance(points - 1, c) > weights.ends) return false
            j = points - 1
        }
        while (j + 1 < points && distance(j + 1, c) <= distance(j, c)) j++
        at[depth] = j
        cost[depth] = cost[depth - 1] + distance(j, c)
        distinct[depth] = distinct[depth - 1] + 1
        return true
    }

    fun cost(length: Int): Float? {
        val end = distance(points - 1, keys[length - 1])
        if (end > weights.ends) return null
        // every key after the first on the path's first point: a tap, not a glide
        if (length > 1 && at[length - 1] == 0) return null
        val near = (cost[length - 1] + end) / (distinct[length - 1] + 1)
        val shape = shapeDistance(length)
        return weights.shape * shape * shape + weights.proximity * near + weights.edges * (cost[0] + end) + weights.corner * unexplained(length)
    }

    private fun unexplained(length: Int): Float {
        var sum = 0f
        var i = 0
        for (c in corners.at.indices) {
            val k = corners.at[c]
            while (i < length && at[i] < k - CORNER_REACH) i++
            if (i >= length || at[i] > k + CORNER_REACH) sum += corners.turn[c]
        }
        return sum
    }

    /** Where [p]'s first [n] points turn past [cutoff] (a cosine): the sharpest of each run, and by how much (1 - cosine). */
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

        private fun cosine(p: FloatArray, k: Int): Float {
            val ax = p[2 * k] - p[2 * (k - ARM)]
            val ay = p[2 * k + 1] - p[2 * (k - ARM) + 1]
            val bx = p[2 * (k + ARM)] - p[2 * k]
            val by = p[2 * (k + ARM) + 1] - p[2 * k + 1]
            val norm = sqrt((ax * ax + ay * ay) * (bx * bx + by * by))
            return if (norm == 0f) 1f else (ax * bx + ay * by) / norm
        }
    }

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
        const val SAMPLES = 32

        // bounds a scribble's search, not a word's: a clean path steps far fewer
        const val MAX_STEPS = 1_000_000

        // close enough that no key passed is missed, far enough that many touch points don't slow the search
        private const val STEP = 0.25f

        private const val CORNER_REACH = 3

        private const val ARM = 2

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

        internal fun resample(line: FloatArray, points: Int, count: Int, out: FloatArray): FloatArray {
            if (points == 0) return out
            var total = 0f
            for (i in 1 until points) total += segment(line, i)
            var seg = 1
            var before = 0f
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

        private fun segment(line: FloatArray, i: Int): Float {
            val dx = line[2 * i] - line[2 * i - 2]
            val dy = line[2 * i + 1] - line[2 * i - 1]
            return sqrt(dx * dx + dy * dy)
        }
    }
}
