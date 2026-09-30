/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.session.PinyinSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer

class KeystrokeRunTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun syl(vararg s: String) = s.map { Syllables.id(it) }.toIntArray()

    private val bytes = PinyinDataBuilder()
        .unigram("<unk>", -7f, 0f)
        .unigram("你", -2f, 0f)
        .unigram("拟", -5f, 0f)
        .unigram("好", -2.5f, 0f)
        .unigram("你好", -3f, 0f)
        .unigram("吗", -3.5f, 0f)
        .unigram("泥", -3f, 0f)
        .unigram("尼", -3.5f, 0f)
        .unigram("号", -3f, 0f)
        .unigram("毫", -3.5f, 0f)
        .entry("你", syl("ni"))
        .entry("拟", syl("ni"))
        .entry("好", syl("hao"))
        .entry("你好", syl("ni", "hao"))
        .entry("吗", syl("ma"))
        .entry("泥", syl("ni"))
        .entry("尼", syl("ni"))
        .entry("号", syl("hao"))
        .entry("毫", syl("hao"))
        .build().toByteArray()

    private val data = PinyinData.load(ByteBuffer.wrap(bytes))

    private fun run(pageSize: Int = PinyinSession.DEFAULT_PAGE_SIZE) =
        KeystrokeRun(PinyinSession(data, PinyinSegmenter(), pageSize = pageSize))

    @Test
    fun theFirstCandidateCostsOneKey() {
        val outcome = run().type(Sample("nihao", "你好", "daily"))
        assertTrue(outcome.reached)
        assertTrue(outcome.first)
        assertEquals(6, outcome.keys)
    }

    @Test
    fun aPieceIsPickedWhenTheWholeIsNotShown() {
        // 拟好 is not among the best readings: 拟 is picked, then 好
        val outcome = run(pageSize = 20).type(Sample("nihao", "拟好", "daily"))
        assertTrue(outcome.reached)
        assertFalse(outcome.first)
        assertEquals(7, outcome.keys)
    }

    @Test
    fun pagesAreTurnedToReachIt() {
        // one to a page: 拟 comes after 你, 泥, 尼
        val outcome = run(pageSize = 1).type(Sample("ni", "拟", "daily"))
        assertTrue(outcome.reached)
        assertEquals(2 + 3 + 1, outcome.keys)
    }

    @Test
    fun textNoCandidateStartsIsNotReached() {
        assertFalse(run().type(Sample("nihao", "腻好", "daily")).reached)
        // one page only: 拟 is on the fourth
        assertFalse(KeystrokeRun(PinyinSession(data, PinyinSegmenter(), pageSize = 1), maxPages = 1).type(Sample("ni", "拟", "daily")).reached)
    }

    @Test
    fun theReportCountsKeysPerCharacterReached() {
        val r = run()
        val outcomes = listOf(r.type(Sample("nihao", "你好", "daily")), r.type(Sample("nihao", "腻好", "daily")))
        val lines = KeystrokeRun.report(outcomes).lines()
        assertEquals(listOf("group", "n", "reached", "top1", "KSC"), lines[0].split(Regex(" +")))
        assertEquals(listOf("daily", "2", "1", "50.0%", "3.000"), lines[1].split(Regex(" +")))
        assertEquals(listOf("all", "2", "1", "50.0%", "3.000"), lines[2].split(Regex(" +")))
        // nothing reached, nothing typed
        assertEquals(listOf("none", "0", "0", "-", "-"), KeystrokeRun.row("none", emptyList()).split(Regex(" +")))
    }

    @Test
    fun learningGainsOnWhatIsTypedAgain() {
        val sample = Sample("nihao", "尼好", "daily")
        // 尼好 is in the tune half, 你好 in the held-out one
        assertEquals(Halves.TUNE, Halves.of(sample))
        val rows = Learning(data, PinyinSegmenter()).measure(listOf(sample, Sample("nihao", "你好", "daily"))).toMap()
        assertEquals(6, rows.size)
        // picked over 你好, then learned as one word: first the next time
        assertFalse(rows.getValue("tune, typed once").single().first)
        assertTrue(rows.getValue("tune, typed again").single().first)
        assertFalse(rows.getValue("tune, learning nothing").single().first)
        // the other sentence is still read right after it
        assertTrue(rows.getValue("held-out, learning nothing").single().first)
        assertTrue(rows.getValue("held-out, learning").single().first)
        // after the tune half, nihao is 尼好: the same keys, and the user picked it twice
        assertFalse(rows.getValue("held-out, tune learned").single().first)
    }

    @Test
    fun theCommandsReportKscAndLearning() {
        val dataFile = tmp.newFile("pinyin.data").apply { writeBytes(bytes) }
        val set = tmp.newFile("set.tsv").apply { writeText("nihao\t尼好\tdaily\nnihao\t你好\tdaily\n") }
        val out = StringBuilder()
        assertEquals(0, runCli(arrayOf("ksc", dataFile.path, set.path, "--half", Halves.TUNE), out, StringBuilder()))
        assertEquals(listOf("daily", "1", "1", "0.0%", "3.000"), out.lines()[1].split(Regex(" +")))
        out.setLength(0)
        assertEquals(0, runCli(arrayOf("learn", dataFile.path, set.path, "--fuzzy", "all"), out, StringBuilder()))
        assertEquals(7, out.lines().count { it.isNotBlank() })
        assertTrue(out.lines()[3], out.lines()[3].startsWith("tune, typed again") && "100.0%" in out.lines()[3])
    }
}
