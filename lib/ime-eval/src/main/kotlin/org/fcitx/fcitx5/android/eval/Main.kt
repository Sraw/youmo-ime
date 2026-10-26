/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.data.CodeTable
import org.fcitx.fcitx5.android.engine.data.DataFormatException
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.english.EnglishData
import org.fcitx.fcitx5.android.engine.english.EnglishSession
import org.fcitx.fcitx5.android.engine.glide.GlideLayout
import org.fcitx.fcitx5.android.engine.glide.GlidePath
import org.fcitx.fcitx5.android.engine.glide.PinyinGlide
import org.fcitx.fcitx5.android.engine.glide.TableGlide
import org.fcitx.fcitx5.android.engine.handwriting.HandwritingDecoder
import org.fcitx.fcitx5.android.engine.host.EngineSettings
import org.fcitx.fcitx5.android.engine.lattice.Penalties
import org.fcitx.fcitx5.android.engine.lattice.PinyinDecoder
import org.fcitx.fcitx5.android.engine.lattice.Predictor
import org.fcitx.fcitx5.android.engine.lattice.TextWords
import org.fcitx.fcitx5.android.engine.libime.LibimeFiles
import org.fcitx.fcitx5.android.engine.pinyin.Fuzzy
import org.fcitx.fcitx5.android.engine.pinyin.GroupedShuangpinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.LatinWords
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.Segmenter
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.T9Segmenter
import org.fcitx.fcitx5.android.engine.rerank.Reranker
import org.fcitx.fcitx5.android.engine.rerank.SentenceModel
import org.fcitx.fcitx5.android.engine.rerank.SentencePicker
import org.fcitx.fcitx5.android.engine.rerank.SentenceRefiner
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.PinyinSession
import org.fcitx.fcitx5.android.engine.table.TableDictionary
import org.fcitx.fcitx5.android.engine.table.TableLanguage
import org.fcitx.fcitx5.android.engine.user.DictionaryCompiler
import org.fcitx.fcitx5.android.engine.user.DictionaryFile
import org.fcitx.fcitx5.android.engine.user.LearningUse
import org.fcitx.fcitx5.android.engine.user.WordPack
import org.fcitx.fcitx5.android.eval.parity.PARITY_COMMANDS
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.util.Locale
import java.util.Random
import kotlin.system.exitProcess

/** The options of [learningUse]: before [COMMANDS], which take them. */
private val USE_OPTIONS = arrayOf("user-counts", "short-guard")

