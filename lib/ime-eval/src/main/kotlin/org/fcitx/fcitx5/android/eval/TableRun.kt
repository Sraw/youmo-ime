/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.data.CodeTable
import org.fcitx.fcitx5.android.engine.rerank.SentenceRefiner
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.table.TableDictionary
import org.fcitx.fcitx5.android.engine.table.TableLanguage
import org.fcitx.fcitx5.android.engine.table.TableOptions
import org.fcitx.fcitx5.android.engine.table.TableSession
import java.util.Locale

/**
 * A table input method typed by someone who knows every code in it. Text is cut into the words
 * the table has so as to take the fewest keys, each word typed by whichever of its codes costs
 * the fewest: the code, a page turn per page, and a pick unless the engine commits it by itself.
 * A first candidate needs no pick when the next word's first key commits it (顶字). Each word is
 * typed afresh, so what the engine learns from picks plays no part: this measures the table and
 * the engine's rules, not a user's history.
 *
 * [entries] types the table's own entries by their own codes: how often an entry is first, on
 * the first page, or cannot be reached (another candidate commits itself first).
 */
class TableRun(private val table: CodeTable, private val options: TableOptions) {

    private val dictionary = TableDictionary(table)

    // text to its codes, shortest first; entries under a marker (拼音, 构词) are not typed
    private val codes = HashMap<String, MutableList<String>>().also { map ->
        for (i in 0 until table.size) {
            val code = table.code(i)
            if (code.all { it in dictionary.keys }) map.getOrPut(table.text(i)) { ArrayList() } += code
        }
        map.values.forEach { list -> list.sortBy { it.length } }
    }
    private val longestWord = codes.keys.maxOfOrNull { it.codePointCount(0, it.length) } ?: 0

    /** How one code went: [keys] pressed, [first] the word came first, [auto] it committed itself. */
    data class Typed(val keys: Int, val first: Boolean, val auto: Boolean)

    /** Totals over some text. */
    data class Outcome(
        val chars: Int,
        val missing: Int,
        val words: Int,
        val keys: Int,
        val first: Int,
        val auto: Int,
        val spared: Int,
        val keyNanos: Long,
        val actions: Int,
        val slowestKeyNanos: Long,
    ) {
        operator fun plus(o: Outcome) = Outcome(
            chars = chars + o.chars, missing = missing + o.missing, words = words + o.words, keys = keys + o.keys,
            first = first + o.first, auto = auto + o.auto, spared = spared + o.spared, keyNanos = keyNanos + o.keyNanos,
            actions = actions + o.actions, slowestKeyNanos = maxOf(slowestKeyNanos, o.slowestKeyNanos),
        )

        companion object {
            val ZERO = Outcome(
                chars = 0, missing = 0, words = 0, keys = 0, first = 0, auto = 0, spared = 0, keyNanos = 0, actions = 0, slowestKeyNanos = 0,
            )
        }
    }

    // what [type] reports of the actions timed: it starts them afresh, [entries] only adds to them
    private var nanos = 0L
    private var actions = 0
    private var slowest = 0L

    private fun TableSession.timed(action: Action) = System.nanoTime().let { start ->
        apply(action).also {
            val t = System.nanoTime() - start
            nanos += t
            actions++
            slowest = maxOf(slowest, t)
        }
    }

    /** Types [code] into a fresh session, then picks [word]; null if it cannot be picked. */
    fun typeCode(code: String, word: String): Typed? {
        val session = TableSession(dictionary, options)
        var committed = ""
        var shown = session.timed(Action.Key(code[0]))
        committed += shown.commit
        for (c in code.substring(1)) {
            shown = session.timed(Action.Key(c))
            committed += shown.commit
        }
        if (committed.isNotEmpty()) return if (committed == word) Typed(code.length, first = true, auto = true) else null
        var keys = code.length
        for (page in 0 until MAX_PAGES) {
            val index = shown.candidates.indexOf(word)
            if (index >= 0) return Typed(keys + 1, first = page == 0 && index == 0, auto = false)
            if (!shown.hasNextPage) return null
            shown = session.timed(Action.NextPage)
            keys++
        }
        return null
    }

