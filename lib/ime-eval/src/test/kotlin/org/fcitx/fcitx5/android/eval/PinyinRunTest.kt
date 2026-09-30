/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PinyinRunTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun theEngineTypesEachInputALetterAtATime() {
        val data = tmp.newFile("pinyin.data")
        data.writeBytes(
            PinyinDataBuilder()
                .unigram("<unk>", -7f, 0f)
                .unigram("你", -2f, 0f)
                .unigram("你好", -3f, 0f)
                .entry("你", intArrayOf(Syllables.id("ni")))
                .entry("你好", intArrayOf(Syllables.id("ni"), Syllables.id("hao")))
                .build().toByteArray(),
        )
        val set = tmp.newFile("set.tsv").apply { writeText("nihao\t你好\tdaily\nnh\t你好\tabbrev\n") }
        val result = tmp.newFile("result.tsv")
        assertEquals(0, runCli(arrayOf("pinyin", data.path, set.path, result.path), StringBuilder(), StringBuilder()))
        val results = result.useLines { RunResultFormat.parse(it) }
        assertEquals(listOf("nihao", "nh"), results.map { it.input })
        assertEquals(listOf(5, 2), results.map { it.keyLatenciesMicros.size })
        assertEquals(listOf("你好", "你好"), results.map { it.candidates.first() })
        // sentences first, then words; 好 alone is not in this dictionary, so hao stays as typed
        assertEquals(listOf("你好", "你hao", "你"), results[0].candidates)

        val shuangpin = tmp.newFile("shuangpin.tsv").apply { writeText("nihc\t你好\tdaily\n") }
        assertEquals(0, runCli(arrayOf("pinyin", data.path, shuangpin.path, result.path, "xiaohe"), StringBuilder(), StringBuilder()))
        assertEquals("你好", result.useLines { RunResultFormat.parse(it) }.single().candidates.first())
        assertEquals(2, runCli(arrayOf("pinyin", data.path, shuangpin.path, result.path, "nope"), StringBuilder(), StringBuilder()))
    }
}
