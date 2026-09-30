/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.lattice.Penalties
import org.fcitx.fcitx5.android.engine.pinyin.Fuzzy
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinSegmenter
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.util.Locale
import kotlin.system.exitProcess

val USAGE = """usage: score <set.tsv> <result.tsv> [<baseline-result.tsv>] [--half <half>]
       pinyin <pinyin.data> <set.tsv> <result.tsv> [--scheme <scheme>] [--fuzzy all|<pair>,...] [--half <half>]
       shuangpin <scheme> <set.tsv> <shuangpin-set.tsv>
       slips <set.tsv> <slip-set.tsv>
       tune <pinyin.data> <set.tsv> <slip-set.tsv>
schemes: ${ShuangpinSet.SCHEMES.keys.joinToString(" ")}
pairs: ${Fuzzy.entries.joinToString(" ") { it.name.lowercase() }}
halves: ${Halves.NAMES.joinToString(" ")}"""

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
    val ok = (half == null || half in Halves.NAMES) && (scheme == null || scheme in ShuangpinSet.SCHEMES) &&
        (a.options["fuzzy"] == null || fuzzy != null)
    return when {
        !ok -> usage(err)
        a.has("score", 3..4, "half") -> score(p[1], p[2], p.getOrNull(3), half, out)
        a.has("pinyin", 4..4, "scheme", "fuzzy", "half") -> runPinyin(p[1], p[2], p[3], scheme, fuzzy.orEmpty(), half)
        a.has("shuangpin", 4..4) && p[1] in ShuangpinSet.SCHEMES -> writeShuangpinSet(p[1], p[2], p[3], out)
        a.has("slips", 3..3) -> writeSlipSet(p[1], p[2], out)
        a.has("tune", 4..4) -> tune(p[1], p[2], p[3], out)
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

private fun loadData(path: String): PinyinData = RandomAccessFile(path, "r").use {
    PinyinData.load(it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()))
}

private fun score(setPath: String, resultPath: String, baselinePath: String?, half: String?, out: Appendable): Int {
    val samples = Halves.select(readSet(setPath), half)
    fun scoresOf(path: String) = Metrics.score(samples, File(path).useLines { RunResultFormat.parse(it) })
    out.appendLine(Report.table(scoresOf(resultPath), baselinePath?.let(::scoresOf)))
    return 0
}

private fun runPinyin(dataPath: String, setPath: String, resultPath: String, scheme: String?, fuzzy: Set<Fuzzy>, half: String?): Int {
    val segmenter = scheme?.let { ShuangpinSegmenter(ShuangpinSet.SCHEMES.getValue(it), fuzzy) } ?: PinyinSegmenter(fuzzy)
    val results = PinyinRun(loadData(dataPath), segmenter).run(Halves.select(readSet(setPath), half))
    File(resultPath).printWriter().use { out -> results.forEach { out.println(RunResultFormat.format(it)) } }
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
    fun header(label: String, point: Tuning.Point) = out.appendLine(
        String.format(Locale.ROOT, "%-24s", label) +
            (Tuning.COLUMNS.zip(point.sizes) { c, n -> "$c/$n" } + "mean").joinToString("") { String.format(Locale.ROOT, "%17s", it) },
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
    return 0
}