    // a word is typed by the same code wherever it is: worked out once
    private val cheapest = HashMap<String, Pair<String, Typed>?>()

    /** The cheapest code for [word] and how it went, or null if none of its codes reaches it. */
    private fun typeWord(word: String): Pair<String, Typed>? {
        val codes = codes[word] ?: return null
        // not getOrPut, which takes a stored null (out of reach) for none and types the word again
        if (word in cheapest) return cheapest[word]
        return codes.mapNotNull { code -> typeCode(code, word)?.let { code to it } }
            .minWithOrNull(compareBy<Pair<String, Typed>> { it.second.keys }.thenBy { !it.second.first })
            .also { cheapest[word] = it }
    }

    // as cheapest: by the code, the word and the key after it
    private val commits = HashMap<Triple<String, String, Char>, Boolean>()

    /** Whether [word], first after [code], is committed by [next] starting the next code. */
    private fun commitsOnTo(code: String, word: String, next: Char): Boolean = commits.getOrPut(Triple(code, word, next)) {
        val session = TableSession(dictionary, options)
        for (c in code) session.timed(Action.Key(c))
        val shown = session.timed(Action.Key(next))
        shown.commit == word && shown.preedit == next.toString()
    }

    /** A cut of some text: the character offsets it is cut at, and each piece's word as typed (null: missing). */
    private class Cut(val at: IntArray, val path: List<Int>, val step: Array<Pair<String, Typed>?>)

    private fun cut(text: String): Cut {
        // where each character starts, and the text's end
        val at = IntArray(text.codePointCount(0, text.length) + 1)
        for (k in 1 until at.size) at[k] = text.offsetByCodePoints(at[k - 1], 1)
        val n = at.size - 1
        // the fewest keys to type the first j characters, a character the table lacks weighing
        // more than any word, and what the last step there typed (null: a missing character)
        val cost = LongArray(n + 1)
        val step = arrayOfNulls<Pair<String, Typed>>(n + 1)
        val from = IntArray(n + 1)
        for (j in 1..n) {
            cost[j] = cost[j - 1] + MISSING
            from[j] = j - 1
            for (len in 1..minOf(longestWord, j)) {
                val typed = typeWord(text.substring(at[j - len], at[j])) ?: continue
                if (cost[j - len] + typed.second.keys < cost[j]) {
                    cost[j] = cost[j - len] + typed.second.keys
                    from[j] = j - len
                    step[j] = typed
                }
            }
        }
        return Cut(at, generateSequence(n) { if (it == 0) null else from[it] }.toList().asReversed(), step)
    }

    fun type(text: String): Outcome {
        nanos = 0
        actions = 0
        slowest = 0
        val cut = cut(text)
        val path = cut.path
        var missing = 0
        var words = 0
        var keys = 0
        var first = 0
        var auto = 0
        var spared = 0
        for (k in 1 until path.size) {
            val typed = cut.step[path[k]]
            if (typed == null) {
                missing++
                continue
            }
            words++
            keys += typed.second.keys
            if (typed.second.first) first++
            if (typed.second.auto) auto++
            val next = if (k + 1 < path.size) cut.step[path[k + 1]] else null
            if (next != null && spares(typed, text.substring(cut.at[path[k - 1]], cut.at[path[k]]), next.first[0])) {
                keys--
                spared++
            }
        }
        return Outcome(cut.at.size - 1, missing, words, keys, first, auto, spared, nanos, actions, slowest)
    }

    /** Whether [word], picked first by [typed], needs no pick before a code starting with [next]. */
    private fun spares(typed: Pair<String, Typed>, word: String, next: Char) =
        typed.second.first && !typed.second.auto && commitsOnTo(typed.first, word, next)