/** Every command, in the order [USAGE] lists them: it is made from them. */
internal val COMMANDS = listOf(
    Command("score", "<set.tsv> <result.tsv> [<baseline-result.tsv>] [--half <half>]", 2..3, "half") { args, out, _ ->
        val (setPath, resultPath) = args.operands
        val baselinePath = args.operands.getOrNull(2)
        score(setPath, resultPath, baselinePath, args.options["half"], out)
    },
    Command(
        "pinyin",
        "<pinyin.data> <set.tsv> <result.tsv> [--scheme <scheme>] [--fuzzy all|<pair>,...] [--half <half>] [--neighbours on|off] [--rerank <model.safetensors>] [--refine <model.safetensors>] [--weight <rerank>[,<refine>]] [--limit <rerank>[,<refine>]] [--beam <n>] [--sentences <n>] [--layers <name>=<log10>,...] [--pack <file.words>] [--merged <dictionary.txt> [--stale]] [--threads <n>] [--taps <hand>:<α>[:<seed>]]",
        3..3, "scheme", "fuzzy", "half", "neighbours", "rerank", "refine", "weight", "limit", "beam", "sentences", "layers", "pack", "merged", "stale", "threads", "taps",
    ) { args, _, _ ->
        val (dataPath, setPath, resultPath) = args.operands
        runPinyin(dataPath, setPath, resultPath, RunOptions(args.options), Models(args.options))
    },
    Command(
        "predict", "<pinyin.data> <predict.tsv> [--offers <out.tsv>] [--threads <n>] [--rerank <model.safetensors> [--weight <w>]]",
        2..2, "threads", "offers", "rerank", "weight",
    ) { args, out, _ ->
        val (dataPath, setPath) = args.operands
        // checked already: see optionsValid
        val weight = args.options["weight"]?.let { weights(it)?.first } ?: Reranker.WEIGHT
        predict(dataPath, setPath, RunOptions(args.options).threads, args.options["offers"], args.options["rerank"], weight, out)
    },
    Command(
        "english", "<english.data> <sentences.txt> [--keys nine] [--noise <rate>] [--max-correction <cost>] [--misses <out.tsv>] [--threads <n>]",
        2..2, "keys", "noise", "max-correction", "misses", "threads",
    ) { args, out, _ ->
        if (args.options["keys"].let { it != null && it != "nine" }) return@Command null
        val (dataPath, setPath) = args.operands
        val sentences = File(setPath).readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        // checked already: see optionsValid
        val nine = args.options["keys"] == "nine"
        val run = EnglishRun(EnglishData.load(mapFile(dataPath)), maxCorrection = args.options["max-correction"]?.toFloat() ?: EnglishSession.MAX_CORRECTION, nineKeys = nine)
        val noise = args.options["noise"]?.toDouble() ?: ENGLISH_NOISE
        val threads = RunOptions(args.options).threads
        if (nine) {
            // each key meant: no slip to measure; a word not offered at all is spelled by its letters, pinned by long press
            val r = run.run(sentences, 0.0, ENGLISH_SEED, threads)
            out.appendLine(EnglishRun.Result.NINE_HEADER)
            out.appendLine(r.nineRow())
            args.options["misses"]?.let { path -> File(path).printWriter().use { w -> r.misses.forEach(w::println) } }
            return@Command 0
        }
        out.appendLine(EnglishRun.Result.HEADER)
        val clean = run.run(sentences, 0.0, ENGLISH_SEED, threads)
        val slips = run.run(sentences, noise, ENGLISH_SEED, threads)
        out.appendLine(clean.row("clean"))
        out.appendLine(slips.row("slips"))
        args.options["misses"]?.let { path ->
            File(path).printWriter().use { w -> (clean.misses.map { "clean\t$it" } + slips.misses.map { "slips\t$it" }).forEach(w::println) }
        }
        0
    },
    Command(
        "english-glide", "<english.data> <sentences.txt> [--wobble <key widths>] [--shape <w>] [--proximity <w>] [--edges <w>] [--corner <w>] [--turn <cos>] [--near <r>] [--ends <r>] [--misses <out.tsv>] [--threads <n>]",
        2..2, "wobble", "shape", "proximity", "edges", "corner", "turn", "near", "ends", "misses", "threads",
    ) { args, out, _ ->
        val (dataPath, setPath) = args.operands
        val sentences = File(setPath).readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        val o = args.options
        val run = GlideRun(EnglishData.load(mapFile(dataPath)), glideWeights(o))
        out.appendLine(GlideRun.Result.HEADER)
        val r = run.run(sentences, o["wobble"]?.toFloat() ?: GLIDE_WOBBLE, ENGLISH_SEED, RunOptions(o).threads)
        out.appendLine(r.row("wobble"))
        if (r.skipped > 0) out.appendLine("${r.skipped} words left out: a key the layout lacks, or all on one key")
        out.appendLine(r.stepsRow())
        o["misses"]?.let { path -> File(path).printWriter().use { w -> r.misses.forEach(w::println) } }
        0
    },
    Command(
        "pinyin-glide", "<pinyin.data> <set.tsv> [--syllables <n; 0, the words>] [--word <letters>] [--shuangpin <scheme, as EngineSettings names it>] [--keys nine] [--table <x.data>] [--wobble <key widths>] [--shape <w>] [--proximity <w>] [--edges <w>] [--corner <w>] [--turn <cos>] [--near <r>] [--ends <r>] [--misses <out.tsv>]",
        2..2, "syllables", "wobble", "shape", "proximity", "edges", "corner", "turn", "near", "ends", "misses", "word", "shuangpin", "keys", "table",
    ) { args, out, _ ->
        if (args.options["keys"].let { it != null && it != "nine" }) return@Command null
        val (dataPath, setPath) = args.operands
        val o = args.options
        // one word's clean path: its readings, scored
        o["word"]?.let { word ->
            val layout = if (o["keys"] == "nine") GlideLayout.nineKeys() else GlideLayout.QWERTY
            val path = word.flatMap { layout.centre(it).toList() }.toFloatArray()
            PinyinGlide(loadData(dataPath), glideWeights(o, PinyinGlide.WEIGHTS)).decode(path, layout, 10)
                .forEach { out.appendLine(String.format(Locale.ROOT, "%-16s %-8s %7.2f", it.keys, it.word, it.score)) }
            return@Command 0
        }
        val samples = readSet(setPath).map { it.input to it.expected }
        val data = loadData(dataPath)
        val weights = glideWeights(o, PinyinGlide.WEIGHTS)
        val spelling = o["shuangpin"]?.let { PinyinGlide.Spelling.shuangpin(EngineSettings.SCHEMES[it] ?: throw IllegalArgumentException("no scheme $it")) }
        val layout = if (o["keys"] == "nine") GlideLayout.nineKeys() else GlideLayout.QWERTY
        val table = o["table"]?.let { path ->
            val words = TextWords(data.model, data.wordIndex)
            TableDictionary(CodeTable.load(mapFile(path))).let { it to TableGlide(it, { text -> words.logProb(text) }, weights) }
        }
        val r = PinyinGlideRun(data, weights).run(
            samples, o["syllables"]?.toInt() ?: 0, o["wobble"]?.toFloat() ?: GLIDE_WOBBLE, ENGLISH_SEED,
            spelling ?: PinyinGlide.Spelling.FULL, layout, table,
        )
        out.appendLine(GlideRun.Result.HEADER)
        out.appendLine(r.row("wobble"))
        if (r.skipped > 0) out.appendLine("${r.skipped} words left out: a key the layout lacks, or all on one key")
        if (r.samplesSkipped > 0) out.appendLine("${r.samplesSkipped} samples left out: their syllables are not one a character")
        out.appendLine(r.stepsRow())
        o["misses"]?.let { path -> File(path).printWriter().use { w -> r.misses.forEach(w::println) } }
        0
    },
    Command("predict-learn", "<pinyin.data> <set.tsv> [--user-weight <w>]", 2..2, "user-weight") { args, out, _ ->
        val (dataPath, setPath) = args.operands
        val userWeight = args.options["user-weight"]?.toFloat() ?: Predictor.USER_WEIGHT
        val rows = PredictLearning(loadData(dataPath), userWeight).measure(readSet(setPath))
        rows.forEach { (label, score) -> out.appendLine("%-28s %s".format(label, score)) }
        0
    },
    Command("learn-log-bench", "<dir> [--commits <n>] [--days <n>] [--legacy <pinyin.data>] [--counts <pinyin.data>]", 1..1, "commits", "days", "legacy", "counts") { args, out, _ ->
        require("legacy" !in args.options || "counts" !in args.options) { "--legacy or --counts, not both" }
        val dir = File(args.operands[0]).apply { mkdirs() }
        val bench = LearnLogBench(args.options["commits"]?.toInt() ?: LEARN_LOG_COMMITS, args.options["days"]?.toInt() ?: LEARN_LOG_DAYS)
        val lines = args.options["legacy"]?.let { bench.legacy(loadData(it), dir).lines() }
            ?: args.options["counts"]?.let { bench.counts(loadData(it), dir).lines() } ?: bench.run(dir).lines()
        lines.forEach(out::appendLine)
        0
    },
    Command("predict-set", "<pinyin.data> <set.tsv> <predict.tsv>", 3..3) { args, _, _ ->
        val (dataPath, setPath, outPath) = args.operands
        // after each word of a pinyin set's sentences, what follows: for `predict`
        File(outPath).printWriter().use { w ->
            PredictLearning(loadData(dataPath), 0f).continuations(readSet(setPath)).forEach { w.println("${it.context}\t${it.next}") }
        }
        0
    },
    Command(
        "sentences", "<pinyin.data> <set.tsv> <out.tsv> [--scheme <scheme>] [--neighbours on|off] [--beam <n>] [--sentences <n>] [--threads <n>]",
        3..3, "scheme", "neighbours", "beam", "sentences", "threads",
    ) { args, _, _ ->
        val (dataPath, setPath, outPath) = args.operands
        sentences(dataPath, setPath, outPath, RunOptions(args.options))
    },
    Command("lm", "<model.safetensors> <context> <text>...", 3..Int.MAX_VALUE) { args, out, _ ->
        val (modelPath, context) = args.operands
        lm(modelPath, context, args.operands.drop(2), out)
    },
    Command("shuangpin", "<scheme> <set.tsv> <shuangpin-set.tsv> [--keys 18|nine] [--same <set-kept.tsv>]", 3..3, "keys", "same") { args, out, _ ->
        val (scheme, setPath, outPath) = args.operands
        val keys = args.options["keys"]?.let { ShuangpinSet.KEYS[it] ?: return@Command null }
        if (scheme in ShuangpinSet.SCHEMES) writeShuangpinSet(scheme, setPath, outPath, out, keys, args.options["same"]) else null
    },
    Command("t9", "<set.tsv> <t9-set.tsv> [--presses once|letters] [--keys nine|14] [--same <set-kept.tsv>]", 2..2, "presses", "keys", "same") { args, out, err ->
        val (setPath, outPath) = args.operands
        val presses = T9Set.Presses.of(args.options["presses"] ?: "once")!!
        val keys = T9Set.KEYS[args.options["keys"] ?: "nine"] ?: return@Command null
        // the 14 keys have no 连按
        if (presses != T9Set.Presses.ONCE && keys != NineKeyLetters.STANDARD) {
            err.appendLine("t9: --presses ${presses.option} is the nine keys' alone, not --keys 14")
            null
        } else writeT9Set(setPath, outPath, presses, keys, out, args.options["same"])
    },
    Command("slips", "<set.tsv> <slip-set.tsv>", 2..2) { args, out, _ ->
        val (setPath, outPath) = args.operands
        writeSlipSet(setPath, outPath, out)
    },
    Command("tune", "<pinyin.data> <set.tsv> <slip-set.tsv> [--threads <n>]", 3..3, "threads") { args, out, _ ->
        val (dataPath, setPath, slipsPath) = args.operands
        tune(dataPath, setPath, slipsPath, RunOptions(args.options).threads, out)
    },
    Command(
        "ksc",
        "<pinyin.data> <set.tsv> [--scheme <scheme>] [--fuzzy all|<pair>,...] [--half <half>] [--rerank <model.safetensors>] [--refine <model.safetensors>] [--weight <rerank>[,<refine>]] [--threads <n>] [--outcomes <out.tsv>]",
        2..2, "scheme", "fuzzy", "half", "rerank", "refine", "weight", "threads", "outcomes",
    ) { args, out, _ ->
        val (dataPath, setPath) = args.operands
        ksc(dataPath, setPath, RunOptions(args.options), Models(args.options), args.options["outcomes"], out)
    },
    Command(
        "learn",
        "<pinyin.data> <set.tsv> [--scheme <scheme>] [--fuzzy all|<pair>,...] [--neighbours on|off] [--rerank <model.safetensors>] [--refine <model.safetensors>] [--weight <rerank>[,<refine>]] [--limit <rerank>[,<refine>]] [--pack <file.words>] [--misses <out.tsv>] $USE_USAGE",
        2..2, "scheme", "fuzzy", "neighbours", "rerank", "refine", "weight", "limit", "pack", "misses", *USE_OPTIONS,
    ) { args, out, _ ->
        val (dataPath, setPath) = args.operands
        learn(dataPath, setPath, RunOptions(args.options), Models(args.options), args.options["pack"], learningUse(args.options), args.options["misses"], out)
    },
    Command(
        "learn-ux",
        "<pinyin.data> <script.txt or a directory of them> [--seed <commits>] [--gate] [--rerank <model.safetensors>] [--refine <model.safetensors>] $USE_USAGE",
        2..2, "seed", "gate", "rerank", "refine", *USE_OPTIONS,
    ) { args, out, _ ->
        val (dataPath, scriptPath) = args.operands
        learnUx(dataPath, File(scriptPath), args.options, out)
    },
    Command("chars", "<pinyin.data>", 1..1) { args, out, _ ->
        CharRanks.report(CharRanks(loadData(args.operands[0])).measure(), out)
        0
    },
    Command(
        "handwriting",
        "<pinyin.data> <rows.tsv> [--weights <ink weight>,... (one with --rerank)] [--alternatives <n>] [--limit <rows>] [--rerank <small model.safetensors> [--refine <large model.safetensors>] [--model-weights <w>,...]]",
        2..2, "weights", "alternatives", "limit", "rerank", "refine", "model-weights",
    ) { args, out, _ ->
        val (dataPath, rowsPath) = args.operands
        // the models read the sentences at one ink weight: more would be measured at the first alone
        val inkWeights = args.options["weights"]?.split(',')?.size ?: 1
        if (modelsAlone(args.options, "model-weights" to "rerank") || ("rerank" in args.options && inkWeights > 1)) {
            null
        } else {
            handwriting(dataPath, rowsPath, args.options, out)
        }
    },
    Command(
        "table", "<table.data> <set.tsv> [--preset <preset> [--pinyin <pinyin.data> [--rerank <small.safetensors> [--refine <large.safetensors>]]]]",
        2..2, "preset", "pinyin", "rerank", "refine",
    ) { args, out, _ ->
        val (dataPath, setPath) = args.operands
        val preset = args.options["preset"] ?: DEFAULT_PRESET
        if (modelsAlone(args.options, "rerank" to "pinyin")) {
            null
        } else {
            table(dataPath, setPath, preset, out)
            // the 重码 after the text before, as the app ranks them (TableLanguage)
            args.options["pinyin"]?.let { tableHomographs(dataPath, setPath, preset, it, args.options["rerank"], args.options["refine"], out) }
            0
        }
    },
    Command("libime", "pinyin|history|table <file>", 2..2) { args, out, err ->
        val (kind, path) = args.operands
        if (kind in LIBIME_KINDS) libime(kind, path, out, err) else null
    },
) + PARITY_COMMANDS

