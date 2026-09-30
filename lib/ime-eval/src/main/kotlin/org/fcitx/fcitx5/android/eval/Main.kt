/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.data.CodeTable
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.lattice.Penalties
import org.fcitx.fcitx5.android.engine.pinyin.Fuzzy
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.Segmenter
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinSegmenter
import org.fcitx.fcitx5.android.engine.rerank.Reranker
import org.fcitx.fcitx5.android.engine.rerank.SentenceModel
import org.fcitx.fcitx5.android.engine.session.PinyinSession
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.util.Locale
import kotlin.system.exitProcess

val USAGE = """usage: score <set.tsv> <result.tsv> [<baseline-result.tsv>] [--half <half>]
       pinyin <pinyin.data> <set.tsv> <result.tsv> [--scheme <scheme>] [--fuzzy all|<pair>,...] [--half <half>] [--neighbours on|off] [--rerank <model.safetensors>]
       lm <model.safetensors> <context> <text>...
       shuangpin <scheme> <set.tsv> <shuangpin-set.tsv>
       slips <set.tsv> <slip-set.tsv>
       tune <pinyin.data> <set.tsv> <slip-set.tsv>
       ksc <pinyin.data> <set.tsv> [--scheme <scheme>] [--fuzzy all|<pair>,...] [--half <half>]
       learn <pinyin.data> <set.tsv> [--scheme <scheme>] [--fuzzy all|<pair>,...]
       table <table.data> <set.tsv> [--preset <preset>]
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
    val ok = listOf(
        half == null || half in Halves.NAMES,
        scheme == null || scheme in ShuangpinSet.SCHEMES,
        a.options["fuzzy"] == null || fuzzy != null,
        preset == null || preset in TableRun.PRESETS,
        // 双拼 reads no slips
        neighbours == null || neighbours in ON_OFF && scheme == null,
    ).all { it }
    return when {
        !ok -> usage(err)
        a.has("score", 3..4, "half") -> score(p[1], p[2], p.getOrNull(3), half, out)
        a.has("pinyin", 4..4, "scheme", "fuzzy", "half", "neighbours", "rerank") ->
            runPinyin(p[1], p[2], p[3], scheme, fuzzy.orEmpty(), half, neighbours == "on", a.options["rerank"])
        a.has("lm", 4..Int.MAX_VALUE) -> lm(p[1], p[2], p.drop(3), out)
        a.has("shuangpin", 4..4) && p[1] in ShuangpinSet.SCHEMES -> writeShuangpinSet(p[1], p[2], p[3], out)
        a.has("slips", 3..3) -> writeSlipSet(p[1], p[2], out)
        a.has("tune", 4..4) -> tune(p[1], p[2], p[3], out)
        a.has("ksc", 3..3, "scheme", "fuzzy", "half") -> ksc(p[1], p[2], scheme, fuzzy.orEmpty(), half, out)
        a.has("learn", 3..3, "scheme", "fuzzy") -> learn(p[1], p[2], scheme, fuzzy.orEmpty(), out)
        a.has("table", 3..3, "preset") -> table(p[1], p[2], preset ?: "plain", out)
        else -> usage(err)
    }
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
    rerankPath: String?,
): Int {
    val reranker = rerankPath?.let { Reranker(SentenceModel.load(mapFile(it), unpack = true)) }
    val results = PinyinRun(loadData(dataPath), segmenter(scheme, fuzzy, neighbours), reranker = reranker).run(Halves.select(readSet(setPath), half))
    File(resultPath).printWriter().use { out -> results.forEach { out.println(RunResultFormat.format(it)) } }
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

private fun ksc(dataPath: String, setPath: String, scheme: String?, fuzzy: Set<Fuzzy>, half: String?, out: Appendable): Int {
    val session = PinyinSession(loadData(dataPath), segmenter(scheme, fuzzy), spell = scheme != null)
    val run = KeystrokeRun(session)
    out.appendLine(KeystrokeRun.report(Halves.select(readSet(setPath), half).map { run.type(it) }))
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

private val ON_OFF = setOf("on", "off")

// about 2,000 of 五笔's 100,000 entries
private const val ENTRY_STEP = 50
