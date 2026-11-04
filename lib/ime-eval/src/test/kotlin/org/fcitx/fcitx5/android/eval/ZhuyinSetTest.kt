/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.pinyin.ZhuyinKeys
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ZhuyinSetTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val ni = Sample("nihao", "你好", "daily")

    private val tones by lazy {
        val chars = tmp.newFile("chars.txt").apply { writeText("# chars\nU+4F60: nǐ  # 你\nU+597D: hǎo,hào  # 好\n") }
        val phrases = tmp.newFile("phrases.txt").apply { writeText("# phrases\n爱好: ài hào\n") }
        ZhuyinSet.Tones(chars, phrases)
    }

    @Test
    fun eachSymbolIsTypedOnItsKey() {
        val s = ZhuyinKeys::symbolCode
        assertEquals(listOf("${s('ㄋ')}${s('ㄧ')}${s('ㄏ')}${s('ㄠ')}"), ZhuyinSet.convert(listOf(ni), ZhuyinKeys.DACHEN).map { it.input })
        val nine = ZhuyinSet.convert(listOf(ni), ZhuyinKeys.NINE).single().input
        assertEquals("ㄋㄧㄏㄠ".map { ZhuyinKeys.NINE.keysOf(it).first() }.joinToString(""), nine)
    }

    @Test
    fun tonesFollowTheirSyllables() {
        val t = ZhuyinKeys::toneCode
        val typed = ZhuyinSet.convert(listOf(ni), ZhuyinKeys.DACHEN, tones).single().input
        assertEquals(t(3), typed[2])
        assertEquals(t(3), typed[5])
    }

    @Test
    fun aPhraseReadsItsOwnTones() {
        val ai = Syllables.id("ai")
        val hao = Syllables.id("hao")
        val spacedTwice by lazy {
            val chars = tmp.newFile("c.txt").apply { writeText("U+597D: hǎo  # 好\n") }
            val phrases = tmp.newFile("p.txt").apply { writeText("爱好: ài  hào\n") }
            ZhuyinSet.Tones(chars, phrases)
        }
        class Row(val tones: () -> ZhuyinSet.Tones, val text: String, val expected: IntArray, val why: String)
        assertRows(
            listOf(
                Row({ tones }, "爱好", intArrayOf(4, 4), "a phrase's own line, not its characters' first readings"),
                Row({ tones }, "碍好", intArrayOf(0, 3), "a character alone its first reading's; one not listed none"),
                Row({ spacedTwice }, "爱好", intArrayOf(4, 4), "a line with two spaces between its readings was dropped whole"),
            ),
            { it.why },
        ) { assertArrayEquals(it.expected, it.tones().of(it.text, intArrayOf(ai, hao))) }
    }

    @Test
    fun theCommandsTypeASetAndReadIt() {
        val set = tmp.newFile("set.tsv").apply { writeText("nihao\t你好\tdaily\n") }
        val bytes = PinyinDataBuilder().unigram("<unk>", -7f, 0f)
            .unigram("你好", -2f, 0f).entry("你好", intArrayOf(Syllables.id("ni"), Syllables.id("hao")))
            // read the same, less likely, any tone allowed (号 has none in the data): first only when 你好's tone is refused
            .unigram("你号", -3f, 0f).entry("你号", intArrayOf(Syllables.id("ni"), Syllables.id("hao")))
            .charTone('好'.code, Syllables.id("hao"), 3)
            .build().toByteArray()
        val data = tmp.newFile("pinyin.data").apply { writeBytes(bytes) }
        // typed with the tones a chars file gives, read with the data's own tones, as the app reads zhuyin
        fun candidates(chars: String): List<String> {
            val charsFile = tmp.newFile().apply { writeText(chars) }
            val typed = tmp.newFile()
            val out = StringBuilder()
            assertEquals(0, runCli(arrayOf("zhuyin", set.path, typed.path, "--keys", "nine", "--tones", charsFile.path), out, StringBuilder()))
            assertTrue(out.toString(), out.startsWith("1 of 1 samples typed in zhuyin"))
            val result = tmp.newFile()
            assertEquals(0, runCli(arrayOf("pinyin", data.path, typed.path, result.path, "--scheme", "zy9"), StringBuilder(), StringBuilder()))
            return result.useLines { RunResultFormat.parse(it) }.single().candidates
        }
        class Row(val chars: String, val first: String, val why: String)
        assertRows(
            listOf(
                Row("U+4F60: nǐ  # 你\nU+597D: hǎo  # 好\n", "你好", "好 typed with the tone the data has for it"),
                Row("U+4F60: nǐ  # 你\nU+597D: hào  # 好\n", "你号", "好 typed with a tone the data does not allow it: the data's tones read the keys, 你号 first"),
            ),
            { it.why },
        ) {
            val candidates = candidates(it.chars)
            assertEquals(it.why, it.first, candidates.firstOrNull())
            // the tone's penalty is soft: 你好 put after, never out
            assertTrue("$candidates", "你好" in candidates)
        }
    }
}
