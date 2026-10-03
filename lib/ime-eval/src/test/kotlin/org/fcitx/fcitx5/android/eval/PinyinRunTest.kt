/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.rerank.SentencePicker
import org.fcitx.fcitx5.android.engine.rerank.SentenceRefiner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer

class PinyinRunTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val bytes = PinyinDataBuilder()
        .unigram("<unk>", -7f, 0f)
        .unigram("你", -2f, 0f)
        .unigram("你好", -3f, 0f)
        .entry("你", intArrayOf(Syllables.id("ni")))
        .entry("你好", intArrayOf(Syllables.id("ni"), Syllables.id("hao")))
        .build().toByteArray()

    @Test
    fun theEngineTypesEachInputALetterAtATime() {
        val data = tmp.newFile("pinyin.data")
        data.writeBytes(bytes)
        val set = tmp.newFile("set.tsv").apply { writeText("nihao\t你好\tdaily\nnh\t你好\tabbrev\n") }
        val result = tmp.newFile("result.tsv")
        assertEquals(0, runCli(arrayOf("pinyin", data.path, set.path, result.path), StringBuilder(), StringBuilder()))
        val results = result.useLines { RunResultFormat.parse(it) }
        assertEquals(listOf("nihao", "nh"), results.map { it.input })
        assertEquals(listOf(5, 2), results.map { it.keyLatenciesMicros.size })
        assertEquals(listOf("你好", "你好"), results.map { it.candidates.first() })
        // sentences first, then words; 好 alone is not in this dictionary, so hao stays as typed
        assertEquals(listOf("你好", "你hao", "你"), results[0].candidates)

        // p is next to o: a slip read only when asked
        val slip = tmp.newFile("slip.tsv").apply { writeText("nihap\t你好\tkey\n") }
        assertEquals(0, runCli(arrayOf("pinyin", data.path, slip.path, result.path, "--neighbours", "on"), StringBuilder(), StringBuilder()))
        assertEquals("你好", result.useLines { RunResultFormat.parse(it) }.single().candidates.first())
        assertEquals(0, runCli(arrayOf("pinyin", data.path, slip.path, result.path, "--neighbours", "off"), StringBuilder(), StringBuilder()))
        assertEquals("你hap", result.useLines { RunResultFormat.parse(it) }.single().candidates.first())

        val shuangpin = tmp.newFile("shuangpin.tsv").apply { writeText("nihc\t你好\tdaily\n") }
        assertEquals(0, runCli(arrayOf("pinyin", data.path, shuangpin.path, result.path, "--scheme", "xiaohe"), StringBuilder(), StringBuilder()))
        assertEquals("你好", result.useLines { RunResultFormat.parse(it) }.single().candidates.first())
        assertEquals(2, runCli(arrayOf("pinyin", data.path, shuangpin.path, result.path, "--scheme", "nope"), StringBuilder(), StringBuilder()))
    }

    @Test
    fun sentencesAreTheDecodersReadingsWithItsScores() {
        val data = tmp.newFile("pinyin.data").apply { writeBytes(bytes) }
        val set = tmp.newFile("set.tsv").apply { writeText("nihao\t你好\tdaily\nni\t你\tdaily\t你好\n") }
        val out = tmp.newFile("sentences.tsv")
        assertEquals(0, runCli(arrayOf("sentences", data.path, set.path, out.path, "--threads", "2"), StringBuilder(), StringBuilder()))
        val lines = out.readLines().map { it.split('\t') }
        assertEquals(listOf("nihao", "ni"), lines.map { it[0] })
        // text, score, text, score: best first, as the engine ranks them before any model
        val run = PinyinRun(PinyinData.load(ByteBuffer.wrap(bytes)))
        assertEquals(run.sentences("nihao").flatMap { listOf(it.first, it.second.toString()) }, lines[0].drop(1))
        assertEquals("你好", lines[0][1])
        assertTrue(run.sentences("nihao").zipWithNext().all { (a, b) -> a.second >= b.second })
    }

    @Test
    fun aWeightIsOneOrTwoNumbers() {
        val data = tmp.newFile("pinyin.data").apply { writeBytes(bytes) }
        val set = tmp.newFile("set.tsv").apply { writeText("nihao\t你好\tdaily\n") }
        val result = tmp.newFile("result.tsv")
        fun run(weight: String) = runCli(arrayOf("pinyin", data.path, set.path, result.path, "--weight", weight), StringBuilder(), StringBuilder())
        assertEquals(0, run("0.7"))
        assertEquals(0, run("0.4,1"))
        assertEquals(2, run("much"))
        assertEquals(2, run("-1"))
        assertEquals(2, run("1,2,3"))
    }

    /** What each reranker made was asked about: the contexts it saw, and whether it was paused on. */
    private class Recording : SentencePicker, SentenceRefiner {
        val contexts = HashSet<String>()
        var refined = 0

        override fun pick(context: String, readings: List<String>, scores: List<Float>): Int {
            contexts += context
            return 0
        }

        override fun refine(context: String, readings: List<String>, scores: List<Float>, budget: Int): Int? {
            contexts += context
            refined++
            return SentenceRefiner.NONE
        }
    }

    @Test
    fun eachSampleIsWeighedByRerankersOfItsOwn() {
        val pickers = ArrayList<Recording>()
        val refiners = ArrayList<Recording>()
        val run = PinyinRun(
            PinyinData.load(ByteBuffer.wrap(bytes)),
            reranker = { Recording().also { pickers += it } },
            refiner = { Recording().also { refiners += it } },
        )
        val samples = listOf(Sample("nihao", "你好", "daily", "甲"), Sample("ni", "你", "daily", "乙"), Sample("nihao", "你好", "daily", "丙"))
        run.run(samples)
        // whatever a reranker keeps from one input cannot reach the next sample
        assertEquals(pickers.size, refiners.size)
        assertTrue(pickers.all { it.contexts.size == 1 && it.refined == 0 })
        // the timed rounds: a picker for each sample's keys, a refiner for its pause
        assertEquals(listOf("甲", "乙", "丙"), pickers.takeLast(3).map { it.contexts.single() })
        assertEquals(listOf(1, 1, 1), refiners.takeLast(3).map { it.refined })
    }

    @Test
    fun threadsChangeNothingButTheTime() {
        val data = tmp.newFile("pinyin.data").apply { writeBytes(bytes) }
        val set = tmp.newFile("set.tsv").apply { writeText("nihao\t你好\tdaily\tA\nnh\t你好\tabbrev\nni\t你\tdaily\tB\nnihao\t你好\tdaily\n") }
        fun candidates(threads: String): List<Pair<String, List<String>>> {
            val result = File(tmp.root, "result-$threads.tsv")
            assertEquals(0, runCli(arrayOf("pinyin", data.path, set.path, result.path, "--threads", threads), StringBuilder(), StringBuilder()))
            return result.useLines { RunResultFormat.parse(it) }.map { it.input to it.candidates }
        }
        assertEquals(listOf("nihao", "nh", "ni", "nihao"), candidates("1").map { it.first })
        assertEquals(candidates("1"), candidates("3"))
    }
}
