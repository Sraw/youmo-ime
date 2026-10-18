/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.glide.GlidePath
import org.fcitx.fcitx5.android.engine.english.EnglishData
import org.fcitx.fcitx5.android.engine.english.EnglishDecoder
import org.fcitx.fcitx5.android.engine.english.EnglishText
import org.fcitx.fcitx5.android.engine.english.GlideDecoder
import org.fcitx.fcitx5.android.engine.glide.GlideLayout
import java.util.Locale
import kotlin.random.Random

/**
 * Glide typing measured: each word of each sentence glided as a finger would, its keys' centres
 * each off by [GlideRun.wobble] key widths (a Gaussian's sigma), joined by lines, after the words
 * meant before it; right if the decoder's first word is the word (its case aside), top3 if among
 * its first three. Words of one key (I, a) are not glided.
 */
internal class GlideRun(private val data: EnglishData, private val weights: GlidePath.Weights = GlidePath.Weights()) {
    class Result {
        var words = 0
        var right = 0
        var top3 = 0
        var nanos = 0L
        val misses = ArrayList<String>()

        fun add(other: Result) {
            words += other.words
            right += other.right
            top3 += other.top3
            nanos += other.nanos
            misses += other.misses
        }

        fun row(name: String): String = String.format(
            Locale.ROOT, "%-8s %6d %7.1f%% %7.1f%% %8.1f",
            name, words, right * 100.0 / words.coerceAtLeast(1), top3 * 100.0 / words.coerceAtLeast(1), nanos / 1e6 / words.coerceAtLeast(1),
        )

        companion object {
            const val HEADER = "glide     words   right    top3   ms/word"
        }
    }

    fun run(sentences: List<String>, wobble: Float, seed: Long, threads: Int): Result {
        data.warmUp()
        val each = dealt(sentences.withIndex().toList(), threads, {}) { hand ->
            hand.map { (i, sentence) -> Result().also { glide(sentence, wobble, Random(seed + i), it) } }
        }
        return Result().apply { each.forEach(::add) }
    }

    private fun glide(sentence: String, wobble: Float, random: Random, result: Result) {
        val decoder = GlideDecoder(data, weights = weights)
        val context = EnglishDecoder(data)
        val before = arrayListOf(EnglishText.START)
        for (token in sentence.split(' ')) {
            val word = token.trimEnd(',', '.', '!', '?', ';', ':')
            val keys = EnglishData.key(word).lowercase()
            if (keys.length >= 2 && keys.all { it in GlideLayout.QWERTY } && keys.toSet().size >= 2) {
                val t = System.nanoTime()
                val p = path(keys, wobble, random)
                val got = decoder.decode(p, GlideLayout.QWERTY, context.context(before), 3)
                    .map { data.word(it.word) }
                result.nanos += System.nanoTime() - t
                result.words++
                if (got.firstOrNull().equals(word, ignoreCase = true)) {
                    result.right++
                } else {
                    // found at all, and where: past the first, outranked; none, the path was held to miss it
                    val all = decoder.decode(p, GlideLayout.QWERTY, context.context(before), Int.MAX_VALUE)
                    val rank = all.indexOfFirst { data.word(it.word).equals(word, ignoreCase = true) }
                    val pts = p.toList().chunked(2).joinToString(" ") { (x, y) -> String.format(Locale.ROOT, "%.1f,%.1f", x, y) }
                    result.misses += "$word\t${got.joinToString(" ")}\t${if (rank < 0) "missing" else "rank $rank"}\t$pts"
                }
                if (got.any { it.equals(word, ignoreCase = true) }) result.top3++
            }
            before += word
            if (token != word) before += EnglishText.START
        }
    }

    companion object {
        /**
         * [keys]' centres on [layout], each moved by a Gaussian of sigma [wobble], a key again
         * (a letter doubled, the nine keys' next on the same) once, joined as a finger's touch points
         * are: one every [TOUCH] key widths, each a little off the line.
         */
        fun path(keys: String, wobble: Float, random: Random, layout: GlideLayout = GlideLayout.QWERTY): FloatArray {
            val corners = ArrayList<Pair<Float, Float>>()
            var last = ' '
            for (c in keys) {
                if (c == last || layout.sameKey(c, last)) continue
                last = c
                val (x, y) = layout.centre(c)
                corners += x + gauss(random) * wobble to y + gauss(random) * wobble
            }
            val out = arrayListOf(corners[0].first, corners[0].second)
            for ((a, b) in corners.zipWithNext()) {
                val steps = maxOf(1, (kotlin.math.hypot(b.first - a.first, b.second - a.second) / TOUCH).toInt())
                for (k in 1..steps) {
                    val jitter = if (k == steps) 0f else TOUCH_JITTER
                    out += a.first + (b.first - a.first) * k / steps + gauss(random) * jitter
                    out += a.second + (b.second - a.second) * k / steps + gauss(random) * jitter
                }
            }
            return out.toFloatArray()
        }

        private const val TOUCH = 0.05f
        private const val TOUCH_JITTER = 0.03f

        private fun gauss(random: Random): Float {
            val u = random.nextDouble().coerceAtLeast(1e-12)
            val v = random.nextDouble()
            return (kotlin.math.sqrt(-2 * kotlin.math.ln(u)) * kotlin.math.cos(2 * Math.PI * v)).toFloat()
        }
    }
}
