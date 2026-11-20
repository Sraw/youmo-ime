/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.phrase

import org.fcitx.fcitx5.android.engine.data.escapeValue
import org.fcitx.fcitx5.android.engine.data.unescapeValue
import java.util.Calendar
import java.util.TreeSet

/**
 * libime's `customphrase` file, read as fcitx5-chinese-addons does, so users' phrases carry over. Unlike
 * libime, a phrase's order is its place among the shown candidates (`1=A`, `2=B` over `x y`: `A B x y`
 * here, `A x B y` there). Lines are `key,order=value`; a negative order disables it; an empty value takes
 * the following lines; a value starting with `#` is filled in when offered ([TextVariables]).
 */
class CustomPhrases private constructor(private val phrases: Map<String, List<Phrase>>) {

    /** [order] is 1-based; negative means turned off. */
    class Phrase(val key: String, val order: Int, val value: String) {
        val enabled get() = order > 0

        val dynamic get() = value.startsWith("#")

        override fun toString() = "$key,$order=$value"
    }

    val isEmpty get() = phrases.isEmpty()

    operator fun contains(key: String) = key in phrases

    /** Every phrase, turned off too, by key then order: as the settings list them. */
    val all: List<Phrase> get() = phrases.keys.sorted().flatMap { phrases.getValue(it) }

    class Offered(val index: Int, val text: String, val phrase: Phrase)

    fun offered(key: String, now: Calendar): List<Offered> = offered(listOf(key), now)

    /** Turned-on phrases under [keys], each text once: by order, ties by [keys]' order then the file's. */
    fun offered(keys: List<String>, now: Calendar): List<Offered> {
        val found = keys.flatMap { phrases[it].orEmpty() }.filter { it.enabled }
        // stable: one key's are in order already
        val ordered = if (keys.size > 1) found.sortedBy { it.order } else found
        val seen = HashSet<String>()
        return ordered.mapNotNull { p ->
            val text = if (p.dynamic) evaluate(p.value.substring(1), now) else p.value
            if (text.isEmpty() || !seen.add(text)) null else Offered(p.order - 1, text, p)
        }
    }

    fun keysRead(typed: PhraseKeys): List<String> =
        typed.lengths.flatMap { keysByLength[it].orEmpty() }.filter(typed::reads).let { if (typed.lengths.first == typed.lengths.last) it else it.sorted() }

    /** A table's code leading somewhere. */
    fun leadsTo(prefix: String): Boolean = keysOn.ceiling(prefix)?.startsWith(prefix) == true

    // asked every key on grouped keys and tables: indexed once
    private val keysByLength by lazy { phrases.keys.groupBy { it.length }.mapValues { (_, keys) -> keys.sorted() } }
    private val keysOn by lazy { phrases.filterValues { list -> list.any { it.enabled } }.keys.toCollection(TreeSet()) }

    fun lookup(key: String, now: Calendar): List<Pair<Int, String>> = offered(key, now).map { it.index to it.text }

    /** [value] first under [key], as fcitx5-chinese-addons pins: duplicates of it there go, the rest keep order. */
    fun pinned(key: String, value: String): CustomPhrases {
        require(isKey(key)) { "not a key: $key" }
        val list = listOf(Phrase(key, 1, value)) + phrases[key].orEmpty().filter { it.value != value }
        // stable: the pinned one stays ahead of any other of order 1
        return CustomPhrases(LinkedHashMap(phrases).apply { put(key, list.sortedBy { it.order }) })
    }

    /** Plus [builtIn]'s under keys these have none of. */
    fun over(builtIn: CustomPhrases): CustomPhrases =
        if (builtIn.isEmpty) this else CustomPhrases(LinkedHashMap(builtIn.phrases.filterKeys { it !in phrases } + phrases))

    fun adopting(key: String, builtIn: CustomPhrases): CustomPhrases {
        val adopted = builtIn.phrases[key]
        if (key in phrases || adopted == null) return this
        return CustomPhrases(LinkedHashMap(phrases).apply { put(key, adopted) })
    }

    /** Kept, so the settings can turn it on again. */
    fun turnedOff(key: String, value: String): CustomPhrases {
        val list = phrases[key] ?: return this
        return CustomPhrases(LinkedHashMap(phrases).apply {
            put(key, list.map { if (it.value == value && it.enabled) Phrase(key, -it.order, value) else it })
        })
    }

    fun without(key: String, value: String): CustomPhrases {
        val left = phrases[key]?.filter { it.value != value } ?: return this
        return CustomPhrases(LinkedHashMap(phrases).apply { if (left.isEmpty()) remove(key) else put(key, left) })
    }

    /**
     * [candidates] with [key]'s phrases placed (text, not learned). A candidate of the same text reading [all]
     * the input ranked before its phrase stays there (to be picked and learned) and the phrase is not repeated;
     * ranked after, the phrase stands for it. Partial readings of the same text are dropped.
     */
    fun <T> place(
        key: String,
        now: () -> Calendar,
        candidates: List<T>,
        text: (T) -> String,
        all: (T) -> Boolean,
        make: (String) -> T,
    ): List<T> = place(listOf(key), now, candidates, text, all, make = make)

