/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import java.io.File
import kotlin.system.exitProcess

const val USAGE = "usage: score <set.tsv> <result.tsv> [<baseline-result.tsv>]"

fun main(args: Array<String>) {
    exitProcess(runCli(args, System.out, System.err))
}

/** @return the process exit code: 0, or 2 for a usage error */
fun runCli(args: Array<String>, out: Appendable, err: Appendable): Int {
    if (args.firstOrNull() != "score" || args.size !in 3..4) {
        err.appendLine(USAGE)
        return 2
    }
    val samples = File(args[1]).useLines { EvalSet.parse(it) }
    fun scoresOf(path: String) = Metrics.score(samples, File(path).useLines { RunResultFormat.parse(it) })
    out.appendLine(Report.table(scoresOf(args[2]), args.getOrNull(3)?.let(::scoresOf)))
    return 0
}