val USAGE = "usage: " + COMMANDS.joinToString("\n       ") { "${it.name} ${it.usage}" } + """
schemes: ${(ShuangpinSet.SCHEMES.keys + ShuangpinSet.GROUPED.keys + T9Set.SCHEMES + NO_LATIN).joinToString(" ")}
pairs: ${Fuzzy.entries.joinToString(" ") { it.name.lowercase() }}
halves: ${Halves.NAMES.joinToString(" ")}
presets: ${TableRun.PRESETS.keys.joinToString(" ")}
presets for --pinyin, which order by use (the app's 常用的字词排前面): ${TableRun.PRESETS.filterValues { it.orderByUse }.keys.joinToString(" ")}"""

fun main(args: Array<String>) {
    exitProcess(runCli(args, System.out, System.err))
}

/**
 * A command: its [name], what follows the name in the usage, how many arguments it takes after
 * the name, the [options] it takes, and what it does: the exit code, or null for a usage error.
 */
internal class Command(
    val name: String,
    val usage: String,
    val arity: IntRange,
    vararg options: String,
    val run: (Arguments, Appendable, Appendable) -> Int?,
) {
    val options = options.toSet()
}

/** Options that take no value: `--gate`, `--stale`, `--jvm`. */
private val FLAGS = setOf("gate", "stale", "jvm")

