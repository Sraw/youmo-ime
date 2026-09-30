/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.phrase

import org.fcitx.fcitx5.android.engine.data.escapeValue
import java.util.Calendar

/**
 * Text the user put under a key of letters, offered when the input read is that key: libime's
 * `customphrase` file, read as fcitx5-chinese-addons reads it, so what the user kept there
 * carries over. Where one goes differs a little: its order is its place among the candidates
 * shown, as the file says, where libime counts it among the decoder's alone (`1=A`, `2=B` over
 * `x y` show `A B x y` here, `A x B y` there).
 *
 * A line is `key,order=value`: the value is offered as the [order]th candidate; a negative order
 * keeps it but turned off. A value in double quotes is unescaped; an empty value takes the lines
 * after it, up to the next phrase, as its text. A value starting with `#` is filled in when
 * offered: `$year`, `${month_cn}` and the like (see [VARIABLES]). Lines starting with `;` or `#`
 * outside a multi-line value are comments.
 */
class CustomPhrases private constructor(private val phrases: Map<String, List<Phrase>>) {

    /** [value] under [key], at [order] (1 based), turned off if negative. */
    class Phrase(val key: String, val order: Int, val value: String) {
        val enabled get() = order > 0

        /** Filled in when offered, with the time then. */
        val dynamic get() = value.startsWith("#")

        override fun toString() = "$key,$order=$value"
    }

    val isEmpty get() = phrases.isEmpty()

    /** Every phrase, turned off too, as the settings list them: by key, then in order. */
    val all: List<Phrase> get() = phrases.keys.sorted().flatMap { phrases.getValue(it) }

    /**
     * The phrases turned on under [key], each once, as offered at [now]: its text and where it
     * goes among the candidates, 0 based, in order.
     */
    fun lookup(key: String, now: Calendar): List<Pair<Int, String>> {
        val found = phrases[key] ?: return emptyList()
        val seen = HashSet<String>()
        return found.filter { it.enabled }.mapNotNull { p ->
            val text = if (p.dynamic) evaluate(p.value.substring(1), now) else p.value
            if (text.isEmpty() || !seen.add(text)) null else (p.order - 1) to text
        }
    }

    /**
     * [candidates] with the phrases under [key] put where they go, in the file's order among
     * phrases of one order: a candidate of the same text that reads [all] the input moves there
     * itself, as libime does, so what it learns is learned still; one that reads less is left out,
     * as the phrase is what the user meant by the key; for the rest, [make] one. [now] is asked
     * only when [key] has phrases.
     */
    fun <T> place(
        key: String,
        now: () -> Calendar,
        candidates: List<T>,
        text: (T) -> String,
        all: (T) -> Boolean,
        make: (String) -> T,
    ): List<T> {
        if (key !in phrases) return candidates
        val found = lookup(key, now())
        if (found.isEmpty()) return candidates
        val texts = found.mapTo(HashSet()) { it.second }
        val out = candidates.filterTo(ArrayList()) { text(it) !in texts }
        var last = -1
        for ((order, phrase) in found) {
            val c = candidates.firstOrNull { text(it) == phrase && all(it) } ?: make(phrase)
            // found is in order: one of the same order goes after the one before it
            last = minOf(maxOf(order, last + 1), out.size)
            out.add(last, c)
        }
        return out
    }

