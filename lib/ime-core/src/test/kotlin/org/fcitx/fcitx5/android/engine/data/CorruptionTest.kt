/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.data

import org.fcitx.fcitx5.android.engine.lattice.LayerPrior
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/** Damages a file byte by byte: each must fail to load with [DataFormatException] or survive every lookup. */
class CorruptionTest {

    private val pinyin = PinyinDataBuilder()
        .layers(listOf("base", "places", "new"))
        .unigram("<unk>", -7f, 0f)
        .unigram("你", -2f, -0.5f)
        .unigram("好", -2.5f, -0.25f)
        .unigram("你好", -3f, 0f)
        .bigram("你", "好", -0.8f, -0.1f)
        .bigram("好", "你", -1.1f, 0f)
        .trigram("你", "好", "你", -0.2f)
        .entry("你", syl("ni"))
        .entry("拟", syl("ni"), -1f, layer = 1)
        .entry("好", syl("hao"))
        .entry("你好", syl("ni", "hao"))
        .entry("妳好", syl("ni", "hao"), layer = 2)
        .build(mapOf(Misreadings.META_PREFIX + "你好" to Misreadings.meta(Misreadings("你好", "nǐ hǎo", listOf("li'hao")))))
        .toByteArray()

    private val table = CodeTable.Builder()
        .header("码长", "4")
        .rule("e2", "p11+p12")
        .entry("wq", "你")
        .entry("a", "工")
        .entry("aa", "式")
        .entry("b", "了")
        .build()
        .toByteArray()

    private fun walkPinyin(data: PinyinData) {
        val v = data.vocabulary
        (0 until v.size).forEach { data.wordIndex.find(v.word(it)) }
        data.wordIndex.find("无此词")
        data.misreadings.values.forEach { it.typed }
        // the model maps any id it does not know to <unk>, so try some it cannot know
        val ids = -2..v.size
        val d = data.dictionary
        for (node in 0 until d.nodeCount) {
            for (c in d.firstChild(node) until d.firstChild(node) + d.childCount(node)) {
                d.child(node, d.syllable(c))
                Syllables.spelling(d.syllable(c))
            }
            d.child(node, 0)
            for (i in 0 until d.wordCount(node)) {
                d.word(node, i)
                d.weight(node, i)
            }
        }
        d.find(syl("ni", "hao"))
        val m = data.model
        // a word's layer indexes the prior's values as each word is scored
        val prior = LayerPrior(data.layers)
        for (a in ids) {
            m.score(a)
            m.backoff(a)
            prior.of(a)
            for (b in ids) {
                m.score(a, b)
                m.backoff(a, b)
                for (c in ids) m.score(a, b, c)
                // as Predictor takes the words that follow: their text and layer
                m.forEachAfter(m.context(a, b)) { word, _ ->
                    v.word(word)
                    prior.of(word)
                }
            }
        }
    }

    private fun walkTable(t: CodeTable) {
        t.header
        t.rules
        for (i in 0 until t.size) {
            val code = t.code(i)
            t.text(i)
            for (n in 0..code.length) t.prefixRange(code.substring(0, n))
            t.exactRange(code)
        }
        t.prefixRange("zz")
    }

    private fun <T> survivesEveryByteFlip(original: ByteArray, load: (ByteBuffer) -> T, walk: (T) -> Unit) {
        walk(load(ByteBuffer.wrap(original)))
        var loaded = 0
        for (at in original.indices) {
            for (mask in MASKS) {
                val bytes = original.copyOf()
                bytes[at] = (bytes[at].toInt() xor mask).toByte()
                // only loading may refuse the file: a lookup that throws would throw on a key press
                val data = try {
                    load(ByteBuffer.wrap(bytes))
                } catch (_: DataFormatException) {
                    continue
                } catch (e: Exception) {
                    throw AssertionError("byte $at xor $mask: loading threw $e", e)
                }
                try {
                    walk(data)
                } catch (e: Exception) {
                    throw AssertionError("byte $at xor $mask: $e", e)
                }
                loaded++
            }
        }
        assertTrue("no damaged file loaded", loaded > 0)
    }

    @Test
    fun pinyinData() = survivesEveryByteFlip(pinyin, { PinyinData.load(it, verify = false) }, ::walkPinyin)

    @Test
    fun codeTable() = survivesEveryByteFlip(table, { CodeTable.load(it, verify = false) }, ::walkTable)

    private companion object {
        /** the lowest and highest bit, and all of them */
        val MASKS = intArrayOf(0x01, 0x80, 0xff)
    }
}
