/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.pinyin.ZhuyinKeys
import org.fcitx.fcitx5.android.engine.reading.ToneMark
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertArrayEquals(intArrayOf(4, 4), tones.of("爱好", intArrayOf(ai, hao)))
        // a character alone its first reading's; one not listed none
        assertArrayEquals(intArrayOf(0, 3), tones.of("碍好", intArrayOf(ai, hao)))
    }

    @Test
    fun allowsTheListedTonesAndTheNeutral() {
        val hao = Syllables.id("hao")
        val t = tones
        assertTrue(t.allows('好'.code, hao, 4))
        assertTrue(t.allows('好'.code, hao, ToneMark.NEUTRAL))
        assertFalse(t.allows('好'.code, hao, 1))
        assertTrue(t.allows('爱'.code, Syllables.id("ai"), 1))
    }
}
