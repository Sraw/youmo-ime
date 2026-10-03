/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.data.CodeTable
import org.fcitx.fcitx5.android.engine.data.DataFormatException
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.lattice.LayerPrior
import org.fcitx.fcitx5.android.engine.user.WordPack
import org.fcitx.fcitx5.android.engine.lattice.Penalties
import org.fcitx.fcitx5.android.engine.libime.LibimeFiles
import org.fcitx.fcitx5.android.engine.pinyin.Fuzzy
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.Segmenter
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinSegmenter
import org.fcitx.fcitx5.android.engine.remote.HttpRemoteModel
import org.fcitx.fcitx5.android.engine.remote.RemoteRefiner
import org.fcitx.fcitx5.android.engine.remote.ServerAddress
import org.fcitx.fcitx5.android.engine.remote.ServerKey
import org.fcitx.fcitx5.android.engine.rerank.Reranker
import org.fcitx.fcitx5.android.engine.rerank.SentenceRefiner
import org.fcitx.fcitx5.android.engine.rerank.SentenceModel
import org.fcitx.fcitx5.android.engine.session.PinyinSession
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URL
import java.nio.channels.FileChannel
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

val USAGE = """usage: score <set.tsv> <result.tsv> [<baseline-result.tsv>] [--half <half>]
       pinyin <pinyin.data> <set.tsv> <result.tsv> [--scheme <scheme>] [--fuzzy all|<pair>,...] [--half <half>] [--neighbours on|off] [--rerank <model.safetensors>] [--refine <model.safetensors>] [--weight <rerank>[,<refine>]] [--remote <url>] [--remote-timeout <ms>] [--penalty <p>] [--layers <name>=<log10>,...] [--pack <file.words>] [--threads <n>]
       predict <pinyin.data> <predict.tsv> [--offers <out.tsv>] [--threads <n>]
       sentences <pinyin.data> <set.tsv> <out.tsv> [--neighbours on|off] [--threads <n>]
       lm <model.safetensors> <context> <text>...
       shuangpin <scheme> <set.tsv> <shuangpin-set.tsv>
       slips <set.tsv> <slip-set.tsv>
       tune <pinyin.data> <set.tsv> <slip-set.tsv>
       ksc <pinyin.data> <set.tsv> [--scheme <scheme>] [--fuzzy all|<pair>,...] [--half <half>] [--rerank <model.safetensors>] [--refine <model.safetensors>] [--weight <rerank>[,<refine>]] [--remote <url>] [--remote-timeout <ms>] [--penalty <p>] [--threads <n>]
       learn <pinyin.data> <set.tsv> [--scheme <scheme>] [--fuzzy all|<pair>,...]
       table <table.data> <set.tsv> [--preset <preset>]
       libime pinyin|history|table <file>
schemes: ${ShuangpinSet.SCHEMES.keys.joinToString(" ")}
pairs: ${Fuzzy.entries.joinToString(" ") { it.name.lowercase() }}
halves: ${Halves.NAMES.joinToString(" ")}
presets: ${TableRun.PRESETS.keys.joinToString(" ")}"""

fun main(args: Array<String>) {
    exitProcess(runCli(args, System.out, System.err))
}

/** Arguments split into the positional ones and `--name value` options. */
private class Arguments(args: Array<String>) {
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

    fun has(command: String, count: IntRange, vararg allowed: String) =
        valid && positional.firstOrNull() == command && positional.size in count && options.keys.all { it in allowed }
}

/** @return the process exit code: 0, or 2 for a usage error */
fun runCli(args: Array<String>, out: Appendable, err: Appendable): Int {
    val a = Arguments(args)
    val p = a.positional
    val half = a.options["half"]
    val scheme = a.options["scheme"]
    val fuzzy = a.options["fuzzy"]?.let(::fuzzyPairs)
    val preset = a.options["preset"]
    val neighbours = a.options["neighbours"]
    val threads = a.options["threads"]?.toIntOrNull() ?: Runtime.getRuntime().availableProcessors()
    return when {
        !optionsValid(a.options, fuzzy) -> usage(err)
        a.has("score", 3..4, "half") -> score(p[1], p[2], p.getOrNull(3), half, out)
        a.has("pinyin", 4..4, "scheme", "fuzzy", "half", "neighbours", "rerank", "refine", "weight", *REMOTE, "layers", "pack", "threads") ->
            runPinyin(p[1], p[2], p[3], scheme, fuzzy.orEmpty(), half, neighbours == "on", Models(a.options), a.options["layers"], a.options["pack"], threads)
        a.has("predict", 3..3, "threads", "offers") -> predict(p[1], p[2], threads, a.options["offers"], out)
        a.has("sentences", 4..4, "neighbours", "threads") -> sentences(p[1], p[2], p[3], neighbours == "on", threads)
        a.has("lm", 4..Int.MAX_VALUE) -> lm(p[1], p[2], p.drop(3), out)
        a.has("shuangpin", 4..4) && p[1] in ShuangpinSet.SCHEMES -> writeShuangpinSet(p[1], p[2], p[3], out)
        a.has("slips", 3..3) -> writeSlipSet(p[1], p[2], out)
        a.has("tune", 4..4) -> tune(p[1], p[2], p[3], out)
        a.has("ksc", 3..3, "scheme", "fuzzy", "half", "rerank", "refine", "weight", *REMOTE, "threads") ->
            ksc(p[1], p[2], scheme, fuzzy.orEmpty(), half, Models(a.options), threads, out)
        a.has("learn", 3..3, "scheme", "fuzzy") -> learn(p[1], p[2], scheme, fuzzy.orEmpty(), out)
        a.has("table", 3..3, "preset") -> table(p[1], p[2], preset ?: "plain", out)
        a.has("libime", 3..3) && p[1] in LIBIME_KINDS -> libime(p[1], p[2], out, err)
        else -> usage(err)
    }
}

