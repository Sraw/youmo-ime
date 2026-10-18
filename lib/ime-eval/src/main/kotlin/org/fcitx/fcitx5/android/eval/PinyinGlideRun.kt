/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.glide.GlidePath
import org.fcitx.fcitx5.android.engine.glide.PinyinGlide
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import kotlin.random.Random

/**
 * Pinyin glide typing measured: each input of a pinyin set cut into its syllables (the longest
 * first), then into the dictionary's words its expected text is (the longest first), each glided
 * as [GlideRun.path] glides a word ([syllables] above 0: that many syllables a glide instead, words
 * or not); right if the first reading's letters are those glided, top3 if among the first three.
 */
internal class PinyinGlideRun(private val data: PinyinData, private val weights: GlidePath.Weights = PinyinGlide.WEIGHTS) {

    fun run(samples: List<Pair<String, String>>, syllables: Int, wobble: Float, seed: Long): GlideRun.Result {
        val glide = PinyinGlide(data, weights)
        val result = GlideRun.Result()
        for ((i, sample) in samples.withIndex()) {
            val (input, expected) = sample
            val random = Random(seed + i)
            val split = split(input).orEmpty()
            val chunks = (if (syllables > 0) split.chunked(syllables) else words(split, expected).orEmpty())
                .filter { chunk -> chunk.joinToString("").toSet().size >= 2 }
            for (chunk in chunks) {
                val keys = chunk.joinToString("")
                val t = System.nanoTime()
                val got = glide.decode(GlideRun.path(keys, wobble, random), GlideLayoutQwerty, 3).map { it.pinyin }
                result.nanos += System.nanoTime() - t
                result.words++
                val letters = got.map { it.replace("'", "") }
                if (letters.firstOrNull() == keys) result.right++ else result.misses += "${chunk.joinToString("'")}\t${got.joinToString(" ")}"
                if (keys in letters) result.top3++
            }
        }
        return result
    }

    // [split] cut into the dictionary's words that [text] is, the longest first; null where its
    // syllables are not one a character, or a character's is no word
    private fun words(split: List<String>, text: String): List<List<String>>? {
        if (split.size != text.length) return null
        val ids = split.map { Syllables.id(it) }
        val out = ArrayList<List<String>>()
        var at = 0
        while (at < split.size) {
            val k = (minOf(MAX_WORD, split.size - at) downTo 1).firstOrNull { k ->
                val node = data.dictionary.find(ids.subList(at, at + k).toIntArray())
                node >= 0 && (0 until data.dictionary.wordCount(node)).any { data.vocabulary.word(data.dictionary.word(node, it)) == text.substring(at, at + k) }
            } ?: return null
            out += split.subList(at, at + k)
            at += k
        }
        return out
    }

    // [input]'s syllables, the longest that spells on first; null if it is not all pinyin
    private fun split(input: String): List<String>? {
        val out = ArrayList<String>()
        var at = 0
        while (at < input.length) {
            val s = (minOf(MAX_SYLLABLE, input.length - at) downTo 1).map { input.substring(at, at + it) }
                .firstOrNull { Syllables.id(it) >= 0 } ?: return null
            out += s
            at += s.length
        }
        return out
    }

    private companion object {
        const val MAX_SYLLABLE = 6
        const val MAX_WORD = 6
        val GlideLayoutQwerty = org.fcitx.fcitx5.android.engine.glide.GlideLayout.QWERTY
    }
}
