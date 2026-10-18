/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.glide

import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import kotlin.math.sqrt

/**
 * Where the keyboard's letter keys are, for a glide ([GlidePath]): each letter's key centres, x in
 * key widths to the right, y down in [ROW_HEIGHT]s a row, as the app measures the keys shown
 * ([QWERTY] lays them out as most phones do). A letter may have two keys (the split keyboard's G
 * and V, on both halves); one with none is no glide's.
 */
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

    // each letter's keys as one id, letters on the same keys alike: the nine keys' a, b and c all on 2
    private val key = IntArray(LETTERS) { i -> (0..i).first { j -> x[j].contentEquals(x[i]) && y[j].contentEquals(y[i]) } }

    /** Whether [a] and [b] are on the same keys, as the nine keys' letters are: a glide goes from one to the other without moving. */
    fun sameKey(a: Char, b: Char): Boolean = a in this && b in this && key[index(a)] == key[index(b)]

    /** [c]'s first key's centre: its only one but on a split keyboard. */
    fun centre(c: Char): Pair<Float, Float> {
        require(c in this) { "no key for '$c' in the layout" }
        return x[index(c)][0] to y[index(c)][0]
    }

    operator fun contains(c: Char): Boolean = index(c) >= 0 && x[index(c)].isNotEmpty()

    /** How far [px], [py] is from [c]'s nearest key; infinite where it has none. */
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

    /** [c]'s key nearest [px], [py]: its centre into [out] at [at] (x, then y). */
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

        /** A row's height in key widths, keys 1.4 times as tall as wide: what [GlidePath]'s radii are measured in. */
        const val ROW_HEIGHT = 1.4f

        /** The keys rows of [rows], each a key wide, a row apart, each row moved right by its [indents] (in key widths). */
        fun rows(
            rows: List<String> = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm"),
            indents: List<Float> = listOf(0f, 0.5f, 1.5f),
        ): GlideLayout = GlideLayout(
            rows.withIndex().flatMap { (r, keys) ->
                keys.withIndex().map { (i, c) -> c to (indents[r] + i + 0.5f to ROW_HEIGHT * (r + 0.5f)) }
            }
        )

        /** QWERTY's on a phone upright. */
        val QWERTY = rows()

        /** The nine keys' (九键): each digit key's [letters] on it, three keys a row from 1. */
        fun nineKeys(letters: NineKeyLetters = NineKeyLetters.STANDARD): GlideLayout = GlideLayout(
            ('2'..'9').flatMap { d ->
                val k = d - '1'
                letters.letters(d).map { it to (k % 3 + 0.5f to ROW_HEIGHT * (k / 3 + 0.5f)) }
            }
        )
    }
}