    /**
     * The words of [text], cut as [type] cuts it, whose cheapest code shows more than one candidate
     * (a 重码 to pick from), each typed into a fresh session after the text before it: how many there
     * are, and how often the word comes first with each of [languages] (null: the table's order).
     * A language with a refiner is given the slices a pause gives it.
     */
    fun homographs(text: String, languages: List<TableLanguage?>): Pair<Int, IntArray> {
        val cut = cut(text)
        val path = cut.path
        var n = 0
        val first = IntArray(languages.size)
        for (k in 1 until path.size) {
            // a word the table lacks, or one its code commits by itself: nothing to pick
            val (code, _) = cut.step[path[k]]?.takeUnless { it.second.auto } ?: continue
            val word = text.substring(cut.at[path[k - 1]], cut.at[path[k]])
            val before = text.substring(0, cut.at[path[k - 1]])
            val shown = languages.map { language ->
                val session = TableSession(dictionary, options, language = language)
                session.apply(Action.Context(before))
                var s = session.apply(Action.Key(code[0]))
                for (c in code.substring(1)) s = session.apply(Action.Key(c))
                var slices = 0
                while (s.refines && slices++ <= SentenceRefiner.SLICES) s = session.apply(Action.Refine)
                s.candidates
            }
            if (shown.first().size >= 2) {
                n++
                shown.forEachIndexed { i, c -> if (c.firstOrNull() == word) first[i]++ }
            }
        }
        return n to first
    }

    /** Every [step]th entry of the table typed by its own code. */
    data class EntryOutcome(val entries: Int, val first: Int, val firstPage: Int, val unreachable: Int)

    fun entries(step: Int): EntryOutcome {
        var n = 0
        var first = 0
        var firstPage = 0
        var unreachable = 0
        for (i in 0 until table.size step step) {
            val code = table.code(i)
            if (!code.all { it in dictionary.keys }) continue
            n++
            val typed = typeCode(code, table.text(i))
            when {
                typed == null -> unreachable++
                typed.first -> {
                    first++
                    firstPage++
                }
                typed.keys == code.length + 1 -> firstPage++
            }
        }
        return EntryOutcome(n, first, firstPage, unreachable)
    }

    companion object {
        const val MAX_PAGES = 10
        private const val MISSING = 1L shl 32

        val PRESETS = mapOf(
            "wubi" to TableOptions.WUBI,
            "cangjie" to TableOptions.CANGJIE,
            "ziranma" to TableOptions.ZIRANMA,
            "erbi" to TableOptions.ERBI,
            "wubipinyin" to TableOptions.WUBI_PINYIN,
            "plain" to TableOptions(),
        )

        fun report(text: Outcome, sampled: EntryOutcome): String {
            fun pct(n: Int, of: Int) = if (of == 0) "-" else String.format(Locale.ROOT, "%.1f%%", 100.0 * n / of)
            val typed = text.chars - text.missing
            return listOf(
                "text: ${text.chars} characters, ${text.missing} not in the table, ${text.words} words",
                "  first      ${pct(text.first, text.words)} of words (committed by itself: ${pct(text.auto, text.words)}, " +
                    "by the next key: ${pct(text.spared, text.words)})",
                "  keys/char  ${if (typed == 0) "-" else String.format(Locale.ROOT, "%.3f", text.keys.toDouble() / typed)}",
                "  latency    " + String.format(
                    Locale.ROOT,
                    "%.1f µs/action mean, %.2f ms slowest",
                    text.keyNanos / 1000.0 / maxOf(text.actions, 1),
                    text.slowestKeyNanos / 1e6,
                ),
                "entries: ${sampled.entries} sampled, first ${pct(sampled.first, sampled.entries)}, on page one ${pct(sampled.firstPage, sampled.entries)}, " +
                    "unreachable ${pct(sampled.unreachable, sampled.entries)}",
            ).joinToString("\n")
        }
    }
}