    /** [place] for several [keys], none before candidate [from] (English: the word space commits stays first). */
    fun <T> place(
        keys: List<String>,
        now: () -> Calendar,
        candidates: List<T>,
        text: (T) -> String,
        all: (T) -> Boolean,
        from: Int = 0,
        make: (String) -> T,
    ): List<T> {
        if (keys.none { it in phrases }) return candidates
        val found = offered(keys, now()).map { it.index to it.text }
        if (found.isEmpty()) return candidates
        val texts = found.mapTo(HashSet()) { it.second }
        val out = candidates.filterTo(ArrayList()) { text(it) !in texts || all(it) }
        var last = -1
        for ((order, phrase) in found) {
            // found is in order: one of the same order goes after the one before
            val at = minOf(maxOf(order, last + 1, from), out.size)
            // the same text before it stands for it: nothing added, so later phrases do not shift
            if ((0 until at).any { text(out[it]) == phrase }) {
                last = at - 1
                continue
            }
            out.add(at, make(phrase))
            last = at
            // it stands for a same-text one after it: removed before placing the next
            for (i in out.lastIndex downTo at + 1) if (text(out[i]) == phrase) out.removeAt(i)
        }
        return out
    }

    companion object {
        val EMPTY = CustomPhrases(emptyMap())

        /** Date, time and weekday under `rq`, `sj`, `xq`, after the first candidate (as Sogou). Offered where the user has no phrase of that key; deleting one turns it off in the user's file so it stays gone. */
        val BUILT_IN: CustomPhrases = parse(
            """
            rq,2=#${'$'}year年${'$'}month月${'$'}day日
            rq,3=#${'$'}year-${'$'}month_mm-${'$'}day_dd
            rq,4=#${'$'}year_cn年${'$'}month_cn月${'$'}day_cn日
            sj,2=#${'$'}fullhour:${'$'}minute
            sj,3=#${'$'}fullhour时${'$'}minute分
            xq,2=#星期${'$'}weekday_cn
            xq,3=#周${'$'}weekday_cn
            """.trimIndent()
        )

        fun parse(text: String): CustomPhrases {
            val phrases = LinkedHashMap<String, MutableList<Phrase>>()
            // the phrase taking the following lines, and them so far
            var open: Phrase? = null
            val lines = StringBuilder()
            fun close() {
                val p = open ?: return
                if (lines.isNotEmpty()) lines.setLength(lines.length - 1)
                phrases.getOrPut(p.key) { ArrayList() } += Phrase(p.key, p.order, lines.toString())
                open = null
                lines.setLength(0)
            }
            // as getline: a final newline ends the last line rather than starting another
            for (line in text.removeSuffix("\n").removeSuffix("\r").lineSequence()) {
                if (open == null && (line.startsWith(";") || line.startsWith("#"))) continue
                val p = line(line)
                if (p != null) {
                    close()
                    // `""` is an empty phrase, not the following lines
                    if (p.value.isEmpty() && line.endsWith('=')) open = p else phrases.getOrPut(p.key) { ArrayList() } += p
                } else if (open != null) {
                    lines.append(line).append('\n')
                }
            }
            close()
            // stable: file order among phrases of one order
            for (list in phrases.values) list.sortBy { it.order }
            return CustomPhrases(phrases)
        }

        /** As fcitx5-chinese-addons saves them, quoting values it would escape; an empty value is `""`, else it would take the following lines. */
        fun format(phrases: List<Phrase>): String {
            val out = StringBuilder()
            for ((key, list) in phrases.groupBy { it.key }.toSortedMap()) {
                for (p in list) {
                    out.append(key).append(',').append(p.order).append('=')
                    out.append(if (p.value.isEmpty()) "\"\"" else escapeValue(p.value)).append('\n')
                }
            }
            return out.toString()
        }

        /** Letters only: `'` or `;` would end it. */
        fun isKey(key: String) = key.isNotEmpty() && key.all { it.isAsciiLetter() }

        private fun line(line: String): Phrase? {
            var i = 0
            while (i < line.length && line[i].isAsciiLetter()) i++
            if (i == 0 || i >= line.length || line[i] != ',') return null
            // matched in either case (English) or by keys (nine keys): one case kept
            val key = line.substring(0, i).lowercase()
            i++
            val negative = i < line.length && line[i] == '-'
            if (negative) i++
            val digits = i
            while (i < line.length && line[i] in '0'..'9') i++
            if (i == digits || i >= line.length || line[i] != '=') return null
            // non-int is 0 as libime reads it, and 0 is no order
            val order = line.substring(digits, i).toIntOrNull()?.takeIf { it != 0 } ?: return null
            return Phrase(key, if (negative) -order else order, unescapeValue(line.substring(i + 1)))
        }

        private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

        /** Fills in `$name` / `${name}` at [now] ([TextVariables.expand]); unknown ones dropped, `$$` a `$`. */
        fun evaluate(content: String, now: Calendar): String = TextVariables.expand(content, now)
    }
}