/** Arguments split into the positional ones and `--name value` options, a [FLAGS] one with an empty value. */
internal class Arguments(args: Array<String>) {
    val positional = ArrayList<String>()
    val options = HashMap<String, String>()
    var valid = true
        private set

    init {
        var i = 0
        while (i < args.size) {
            val arg = args[i++]
            if (!arg.startsWith("--")) {
                positional += arg
            } else if (arg.removePrefix("--") in FLAGS) {
                if (options.put(arg.removePrefix("--"), "") != null) valid = false
            } else if (i == args.size || options.put(arg.removePrefix("--"), args[i++]) != null) {
                valid = false // no value, or given twice
            }
        }
    }

    /** The positional arguments after the command's name. */
    val operands: List<String> get() = positional.drop(1)

    /** Whether these are [command]'s: its name, as many arguments after it as it takes, and only its options. */
    fun fit(command: Command) =
        valid && positional.firstOrNull() == command.name && operands.size in command.arity && options.keys.all { it in command.options }
}

/** @return the process exit code: 0, or 2 for a usage error */
fun runCli(args: Array<String>, out: Appendable, err: Appendable): Int {
    val arguments = Arguments(args)
    val command = COMMANDS.find { arguments.fit(it) }
    if (command == null || !optionsValid(arguments.options)) return usage(err)
    return command.run(arguments, out, err) ?: usage(err)
}

/** How often a key slips in `english`'s second run, and the slips' seed: the same each run. */
private const val ENGLISH_NOISE = 0.04
private const val ENGLISH_SEED = 20261011L

// how far a finger misses each key it glides through, key widths
private const val GLIDE_WOBBLE = 0.3f

/** A heavy user's year (impl/design-1a.md §8): what learn-log-bench writes. */
private const val LEARN_LOG_COMMITS = 200_000
private const val LEARN_LOG_DAYS = 365

private const val NANOS_PER_MILLI = 1_000_000

/** Full pinyin without the Latin words. */
private const val NO_LATIN = "no-latin"

/** `table`'s preset unless given one: [TableRun.PRESETS]' plain, the options' defaults. */
private const val DEFAULT_PRESET = "plain"

private fun optionsValid(options: Map<String, String>): Boolean {
    fun valid(name: String, test: (String) -> Boolean) = options[name]?.let(test) ?: true
    val scheme = options["scheme"]
    return listOf(
        valid("half") { it in Halves.NAMES },
        valid("presses") { T9Set.Presses.of(it) != null },
        valid("scheme") { it == NO_LATIN || it in ShuangpinSet.SCHEMES || it in ShuangpinSet.GROUPED || it in T9Set.SCHEMES },
        valid("fuzzy") { fuzzyPairs(it) != null },
        valid("preset") { it in TableRun.PRESETS },
        // a table that does not order by use keeps its order whatever the text before: each row the same
        valid("pinyin") { TableRun.PRESETS[options["preset"] ?: DEFAULT_PRESET]?.orderByUse == true },
        valid("weight") { weights(it) != null },
        valid("limit") { limits(it) != null },
        valid("user-weight") { weight(it) != null },
        valid("max-correction") { weight(it) != null },
        valid("noise") { n -> n.toDoubleOrNull()?.let { it in 0.0..1.0 } == true },
        // the union; each command checks its own: english and pinyin-glide nine only, t9 nine or 14,
        // shuangpin 18 or nine
        valid("keys") { it in T9Set.KEYS || it in ShuangpinSet.KEYS },
        valid("user-counts") { it in setOf("full", "none", "multi") },
        valid("short-guard") { it in ON_OFF },
        // the taps type the 14 keys: a 14-key scheme
        valid("taps") { runCatching { Taps.parse(it) }.isSuccess && scheme != null && scheme in T9Set.SCHEMES && T9Set.lettersOf(scheme) == NineKeyLetters.G14 },
        valid("seed") { (it.toIntOrNull() ?: -1) >= 0 },
        // a merged dictionary's file is what is stale
        valid("stale") { "merged" in options },
        listOf("threads", "beam", "sentences").all { name -> valid(name) { (it.toIntOrNull() ?: 0) > 0 } },
        // only full pinyin reads slips: 双拼 and the nine keys read none
        valid("neighbours") { it in ON_OFF && (scheme == null || scheme == NO_LATIN) },
    ).all { it }
}

private fun usage(err: Appendable): Int {
    err.appendLine(USAGE)
    return 2
}

/** `all`, or pair names separated by commas; null if one is no pair. */
private fun fuzzyPairs(names: String): Set<Fuzzy>? {
    if (names == "all") return Fuzzy.entries.toSet()
    val byName = Fuzzy.entries.associateBy { it.name.lowercase() }
    return names.split(',').map { byName[it] ?: return null }.toSet()
}

// [d] (GlidePath's), the options' where given
private fun glideWeights(o: Map<String, String>, d: GlidePath.Weights = GlidePath.Weights()): GlidePath.Weights {
    return GlidePath.Weights(
        near = o["near"]?.toFloat() ?: d.near, ends = o["ends"]?.toFloat() ?: d.ends,
        shape = o["shape"]?.toFloat() ?: d.shape, proximity = o["proximity"]?.toFloat() ?: d.proximity,
        edges = o["edges"]?.toFloat() ?: d.edges,
        corner = o["corner"]?.toFloat() ?: d.corner, turn = o["turn"]?.toFloat() ?: d.turn,
    )
}

private fun readSet(path: String) = File(path).useLines { EvalSet.parse(it) }

private fun mapFile(path: String) = RandomAccessFile(path, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()) }

private fun loadData(path: String): PinyinData = PinyinData.load(mapFile(path))

/** A sentence model's weight, or the user's: a number, not negative. */
private fun weight(spec: String): Float? = spec.toFloatOrNull()?.takeIf { it >= 0 && it.isFinite() }

/** `<rerank>[,<refine>]`: the sentence models' weights against the decoder, the refiner's the reranker's if not given. */
private fun weights(spec: String): Pair<Float, Float>? = pairOf(spec, ::weight)

