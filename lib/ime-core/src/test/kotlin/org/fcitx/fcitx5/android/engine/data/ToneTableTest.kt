/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.data

import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.reading.ToneMark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class ToneTableTest {

    private fun ids(reading: String) = reading.split(' ').map(Syllables::id).toIntArray()

    private val unihan = """
        # version: test
        U+597D: hǎo,hào  # 好
        U+7231: ài  # 爱
        U+4EBA: rén  # 人
        U+884C: xíng,háng,hàng,héng  # 行
        U+94F6: yín  # 银
        U+8D70: zǒu  # 走
        U+4E1C: dōng  # 东
        U+897F: xī  # 西
        U+4E00: yī  # 一
        U+4E0D: bù,fǒu  # 不
        U+5B50: zǐ  # 子
        U+684C: zhuō  # 桌
        U+6211: wǒ  # 我
        U+4EEC: men  # 们
        U+3400: ê̄  # 㐀
        U+20000: hē  # 𠀀
    """.trimIndent()

    private val phrases = """
        爱好: ài hào
        东西: dōng xi
        银行: yín háng
        好人: hǎo rén
        一个: yí gè
        桌子: zhuō zi
        我们: wǒ men
        不好: bú hǎo
        长长长: cháng
    """.trimIndent()

    private fun data(curated: String = ""): PinyinData {
        val b = PinyinDataBuilder().unigram(PinyinDataBuilder.UNKNOWN, -7f, 0f)
        listOf("爱好" to "ai hao", "东西" to "dong xi", "银行" to "yin hang", "行走" to "xing zou", "好人" to "hao ren",
            "一个" to "yi ge", "桌子" to "zhuo zi", "我们" to "wo men", "不好" to "bu hao", "好" to "hao", "𠀀" to "he")
            .forEach { (w, r) -> b.entry(w, ids(r)) }
        ToneSources.chars(unihan.lineSequence(), "unihan", b::charTone)
        ToneSources.phrases(phrases.lineSequence(), "phrases", b::wordTones)
        ToneSources.curated(curated.lineSequence(), "tones.tsv", b::wordTones)
        return PinyinData.load(ByteBuffer.wrap(b.build().toByteArray()))
    }

    private fun PinyinData.tonesOf(word: String, reading: String, known: Boolean = true) =
        tones.tones(word, ids(reading), if (known) (0 until vocabulary.size).first { vocabulary.word(it) == word } else -1)?.toList()

    private fun t(vararg tones: Int) = tones.map { it.toByte() }

    @Test
    fun aWordsListedTonesWinOverItsCharactersUsualOnes() {
        val d = data()
        assertEquals(t(4, 4), d.tonesOf("爱好", "ai hao"))
        assertEquals(t(3, 2), d.tonesOf("好人", "hao ren"))
        // the user's word, not in the data: by its characters
        assertEquals(t(4, 3), d.tonesOf("爱好", "ai hao", known = false))
    }

    @Test
    fun theReadingDecidesThePolyphone() {
        val d = data()
        assertEquals(t(2, 2), d.tonesOf("银行", "yin hang"))
        assertEquals(t(2, 3), d.tonesOf("行走", "xing zou"))
        assertEquals(ToneMark.NONE, d.tones.usual('行'.code, Syllables.id("ni")))
    }

    @Test
    fun aWordsNeutralToneIsItsCharactersOwn() {
        val d = data()
        // listed dōng xi and zhuō zi: read in 西's and 子's own tones (the user, 2026-11-02)
        assertEquals(t(1, 1), d.tonesOf("东西", "dong xi"))
        assertEquals(t(1, 3), d.tonesOf("桌子", "zhuo zi"))
        // typed: in its own tone and, as a word reads it, with ˙ (the user, 2026-11-02); not in another
        for ((c, syllable, own) in listOf(Triple('子', "zi", 3), Triple('西', "xi", 1))) {
            assertTrue("$c ˙", d.tones.allows(c.code, Syllables.id(syllable), ToneMark.NEUTRAL))
            assertTrue("$c $own", d.tones.allows(c.code, Syllables.id(syllable), own))
            assertFalse("$c 2", d.tones.allows(c.code, Syllables.id(syllable), 2))
        }
        // no word reads 好 neutral: ˙ is not its tone
        assertFalse(d.tones.allows('好'.code, Syllables.id("hao"), ToneMark.NEUTRAL))
        // 们 has no tone but the neutral one: kept
        assertEquals(t(3, ToneMark.NEUTRAL), d.tonesOf("我们", "wo men"))
        // a curated one is no different
        assertEquals(t(1, 1), data("东西\tdōng xi\t2026-11-02\ttest\n").tonesOf("东西", "dong xi"))
    }

    @Test
    fun yiAndBuKeepTheirOwnTones() {
        val d = data()
        assertEquals(t(1, 4), d.tonesOf("一个", "yi ge"))
        assertEquals(t(4, 3), d.tonesOf("不好", "bu hao"))
    }

    @Test
    fun onlyWordsTheirCharactersWouldGetWrongAreStored() {
        // 爱好; not 好人 银行 不好 一个, whose characters' usual tones are theirs, nor 东西 桌子 我们, read so too
        assertEquals(1, data().tones.exceptions)
    }

    @Test
    fun unknownCharactersAndBadReadingsAreLeftUnmarked() {
        val d = data()
        assertEquals(t(4, ToneMark.NONE), d.tonesOf("爱x", "ai ni", known = false))
        assertTrue(d.tones.allows('x'.code, Syllables.id("ni"), 2))
        assertNull(d.tonesOf("爱好", "ai"))
        assertEquals(t(1), d.tonesOf("𠀀", "he"))
        assertEquals(ToneMark.NONE, d.tones.usual(0x3400, Syllables.id("e")))
    }

    @Test
    fun theCuratedListIsLast() {
        val d = data("好人\thào rén\t2026-11-01\ttest\n# comment\n")
        assertEquals(t(4, 2), d.tonesOf("好人", "hao ren"))
        assertThrows(SourceException::class.java) { data("好人\thǎo\t2026-11-01\tone short") }
        assertThrows(SourceException::class.java) { data("好人") }
    }

    @Test
    fun aCharacterListedAloneSetsItsUsualTone() {
        val d = data("好\thào\t2026-11-01\ttest\n")
        assertEquals(4, d.tones.usual('好'.code, Syllables.id("hao")))
        assertEquals(t(4, 2), d.tonesOf("好人", "hao ren", known = false))
        assertTrue(d.tones.allows('好'.code, Syllables.id("hao"), 3))
    }

    @Test
    fun badSourceLinesAreRefused() {
        assertThrows(SourceException::class.java) { ToneSources.chars(sequenceOf("597D: hǎo"), "x") { _, _, _ -> } }
        assertThrows(SourceException::class.java) { ToneSources.phrases(sequenceOf("爱好 ài hào"), "x") { _, _, _ -> } }
    }

    @Test
    fun builderRefusesBadTones() {
        val b = PinyinDataBuilder()
        assertThrows(IllegalArgumentException::class.java) { b.charTone('好'.code, Syllables.id("hao"), 6) }
        assertThrows(IllegalArgumentException::class.java) { b.wordTones("好", ids("hao"), byteArrayOf(0)) }
        assertThrows(IllegalArgumentException::class.java) { b.wordTones("好人", ids("hao"), byteArrayOf(3)) }
    }

    @Test
    fun exceptionArraysMustAgree() {
        val one = ByteBuffer.wrap(BitPacked.encode(intArrayOf(0)))
        val e = assertThrows(DataFormatException::class.java) {
            ToneTable(BitPacked(one), ByteBuffer.allocate(2), BitPacked(one), BitPacked(one), BitPacked(one), ByteBuffer.allocate(1))
        }
        assertEquals("tones disagree with their characters", e.message)
    }
}
