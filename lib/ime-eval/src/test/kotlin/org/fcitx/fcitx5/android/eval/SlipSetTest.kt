/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.pinyin.KeyNeighbours
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SlipSetTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun slips(input: String, expected: String) =
        SlipSet.generate(listOf(Sample(input, expected, "daily"))).associate { it.tag to it.input }

    @Test
    fun eachPairAndSlipChangesOneSyllable() {
        val zhongguo = slips("zhongguo", "中国")
        assertEquals("zongguo", zhongguo["fuzzy-z_zh"])
        assertEquals("zhognguo", zhongguo["typo-gn"])
        assertEquals("zhonguo", zhongguo["typo-on"])
        assertEquals(setOf("fuzzy-z_zh", "typo-gn", "typo-on", SlipSet.KEY), zhongguo.keys)
        val nihao = slips("nihao", "你好")
        assertEquals("lihao", nihao["fuzzy-l_n"])
        // a partner must be a syllable: fao is none
        assertTrue("fuzzy-f_h" !in nihao)
    }

    @Test
    fun aKeySlipsOntoOneNextToIt() {
        val slipped = slips("zhongguo", "中国").getValue(SlipSet.KEY)
        val at = slipped.indices.single { slipped[it] != "zhongguo"[it] }
        assertTrue(slipped, slipped[at] in KeyNeighbours.of("zhongguo"[at]))
        // any letter, 简拼 too, but never a separator
        assertEquals(2, slips("nh", "你好").getValue(SlipSet.KEY).length)
        assertEquals('\'', slips("xi'an", "西安").getValue(SlipSet.KEY)[2])
    }

    @Test
    fun finalsFollowTheirRules() {
        val xue = slips("xuexi", "学习")
        assertEquals("xvexi", xue["typo-v"])
        // xue has no fuzzy partner: ü is not u after x
        assertTrue(xue.keys.none { it.startsWith("fuzzy-") })
        assertEquals("ang", slips("an", "安")["fuzzy-an_ang"])
        assertEquals("lu", slips("lv", "绿")["fuzzy-v_u"])
    }

    @Test
    fun separatorsAndTheRestOfTheInputAreKept() {
        assertEquals("xi'ang", slips("xi'an", "西安")["fuzzy-an_ang"])
        assertEquals(setOf(SlipSet.KEY), slips("nh", "你好").keys)
    }

    @Test
    fun theSyllableIsChosenByTheTextAndKind() {
        // five syllables take z for zh; CRC32 of 张三赵州住者fuzzy-z_zh picks the fifth
        assertEquals("zhangsanzhaozhouzhuze", slips("zhangsanzhaozhouzhuzhe", "张三赵州住者")["fuzzy-z_zh"])
    }

    @Test
    fun theCommandWritesTheSet() {
        val set = tmp.newFile("set.tsv").apply { writeText("zhongguo\t中国\tdaily\n") }
        val out = tmp.newFile("slips.tsv")
        val printed = StringBuilder()
        assertEquals(0, runCli(arrayOf("slips", set.path, out.path), printed, StringBuilder()))
        assertEquals("4 samples", printed.trim())
        assertEquals(4, out.useLines { EvalSet.parse(it) }.size)
    }
}