/** `<rerank>[,<refine>]`: how many readings each sentence model weighs, the refiner's the reranker's if not given. */
private fun limits(spec: String): Pair<Int, Int>? = pairOf(spec) { it.toIntOrNull()?.takeIf { n -> n > 0 } }

/** `<rerank>[,<refine>]`, each read by [part]: null if one is not, or there are more than two. */
private fun <T : Any> pairOf(spec: String, part: (String) -> T?): Pair<T, T>? {
    val parts = spec.split(',').map { part(it) ?: return null }
    return when (parts.size) {
        1 -> parts[0] to parts[0]
        2 -> parts[0] to parts[1]
        else -> null
    }
}

/** The options of the commands that run the engine over a set, read once (checked already: see optionsValid). */
private class RunOptions(options: Map<String, String>) {
    private val scheme = options["scheme"]
    private val fuzzy = options["fuzzy"]?.let(::fuzzyPairs).orEmpty()
    private val neighbours = options["neighbours"] == "on"
    val half = options["half"]
    val beam = options["beam"]?.toInt() ?: PinyinDecoder.DEFAULT_BEAM
    /** How many whole-input readings the decoder gives the rerankers. */
    val sentences = options["sentences"]?.toInt() ?: PinyinDecoder.DEFAULT_SENTENCES
    val threads = options["threads"]?.toInt() ?: Runtime.getRuntime().availableProcessors()
    // pinyin's: the layers' weights, a word pack, a merged dictionary (and whether it is out of date)
    val layers = options["layers"]
    val pack = options["pack"]
    val merged = options["merged"]
    val stale = "stale" in options

    /** The 14 keys tapped by a simulated hand ([Taps]): the set, full pinyin, typed so. */
    val taps = options["taps"]?.let(Taps::parse)?.also {
        require(scheme != null && scheme in T9Set.SCHEMES && T9Set.lettersOf(scheme) == NineKeyLetters.G14) { "--taps types the 14 keys: a 14-key --scheme, not $scheme" }
    }

    /** Each syllable spelt out in the preedit, as the app shows 双拼 and the nine keys. */
    val spell = scheme != null && scheme != NO_LATIN

    /**
     * Full pinyin reads the Latin words of [data] as the app does, unless the scheme is [NO_LATIN]:
     * what it was before them.
     */
    fun segmenter(data: PinyinData): Segmenter = when (scheme) {
        null -> PinyinSegmenter(fuzzy, neighbours = neighbours, latin = LatinWords.of(data.dictionary))
        NO_LATIN -> PinyinSegmenter(fuzzy, neighbours = neighbours)
        in T9Set.SCHEMES -> T9Segmenter(fuzzy, abbreviations = T9Set.abbreviates(scheme), letters = T9Set.lettersOf(scheme))
        in ShuangpinSet.GROUPED -> ShuangpinSet.GROUPED.getValue(scheme).let { (s, letters) -> GroupedShuangpinSegmenter(s, letters, fuzzy) }
        else -> ShuangpinSegmenter(ShuangpinSet.SCHEMES.getValue(scheme), fuzzy)
    }
}

/**
 * The sentence models: the one weighing the readings at each key, and the one while the user
 * pauses. Loaded once and shared by the threads; each reranker keeps its own state.
 */
private class Models(options: Map<String, String>) {
    private val rerankPath = options["rerank"]
    private val refinePath = options["refine"]
    private val weights = options["weight"]?.let(::weights) ?: (Reranker.WEIGHT to Reranker.WEIGHT)
    private val limits = options["limit"]?.let(::limits) ?: (Reranker.LIMIT to Reranker.REFINE_LIMIT)

    // floats, as the app has the smaller: the same scores, sooner
    private val rerank by lazy { rerankPath?.let { SentenceModel.load(mapFile(it), unpack = true) } }
    private val refine by lazy { refinePath?.let { SentenceModel.load(mapFile(it), unpack = true) } }

    /** Makes a reranker: none when there is no model. */
    val reranker: (() -> Reranker)? get() = rerank?.let { model -> { Reranker(model, weights.first, limit = limits.first) } }

    val refiner: (() -> SentenceRefiner)? get() = refine?.let { model -> { Reranker(model, weights.second, limit = limits.second) } }
}

private fun predict(dataPath: String, setPath: String, threads: Int, offersPath: String?, rerank: String?, weight: Float, out: Appendable): Int {
    val samples = File(setPath).useLines { PredictRun.parse(it) }
    val model = rerank?.let { SentenceModel.load(mapFile(it), unpack = true) }
    val offers = dealt(samples, threads, { PredictRun(loadData(dataPath), model, weight) }) { hand -> hand.map { offers(it.context) } }
    // what was offered, for a look at the misses: context<TAB>next<TAB>offers, space-separated
    offersPath?.let { path ->
        File(path).printWriter().use { w -> samples.zip(offers).forEach { (s, o) -> w.println("${s.context}\t${s.next}\t${o.joinToString(" ")}") } }
    }
    out.appendLine(PredictRun.score(samples, offers).toString())
    return 0
}

private fun score(setPath: String, resultPath: String, baselinePath: String?, half: String?, out: Appendable): Int {
    val samples = readSet(setPath)
    fun resultsOf(path: String) = File(path).useLines { RunResultFormat.parse(it).toList() }
    val results = resultsOf(resultPath)
    val baseline = baselinePath?.let(::resultsOf)
    out.appendLine(Report.table(Metrics.score(samples, results, half), baseline?.let { Metrics.score(samples, it, half) }))
    // which samples changed: a delta of a point on a few hundred is as often chance as not
    if (baseline != null) out.appendLine().appendLine(Report.flips(Metrics.flips(samples, results, baseline, half)))
    return 0
}

private fun runPinyin(dataPath: String, setPath: String, resultPath: String, options: RunOptions, models: Models): Int {
    val mergedPath = options.merged
    val pack = options.pack?.let { path -> File(path).useLines { WordPack.parse(it, path) } }
    val work = mergedPath?.let { Files.createTempDirectory("ime-eval-merged").toFile() }
    try {
        val merged = mergedPath?.let { compileMerged(it, loadData(dataPath), File(checkNotNull(work), "merged.ymd")) }
        // a data file each: its buffers are not to be shared
        val set = Halves.select(readSet(setPath), options.half)
        val selected = options.taps?.let { spec ->
            Taps.convert(set, spec).also { require(it.isNotEmpty()) { "--taps: no sample of $setPath has one exact reading (a set already of codes?)" } }
        } ?: set
        val results = dealt(selected, options.threads, {
            val data = loadData(dataPath)
            PinyinRun(
                data, options.segmenter(data), penalties = Penalties(tap = options.taps?.let { -it.alpha } ?: Penalties().tap),
                beam = options.beam, sentenceCount = options.sentences, layers = options.layers, pack = pack,
                reranker = models.reranker, refiner = models.refiner,
                merged = merged?.let { DictionaryFile.open(it, verify = false) }, mergedStale = options.stale,
            )
        }) { hand -> run(hand) }
        File(resultPath).printWriter().use { out -> results.forEach { out.println(RunResultFormat.format(it)) } }
    } finally {
        work?.deleteRecursively()
    }
    return 0
}

