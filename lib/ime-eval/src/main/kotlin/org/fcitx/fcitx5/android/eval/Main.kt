/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.data.PinyinData
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import kotlin.system.exitProcess

const val USAGE = """usage: score <set.tsv> <result.tsv> [<baseline-result.tsv>]
       pinyin <pinyin.data> <set.tsv> <result.tsv>"""

fun main(args: Array<String>) {
    exitProcess(runCli(args, System.out, System.err))
}

/** @return the process exit code: 0, or 2 for a usage error */
fun runCli(args: Array<String>, out: Appendable, err: Appendable): Int {
    when {
        args.firstOrNull() == "pinyin" && args.size == 4 -> return runPinyin(args[1], args[2], args[3])
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

private fun runPinyin(dataPath: String, setPath: String, resultPath: String): Int {
    val data = RandomAccessFile(dataPath, "r").use {
        PinyinData.load(it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()))
    }
    val results = PinyinRun(data).run(File(setPath).useLines { EvalSet.parse(it) })
    File(resultPath).printWriter().use { out -> results.forEach { out.println(RunResultFormat.format(it)) } }
    return 0
}