private fun optionsValid(options: Map<String, String>, fuzzy: Set<Fuzzy>?): Boolean {
    val scheme = options["scheme"]
    val neighbours = options["neighbours"]
    return listOf(
        options["half"].let { it == null || it in Halves.NAMES },
        scheme == null || scheme in ShuangpinSet.SCHEMES,
        options["fuzzy"] == null || fuzzy != null,
        options["preset"].let { it == null || it in TableRun.PRESETS },
        options["threads"].let { it == null || it.toIntOrNull()?.let { n -> n > 0 } == true },
        options["weight"].let { it == null || weights(it) != null },
        remoteValid(options),
        // 双拼 reads no slips
        neighbours == null || neighbours in ON_OFF && scheme == null,
    ).all { it }
}

/** The options for the user's server, each absent or good. */
private fun remoteValid(options: Map<String, String>) = listOf(
    options["remote"].let { it == null || ServerAddress.check(it, plainLoopback = true) is ServerAddress.Valid },
    // an hour at most: in nanoseconds, more would overflow added to a clock's reading
    options["remote-timeout"].let { it == null || it.toLongOrNull()?.let { ms -> ms in 1..MAX_REMOTE_TIMEOUT } == true },
    options["penalty"].let { it == null || it.toFloatOrNull()?.let { p -> p >= 0 && p.isFinite() } == true },
).all { it }

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

/** `<rerank>[,<refine>]`: the sentence models' weights against the decoder, the refiner's the reranker's if not given. */
private fun weights(spec: String): Pair<Float, Float>? {
    val parts = spec.split(',').map { it.toFloatOrNull()?.takeIf { w -> w >= 0 && w.isFinite() } ?: return null }
    return when (parts.size) {
        1 -> parts[0] to parts[0]
        2 -> parts[0] to parts[1]
        else -> null
    }
}

private val REMOTE = arrayOf("remote", "remote-timeout", "penalty")
private const val MAX_REMOTE_TIMEOUT = 3_600_000L

/**
 * The sentence models: the one weighing the readings at each key, and the one while the user
 * pauses, then the user's server if one is given (`cloud/server.py`; its token, if any, from
 * `YOUMO_TOKEN`, and over HTTPS the key it printed from `YOUMO_KEY`). Loaded once and shared by the threads; each reranker keeps its own state.
 */
private class Models(options: Map<String, String>) {
    private val rerankPath = options["rerank"]
    private val refinePath = options["refine"]
    private val weights = options["weight"]?.let(::weights) ?: (Reranker.WEIGHT to Reranker.WEIGHT)
    private val remote = options["remote"]?.let { (ServerAddress.check(it, plainLoopback = true) as ServerAddress.Valid).url }
    // measuring what the server knows, not how fast it is: by default it is waited for
    private val remoteTimeout = TimeUnit.MILLISECONDS.toNanos(options["remote-timeout"]?.toLong() ?: 120_000)
    private val penalty = options["penalty"]?.toFloat() ?: RemoteRefiner.PENALTY
    private val executor by lazy {
        Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
    }

    // floats, as the app has the smaller: the same scores, sooner
    private val rerank by lazy { rerankPath?.let { SentenceModel.load(mapFile(it), unpack = true) } }
    private val refine by lazy { refinePath?.let { SentenceModel.load(mapFile(it), unpack = true) } }

    /** Makes a reranker: none when there is no model. */
    val reranker: (() -> Reranker)? get() = rerank?.let { model -> { Reranker(model, weights.first) } }

    private val local: (() -> Reranker)? get() = refine?.let { model -> { Reranker(model, weights.second, limit = Reranker.REFINE_LIMIT) } }

    val refiner: (() -> SentenceRefiner)? get() {
        val local = local
        val url = remote ?: return local
        val model = remoteModel(url)
        return { RemoteRefiner(local?.invoke(), model, remoteTimeout, WAIT, penalty) }
    }

    // the token, and over HTTPS the server's key (as server.py prints it), from the environment:
    // not on a command line, where other users of the machine can read them
    private fun remoteModel(url: URL) = HttpRemoteModel(
        url, System.getenv("YOUMO_TOKEN"), executor, System.getenv("YOUMO_KEY")?.let(ServerKey::normalized),
        timeoutMillis = TimeUnit.NANOSECONDS.toMillis(remoteTimeout).toInt() + SOCKET_SLACK,
        // every question asked: a server that missed one would otherwise drop the next 30 s of them, unseen
        backoff = 0,
    )

