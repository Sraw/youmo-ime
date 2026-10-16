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
import org.fcitx.fcitx5.android.engine.handwriting.HandwritingDecoder
import org.fcitx.fcitx5.android.engine.lattice.Penalties
import org.fcitx.fcitx5.android.engine.lattice.PinyinDecoder
import org.fcitx.fcitx5.android.engine.lattice.Predictor
import org.fcitx.fcitx5.android.engine.lattice.TextWords
import org.fcitx.fcitx5.android.engine.libime.LibimeFiles
import org.fcitx.fcitx5.android.engine.pinyin.Fuzzy
import org.fcitx.fcitx5.android.engine.pinyin.LatinWords
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
import org.fcitx.fcitx5.android.engine.table.TableLanguage
import org.fcitx.fcitx5.android.engine.user.WordPack
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.util.Locale
import kotlin.system.exitProcess

/** Every command, in the order [USAGE] lists them: it is made from them. */
internal val COMMANDS = listOf(
    Command("score", "<set.tsv> <result.tsv> [<baseline-result.tsv>] [--half <half>]", 2..3, "half") { args, out, _ ->
        val (setPath, resultPath) = args.operands
        val baselinePath = args.operands.getOrNull(2)
        score(setPath, resultPath, baselinePath, args.options["half"], out)
    },
    Command(
        "pinyin",
        "<pinyin.data> <set.tsv> <result.tsv> [--scheme <scheme>] [--fuzzy all|<pair>,...] [--half <half>] [--neighbours on|off] [--rerank <model.safetensors>] [--refine <model.safetensors>] [--weight <rerank>[,<refine>]] [--limit <rerank>[,<refine>]] [--beam <n>] [--sentences <n>] [--layers <name>=<log10>,...] [--pack <file.words>] [--threads <n>]",
        3..3, "scheme", "fuzzy", "half", "neighbours", "rerank", "refine", "weight", "limit", "beam", "sentences", "layers", "pack", "threads",
    ) { args, _, _ ->
        val (dataPath, setPath, resultPath) = args.operands
        runPinyin(dataPath, setPath, resultPath, RunOptions(args.options), Models(args.options), args.options["layers"], args.options["pack"])
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
        "english", "<english.data> <sentences.txt> [--noise <rate>] [--max-correction <cost>] [--misses <out.tsv>] [--threads <n>]",
        2..2, "noise", "max-correction", "misses", "threads",
    ) { args, out, _ ->
        val (dataPath, setPath) = args.operands
        val sentences = File(setPath).readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        // checked already: see optionsValid
        val run = EnglishRun(EnglishData.load(mapFile(dataPath)), maxCorrection = args.options["max-correction"]?.toFloat() ?: EnglishSession.MAX_CORRECTION)
        val noise = args.options["noise"]?.toDouble() ?: ENGLISH_NOISE
        out.appendLine(EnglishRun.Result.HEADER)
        val threads = RunOptions(args.options).threads
        val clean = run.run(sentences, 0.0, ENGLISH_SEED, threads)
        val slips = run.run(sentences, noise, ENGLISH_SEED, threads)
        out.appendLine(clean.row("clean"))
        out.appendLine(slips.row("slips"))
        args.options["misses"]?.let { path ->
            File(path).printWriter().use { w -> (clean.misses.map { "clean\t$it" } + slips.misses.map { "slips\t$it" }).forEach(w::println) }
        }
        0
    },
    Command("predict-learn", "<pinyin.data> <set.tsv> [--user-weight <w>]", 2..2, "user-weight") { args, out, _ ->
        val (dataPath, setPath) = args.operands
        val userWeight = args.options["user-weight"]?.toFloat() ?: Predictor.USER_WEIGHT
        val rows = PredictLearning(loadData(dataPath), userWeight).measure(readSet(setPath))
        rows.forEach { (label, score) -> out.appendLine("%-28s %s".format(label, score)) }
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
    Command("shuangpin", "<scheme> <set.tsv> <shuangpin-set.tsv>", 3..3) { args, out, _ ->
        val (scheme, setPath, outPath) = args.operands
        if (scheme in ShuangpinSet.SCHEMES) writeShuangpinSet(scheme, setPath, outPath, out) else null
    },
    Command("t9", "<set.tsv> <t9-set.tsv>", 2..2) { args, out, _ ->
        val (setPath, outPath) = args.operands
        writeT9Set(setPath, outPath, out)
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
        "<pinyin.data> <set.tsv> [--scheme <scheme>] [--fuzzy all|<pair>,...] [--neighbours on|off] [--rerank <model.safetensors>] [--refine <model.safetensors>] [--weight <rerank>[,<refine>]] [--limit <rerank>[,<refine>]] [--pack <file.words>]",
        2..2, "scheme", "fuzzy", "neighbours", "rerank", "refine", "weight", "limit", "pack",
    ) { args, out, _ ->
        val (dataPath, setPath) = args.operands
        learn(dataPath, setPath, RunOptions(args.options), Models(args.options), args.options["pack"], out)
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
)

val USAGE = "usage: " + COMMANDS.joinToString("\n       ") { "${it.name} ${it.usage}" } + """
schemes: ${(ShuangpinSet.SCHEMES.keys + T9Set.SCHEMES + NO_LATIN).joinToString(" ")}
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

/** Arguments split into the positional ones and `--name value` options. */
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

/** Full pinyin without the Latin words. */
private const val NO_LATIN = "no-latin"

/** `table`'s preset unless given one: [TableRun.PRESETS]' plain, the options' defaults. */
private const val DEFAULT_PRESET = "plain"

private fun optionsValid(options: Map<String, String>): Boolean {
    fun valid(name: String, test: (String) -> Boolean) = options[name]?.let(test) ?: true
    val scheme = options["scheme"]
    return listOf(
        valid("half") { it in Halves.NAMES },
        valid("scheme") { it == NO_LATIN || it in ShuangpinSet.SCHEMES || it in T9Set.SCHEMES },
        valid("fuzzy") { fuzzyPairs(it) != null },
        valid("preset") { it in TableRun.PRESETS },
        // a table that does not order by use keeps its order whatever the text before: each row the same
        valid("pinyin") { TableRun.PRESETS[options["preset"] ?: DEFAULT_PRESET]?.orderByUse == true },
        valid("weight") { weights(it) != null },
        valid("limit") { limits(it) != null },
        valid("user-weight") { weight(it) != null },
        valid("max-correction") { weight(it) != null },
        valid("noise") { n -> n.toDoubleOrNull()?.let { it in 0.0..1.0 } == true },
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
    /** Each syllable spelt out in the preedit, as the app shows 双拼 and the nine keys. */
    val spell = scheme != null && scheme != NO_LATIN

    /**
     * Full pinyin reads the Latin words of [data] as the app does, unless the scheme is [NO_LATIN]:
     * what it was before them.
     */
    fun segmenter(data: PinyinData): Segmenter = when (scheme) {
        null -> PinyinSegmenter(fuzzy, neighbours = neighbours, latin = LatinWords.of(data.dictionary))
        NO_LATIN -> PinyinSegmenter(fuzzy, neighbours = neighbours)
        in T9Set.SCHEMES -> T9Segmenter(fuzzy, abbreviations = scheme == T9Set.ABBREVIATING)
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

private fun runPinyin(dataPath: String, setPath: String, resultPath: String, options: RunOptions, models: Models, layers: String?, packPath: String?): Int {
    val pack = packPath?.let { path -> File(path).useLines { WordPack.parse(it, path) } }
    // a data file each: its buffers are not to be shared
    val results = dealt(Halves.select(readSet(setPath), options.half), options.threads, {
        val data = loadData(dataPath)
        PinyinRun(
            data, options.segmenter(data), beam = options.beam, sentenceCount = options.sentences, layers = layers, pack = pack,
            reranker = models.reranker, refiner = models.refiner,
        )
    }) { hand -> run(hand) }
    File(resultPath).printWriter().use { out -> results.forEach { out.println(RunResultFormat.format(it)) } }
    return 0
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

private fun writeT9Set(setPath: String, outPath: String, out: Appendable): Int {
    val samples = readSet(setPath)
    val converted = T9Set.convert(samples)
    writeSet(outPath, "$setPath typed on the nine keys by `t9`; samples without one exact reading left out", converted)
    out.appendLine("${converted.size} of ${samples.size} samples typed on the nine keys")
    return 0
}

private fun writeShuangpinSet(scheme: String, setPath: String, outPath: String, out: Appendable): Int {
    val samples = readSet(setPath)
    val converted = ShuangpinSet.convert(samples, ShuangpinSet.SCHEMES.getValue(scheme))
    writeSet(outPath, "$setPath typed in $scheme 双拼 by `shuangpin`; samples without one exact reading left out", converted)
    out.appendLine("${converted.size} of ${samples.size} samples typed in $scheme")
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

private fun learn(dataPath: String, setPath: String, options: RunOptions, models: Models, packPath: String?, out: Appendable): Int {
    val data = loadData(dataPath)
    val pack = packPath?.let { path -> File(path).useLines { WordPack.parse(it, path) } }
    val rows = Learning(data, options.segmenter(data), models.reranker, models.refiner, pack).measure(readSet(setPath))
    out.appendLine(KeystrokeRun.HEADER)
    // null for a note (where the prior ended up): a half with no samples is still a row, of none
    rows.forEach { (label, outcomes) -> out.appendLine(if (outcomes == null) label else KeystrokeRun.row(label, outcomes)) }
    return 0
}

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
