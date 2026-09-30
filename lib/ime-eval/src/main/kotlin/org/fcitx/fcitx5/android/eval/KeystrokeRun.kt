/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Session
import org.fcitx.fcitx5.android.engine.session.Snapshot
import java.util.Locale

/**
 * Keys pressed per character of text (KSC), through an engine's [Session]: a user types the whole
 * input, then picks the expected text, or failing that the longest piece it starts with, turning
 * pages until one shows; a page turn and a pick are a key each. What top1 misses, this counts at
 * its cost: 你好吗 typed as `nihaoma` is 8 keys if it comes first, 9 if 你好 must be picked
 * before 吗.
 */
class KeystrokeRun(private val session: Session, private val maxPages: Int = MAX_PAGES) {

    /**
     * [keys] pressed for [sample]; [reached] false if its text could not be picked, [first] true
     * if it was the first candidate once typed.
     */
    data class Outcome(val sample: Sample, val keys: Int, val reached: Boolean, val first: Boolean = false)

    fun type(sample: Sample): Outcome {
        session.apply(if (sample.context.isEmpty()) Action.Reset else Action.Context(sample.context))
        var shown: Snapshot? = null
        for (c in sample.input) shown = session.apply(Action.Key(c))
        var keys = sample.input.length
        val first = shown?.candidates?.firstOrNull() == sample.expected
        var picked = ""
        var page = 0
        while (shown != null && shown.commit.isEmpty()) {
            val rest = sample.expected.substring(picked.length)
            val index = choose(shown.candidates, rest)
            if (index >= 0) {
                picked += shown.candidates[index]
                shown = session.apply(Action.Select(index))
                keys++
                page = 0
            } else if (shown.hasNextPage && page + 1 < maxPages) {
                shown = session.apply(Action.NextPage)
                keys++
                page++
            } else {
                return Outcome(sample, keys, false, first)
            }
        }
        return Outcome(sample, keys, shown?.commit == sample.expected, first)
    }

    /** The whole of [rest] if shown, else the longest candidate [rest] starts with, else -1. */
    private fun choose(candidates: List<String>, rest: String): Int {
        val whole = candidates.indexOf(rest)
        if (whole >= 0) return whole
        return candidates.indices.filter { candidates[it].isNotEmpty() && rest.startsWith(candidates[it]) }
            .maxByOrNull { candidates[it].length } ?: -1
    }

    companion object {
        const val MAX_PAGES = 10

        /** Per tag and in all: samples, how many were reached, top1, and KSC over those reached. */
        fun report(outcomes: List<Outcome>): String {
            val groups = outcomes.groupBy { it.sample.tag }.toList() + (Metrics.ALL to outcomes)
            return (listOf(HEADER) + groups.map { (group, members) -> row(group, members) }).joinToString("\n")
        }

        val HEADER: String = String.format(Locale.ROOT, "%-28s %6s %8s %7s %6s", "group", "n", "reached", "top1", "KSC")

        /** One line of [report]: KSC is `-` when nothing was reached. */
        fun row(label: String, outcomes: List<Outcome>): String {
            val reached = outcomes.filter { it.reached }
            val chars = reached.sumOf { it.sample.expected.codePointCount(0, it.sample.expected.length) }
            val ksc = if (chars == 0) "-" else String.format(Locale.ROOT, "%.3f", reached.sumOf { it.keys }.toDouble() / chars)
            val top1 = if (outcomes.isEmpty()) "-" else String.format(Locale.ROOT, "%.1f%%", 100.0 * outcomes.count { it.first } / outcomes.size)
            return String.format(Locale.ROOT, "%-28s %6d %8d %7s %6s", label, outcomes.size, reached.size, top1, ksc)
        }
    }
}
