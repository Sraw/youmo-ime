/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.data.CodeTable
import org.fcitx.fcitx5.android.engine.pinyin.Touch
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.fcitx.fcitx5.android.engine.table.TableDictionary
import org.fcitx.fcitx5.android.engine.table.TableLanguage
import org.fcitx.fcitx5.android.engine.table.TableOptions
import org.fcitx.fcitx5.android.engine.table.TableSession
import org.fcitx.fcitx5.android.engine.table.TableSlips
import java.util.Locale

/**
 * A table's codes tapped with a place (061-neighbour-table): each word of some text, cut and typed
 * as [TableRun] types it (its cheapest code; a pick, a space, nothing where it commits itself, or the
 * next word's first key where that commits it, 顶字), each key tapped as [TouchSim] taps the QWERTY
 * keys, clean or with one of the code's keys slipped onto a key next to it. Each word in a fresh
 * session, typed by someone who does not look: what was planned is done whatever the keys showed,
 * anything left over committed by a space. Typed with [slips] and without, the same taps. Or, [units]'
 * `prefix`, by one who stops where the word shows first: the shortest part of one of its codes (a whole
 * code too) that shows it first, then the next word's first key where that commits it (顶字), else a space;
 * a word that commits itself sooner, or never shows first, is left out.
 */
