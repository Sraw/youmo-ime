/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.data

import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer

/**
 * Damages a small file one byte at a time and requires that it either fails to load with a
 * [DataFormatException] or loads and then survives every lookup. Anything else would crash the
 * input method on a key press rather than when the file is opened.
 */
class CorruptionTest {

    private fun syl(vararg s: String) = s.map { Syllables.id(it) }.toIntArray()

    private val pinyin = PinyinDataBuilder()
        .unigram("<unk>", -7f, 0f)
        .unigram("你", -2f, -0.5f)
        .unigram("好", -2.5f, -0.25f)
        .unigram("你好", -3f, 0f)
        .bigram("你", "好", -0.8f, -0.1f)
        .bigram("好", "你", -1.1f, 0f)
        .trigram("你", "好", "你", -0.2f)
        .entry("你", syl("ni"))
        .entry("拟", syl("ni"), -1f)
        .entry("好", syl("hao"))
        .entry("你好", syl("ni", "hao"))
        .build()
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

    private fun walkPinyin(bytes: ByteArray) {
        val data = PinyinData.load(ByteBuffer.wrap(bytes))
        (0 until data.vocabulary.size).forEach { data.vocabulary.word(it) }
        // the model maps any id it does not know to <unk>, so try some it cannot know
        val ids = -2..data.vocabulary.size
        val d = data.dictionary
        for (node in 0 until d.nodeCount) {
            for (c in d.firstChild(node) until d.firstChild(node) + d.childCount(node)) d.child(node, d.syllable(c))
            d.child(node, 0)
            for (i in 0 until d.wordCount(node)) {
                d.word(node, i)
                d.weight(node, i)
            }
        }
        d.find(syl("ni", "hao"))
        val m = data.model
        for (a in ids) {
            m.score(a)
            m.backoff(a)
            for (b in ids) {
                m.score(a, b)
                m.backoff(a, b)
                for (c in ids) m.score(a, b, c)
            }
        }
    }

    private fun walkTable(bytes: ByteArray) {
        val t = CodeTable.load(ByteBuffer.wrap(bytes))
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

    private fun survivesEveryByteFlip(original: ByteArray, walk: (ByteArray) -> Unit) {
        walk(original)
        var loaded = 0
        for (at in original.indices) {
            for (mask in MASKS) {
                val bytes = original.copyOf()
                bytes[at] = (bytes[at].toInt() xor mask).toByte()
                try {
                    walk(bytes)
                    loaded++
                } catch (_: DataFormatException) {
                } catch (e: Exception) {
                    fail("byte $at xor $mask: $e")
                }
            }
        }
        // padding and quantised values change nothing that can be checked, so some must load:
        // a sign that the flips reached the walk rather than all failing on the header
        assertTrue("no damaged file loaded", loaded > 0)
    }

    @Test
    fun pinyinData() = survivesEveryByteFlip(pinyin, ::walkPinyin)

    @Test
    fun codeTable() = survivesEveryByteFlip(table, ::walkTable)

    private companion object {
        /** the lowest and highest bit, and all of them */
        val MASKS = intArrayOf(0x01, 0x80, 0xff)
    }
}