/**
 * [source], a dictionary's text as the app keeps one, compiled as the app compiles it, against
 * [data]'s dictionary: the file typing with it reads, its size told on stderr.
 */
private fun compileMerged(source: String, data: PinyinData, dest: File): File {
    val started = System.nanoTime()
    val result = File(source).useLines { lines -> DictionaryCompiler.compile(listOf(lines), dest, inBase = DictionaryCompiler.inBase(data)) }
    System.err.println(
        "merged: ${result.words} words, ${result.inBase} the base's, ${result.unread} unread; " +
            "${dest.length()} bytes, compiled in ${(System.nanoTime() - started) / NANOS_PER_MILLI} ms",
    )
    // checked once here; each thread opens it unchecked
    DictionaryFile.open(dest)
    return dest
}

/**
 * Each sample's whole-input readings as the decoder ranks them, each with its score (log10), a
 * line per sample: `input<TAB>text<TAB>score...`. What a sentence model is added to in the app,
 * for training one on top of it (dev/training). The lines are in the set's order, one per sample
 * that [EvalSet.parse] keeps (blank and `#` lines are not samples): that is how the training
 * scripts join them back to the set's expected text, so nothing else is repeated here.
 */
private fun sentences(dataPath: String, setPath: String, outPath: String, options: RunOptions): Int {
    val lines = dealt(readSet(setPath), options.threads, { loadData(dataPath).let { PinyinRun(it, options.segmenter(it), beam = options.beam, sentenceCount = options.sentences) } }) { hand ->
        hand.map { sample ->
            (listOf(sample.input) + sentences(sample.input, sample.context).flatMap { (text, score) -> listOf(text, score.toString()) })
                .joinToString("\t")
        }
    }
    File(outPath).printWriter().use { out -> lines.forEach(out::println) }
    return 0
}

/** Each text's log-probability after the context, whole and per character, as the model reads it. */
private fun lm(modelPath: String, context: String, texts: List<String>, out: Appendable): Int {
    // floats, as the other commands load it: the same scores, sooner than the JVM kernel's get per weight of the mapped file
    val model = SentenceModel.load(mapFile(modelPath), unpack = true)
    model.Scorer().score(context, texts).forEachIndexed { i, score ->
        out.appendLine(String.format(Locale.ROOT, "%8.3f %6.3f %s", score, score / texts[i].length, texts[i]))
    }
    return 0
}

private fun writeSet(outPath: String, header: String, samples: List<Sample>) {
    File(outPath).printWriter().use { w ->
        w.println("# SPDX-License-Identifier: LGPL-2.1-or-later")
        w.println("# SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors")
        w.println("# $header")
        // the context too: without it the models are measured reading nothing before the input
        samples.forEach { w.println(listOf(it.input, it.expected, it.tag, it.context).joinToString("\t").trimEnd('\t')) }
    }
}

// [same]: the samples kept, as they were, for full pinyin to be scored on the same ones (only a sample of one
// exact reading is typed: 简拼 and half syllables are left out, so the whole set is no like for like)
private fun writeT9Set(setPath: String, outPath: String, presses: T9Set.Presses, keys: NineKeyLetters, out: Appendable, same: String? = null): Int {
    val samples = readSet(setPath)
    val converted = T9Set.convert(samples, presses, keys)
    same?.let { path -> writeSet(path, "$setPath: the samples `t9` typed, as they were", samples.filter { T9Set.convert(listOf(it), presses, keys).isNotEmpty() }) }
    val on = if (keys == NineKeyLetters.STANDARD) "the nine keys" else "the 14 keys"
    writeSet(outPath, "$setPath typed on $on by `t9`, keys pressed ${presses.option}; samples without one exact reading left out", converted)
    out.appendLine("${converted.size} of ${samples.size} samples typed on $on")
    return 0
}

// [same]: the samples kept, as they were, for the full keyboard and full pinyin to be scored on the same ones
private fun writeShuangpinSet(scheme: String, setPath: String, outPath: String, out: Appendable, keys: NineKeyLetters? = null, same: String? = null): Int {
    val samples = readSet(setPath)
    val s = ShuangpinSet.SCHEMES.getValue(scheme)
    val converted = ShuangpinSet.convert(samples, s, keys)
    same?.let { path -> writeSet(path, "$setPath: the samples `shuangpin` typed, as they were", samples.filter { ShuangpinSet.convert(listOf(it), s, keys).isNotEmpty() }) }
    val on = when (keys) {
        null -> ""
        NineKeyLetters.G18 -> " on the 18 keys"
        else -> " on the nine keys"
    }
    writeSet(outPath, "$setPath typed in $scheme 双拼$on by `shuangpin`; samples without one exact reading left out", converted)
    out.appendLine("${converted.size} of ${samples.size} samples typed in $scheme$on")
    return 0
}

private fun writeSlipSet(setPath: String, outPath: String, out: Appendable): Int {
    val slips = SlipSet.generate(readSet(setPath))
    writeSet(outPath, "$setPath with one syllable mixed up or slipped, by `slips`; the tag says how", slips)
    out.appendLine("${slips.size} samples")
    return 0
}

