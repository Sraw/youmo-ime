/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.data.SourceException
import org.fcitx.fcitx5.android.engine.data.WordLayers
import org.fcitx.fcitx5.android.engine.host.EngineSettings
import org.fcitx.fcitx5.android.engine.lattice.CoarseWords
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.rerank.SentencePicker
import org.fcitx.fcitx5.android.engine.rerank.SentenceRefiner
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.PinyinSession
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.fcitx.fcitx5.android.engine.user.WordPack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
    fun aWordPackIsTypedAsTheUsersAtTheScoreItSays() {
        val data = tmp.newFile("pinyin.data").apply { writeBytes(bytes) }
        val set = tmp.newFile("set.tsv").apply { writeText("ni\t泥\tdaily\n") }
        val result = tmp.newFile("result.tsv")
        fun first(vararg more: String): String {
            assertEquals(0, runCli(arrayOf("pinyin", data.path, set.path, result.path, *more), StringBuilder(), StringBuilder()))
            return result.useLines { RunResultFormat.parse(it) }.single().candidates.first()
        }
        assertEquals("你", first())
        // 泥, which the data lacks, scored above 你 (-2)
        val pack = tmp.newFile("new.words").apply { writeText("# youmo words 1\n# layer: test\n泥\tni\t-1.5\n") }
        assertEquals("泥", first("--pack", pack.path))
        val worse = tmp.newFile("old.words").apply { writeText("# youmo words 1\n泥\tni\t-2.5\n") }
        assertEquals("你", first("--pack", worse.path))
        // the pack's layer raised above 你 by a fixed prior, by its name
        val tier = tmp.newFile("tier.words").apply { writeText("# youmo words 1\n# layer: tier\n泥\tni\t-2.5\n") }
        assertEquals("泥", first("--pack", tier.path, "--layers", "tier=1"))
        // a bad pack is an error with its line, as a bad set is
        val broken = tmp.newFile("broken.words").apply { writeText("# youmo words 1\n泥\tni\t0.5\n") }
        val e = assertThrows(SourceException::class.java) {
            runCli(arrayOf("pinyin", data.path, set.path, result.path, "--pack", broken.path), StringBuilder(), StringBuilder())
        }
        assertEquals("${broken.path}:2: expected \"word pin'yin log10P\"", e.message)
    }

    @Test
    fun aLayerOfTheDictionaryIsLiftedWithNoPack() {
        // 毫 in a layer of its own, below 好 by the model
        val layered = PinyinDataBuilder()
            .layers(listOf(WordLayers.BASE, "places"))
            .unigram("<unk>", -7f, 0f)
            .unigram("好", -2.5f, 0f)
            .unigram("毫", -3.5f, 0f)
            .entry("好", intArrayOf(Syllables.id("hao")))
            .entry("毫", intArrayOf(Syllables.id("hao")), layer = 1)
            .build().toByteArray()
        val data = tmp.newFile("pinyin.data").apply { writeBytes(layered) }
        val set = tmp.newFile("set.tsv").apply { writeText("hao\t毫\tplaces\n") }
        val result = tmp.newFile("result.tsv")
        fun first(vararg more: String): String {
            assertEquals(0, runCli(arrayOf("pinyin", data.path, set.path, result.path, *more), StringBuilder(), StringBuilder()))
            return result.useLines { RunResultFormat.parse(it) }.single().candidates.first()
        }
        assertEquals("好", first())
        // with no pack no word is a pack's, so each is of its dictionary layer, not all of the base
        assertEquals("毫", first("--layers", "places=2"))
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
        // the session's context: text that ends in no word (甲) is none to the rerankers either
        val samples = listOf(Sample("nihao", "你好", "daily", "你"), Sample("nihao", "你好", "daily", "甲"), Sample("nihao", "你好", "daily", "你好"))
        run.run(samples)
        // whatever a reranker keeps from one input cannot reach the next sample
        assertEquals(pickers.size, refiners.size)
        assertTrue(pickers.all { it.contexts.size == 1 && it.refined == 0 })
        // the timed rounds: a picker for each sample's keys, a refiner for its pause
        assertEquals(listOf("你", "", "你好"), pickers.takeLast(3).map { it.contexts.single() })
        assertEquals(listOf(1, 1, 1), refiners.takeLast(3).map { it.refined })
    }

    @Test
    fun thePickersPickIsShownFirstAndTheRefinersOnceTheUserPauses() {
        val data = PinyinData.load(ByteBuffer.wrap(bytes))
        fun run(pick: Int, refine: Int?) = PinyinRun(
            data,
            reranker = { SentencePicker { _, readings, _ -> if (readings.size > 1) pick else 0 } },
            refiner = { SentenceRefiner { _, _, _, _ -> refine } },
        )
        // nihao reads 你好, then 你hao
        val decoders = listOf("你好", "你hao", "你")
        val second = listOf("你hao", "你好", "你")
        assertEquals(decoders, run(0, SentenceRefiner.NONE).candidates("nihao", paused = true))
        assertEquals(second, run(1, SentenceRefiner.NONE).candidates("nihao"))
        // nothing to say: the picker's order stands
        assertEquals(second, run(1, SentenceRefiner.NONE).candidates("nihao", paused = true))
        // the refiner's pick over the picker's, the decoder's first too, but not before the pause
        assertEquals(second, run(0, 1).candidates("nihao", paused = true))
        assertEquals(decoders, run(1, 0).candidates("nihao", paused = true))
        assertEquals(decoders, run(0, 1).candidates("nihao"))
    }

    @Test
    fun aPauseIsWeighedWhereAndForAsLongAsTheSessionWeighsIt() {
        val data = PinyinData.load(ByteBuffer.wrap(bytes))
        val budgets = ArrayList<Int>()
        val late = PinyinRun(data, refiner = { SentenceRefiner { _, readings, _, budget -> budgets += budget; if (budgets.size < 3) null else readings.lastIndex } })
        // slices that pick nothing yet, then the one that does, each of the session's budget
        assertEquals("你hao", late.candidates("nihao", paused = true).first())
        assertEquals(List(3) { PinyinSession.REFINE_BUDGET }, budgets)
        var asked = 0
        val never = PinyinRun(data, refiner = { SentenceRefiner { _, _, _, _ -> asked++; null } })
        // given up on after its slices, as the session does: the order shown stands
        assertEquals("你好", never.candidates("nihao", paused = true).first())
        assertEquals(SentenceRefiner.SLICES, asked)
        // a syllable still being typed (h), and a single reading, are not weighed again
        asked = 0
        assertEquals(2, never.sentences("nih").size)
        never.candidates("nih", paused = true)
        assertEquals(1, never.sentences("ni").size)
        never.candidates("ni", paused = true)
        assertEquals(0, asked)
    }

    @Test
    fun aPauseIsWeighedForTheInputsTheSessionWeighs() {
        val data = PinyinData.load(ByteBuffer.wrap(bytes))
        // a whole syllable last or not (h, n), one reading or more (ni): PinyinRun's copy of the session's gate
        val inputs = listOf("nihao", "nihaoni", "nih", "nihaon", "ni")
        val sessionWeighs = inputs.map { input ->
            val session = PinyinSession(data, PinyinSegmenter(), refiner = { _, _, _, _ -> SentenceRefiner.NONE })
            input.map { session.apply(Action.Key(it)) }.last().refines
        }
        val runWeighs = inputs.map { input ->
            var asked = false
            PinyinRun(data, refiner = { SentenceRefiner { _, _, _, _ -> asked = true; SentenceRefiner.NONE } }).candidates(input, paused = true)
            asked
        }
        assertEquals(sessionWeighs, runWeighs)
        // both answers among them, or the two could agree by never weighing at all
        assertTrue(true in sessionWeighs && false in sessionWeighs)
    }

    @Test
    fun aCandidateWithACoarseWordIsHeldPastTheFirstPageAsInTheSession() {
        // CoarseCandidatesTest's: 小穴 likelier than 小学 even lowered, and 小 then 穴 spell it too
        val syl = { s: String -> s.split(' ').map { Syllables.id(it) }.toIntArray() }
        val data = PinyinData.load(
            ByteBuffer.wrap(
                PinyinDataBuilder()
                    .unigram("<unk>", -7f, 0f)
                    .unigram("小穴", -1f, 0f)
                    .unigram("小学", -3f, 0f)
                    .unigram("小雪", -3.5f, 0f)
                    .unigram("小", -2f, 0f)
                    .unigram("学", -3f, 0f)
                    .unigram("穴", -1f, 0f)
                    .entry("小穴", syl("xiao xue"))
                    .entry("小学", syl("xiao xue"))
                    .entry("小雪", syl("xiao xue"))
                    .entry("小", syl("xiao"))
                    .entry("学", syl("xue"))
                    .entry("穴", syl("xue"))
                    .build().toByteArray(),
            ),
        )
        val session = PinyinSession(data, PinyinSegmenter(), prediction = false)
        val page = "xiaoxue".map { session.apply(Action.Key(it)) }.last().candidates
        val shown = PinyinRun(data).candidates("xiaoxue")
        assertEquals(page, shown.take(page.size))
        // held, not left out
        assertTrue(shown.toString(), "小穴" in shown && shown.first() != "小穴")
    }

    /**
     * Only those the first page would show wait past it, as in the session: one further down keeps
     * its place, where moving every one after the first page would put 骚穴 right after 小穴. With
     * the user's words too: a coarse word of a pack, which the data lacks, waits as well.
     */
    @Test
    fun aCandidateWithACoarseWordFurtherDownKeepsItsPlaceAsInTheSession() {
        // ten words of xiao xue a quarter apart from a point a step above the penalty, so that, lowered
        // by it, 小穴 falls among the first page's and 骚穴 past it, the penalty retuned or not
        val reading = intArrayOf(Syllables.id("xiao"), Syllables.id("xue"))
        val words = List(10) { (0x4E00 + it).toChar().toString().repeat(2) }
        val data = PinyinData.load(
            ByteBuffer.wrap(
                PinyinDataBuilder().unigram("<unk>", -7f, 0f)
                    .apply { words.forEachIndexed { i, w -> unigram(w, CoarseWords.PENALTY + 1f - i / 4f, 0f).entry(w, reading) } }
                    .unigram("小穴", -0.1f, 0f).entry("小穴", reading)
                    .unigram("骚穴", -0.85f, 0f).entry("骚穴", reading)
                    .build().toByteArray(),
            ),
        )
        val page = EngineSettings.DEFAULT_PAGE_SIZE
        fun sessionShows(user: UserModel?, count: Int): List<String> {
            val session = PinyinSession(data, PinyinSegmenter(), pageSize = page, user = user, prediction = false)
            "xiaoxue".forEach { session.apply(Action.Key(it)) }
            return session.candidates(0, count).map { it.text }
        }
        val shown = PinyinRun(data).candidates("xiaoxue")
        assertEquals(sessionShows(null, shown.size), shown)
        assertEquals(page, shown.indexOf("小穴"))
        // a word of the second page still before it
        assertEquals(page + 2, shown.indexOf("骚穴"))

        val pack = WordPack("test", listOf(WordPack.Word(UserModel.Entry("嫩穴", reading), -0.2f)))
        // as PinyinRun lists a pack given no layers
        val user = UserModel(data.dictionary, data.vocabulary).also { u -> pack.words.forEach { u.list(it.entry, it.score, 1, data.model.vocabularySize) } }
        val packed = PinyinRun(data, pack = pack).candidates("xiaoxue")
        assertEquals(sessionShows(user, packed.size), packed)
        assertEquals(listOf("小穴", "嫩穴"), packed.subList(page, page + 2))
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
