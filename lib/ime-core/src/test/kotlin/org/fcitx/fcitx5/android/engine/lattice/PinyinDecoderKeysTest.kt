/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.lattice

import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.data.pinyinDataOf
import org.fcitx.fcitx5.android.engine.pinyin.GroupedShuangpinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.LatinWords
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.pinyin.T9Segmenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** A decoder going on from its last decode (LatticeBuilder collectOn) on keys standing for several letters, Latin words among them. */
class PinyinDecoderKeysTest {
    // Latin words sharing keys with pinyin (de, hao, ok on the nine keys) and with each other (cpu, crt)
    private val latinData = pinyinDataOf(
        Triple("我", "wo", -2f),
        Triple("的", "de", -1.5f),
        Triple("你好", "ni hao", -3f),
        Triple("iPhone", "I P H O N E", -5f),
        Triple("cpu", "C P U", -5f),
        Triple("CRT", "C R T", -5.5f),
        Triple("app", "A P P", -5f),
        Triple("我的你好吗", "wo de ni hao ma", -6f),
        Triple("的你好", "de ni hao", -6f),
        Triple("好的", "hao de", -4f),
        Triple("de", "D E", -6f),
        Triple("ok", "O K", -5f),
        Triple("hao", "H A O", -6f),
        Triple("A股", "A gu", -6f),
    )

    private fun latinKeys(letters: NineKeyLetters = NineKeyLetters.STANDARD) =
        T9Segmenter(abbreviations = false, letters = letters, latin = LatinWords.of(latinData.dictionary))

    // keys typed at once (a glide, a retype), then fewer: steps kept to go on from a position started past the new end
    @Test
    fun goingOnAfterSeveralKeysAtOnceAndBackReadsAsAFreshDecode() {
        val latin = latinKeys()
        val plain = T9Segmenter(abbreviations = false)
        // 双拼 on the nine keys, the same keys: every key may be read alone, a syllable begun
        val shuangpin = GroupedShuangpinSegmenter(ShuangpinScheme.ZIRANMA, NineKeyLetters.NINE_SP)
        fun keysOf(segmenter: Any, digits: String) =
            if (segmenter !== shuangpin) digits else digits.filter { it != '\'' }.map { NineKeyLetters.NINE_SP.key(NineKeyLetters.STANDARD.letters(it)[0]) }.joinToString("")
        assertRows(listOf("963 963365 963", "33' 33'64426248 33'", "963 9633474663 96334"), { it }) { row ->
            for (segmenter in listOf(plain, latin, shuangpin)) {
                val kept = PinyinDecoder(latinData.dictionary, latinData.vocabulary, WordScorer.of(latinData.model))
                for (input in row.split(' ').map { keysOf(segmenter, it) }) {
                    val fresh = PinyinDecoder(latinData.dictionary, latinData.vocabulary, WordScorer.of(latinData.model)).decode(segmenter.segment(input))
                    assertEquals(input, describe(fresh), describe(kept.decode(segmenter.segment(input))))
                }
            }
        }
    }

    @Test
    fun buildingOnTheLastDecodeChangesNothingWithLatinWordsOnKeys() {
        val pieces = listOf("wo", "de", "nihao", "ma", "hao", "ok", "agu", "iphone", "cpu", "crt", "app", "i", "p", "c", "o", "'")
        assertRows(listOf(NineKeyLetters.STANDARD, NineKeyLetters.G14, NineKeyLetters.G18), { "on ${it.tag()}" }) { letters ->
            val segmenter = latinKeys(letters)
            val typed = pieces.map { p -> p.map(letters::key).joinToString("") }
            val random = Random(23)
            val kept = PinyinDecoder(latinData.dictionary, latinData.vocabulary, WordScorer.of(latinData.model))
            // sentences typed a key at a time, each going on from a start of the last
            var sentence = ""
            val inputs = sequence {
                while (true) {
                    val shared = sentence.take(random.nextInt(sentence.length + 1))
                    sentence = shared + (1..1 + random.nextInt(4)).joinToString("") { typed[random.nextInt(typed.size)] }
                    // now and then all at once, as a glide or a retype puts it
                    if (random.nextInt(4) == 0) yield(sentence) else for (n in shared.length + 1..sentence.length) yield(sentence.take(n))
                    if (random.nextInt(3) == 0) yield(sentence.dropLast(1 + random.nextInt(3)))
                }
            }
            var on = 0
            inputs.take(6000).forEachIndexed { step, input ->
                val graph = segmenter.segment(input)
                val fresh = PinyinDecoder(latinData.dictionary, latinData.vocabulary, WordScorer.of(latinData.model)).decode(graph)
                assertEquals("step $step: $input", describe(fresh), describe(kept.decode(graph)))
                on += kept.collectedOn
            }
            assertTrue("went on $on times", on > 300)
        }
    }

    private fun describe(d: Decoding) = (d.sentences + listOf(null) + d.words).map { c ->
        c?.let { "${it.text} ${it.end} ${it.score} ${it.words.toList()}" }
    }
}