private fun tune(dataPath: String, setPath: String, slipsPath: String, threads: Int, out: Appendable): Int {
    val tuning = Tuning({ loadData(dataPath) }, readSet(setPath), readSet(slipsPath), threads)
    fun row(label: String, point: Tuning.Point) = out.appendLine(
        String.format(Locale.ROOT, "%-24s", label) +
            (point.rates + point.mean).joinToString("") { rate ->
                if (rate == null) String.format(Locale.ROOT, "%17s", "-") else String.format(Locale.ROOT, "%16.1f%%", rate * 100)
            },
    )
    fun header(label: String, point: Tuning.Point, columns: List<String> = Tuning.COLUMNS) = out.appendLine(
        String.format(Locale.ROOT, "%-24s", label) +
            (columns.zip(point.sizes) { c, n -> "$c/$n" } + "mean").joinToString("") { String.format(Locale.ROOT, "%17s", it) },
    )
    val default = tuning.measure(Penalties(), Halves.TUNE)
    val points = tuning.search()
    header("fuzzy typo (tune)", default)
    points.forEach { row("${it.penalties.fuzzy} ${it.penalties.typo}", it) }
    val chosen = tuning.choose(points, default)
    val heldOut = tuning.measure(Penalties(), Halves.HELD_OUT)
    header("held-out", heldOut)
    row("default", heldOut)
    row("chosen ${chosen.penalties.fuzzy} ${chosen.penalties.typo}", tuning.measure(chosen.penalties, Halves.HELD_OUT))

    val off = tuning.measureKeys(Penalties(), Halves.TUNE, read = false)
    header("neighbour (tune)", off, Tuning.KEY_COLUMNS)
    row("off", off)
    val keyPoints = tuning.searchKeys()
    keyPoints.forEach { row("${it.penalties.neighbour}", it) }
    val keyChoice = tuning.chooseKeys(keyPoints, off)
    val offHeldOut = tuning.measureKeys(Penalties(), Halves.HELD_OUT, read = false)
    header("held-out", offHeldOut, Tuning.KEY_COLUMNS)
    row("off", offHeldOut)
    row("default ${Penalties().neighbour}", tuning.measureKeys(Penalties(), Halves.HELD_OUT, read = true))
    if (keyChoice == null) {
        out.appendLine("chosen: none keeps clean input")
    } else {
        row("chosen ${keyChoice.penalties.neighbour}", tuning.measureKeys(keyChoice.penalties, Halves.HELD_OUT, read = true))
    }
    return 0
}

private fun ksc(dataPath: String, setPath: String, options: RunOptions, models: Models, outcomesPath: String?, out: Appendable): Int {
    val outcomes = dealt(Halves.select(readSet(setPath), options.half), options.threads, {
        val data = loadData(dataPath)
        Typist(data, options.segmenter(data), options.spell, models.reranker, models.refiner)
    }) { hand -> hand.map { type(it) } }
    out.appendLine(KeystrokeRun.report(outcomes))
    // a line a sample, for two runs to be compared on the samples both reached
    outcomesPath?.let { path -> File(path).printWriter().use { w -> outcomes.forEach { w.println(KeystrokeRun.line(it)) } } }
    return 0
}

/**
 * Types one thread's samples through one session, as the app keeps one: its decoder's look-ahead
 * and buffers are made once, not per sample. The rerankers are new for each sample, as one keeps
 * what it read for the next input, which would make a sample's result hang on the one before it,
 * and so on how they are dealt.
 */
internal class Typist(
    data: PinyinData,
    segmenter: Segmenter,
    spell: Boolean = false,
    /** Makes the reranker of a sample's keys. */
    private val reranker: (() -> SentencePicker)? = null,
    /** Makes what weighs a sample's readings once more as the user pauses. */
    private val refiner: (() -> SentenceRefiner)? = null,
) {
    private lateinit var sampleReranker: SentencePicker
    private lateinit var sampleRefiner: SentenceRefiner

    // the session's, made once: the sample's, its slices too
    private val forwardingRefiner = object : SentenceRefiner {
        override val slices: Int get() = sampleRefiner.slices

        override fun refine(context: String, readings: List<String>, scores: List<Float>, budget: Int): Int? =
            sampleRefiner.refine(context, readings, scores, budget)
    }

    // no prediction: KeystrokeRun reads none, and the next sample clears it
    private val session = PinyinSession(
        data, segmenter, spell = spell, prediction = false,
        refiner = if (refiner == null) null else forwardingRefiner,
        reranker = if (reranker == null) null else SentencePicker { context, readings, scores -> sampleReranker.pick(context, readings, scores) },
    )
    private val keys = KeystrokeRun(session)

    fun type(sample: Sample): KeystrokeRun.Outcome {
        reranker?.let { sampleReranker = it() }
        refiner?.let { sampleRefiner = it() }
        // what the sample before committed is no context of this one's
        session.apply(Action.Reset)
        return keys.type(sample)
    }
}

private fun learn(dataPath: String, setPath: String, options: RunOptions, models: Models, packPath: String?, use: LearningUse, missesPath: String?, out: Appendable): Int {
    val data = loadData(dataPath)
    val pack = packPath?.let { path -> File(path).useLines { WordPack.parse(it, path) } }
    val rows = Learning(data, options.segmenter(data), models.reranker, models.refiner, pack, use).measure(readSet(setPath))
    out.appendLine(KeystrokeRun.HEADER)
    // a half with no samples is still a row, of none
    rows.forEach { (label, outcomes) -> out.appendLine(KeystrokeRun.row(label, outcomes)) }
    // each row's samples not first once typed: the row, the keys, what was meant
    missesPath?.let { path ->
        File(path).printWriter().use { w ->
            for ((label, outcomes) in rows) outcomes.filterNot { it.first }.forEach { w.println("$label\t${it.sample.input}\t${it.sample.expected}") }
        }
    }
    return 0
}

/** `--user-counts` and `--short-guard`: how what is learned reaches the scores, the app's where not given (checked already: see optionsValid). */
private fun learningUse(o: Map<String, String>) = LearningUse(
    counts = o["user-counts"]?.let { LearningUse.Counts.valueOf(it.uppercase(Locale.ROOT)) } ?: LearningUse.DEFAULT.counts,
    shortGuard = o["short-guard"] != "off",
)

/**
 * The scripts at [scripts] (a file, or every .txt of a directory, by name) run by [LearnUx]: the
 * report, and with `--gate` exit code 1 where an expectation failed. `--seed <n>` commits the
 * first n samples of the sets beside the scripts' directory (as dev/design/user-recency.md's
 * harness did: web, chat, dialog, mixed, collide, slips, shuffled) before each scenario.
 */
