/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinSegmenter
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import kotlin.system.exitProcess

val USAGE = """usage: score <set.tsv> <result.tsv> [<baseline-result.tsv>]
       pinyin <pinyin.data> <set.tsv> <result.tsv> [<shuangpin scheme>]
       shuangpin <scheme> <set.tsv> <shuangpin-set.tsv>
schemes: ${ShuangpinSet.SCHEMES.keys.joinToString(" ")}"""

fun main(args: Array<String>) {
    exitProcess(runCli(args, System.out, System.err))
}

/** @return the process exit code: 0, or 2 for a usage error */
fun runCli(args: Array<String>, out: Appendable, err: Appendable): Int {
    when {
        args.firstOrNull() == "pinyin" && args.size in 4..5 && args.getOrNull(4).let { it == null || it in ShuangpinSet.SCHEMES } ->
            return runPinyin(args[1], args[2], args[3], args.getOrNull(4))
        args.firstOrNull() == "shuangpin" && args.size == 4 && args[1] in ShuangpinSet.SCHEMES ->
            return writeShuangpinSet(args[1], args[2], args[3], out)
        args.firstOrNull() != "score" || args.size !in 3..4 -> {
            err.appendLine(USAGE)
            return 2
        }
    }
    val samples = File(args[1]).useLines { EvalSet.parse(it) }
    fun scoresOf(path: String) = Metrics.score(samples, File(path).useLines { RunResultFormat.parse(it) })
    out.appendLine(Report.table(scoresOf(args[2]), args.getOrNull(3)?.let(::scoresOf)))
    return 0
}

private fun runPinyin(dataPath: String, setPath: String, resultPath: String, scheme: String?): Int {
    val data = RandomAccessFile(dataPath, "r").use {
        PinyinData.load(it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()))
    }
    val segmenter = scheme?.let { ShuangpinSegmenter(ShuangpinSet.SCHEMES.getValue(it)) } ?: PinyinSegmenter()
    val results = PinyinRun(data, segmenter).run(File(setPath).useLines { EvalSet.parse(it) })
    File(resultPath).printWriter().use { out -> results.forEach { out.println(RunResultFormat.format(it)) } }
    return 0
}

private fun writeShuangpinSet(scheme: String, setPath: String, outPath: String, out: Appendable): Int {
    val samples = File(setPath).useLines { EvalSet.parse(it) }
    val converted = ShuangpinSet.convert(samples, ShuangpinSet.SCHEMES.getValue(scheme))
    File(outPath).printWriter().use { w ->
        w.println("# $setPath typed in $scheme 双拼 by `shuangpin`; samples without one exact reading left out")
        converted.forEach { w.println("${it.input}\t${it.expected}\t${it.tag}") }
    }
    out.appendLine("${converted.size} of ${samples.size} samples typed in $scheme")
    return 0
}
