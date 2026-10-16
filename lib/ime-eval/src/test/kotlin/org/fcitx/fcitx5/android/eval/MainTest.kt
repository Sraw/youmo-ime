/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.data.WordLayers
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.rerank.SentencePicker
import org.fcitx.fcitx5.android.engine.rerank.SentenceRefiner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

class MainTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun exit(vararg args: String): Int = runCli(arrayOf(*args), StringBuilder(), StringBuilder())

    private fun syl(vararg s: String) = s.map { Syllables.id(it) }.toIntArray()

    /** The words KeystrokeRunTest types; [layered], with 毫 in a layer of its own. */
    private fun dataFile(layered: Boolean = false): File {
        val bytes = PinyinDataBuilder()
            .layers(if (layered) listOf(WordLayers.BASE, "places") else listOf(WordLayers.BASE))
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
            // no reading: only ever a context
            .unigram("他", -2f, 0f)
            .bigram("他", "号", -0.5f, 0f)
            .entry("你", syl("ni"))
            .entry("拟", syl("ni"))
            .entry("好", syl("hao"))
            .entry("你好", syl("ni", "hao"))
            .entry("吗", syl("ma"))
            .entry("泥", syl("ni"))
            .entry("尼", syl("ni"))
            .entry("号", syl("hao"))
            .entry("毫", syl("hao"), layer = if (layered) 1 else 0)
            .build().toByteArray()
        return tmp.newFile("pinyin.data").apply { writeBytes(bytes) }
    }

    /**
     * What release-report.sh's `cell` reads: the first word in the column named [name], in the
     * row whose first cell is [row], of the table whose header names it.
     */
    private fun cell(output: String, name: String, row: String): String? {
        var column = -1
        for (line in output.lines()) {
            val cells = line.split(Regex(" {2,}"))
            if (cells[0] == "group") {
                column = cells.indexOf(name)
            } else if (column > 0 && cells[0] == row) {
                return cells[column].split(' ')[0]
            }
        }
        return null
    }

    /**
     * Stands in for the ime-eval launcher, noting each call in `eval.calls` beside it: `t9` and
     * `pinyin` write empty files, `score` and `learn` print [scores] and [learning].
     */
    private fun launcher(scores: String, learning: String): File {
        val dir = tmp.newFolder()
        File(dir, "score").writeText(scores)
        File(dir, "learn").writeText(learning)
        return File(dir, "eval").apply {
            writeText(
                listOf(
                    "#!/bin/sh",
                    "echo \"\$*\" >> \"\$0.calls\"",
                    "case \"\$1\" in",
                    "  t9) : > \"\$3\" ;;",
                    "  pinyin) : > \"\$4\" ;;",
                    "  score | learn) cat \"\$(dirname \"\$0\")/\$1\" ;;",
                    "  *) exit 3 ;;",
                    "esac",
                ).joinToString("\n", postfix = "\n"),
            )
            setExecutable(true)
        }
    }

    /** release-report.sh run over [launcher], against [base] if given: its exit status, what it printed, and the dir it wrote to. */
    private fun releaseReport(launcher: File, base: File? = null): Triple<Int, String, File> {
        val out = tmp.newFolder()
        val printed = tmp.newFile()
        val builder = ProcessBuilder("/bin/bash", "release-report.sh", out.path).redirectErrorStream(true).redirectOutput(printed)
        builder.environment().apply {
            // the report reads these too: not as the shell that started the tests set them
            listOf("BASE", "PACK", "SMALL").forEach { remove(it) }
            put("EVAL", launcher.path)
            put("DATA", "pinyin.data")
            put("MODELS", "models")
            if (base != null) put("BASE", base.path)
        }
        val process = builder.start()
        process.outputStream.close()
        // to a file, not a pipe read to its end, which would wait out a script that hangs
        val ended = process.waitFor(1, TimeUnit.MINUTES)
        if (!ended) process.destroyForcibly()
        assertTrue(printed.readText(), ended)
        return Triple(process.exitValue(), printed.readText(), out)
    }

    @Test
    fun optionsAreCheckedBeforeAnythingRuns() {
        // every one a usage error, caught before a file is opened
        val usageErrors = listOf(
            arrayOf("pinyin", "d", "s", "r", "--half"), // no value
            arrayOf("pinyin", "d", "s", "r", "--half", "tune", "--half", "tune"), // twice
            arrayOf("pinyin", "d", "s", "r", "--half", "most"),
            arrayOf("pinyin", "d", "s", "r", "--fuzzy", "l_n,x_y"),
            arrayOf("pinyin", "d", "s", "r", "--fuzzy", ""),
            arrayOf("pinyin", "d", "s", "r", "--scheme", "nope"),
            arrayOf("pinyin", "d", "s", "r", "--color", "red"),
            arrayOf("pinyin", "d", "s", "r", "--neighbours", "maybe"),
            arrayOf("pinyin", "d", "s", "r", "--scheme", "ms", "--neighbours", "on"), // 双拼 reads none
            arrayOf("pinyin", "d", "s", "r", "--scheme", "t9", "--neighbours", "on"), // nor the nine keys
            arrayOf("pinyin", "d", "s", "r", "--threads", "0"),
            arrayOf("pinyin", "d", "s", "r", "--beam", "x"),
            arrayOf("pinyin", "d", "s", "r", "--beam", "0"),
            arrayOf("pinyin", "d", "s", "r", "--sentences", "-1"),
            arrayOf("pinyin", "d", "s", "r", "--limit", "2,"),
            arrayOf("pinyin", "d", "s", "r", "--limit", "1,2,3"), // two models, two limits
            arrayOf("pinyin", "d", "s", "r", "--limit", "0"),
            arrayOf("sentences", "d", "s", "o", "--beam", "many"),
            arrayOf("ksc", "d", "s", "--threads", "many"),
            arrayOf("ksc", "d", "s", "--neighbours", "on"), // not ksc's
            arrayOf("predict-learn", "d", "s", "--user-weight", "much"),
            // a weight, as --weight's: not negative, nor endless
            arrayOf("predict-learn", "d", "s", "--user-weight", "-1"),
            arrayOf("predict-learn", "d", "s", "--user-weight", "NaN"),
            arrayOf("predict-learn", "d", "s", "--user-weight", "Infinity"),
            arrayOf("lm", "m", "c"), // nothing to score
            arrayOf("score", "s", "r", "--scheme", "xiaohe"), // not score's
            arrayOf("shuangpin", "xiaohe", "s", "o", "--half", "tune"),
            arrayOf("shuangpin", "nope", "s", "o"),
            arrayOf("slips", "s"),
            arrayOf("tune", "d", "s"),
            arrayOf("ksc"),
            arrayOf("table", "d", "s", "--preset", "dvorak"),
            arrayOf("table", "d", "s", "--half", "tune"),
            // a preset in the table's order ranks no 重码 by the text before: every row would be the same
            arrayOf("table", "d", "s", "--pinyin", "p"),
            arrayOf("table", "d", "s", "--preset", "cangjie", "--pinyin", "p"),
            // the large model without the small, which the 性能档位's high never has
            arrayOf("table", "d", "s", "--preset", "wubi", "--pinyin", "p", "--refine", "m"),
            arrayOf("handwriting", "d", "r", "--refine", "m"),
            // a model option without what reads it: the 重码 are weighed only with --pinyin, model weights only with a model
            arrayOf("table", "d", "s", "--preset", "wubi", "--rerank", "m"),
            arrayOf("table", "d", "s", "--preset", "wubi", "--rerank", "m", "--refine", "m"),
            arrayOf("handwriting", "d", "r", "--model-weights", "1"),
            // the models read the sentences at one ink weight
            arrayOf("handwriting", "d", "r", "--rerank", "m", "--weights", "1,2"),
            arrayOf("libime", "user", "f"),
            arrayOf("libime", "table", "f", "--preset", "plain"),
            arrayOf(),
        )
        for (args in usageErrors) assertEquals(args.joinToString(" "), 2, exit(*args))
    }

    @Test
    fun theUsageNamesEveryCommand() {
        val err = StringBuilder()
        assertEquals(2, runCli(arrayOf("help"), StringBuilder(), err))
        assertEquals(USAGE, err.trim())
        assertEquals(COMMANDS.size, COMMANDS.map { it.name }.toSet().size)
        val lines = USAGE.lines().map { it.removePrefix("usage:").trimStart() }
        for (command in COMMANDS) {
            // each with the options it takes, and no other
            val line = lines.single { it.startsWith("${command.name} ") }
            assertEquals(command.name, command.options, Regex("--([a-z-]+)").findAll(line).map { it.groupValues[1] }.toSet())
        }
    }

    @Test
    fun scoreTakesAHalf() {
        val set = tmp.newFile("set.tsv").apply { writeText("zhongguo\t中国\tdaily\nxi'an\t西安\tdaily\n") }
        val result = tmp.newFile("result.tsv").apply { writeText("zhongguo\t1\t中国\nxi'an\t1\t西安\n") }
        fun samples(vararg half: String): String {
            val out = StringBuilder()
            assertEquals(0, runCli(arrayOf("score", set.path, result.path, *half), out, StringBuilder()))
            return out.lines().first { it.startsWith(Metrics.ALL) }.split(Regex(" +"))[1]
        }
        assertEquals("2", samples())
        // the two texts fall in different halves
        assertEquals(setOf("1"), Halves.NAMES.map { samples("--half", it) }.toSet())
    }

    @Test
    fun theReleaseGateFindsItsNumbersByColumnName() {
        val set = tmp.newFile("set.tsv").apply { writeText("zhongguo\t中国\tdaily\nxi'an\t西安\tdaily\n") }
        val result = tmp.newFile("result.tsv").apply { writeText("zhongguo\t1\t中国\nxi'an\t1\t先\n") }
        val baseline = tmp.newFile("baseline.tsv").apply { writeText("zhongguo\t1\t种过\nxi'an\t1\t先\n") }
        val scores = StringBuilder()
        assertEquals(0, runCli(arrayOf("score", set.path, result.path, baseline.path), scores, StringBuilder()))
        // top1 with its delta in one cell, of which the gate reads the rate
        assertEquals("50.0%", cell(scores.toString(), "top1", Metrics.ALL))
        assertEquals("1", cell(scores.toString(), "won", Metrics.ALL))
        assertEquals("0", cell(scores.toString(), "lost", Metrics.ALL))
        assertEquals(Flips(Metrics.ALL, 2, 1, 0).p, cell(scores.toString(), "p", Metrics.ALL)!!.toDouble(), 0.0)

        // the user layer's rows, as the gate runs it; with layers, rows that start the same follow
        val chat = tmp.newFile("chat.tsv").apply { writeText("nihao\t尼好\tdaily\nnihao\t你好\tdaily\n") }
        val learning = StringBuilder()
        assertEquals(0, runCli(arrayOf("learn", dataFile(layered = true).path, chat.path, "--neighbours", "on"), learning, StringBuilder()))
        assertTrue(learning.toString(), learning.lines().any { it.startsWith("held-out, tune learned, prior learned") })
        // where the prior ended up is a note, not a row of zero counts
        assertTrue(learning.toString(), learning.lines().single { it.startsWith("prior after tune: ") }.matches(Regex("prior after tune: \\S+; after held-out alone: \\S+")))
        assertEquals("100.0%", cell(learning.toString(), "top1", "tune, typed again"))
        assertEquals("100.0%", cell(learning.toString(), "top1", "held-out, learning nothing"))
        assertEquals("0.0%", cell(learning.toString(), "top1", "held-out, tune learned"))
    }

    /**
     * release-report.sh itself, over what `score` and `learn` print: the nine-key sets typed on the
     * nine keys first; against a BASE it wrote itself, a set reported only that BASE has no results
     * for reported without the comparison, and a gate set without them, or with them read with
     * other options, failing it before any set is run, every such set named; a p printed in E
     * notation read as a number; and its exits: 1 when the gate fails, 2 when it cannot read a number.
     */
    @Test
    fun theReleaseReportGatesOnWhatTheCommandsPrint() {
        assumeTrue("needs bash", File("/bin/bash").canExecute())
        // a dozen samples BASE had first and this run lost, none won: a p Double prints in E notation
        val inputs = (1..12).map { "ni$it" }
        fun results(first: String) = tmp.newFile().apply { writeText(inputs.joinToString("") { "$it\t1\t$first\n" }) }
        val set = tmp.newFile().apply { writeText(inputs.joinToString("") { "$it\t你\tdaily\n" }) }
        val scores = StringBuilder()
        assertEquals(0, runCli(arrayOf("score", set.path, results("泥").path, results("你").path), scores, StringBuilder()))
        val p = Flips(Metrics.ALL, inputs.size, 0, inputs.size).p.toString()
        assertTrue(p, 'E' in p)
        val typed = listOf(KeystrokeRun.Outcome(Sample("nihao", "你好", "daily"), 5, reached = true, first = true))
        fun learning(typedAgain: List<KeystrokeRun.Outcome>) = listOf(
            KeystrokeRun.HEADER,
            KeystrokeRun.row("tune, typed again", typedAgain),
            KeystrokeRun.row("held-out, learning nothing", typed),
            KeystrokeRun.row("held-out, tune learned", typed),
        ).joinToString("\n", postfix = "\n")

        val eval = launcher(scores.toString(), learning(typed))
        val (ran, printed, written) = releaseReport(eval)
        assertEquals(printed, 0, ran)
        val calls = File(eval.path + ".calls")
        val made = calls.readLines()
        assertTrue(made.joinToString("\n"), made.any { it.startsWith("t9 ") && it.endsWith("/t9-chat.set.tsv") })
        assertTrue(made.joinToString("\n"), made.any { it.startsWith("pinyin ") && "/t9-chat.set.tsv " in it && "--scheme t9-strict" in it })

        // that run's dir as BASE, as the script wrote it: each set's results, and the options it read them with
        val base = written
        // a set reported only goes without, where BASE has no results for it
        File(base, "context.tsv").delete()
        val (gated, report) = releaseReport(eval, base)
        assertEquals(report, 1, gated)
        assertTrue(report, "web: top1 0.0%, against BASE won 0 lost 12 p $p FAIL" in report)
        assertTrue(report, "context: top1 0.0%, no results in BASE to compare to" in report)
        // a gate set BASE has no results for, or read otherwise (a BASE from before neighbouring keys), cannot
        // pass: found before any set is run, every one named
        File(base, "chat.tsv").delete()
        val before = calls.readLines()
        val (missing, toldMissing) = releaseReport(eval, base)
        assertEquals(toldMissing, 2, missing)
        assertTrue(toldMissing, "chat: a gate set, no results in BASE to compare to" in toldMissing)
        assertEquals(toldMissing, before, calls.readLines())
        File(base, "chat.tsv").writeText("")
        File(base, "web.options").delete()
        val (otherwise, toldOtherwise) = releaseReport(eval, base)
        assertEquals(toldOtherwise, 2, otherwise)
        assertTrue(toldOtherwise, "web: a gate set, BASE read it with other options than --neighbours on" in toldOtherwise)
        File(base, "new.tsv").delete()
        val (both, toldBoth) = releaseReport(eval, base)
        assertEquals(toldBoth, 2, both)
        assertTrue(toldBoth, "web: a gate set, BASE read it with other options than --neighbours on\nnew: a gate set, no results in BASE to compare to" in toldBoth)
        assertEquals(toldBoth, before, calls.readLines())

        // a half with no samples: no top1 to read
        val (unread, said) = releaseReport(launcher(scores.toString(), learning(emptyList())))
        assertEquals(said, 2, unread)
        assertTrue(said, "learn typed again: no number in its output" in said)
    }

    @Test
    fun fullPinyinWithoutLatinWordsReadsNeighbouringKeys() {
        // p is next to o: 你好 comes first only if the slip is read
        val set = tmp.newFile("set.tsv").apply { writeText("nihap\t你好\tdaily\n") }
        val data = dataFile()
        val result = File(tmp.root, "result.tsv")
        fun first(neighbours: String): String? {
            assertEquals(0, exit("pinyin", data.path, set.path, result.path, "--scheme", "no-latin", "--neighbours", neighbours, "--threads", "1"))
            return result.useLines { RunResultFormat.parse(it) }.single().candidates.firstOrNull()
        }
        assertEquals("你好", first("on"))
        assertNotEquals("你好", first("off"))
    }

    @Test
    fun kscIsTheSameHoweverTheSamplesAreDealt() {
        // the second after a context, the fourth after none, as after the second's commit
        val set = tmp.newFile("set.tsv").apply { writeText("nihao\t你好\tdaily\nhao\t号\tpair\t他\nnihao\t尼好\tdaily\nhao\t号\tpair\n") }
        val data = dataFile()
        fun report(threads: String): String {
            val out = StringBuilder()
            assertEquals(0, runCli(arrayOf("ksc", data.path, set.path, "--threads", threads), out, StringBuilder()))
            return out.toString()
        }
        // one session for all four, and one for each
        val oneThread = report("1")
        assertEquals(report("4"), oneThread)
        assertEquals(listOf("pair", "2", "2", "50.0%"), oneThread.lines().first { it.startsWith("pair") }.split(Regex(" +")).take(4))
    }

    /** What each reranker made was asked: the contexts it read, and the slices of a pause it was given. */
    private class Recording(override val slices: Int = SentenceRefiner.SLICES) : SentencePicker, SentenceRefiner {
        val contexts = HashSet<String>()
        var refined = 0

        override fun pick(context: String, readings: List<String>, scores: List<Float>): Int {
            contexts += context
            return 0
        }

        // never done: the session stops asking after its slices
        override fun refine(context: String, readings: List<String>, scores: List<Float>, budget: Int): Int? {
            contexts += context
            refined++
            return null
        }
    }

    @Test
    fun kscTypesEachSampleWithRerankersOfItsOwnAfterItsOwnContext() {
        val pickers = ArrayList<Recording>()
        val refiners = ArrayList<Recording>()
        val typist = Typist(
            PinyinData.load(ByteBuffer.wrap(dataFile().readBytes())), PinyinSegmenter(),
            reranker = { Recording().also { pickers += it } },
            refiner = { Recording(slices = 2).also { refiners += it } },
        )
        typist.type(Sample("nihao", "你好", "daily"))
        // a context that ends with what the sample before committed
        typist.type(Sample("hao", "号", "pair", "他你好"))
        // read from the sample's own context, not kept from that commit, by rerankers that read nothing before
        assertEquals(listOf(setOf(""), setOf("他你好")), pickers.map { it.contexts })
        assertEquals(listOf(setOf(""), setOf("他你好")), refiners.map { it.contexts })
        // a pause as long as the sample's refiner says, not the default's
        assertEquals(listOf(2, 2), refiners.map { it.refined })
    }

    @Test
    fun aHalfWithNoSamplesIsARowOfNoneAndTheNoteStaysANote() {
        // one sample: one half has none
        val set = tmp.newFile("set.tsv").apply { writeText("nihao\t你好\tdaily\n") }
        val out = StringBuilder()
        assertEquals(0, runCli(arrayOf("learn", dataFile(layered = true).path, set.path), out, StringBuilder()))
        val text = out.toString()
        val sizes = listOf("tune, typed again", "held-out, tune learned, prior learned").map { cell(text, "n", it) }
        assertEquals(text, setOf("0", "1"), sizes.toSet())
        assertTrue(text, text.lines().single { it.startsWith("prior after tune: ") }.matches(Regex("prior after tune: \\S+; after held-out alone: \\S+")))
    }

    @Test
    fun libimesHistoryAsText() {
        // libime's HistoryBigram, version 1: two pools, a sentence of a word in the first
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply {
            writeInt(0x000fc315)
            writeInt(1)
            writeInt(1)
            writeInt(1)
            val word = "你好".toByteArray()
            writeInt(word.size)
            write(word)
            writeInt(0)
        }
        val file = tmp.newFile("user.history").apply { writeBytes(bytes.toByteArray()) }
        val out = StringBuilder()
        assertEquals(0, runCli(arrayOf("libime", "history", file.path), out, StringBuilder()))
        assertEquals("你好\n", out.toString())
        // not libime's, or not there: said, not thrown
        val err = StringBuilder()
        assertEquals(1, runCli(arrayOf("libime", "pinyin", file.path), StringBuilder(), err))
        assertEquals(1, runCli(arrayOf("libime", "table", file.path + ".gone"), StringBuilder(), err))
        assertEquals(2, err.lines().count { it.startsWith(file.path) })
    }
}