private fun learnUx(dataPath: String, scripts: File, o: Map<String, String>, out: Appendable): Int {
    val files = if (scripts.isDirectory) scripts.listFiles { f -> f.name.endsWith(".txt") }.orEmpty().sortedBy { it.name } else listOf(scripts)
    val scenarios = files.flatMap { LearnUxScript.parse(it.name, it.readLines()) }
    val seed = o["seed"]?.toInt()?.let { n ->
        val sets = (if (scripts.isDirectory) scripts else scripts.absoluteFile.parentFile).absoluteFile.parentFile
        SEED_SETS.flatMap { readSet(File(sets, "$it.tsv").path) }.shuffled(Random(SEED_SHUFFLE)).take(n)
    }.orEmpty()
    val models = Models(o)
    val results = LearnUx(loadData(dataPath), models.reranker, models.refiner, learningUse(o), seed).run(scenarios)
    out.appendLine(LearnUx.report(results))
    // a gate that checked nothing (no scripts, no expectations) is no pass
    val checked = results.any { it.status != LearnUx.Status.NOTE }
    if (!checked) out.appendLine("no expectation checked in $scripts")
    return if ("gate" in o && (!checked || results.any { it.status == LearnUx.Status.FAIL })) 1 else 0
}

/** The sets `learn-ux --seed` commits from. */
private val SEED_SETS = listOf("pinyin-web", "pinyin-chat", "pinyin-dialog", "pinyin-mixed", "pinyin-collide", "pinyin-slips")
private const val SEED_SHUFFLE = 7L

private const val USE_USAGE = "[--user-counts full|none|multi] [--short-guard on|off]"

/**
 * Whether [options] give a model option without what it is read with, which would be ignored: the
 * large sentence model without the small (`handwriting` and `table` weigh as the 性能档位 does,
 * whose high has both), or [need]'s first without its second.
 */
private fun modelsAlone(options: Map<String, String>, need: Pair<String, String>) =
    ("refine" in options && "rerank" !in options) || (need.first in options && need.second !in options)

/**
 * The rows read by the ink alone, then as a sentence at each ink weight, then with the sentence
 * models weighing the best sentences again, as the 性能档位 has them in the app: medium, the small
 * one at each character within its budget; high, then the large one in the slices a pause gives it.
 */
private fun handwriting(dataPath: String, rowsPath: String, options: Map<String, String>, out: Appendable): Int {
    val run = HandwritingRun(loadData(dataPath))
    val rows = HandwritingRun.read(rowsPath).let { r -> options["limit"]?.toIntOrNull()?.let { r.take(it) } ?: r }
    val alternatives = options["alternatives"]?.toIntOrNull() ?: HandwritingDecoder.ALTERNATIVES
    out.appendLine("ink alone            ${run.ink(rows)}")
    val weights = options["weights"]?.split(',')?.map { it.toDouble() } ?: listOf(HandwritingDecoder.INK_WEIGHT)
    for (w in weights) out.appendLine(String.format(Locale.ROOT, "sentence, ink %-5s %s", w, run.sentences(rows, run.decoder(w, alternatives))))
    val small = options["rerank"]?.let { SentenceModel.load(mapFile(it), unpack = true) } ?: return 0
    val large = options["refine"]?.let { SentenceModel.load(mapFile(it), unpack = true) }
    val modelWeights = options["model-weights"]?.split(',')?.map { it.toFloat() } ?: listOf(Reranker.WEIGHT)
    for (mw in modelWeights) {
        val reranker = Reranker(small, mw)
        val medium = { before: String, d: HandwritingDecoder.Decoding -> reranker.pick(before, d.best, d.bestScores) }
        val decoder = run.decoder(weights.first(), alternatives)
        out.appendLine(String.format(Locale.ROOT, "medium %-5s     %s", mw, run.sentences(rows, decoder, medium)))
        val refiner = large?.let { Reranker(it, mw, limit = Reranker.REFINE_LIMIT) } ?: continue
        val high = HandwritingRun.refined(refiner, medium)
        out.appendLine(String.format(Locale.ROOT, "high %-5s       %s", mw, run.sentences(rows, decoder, high)))
    }
    return 0
}

private fun table(dataPath: String, setPath: String, preset: String, out: Appendable): Int {
    val run = TableRun(CodeTable.load(mapFile(dataPath)), TableRun.PRESETS.getValue(preset))
    val texts = readSet(setPath).map { it.expected }.distinct()
    val total = texts.fold(TableRun.Outcome.ZERO) { sum, text -> sum + run.type(text) }
    out.appendLine(TableRun.report(total, run.entries(ENTRY_STEP)))
    return 0
}

private fun tableHomographs(dataPath: String, setPath: String, preset: String, pinyinPath: String, rerank: String?, refine: String?, out: Appendable) {
    val run = TableRun(CodeTable.load(mapFile(dataPath)), TableRun.PRESETS.getValue(preset))
    val d = loadData(pinyinPath)
    val words = TextWords(d.model, d.wordIndex)
    val small = rerank?.let { Reranker(SentenceModel.load(mapFile(it), unpack = true), Reranker.WEIGHT) }
    val large = refine?.let { Reranker(SentenceModel.load(mapFile(it), unpack = true), Reranker.WEIGHT, limit = Reranker.REFINE_LIMIT) }
    val languages = listOfNotNull(
        "table's order" to null,
        "word model (low)" to TableLanguage(words),
        small?.let { "+ small model (medium)" to TableLanguage(words, reranker = it) },
        large?.let { "+ large model (high)" to TableLanguage(words, reranker = small, refiner = it) },
    )
    val texts = readSet(setPath).map { it.expected }.distinct()
    var n = 0
    val first = IntArray(languages.size)
    for (text in texts) {
        val (k, f) = run.homographs(text, languages.map { it.second })
        n += k
        f.indices.forEach { first[it] += f[it] }
    }
    out.appendLine("重码 words: $n")
    languages.forEachIndexed { i, (label, _) -> out.appendLine(String.format(Locale.ROOT, "  %-24s first %.1f%%", label, 100.0 * first[i] / maxOf(n, 1))) }
}

/** A libime file as the text libime's own tools write of it, to check the readers against them. */
private fun libime(kind: String, path: String, out: Appendable, err: Appendable): Int {
    val data = try {
        File(path).readBytes()
    } catch (e: IOException) {
        err.appendLine("$path: ${e.message}")
        return 1
    }
    try {
        when (kind) {
            "pinyin" -> LibimeFiles.pinyinDictionary(data).forEach { out.appendLine(it) }
            "history" -> LibimeFiles.history(data).forEach { out.appendLine(it) }
            else -> out.append(LibimeFiles.table(data))
        }
    } catch (e: DataFormatException) {
        err.appendLine("$path: ${e.message}")
        return 1
    }
    return 0
}

private val LIBIME_KINDS = setOf("pinyin", "history", "table")

private val ON_OFF = setOf("on", "off")

// about 2,000 of 五笔's 100,000 entries
private const val ENTRY_STEP = 50
