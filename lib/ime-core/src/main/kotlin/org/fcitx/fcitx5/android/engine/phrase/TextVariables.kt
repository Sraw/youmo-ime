/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.phrase

import java.util.Calendar

/** Date and time variables (`$name` / `${name}` of [VARIABLES], fcitx5-chinese-addons's names; `$$` a `$`), filled in alike for dynamic phrases, long-press items and swipe texts. [expand] and [problems] share [scan], so the editor marks what typing does. */
object TextVariables {

    sealed interface Piece {
        /** Typed as it is. */
        data class Plain(val text: String) : Piece

        /** Filled in if [VARIABLES] has [name], else dropped. */
        data class Variable(val name: String, val written: String) : Piece

        /** A `$` starting no name (`$5`, at the end, an unclosed `${`): typed as written. */
        data class Kept(val written: String) : Piece
    }

    /** [dropped]: an unknown name; else typed as is. */
    data class Problem(val written: String, val dropped: Boolean)

    /** Unknown names dropped, `$$` a `$`. */
    fun expand(template: String, now: Calendar): String = buildString {
        for (p in scan(template)) {
            when (p) {
                is Piece.Plain -> append(p.text)
                is Piece.Variable -> VARIABLES[p.name]?.let { append(it(now)) }
                is Piece.Kept -> append(p.written)
            }
        }
    }

    /** Unknown names (`$yaer`, `${lua:x}`), dropped, and `$`s starting none, typed as is. */
    fun problems(template: String): List<Problem> = scan(template).mapNotNull {
        when (it) {
            is Piece.Variable -> if (it.name in VARIABLES) null else Problem(it.written, dropped = true)
            is Piece.Kept -> Problem(it.written, dropped = false)
            is Piece.Plain -> null
        }
    }

    /** Whether variables change how [template] types (it names one, or has `$$`); else stored as plain text. */
    fun fillsIn(template: String): Boolean = "\$\$" in template || scan(template).any { it is Piece.Variable }

    /** Splits [text] as libime reads it: `$$` a `$`; `${…}` to its `}`; `$` plus letter or `_` a name; any other `$` kept. `${lua:...}` is unknown here (no Lua addon). */
    fun scan(text: String): List<Piece> {
        val pieces = ArrayList<Piece>()
        val plain = StringBuilder()
        fun flush() {
            if (plain.isNotEmpty()) pieces += Piece.Plain(plain.toString())
            plain.clear()
        }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '$') {
                plain.append(c)
                i++
                continue
            }
            val next = text.getOrNull(i + 1)
            when {
                next == null -> {
                    flush()
                    pieces += Piece.Kept("$")
                    i++
                }
                next == '$' -> {
                    plain.append('$')
                    i += 2
                }
                next == '{' -> {
                    val end = text.indexOf('}', i + 2)
                    flush()
                    // not closed: kept as written, with the rest
                    if (end < 0) {
                        pieces += Piece.Kept(text.substring(i))
                        return pieces
                    }
                    pieces += Piece.Variable(text.substring(i + 2, end), text.substring(i, end + 1))
                    i = end + 1
                }
                next.isAsciiLetter() || next == '_' -> {
                    var end = i + 2
                    while (end < text.length && text[end].isNameChar()) end++
                    flush()
                    pieces += Piece.Variable(text.substring(i + 1, end), text.substring(i, end))
                    i = end
                }
                else -> {
                    flush()
                    pieces += Piece.Kept(text.substring(i, i + 2))
                    i += 2
                }
            }
        }
        flush()
        return pieces
    }

    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'
    private fun Char.isNameChar() = isAsciiLetter() || this in '0'..'9' || this == '_'

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

    /** In the order the editor offers them and docs/words.md lists them. */
    val VARIABLES: Map<String, (Calendar) -> String> = linkedMapOf(
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
        "ampm_cn" to { c -> if (c.hour() < 12) "上午" else "下午" },
        "minute" to { c -> twoDigits(c.get(Calendar.MINUTE)) },
        "second" to { c -> twoDigits(c.get(Calendar.SECOND)) },
        "year_cn" to { c -> chineseYear(c.year().toString()) },
        "year_yy_cn" to { c -> chineseYear(twoDigits(c.year() % 100)) },
        "month_cn" to { c -> chineseNumber(c.month(), leadingZero = false) },
        "day_cn" to { c -> chineseNumber(c.day(), leadingZero = false) },
        "weekday_cn" to { c -> WEEKDAYS[c.weekday()].toString() },
        "fullhour_cn" to { c -> chineseNumber(c.hour(), leadingZero = false) },
        "halfhour_cn" to { c -> chineseNumber(c.halfHour(), leadingZero = false) },
        "minute_cn" to { c -> chineseNumber(c.get(Calendar.MINUTE), leadingZero = true) },
        "second_cn" to { c -> chineseNumber(c.get(Calendar.SECOND), leadingZero = true) },
    )

    /** The editor's 插入变量 list. */
    val NAMES: List<String> get() = VARIABLES.keys.toList()
}