class TableSlipRun(
    private val table: CodeTable,
    private val options: TableOptions,
    private val slips: TableSlips.Costs,
    // the word model the app's tables weigh by (the low tier's), null: the table's order
    private val language: TableLanguage? = null,
) {

    private val run = TableRun(table, options)
    private val dictionary = TableDictionary(table)

    /** How a word went: as meant, another text, or nothing (the code dropped). */
    enum class Result { RIGHT, WRONG, NONE }

    /** One word, its [planned] typing, its code's keys tapped at [touches] (`input`: the code as tapped), and the next key's. */
    class Tapped(val planned: TableRun.Planned, val input: String, val touches: List<Touch?>, val nextTouch: Touch?)

    /** The words of [texts] tapped with [hand] (TouchSim's), [slip] or clean, by [seed]; typed by whole codes, or the [prefix] that shows each first. */
    fun units(texts: List<String>, hand: String, slip: Boolean, seed: Long, prefix: Boolean = false): List<Tapped> {
        val planned = texts.flatMap { run.plan(it) }.let { if (prefix) prefixed(it) else it }
        // each word its own sample, told apart by its place (TouchSim tags a slip's anew): it picks a slip by the expected text
        val codes = planned.mapIndexed { i, p -> Sample(p.code, "${p.word}#$i", "$i") }
        val nexts = planned.mapIndexed { i, p -> Sample((p.follow ?: 'a').toString(), "${p.word}#$i", "$i") }
        val tapped = TouchSim.convert(codes, "qwerty:$hand:${if (slip) "slip" else "clean"}:$seed").associateBy { it.expected }
        val nextTapped = TouchSim.convert(nexts, "qwerty:$hand:clean:${seed + 1}").associateBy { it.expected }
        return planned.mapIndexedNotNull { i, p ->
            val t = tapped["${p.word}#$i"] ?: return@mapIndexedNotNull null
            Tapped(p, t.input, t.touches.orEmpty(), nextTapped["${p.word}#$i"]?.touches?.firstOrNull())
        }
    }

    // the words of [planned] typed by the part of a code that shows each first (see the class)
    private fun prefixed(planned: List<TableRun.Planned>): List<TableRun.Planned> = planned.mapNotNull { p ->
        val part = parts.getOrPut(p.word) { shortestPart(p.word) }.ifEmpty { return@mapNotNull null }
        val next = p.follow?.takeIf { tops(part, p.word, it) }
        TableRun.Planned(p.word, part, TableRun.Typed(part.length + if (next == null) 1 else 0, first = true, auto = false), next, p.follow)
    }

    // a word's shortest part, "" where it has none: worked out once
    private val parts = HashMap<String, String>()

    // the shortest part of [word]'s codes (a whole one too) that shows it first, typed alone; "" where none does
    // before one commits it by itself (the whole-code plan's way)
    private fun shortestPart(word: String): String {
        val tried = run.codesOf(word).flatMap { code -> (1..code.length).map { code.substring(0, it) } }.distinct().sortedBy { it.length }
        for (part in tried) {
            val session = TableSession(dictionary, options, language = language)
            var shown: Snapshot? = null
            var committed = ""
            for (c in part) shown = session.apply(Action.Key(c)).also { committed += it.commit }
            if (committed == word) return ""
            if (committed.isEmpty() && shown?.candidates?.firstOrNull() == word) return part
        }
        return ""
    }

    /** How many of [units] are typed by a part of a code that is no whole code of their word. */
    fun partial(units: List<Tapped>) = units.count { it.planned.code !in run.codesOf(it.planned.word) }

    // whether [part] typed, then [next], commits [word] with [next] the next code's (顶字)
    private fun tops(part: String, word: String, next: Char): Boolean {
        val session = TableSession(dictionary, options, language = language)
        for (c in part) session.apply(Action.Key(c))
        val shown = session.apply(Action.Key(next))
        return shown.commit == word && shown.preedit == options.shown(next.toString())
    }

    /** What typing [unit] commits, with the slips read or not. */
    fun type(unit: Tapped, reading: Boolean): String {
        val p = unit.planned
        val session = TableSession(dictionary, options, language = language, slips = if (reading) slips else null)
        val committed = StringBuilder()
        var shown: Snapshot? = null
        unit.input.forEachIndexed { k, c ->
            shown = session.apply(Action.Key(c, touch = unit.touches.getOrNull(k))).also { committed.append(it.commit) }
        }
        var left = ""
        when {
            p.next != null -> {
                shown = session.apply(Action.Key(p.next, touch = unit.nextTouch))
                committed.append(shown.commit)
                left = p.next.toString()
            }
            // meant to commit itself: where it did not (a key read as its neighbour waits to be seen, or a code
            // slipped onto another), the next word's first key comes, as typed without looking
            p.typed.auto -> if (shown?.preedit.orEmpty().isNotEmpty() && p.follow != null) {
                shown = session.apply(Action.Key(p.follow, touch = unit.nextTouch))
                committed.append(shown.commit)
                left = p.follow.toString()
            }
            p.typed.first -> {
                shown = session.apply(Action.Select(0))
                committed.append(shown.commit)
            }
            else -> {
                val at = (0 until PICK_RANGE step options.pageSize).asSequence()
                    .flatMap { from -> session.candidates(from, options.pageSize).map { it.text } }
                    .indexOf(p.word)
                shown = session.apply(if (at >= 0) Action.Pick(at) else Action.Select(0))
                committed.append(shown.commit)
            }
        }
        // what is left but the next code's key: a space commits it (or drops it), then the next key's own code
        if (shown?.preedit.orEmpty().let { it.isNotEmpty() && it != options.shown(left) }) {
            committed.append(session.apply(Action.Select(0)).commit)
        }
        return committed.toString()
    }

    private fun result(word: String, typed: String) = when {
        typed == word -> Result.RIGHT
        typed.isEmpty() -> Result.NONE
        else -> Result.WRONG
    }

    /** Whether some part of [input] past its first key leads to no code (空码): what [slips] are read for. */
    private fun empty(input: String) = dead(input) > 0

    // where [input] first leads to no code, past its first key: its length so far; 0 where it never does
    private fun dead(input: String) = (2..input.length).firstOrNull { !dictionary.hasMatch(input.substring(0, it), options.matchingKey) } ?: 0

    /** Whether the key that made [input] lead nowhere came after a whole code: where the tables that top (顶字) commit that code. */
    private fun afterWhole(input: String): Boolean {
        val before = input.substring(0, dead(input) - 1)
        val range = table.prefixRange(before)
        return !range.isEmpty() && dictionary.codeLength(range.first) == before.length
    }

    /** Counts over clean and slipped taps. */
    data class Outcome(
        val clean: Int,
        val cleanChanged: Int,
        val slipped: Int,
        val slipValid: Int,
        val slipEmpty: Int,
        val off: Map<Result, Int>,
        val on: Map<Result, Int>,
        val recovered: Int,
        val broken: Int,
        val emptyOff: Map<Result, Int>,
        val emptyOn: Map<Result, Int>,
        // the empty codes by how they went without slips read and with: off to on
        val moves: Map<Pair<Result, Result>, Int>,
        // of them those made by a key after a whole code (顶字 where the table tops), off to on
        val afterWhole: Map<Pair<Result, Result>, Int> = emptyMap(),
    )

    fun measure(clean: List<Tapped>, slipped: List<Tapped>, misses: Appendable? = null): Outcome {
        var changed = 0
        for (u in clean) {
            val off = type(u, reading = false)
            val on = type(u, reading = true)
            if (off != on) {
                changed++
                misses?.appendLine("clean\t${u.planned.word}\t${u.planned.code}\t${u.input}\t$off\t$on")
            }
        }
        val off = HashMap<Result, Int>()
        val on = HashMap<Result, Int>()
        val emptyOff = HashMap<Result, Int>()
        val emptyOn = HashMap<Result, Int>()
        val moves = HashMap<Pair<Result, Result>, Int>()
        val whole = HashMap<Pair<Result, Result>, Int>()
        var valid = 0
        var emptyCount = 0
        var recovered = 0
        var broken = 0
        for (u in slipped) {
            val word = u.planned.word
            val a = result(word, type(u, reading = false))
            val typedOn = type(u, reading = true)
            val b = result(word, typedOn)
            off.merge(a, 1, Int::plus)
            on.merge(b, 1, Int::plus)
            if (empty(u.input)) {
                emptyCount++
                emptyOff.merge(a, 1, Int::plus)
                emptyOn.merge(b, 1, Int::plus)
                moves.merge(a to b, 1, Int::plus)
                if (afterWhole(u.input)) whole.merge(a to b, 1, Int::plus)
                if (b == Result.WRONG) misses?.appendLine("wrong\t$word\t${u.planned.code}\t${u.input}\t$typedOn")
            } else {
                valid++
            }
            if (a != Result.RIGHT && b == Result.RIGHT) recovered++
            if (a == Result.RIGHT && b != Result.RIGHT) broken++
        }
        return Outcome(clean.size, changed, slipped.size, valid, emptyCount, off, on, recovered, broken, emptyOff, emptyOn, moves, whole)
    }

    /** [units] as a set the device runner types (EngineEvalRunner): the keys of each word and how it ends, [perSample] keys a line or so, with their touches. */
    fun touchedSet(units: List<Tapped>, perSample: Int): List<String> {
        val lines = ArrayList<String>()
        val keys = StringBuilder()
        val touches = ArrayList<String>()
        fun flush() {
            lines += "$keys\t-\tslips\t\t${touches.joinToString(" ")}"
            keys.setLength(0)
            touches.clear()
        }
        for (u in units) {
            u.input.forEachIndexed { k, c ->
                keys.append(c)
                touches += u.touches.getOrNull(k)?.text() ?: "-"
            }
            val p = u.planned
            when {
                p.next != null || p.typed.auto -> Unit
                // a space: the runner times keys, which candidate it commits is no matter
                else -> {
                    keys.append(' ')
                    touches += "-"
                }
            }
            if (keys.length >= perSample) flush()
        }
        // the last words, short of a line: typed too (review F8207)
        if (keys.isNotEmpty()) flush()
        return lines
    }

    companion object {
        private const val PICK_RANGE = 50

        fun report(name: String, o: Outcome): String {
            fun pct(n: Int, of: Int) = if (of == 0) "-" else String.format(Locale.ROOT, "%.1f%%", 100.0 * n / of)
            fun row(m: Map<Result, Int>, of: Int) = Result.entries.joinToString(" / ") { "${it.name.lowercase()} ${m[it] ?: 0} (${pct(m[it] ?: 0, of)})" }
            fun moves(m: Map<Pair<Result, Result>, Int>) = m.entries.sortedBy { (k, _) -> k.first.ordinal * 3 + k.second.ordinal }
                .joinToString(", ") { (k, n) -> "${k.first.name.lowercase()}->${k.second.name.lowercase()} $n" }
            return listOf(
                "$name",
                "  clean: ${o.clean} words, changed by reading slips (exact -> other) ${o.cleanChanged}",
                "  slipped: ${o.slipped} words; still a code ${o.slipValid}, made an empty code ${o.slipEmpty}",
                "    all   off: ${row(o.off, o.slipped)}",
                "          on:  ${row(o.on, o.slipped)}",
                "    empty off: ${row(o.emptyOff, o.slipEmpty)}",
                "          on:  ${row(o.emptyOn, o.slipEmpty)}",
                "    empty, off -> on: " + moves(o.moves),
                "      of them after a whole code (${o.afterWhole.values.sum()}): " + moves(o.afterWhole),
                "    recovered ${o.recovered} (${pct(o.recovered, o.slipped)} of slips, ${pct(o.recovered, o.slipEmpty)} of empty codes), broken ${o.broken}",
            ).joinToString("\n")
        }
    }
}