    private companion object {
        val WAIT = TimeUnit.MILLISECONDS.toNanos(50)
        const val SOCKET_SLACK = 1000
    }
}

private fun predict(dataPath: String, setPath: String, threads: Int, offersPath: String?, out: Appendable): Int {
    val samples = File(setPath).useLines { PredictRun.parse(it) }
    val offers = dealt(samples, threads, { PredictRun(loadData(dataPath)) }) { hand -> hand.map { offers(it.context) } }
    // what was offered, for a look at the misses: context<TAB>next<TAB>offers, space-separated
    offersPath?.let { path ->
        File(path).printWriter().use { w -> samples.zip(offers).forEach { (s, o) -> w.println("${s.context}\t${s.next}\t${o.joinToString(" ")}") } }
    }
    out.appendLine(PredictRun.score(samples, offers).toString())
    return 0
}

private fun score(setPath: String, resultPath: String, baselinePath: String?, half: String?, out: Appendable): Int {
    val samples = Halves.select(readSet(setPath), half)
    fun scoresOf(path: String) = Metrics.score(samples, File(path).useLines { RunResultFormat.parse(it) })
    out.appendLine(Report.table(scoresOf(resultPath), baselinePath?.let(::scoresOf)))
    return 0
}

private fun segmenter(scheme: String?, fuzzy: Set<Fuzzy>, neighbours: Boolean = false): Segmenter =
    scheme?.let { ShuangpinSegmenter(ShuangpinSet.SCHEMES.getValue(it), fuzzy) } ?: PinyinSegmenter(fuzzy, neighbours = neighbours)

private fun runPinyin(
    dataPath: String,
    setPath: String,
    resultPath: String,
    scheme: String?,
    fuzzy: Set<Fuzzy>,
    half: String?,
    neighbours: Boolean,
    models: Models,
    layers: String?,
    packPath: String?,
    threads: Int,
): Int {
    val pack = packPath?.let { path -> File(path).useLines { WordPack.parse(it, path) } }
    // a data file each: its buffers are not to be shared
    val results = dealt(Halves.select(readSet(setPath), half), threads, {
        val data = loadData(dataPath)
        val prior = layers?.let { LayerPrior.parse(data.layers, it) }
        PinyinRun(data, segmenter(scheme, fuzzy, neighbours), prior = prior, pack = pack, reranker = models.reranker, refiner = models.refiner)
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
private fun sentences(dataPath: String, setPath: String, outPath: String, neighbours: Boolean, threads: Int): Int {
    val lines = dealt(readSet(setPath), threads, { PinyinRun(loadData(dataPath), segmenter(null, emptySet(), neighbours)) }) { hand ->
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
    val model = SentenceModel.load(mapFile(modelPath))
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
        samples.forEach { w.println("${it.input}\t${it.expected}\t${it.tag}") }
    }
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

private fun tune(dataPath: String, setPath: String, slipsPath: String, out: Appendable): Int {
    val tuning = Tuning(loadData(dataPath), readSet(setPath), readSet(slipsPath))
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

private fun ksc(
    dataPath: String,
    setPath: String,
    scheme: String?,
    fuzzy: Set<Fuzzy>,
    half: String?,
    models: Models,
    threads: Int,
    out: Appendable,
): Int {
    // a session per sample, rerankers and all: one keeps what it read for the next input, which
    // would make a sample's result hang on the one before it, and so on how they are dealt
    val outcomes = dealt(Halves.select(readSet(setPath), half), threads, { loadData(dataPath) to segmenter(scheme, fuzzy) }) { hand ->
        val (data, segmenter) = this
        hand.map { sample ->
            val session = PinyinSession(
                data, segmenter, spell = scheme != null, reranker = models.reranker?.invoke(), refiner = models.refiner?.invoke(),
            )
            KeystrokeRun(session).type(sample)
        }
    }
    out.appendLine(KeystrokeRun.report(outcomes))
    return 0
}

private fun learn(dataPath: String, setPath: String, scheme: String?, fuzzy: Set<Fuzzy>, out: Appendable): Int {
    val rows = Learning(loadData(dataPath), segmenter(scheme, fuzzy)).measure(readSet(setPath))
    out.appendLine(KeystrokeRun.HEADER)
    rows.forEach { (label, outcomes) -> out.appendLine(KeystrokeRun.row(label, outcomes)) }
    return 0
}

private fun table(dataPath: String, setPath: String, preset: String, out: Appendable): Int {
    val run = TableRun(CodeTable.load(mapFile(dataPath)), TableRun.PRESETS.getValue(preset))
    val texts = readSet(setPath).map { it.expected }.distinct()
    val total = texts.fold(TableRun.Outcome.ZERO) { sum, text -> sum + run.type(text) }
    out.appendLine(TableRun.report(total, run.entries(ENTRY_STEP)))
    return 0
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