    companion object {
        val EMPTY = CustomPhrases(emptyMap())

        fun parse(text: String): CustomPhrases {
            val phrases = LinkedHashMap<String, MutableList<Phrase>>()
            // the phrase taking the lines that follow, and them so far
            var open: Phrase? = null
            val lines = StringBuilder()
            fun close() {
                val p = open ?: return
                if (lines.isNotEmpty()) lines.setLength(lines.length - 1)
                phrases.getOrPut(p.key) { ArrayList() } += Phrase(p.key, p.order, lines.toString())
                open = null
                lines.setLength(0)
            }
            // as getline reads it: a newline ends the last line rather than starting another
            for (line in text.removeSuffix("\n").removeSuffix("\r").lineSequence()) {
                if (open == null && (line.startsWith(";") || line.startsWith("#"))) continue
                val p = line(line)
                if (p != null) {
                    close()
                    // empty as written: `""` is an empty phrase, not the lines after it
                    if (p.value.isEmpty() && line.endsWith('=')) open = p else phrases.getOrPut(p.key) { ArrayList() } += p
                } else if (open != null) {
                    lines.append(line).append('\n')
                }
            }
            close()
            // stable: the file's order among phrases of one order
            for (list in phrases.values) list.sortBy { it.order }
            return CustomPhrases(phrases)
        }

        /**
         * [phrases] as fcitx5-chinese-addons saves them, which [parse] reads back: by key, a
         * line each in the order given, a value fcitx would escape in quotes. An empty value is
         * written `""`, which would otherwise take the lines after it.
         */
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

        /** A phrase line, or null if [line] is none. */
        private fun line(line: String): Phrase? {
            var i = 0
            while (i < line.length && line[i].isAsciiLetter()) i++
            if (i == 0 || i >= line.length || line[i] != ',') return null
            val key = line.substring(0, i)
            i++
            val negative = i < line.length && line[i] == '-'
            if (negative) i++
            val digits = i
            while (i < line.length && line[i] in '0'..'9') i++
            if (i == digits || i >= line.length || line[i] != '=') return null
            // what does not fit an int is 0 as libime reads it, and 0 is no order
            val order = line.substring(digits, i).toIntOrNull()?.takeIf { it != 0 } ?: return null
            return Phrase(key, if (negative) -order else order, unquote(line.substring(i + 1)))
        }

        private val ESCAPES = mapOf('n' to '\n', 't' to '\t', 'r' to '\r', 'f' to '\u000c', 'v' to '\u000b')

        private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'
        private fun Char.isNameChar() = isAsciiLetter() || this in '0'..'9' || this == '_'

        /**
         * [value] unescaped if in double quotes, as fcitx's `unescapeForValue` does: the escapes
         * its editor writes, and any other character after a backslash as it is. As it is if it
         * is not in quotes, or a quote inside is not escaped.
         */
        internal fun unquote(value: String): String {
            if (value.length < 2 || !value.startsWith('"') || !value.endsWith('"')) return value
            val out = StringBuilder()
            var i = 1
            while (i < value.length - 1) {
                val c = value[i]
                if (c == '"') return value
                if (c != '\\') {
                    out.append(c)
                    i++
                    continue
                }
                if (i + 1 >= value.length - 1) return value
                out.append(ESCAPES[value[i + 1]] ?: value[i + 1])
                i += 2
            }
            return out.toString()
        }

        /**
         * [content] with each `$name` or `${name}` filled in from [VARIABLES] at [now]; one it
         * does not know is left out, `$$` is a `$`. libime hands `${lua:...}` to its Lua addon;
         * there is none here, so that is left out too.
         */
        fun evaluate(content: String, now: Calendar): String {
            val out = StringBuilder()
            var i = 0
            while (i < content.length) {
                val c = content[i]
                if (c != '$' || i + 1 >= content.length) {
                    out.append(c)
                    i++
                    continue
                }
                val next = content[i + 1]
                when {
                    next == '$' -> {
                        out.append('$')
                        i += 2
                    }
                    next == '{' -> {
                        val end = content.indexOf('}', i + 2)
                        // not closed: kept as written
                        if (end < 0) return out.append(content, i, content.length).toString()
                        out.append(variable(content.substring(i + 2, end), now))
                        i = end + 1
                    }
                    next.isAsciiLetter() || next == '_' -> {
                        var end = i + 2
                        while (end < content.length && content[end].isNameChar()) end++
                        out.append(variable(content.substring(i + 1, end), now))
                        i = end
                    }
                    else -> {
                        out.append('$').append(next)
                        i += 2
                    }
                }
            }
            return out.toString()
        }

        private fun variable(name: String, now: Calendar): String = VARIABLES[name]?.invoke(now).orEmpty()

        private fun Calendar.year() = get(Calendar.YEAR)
        private fun Calendar.month() = get(Calendar.MONTH) + 1
        private fun Calendar.day() = get(Calendar.DAY_OF_MONTH)
        private fun Calendar.weekday() = get(Calendar.DAY_OF_WEEK) - 1 // 0 for Sunday
        private fun Calendar.hour() = get(Calendar.HOUR_OF_DAY)
        private fun Calendar.halfHour() = (hour() % 12).let { if (it == 0) 12 else it }

        private fun twoDigits(n: Int) = if (n < 10) "0$n" else n.toString()

        private const val DIGITS = "〇一二三四五六七八九"
        private const val NUMBERS = "零一二三四五六七八九"
        private const val WEEKDAYS = "日一二三四五六"

        private fun chineseYear(digits: String) = digits.map { DIGITS[it - '0'] }.joinToString("")

        /** 0 to 99 as said: 十一, 二十, and 零五 with [leadingZero]. */
        internal fun chineseNumber(n: Int, leadingZero: Boolean): String {
            if (n == 0) return "零"
            val tens = n / 10
            val ones = n % 10
            val prefix = when (tens) {
                0 -> if (leadingZero) "零" else ""
                1 -> "十"
                else -> "${NUMBERS[tens]}十"
            }
            return prefix + (if (ones == 0) "" else NUMBERS[ones].toString())
        }

        /** What a dynamic phrase can fill in, as fcitx5-chinese-addons names them. */
        val VARIABLES: Map<String, (Calendar) -> String> = mapOf(
            "year" to { c -> c.year().toString() },
            "year_yy" to { c -> twoDigits(c.year() % 100) },
            "month" to { c -> c.month().toString() },
            "month_mm" to { c -> twoDigits(c.month()) },
            "day" to { c -> c.day().toString() },
            "day_dd" to { c -> twoDigits(c.day()) },
            "weekday" to { c -> c.weekday().toString() },
            "fullhour" to { c -> twoDigits(c.hour()) },
            "halfhour" to { c -> twoDigits(c.halfHour()) },
            "ampm" to { c -> if (c.hour() < 12) "AM" else "PM" },
            "minute" to { c -> twoDigits(c.get(Calendar.MINUTE)) },
            "second" to { c -> twoDigits(c.get(Calendar.SECOND)) },
            "year_cn" to { c -> chineseYear(c.year().toString()) },
            "year_yy_cn" to { c -> chineseYear(twoDigits(c.year() % 100)) },
            "month_cn" to { c -> chineseNumber(c.month(), leadingZero = false) },
            "day_cn" to { c -> chineseNumber(c.day(), leadingZero = false) },
            "weekday_cn" to { c -> WEEKDAYS[c.weekday()].toString() },
            "fullhour_cn" to { c -> chineseNumber(c.hour(), leadingZero = false) },
            "halfhour_cn" to { c -> chineseNumber(c.halfHour(), leadingZero = false) },
            "ampm_cn" to { c -> if (c.hour() < 12) "上午" else "下午" },
            "minute_cn" to { c -> chineseNumber(c.get(Calendar.MINUTE), leadingZero = true) },
            "second_cn" to { c -> chineseNumber(c.get(Calendar.SECOND), leadingZero = true) },
        )
    }
}
