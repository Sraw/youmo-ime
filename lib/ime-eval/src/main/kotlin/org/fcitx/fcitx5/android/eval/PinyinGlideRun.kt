/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.glide.GlideLayout
import org.fcitx.fcitx5.android.engine.glide.GlidePath
import org.fcitx.fcitx5.android.engine.glide.PinyinGlide
import org.fcitx.fcitx5.android.engine.glide.TableGlide
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.table.TableDictionary
import kotlin.random.Random

/**
 * Pinyin glide typing measured: each input of a pinyin set cut into its syllables (the longest
 * first), then into the dictionary's words its expected text is (the longest first), each glided
 * as [GlideRun.path] glides a word ([syllables] above 0: that many syllables a glide instead, words
 * or not), its keys as [spelling] types them over [layout] (双拼's, the nine keys'); right if the
 * first reading's keys are those glided, top3 if among the first three. [table]: each word glided
 * as its code in that table instead, where the table has it so, right by the code.
 */
internal class PinyinGlideRun(private val data: PinyinData, private val weights: GlidePath.Weights = PinyinGlide.WEIGHTS) {

    fun run(
        samples: List<Pair<String, String>>,
        syllables: Int,
        wobble: Float,
        seed: Long,
        spelling: PinyinGlide.Spelling = PinyinGlide.Spelling.FULL,
        layout: GlideLayout = GlideLayout.QWERTY,
        table: Pair<TableDictionary, TableGlide>? = null,
    ): GlideRun.Result {
        val glide = PinyinGlide(data, weights)
        val result = GlideRun.Result()
        for ((i, sample) in samples.withIndex()) {
            val (input, expected) = sample
            val random = Random(seed + i)
            // a syllable a character, or the text looked up for a chunk is another's
            val split = split(input)?.takeIf { it.size == expected.length }
            if (split == null) {
                result.samplesSkipped++
                continue
            }
            val chunks = if (syllables > 0) split.chunked(syllables) else words(split, expected).orEmpty()
            var at = 0
            for (chunk in chunks) {
                val text = expected.substring(minOf(at, expected.length), minOf(at + chunk.size, expected.length))
                at += chunk.size
                // the reading as decoded: its syllables apart where the spelling types them so (xi'an is not xian)
                val reading = if (table != null) {
                    table.first.codeOf(text)?.takeIf { table.first.indexOf(it, text) >= 0 }
                } else {
                    chunk.map { spelling.of(Syllables.id(it)) }.takeIf { null !in it }
                        ?.joinToString(if (spelling.separated) PinyinGlide.SEPARATOR.toString() else "")
                }
                // a table's code is its keys as they are: 二笔's ' is a key, not a syllable mark
                val keys = if (table != null) reading else reading?.replace(PinyinGlide.SEPARATOR.toString(), "")
                val glidable = keys != null && glidable(keys, layout)
                if (keys != null && !glidable) result.skipped++
                if (reading == null || keys == null || !glidable) continue
                val path = GlideRun.path(keys, wobble, random, layout)
                val t = System.nanoTime()
                val got = table?.second?.decode(path, layout, 3) ?: glide.decode(path, layout, 3, spelling)
                result.nanos += System.nanoTime() - t
                result.steps += table?.second?.lastSteps ?: glide.lastSteps
                result.words++
                val typed = got.map { it.keys }
                if (typed.firstOrNull() == reading) result.right++ else result.misses += "$reading\t$text\t${got.joinToString(" ") { "${it.keys}:${it.word}" }}"
                if (reading in typed) result.top3++
            }
        }
        return result
    }

    // a key the layout lacks (微软's ; for ing) or all on one key: no glide types it
    private fun glidable(keys: String, layout: GlideLayout) = keys.all { it in layout } && keys.map { layout.centre(it) }.toSet().size >= 2

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
    }
}
