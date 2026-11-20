/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.glide

import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import kotlin.math.sqrt

/** Letter key centres: x in key widths, y in [ROW_HEIGHT]s, as the app measures the shown keys; a letter may have two keys (split keyboard). */
class GlideLayout(keys: List<Pair<Char, Pair<Float, Float>>>) {

    constructor(centres: Map<Char, Pair<Float, Float>>) : this(centres.toList())

    private val x = Array(LETTERS) { FloatArray(0) }
    private val y = Array(LETTERS) { FloatArray(0) }

    init {
        for ((c, at) in keys) {
            val i = c.lowercaseChar() - 'a'
            if (i !in 0 until LETTERS) continue
            x[i] += at.first
            y[i] += at.second
        }
    }

    // letters on the same keys share an id: the nine keys' a, b and c all on 2
    private val key = IntArray(LETTERS) { i -> (0..i).first { j -> x[j].contentEquals(x[i]) && y[j].contentEquals(y[i]) } }

    /** Letters share keys, as on the nine keys: pinyin hands such a glide to its nine-key session (KeyboardPair). */
    val sharesKeys: Boolean = (0 until LETTERS).any { i -> x[i].isNotEmpty() && key[i] != i }

    fun sameKey(a: Char, b: Char): Boolean = a in this && b in this && key[index(a)] == key[index(b)]

    fun centre(c: Char): Pair<Float, Float> {
        require(c in this) { "no key for '$c' in the layout" }
        return x[index(c)][0] to y[index(c)][0]
    }

    operator fun contains(c: Char): Boolean = index(c) >= 0 && x[index(c)].isNotEmpty()

    fun distance(c: Char, px: Float, py: Float): Float {
        val i = index(c)
        if (i < 0) return Float.POSITIVE_INFINITY
        var best = Float.POSITIVE_INFINITY
        for (k in x[i].indices) {
            val dx = px - x[i][k]
            val dy = py - y[i][k]
            best = minOf(best, dx * dx + dy * dy)
        }
        return sqrt(best)
    }

    fun nearest(c: Char, px: Float, py: Float, out: FloatArray, at: Int) {
        val i = index(c)
        var best = Float.POSITIVE_INFINITY
        for (k in x[i].indices) {
            val dx = px - x[i][k]
            val dy = py - y[i][k]
            val d = dx * dx + dy * dy
            if (d < best) {
                best = d
                out[at] = x[i][k]
                out[at + 1] = y[i][k]
            }
        }
    }

    private fun index(c: Char) = (c.lowercaseChar() - 'a').takeIf { it in 0 until LETTERS } ?: -1

    companion object {
        const val LETTERS = 26

        /** Row height in key widths (keys 1.4 times as tall as wide): [GlidePath]'s radii unit. */
        const val ROW_HEIGHT = 1.4f

        fun rows(
            rows: List<String> = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm"),
            indents: List<Float> = listOf(0f, 0.5f, 1.5f),
        ): GlideLayout = GlideLayout(
            rows.withIndex().flatMap { (r, keys) ->
                keys.withIndex().map { (i, c) -> c to (indents[r] + i + 0.5f to ROW_HEIGHT * (r + 0.5f)) }
            }
        )

        val QWERTY = rows()

        fun nineKeys(letters: NineKeyLetters = NineKeyLetters.STANDARD): GlideLayout = GlideLayout(
            ('2'..'9').flatMap { d ->
                val k = d - '1'
                letters.letters(d).map { it to (k % 3 + 0.5f to ROW_HEIGHT * (k / 3 + 0.5f)) }
            }
        )
    }
}
