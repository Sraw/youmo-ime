/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.abs

/**
 * The sum the user just wrote, worked out: `12*3.5=` typed anywhere, in any input method, and
 * the bar offers `42` to put after the `=`, as Gboard does. Read off the text before the cursor,
 * so it does not matter how the digits got there (the number pad, the letters' long press, a
 * paste); the app asks only when what it put in ends with `=` or `＝`, and never in a field it may
 * not read ([org.fcitx.fcitx5.android.input.FieldPolicy]).
 *
 * The sum is the longest run before the `=` that is one: `价格12*3=` is `12*3`, `2026年1+1=` is
 * `1+1`. It needs an operator between two numbers, so `5=` or `-5=` offers nothing. Operators are
 * `+ - * / ^`, `×` `÷` `x` `X` too, `%` after a number (a hundredth), parentheses, the full-width
 * forms the Chinese punctuation types; numbers may have `,` between thousands. Decimal throughout
 * (0.1+0.2 is 0.3, not 0.30000000000000004), sixteen significant digits shown, so 1/3 is
 * 0.3333333333333333 and 1/3*3 is 1; division by zero and a power past [MAX_POWER] offer nothing,
 * and so does a sum glued to a number before it (`2(3+4)=`, `1.5.2+1=`): no shorter sum stands in.
 */
object Calculation {

    /**
     * The sum as written, its `=` too, at the end of the text before the cursor ([sum]), and its
     * [result] as the bar offers it: put after the `=`, or in place of [sum] where the user
     * would have the sum replaced.
     */
    data class Worked(val result: String, val sum: String)

    /**
     * The sum [before] ends with (its `=` last), worked out; null if none. [cut]: [before] is a
     * window that may have cut the sum's first number, so a sum at its very start offers nothing.
     */
    fun of(before: CharSequence, cut: Boolean = false): Worked? {
        val end = before.length - 1
        if (end < 1 || before[end] != '=' && before[end] != '＝') return null
        var start = end
        while (start > 0 && normal(before[start - 1]) != null) start--
        // a run from the window's start may begin mid-sum (`+23+1=`, `,345+1=`, ` 23+1=` of a cut
        // number): any sum read from it could be a shorter one's
        if (cut && start == 0) return null
        // where each of the run's characters is in [before]: the spaces are left out, but for one
        // between two numbers, which parts them (`1 2+3` is no 12+3) and stays as a mark no sum has
        val at = (start until end).filter { before[it] != ' ' || parts(before, it, start, end) }
        val chars = at.map { if (before[it] == ' ') APART else normal(before[it])!! }.joinToString("")
        // the longest run that is a sum: a word's letters (`x`) or a stray operator may lead it
        for (from in chars.indices) {
            val parser = if (startsSum(chars, from)) Parser(chars.substring(from)) else null
            val value = parser?.value() ?: continue
            // the longest sum is the one meant: 1/0+5 offers nothing, not the 5 a shorter one gives
            if (parser.undefined) return null
            return Worked(format(value), before.substring(at[from]))
        }
        return null
    }

    // a sum starts neither inside a number (`1.5.2`, `.5` after a digit) nor glued to one (`2(3+4)`)
    private fun startsSum(chars: String, from: Int): Boolean {
        val c = chars[from]
        if (c in ")*/^%,") return false
        if (c == '.' && chars.getOrNull(from + 1)?.isDigit() != true) return false
        return from == 0 || chars[from - 1] !in GLUED
    }

    /**
     * A power above this offers nothing: 2^100000 would take long and fill the bar. Nor does a power
     * whose result has more than twice as many digits either side of the point: (10^-999)^999.
     */
    const val MAX_POWER = 999

    // worked out to 34 digits, shown to 16: rounding each step to 16 makes 1/3*3 0.9999999999999999
    private val CONTEXT = MathContext.DECIMAL128

    private val SHOWN = MathContext(16, RoundingMode.HALF_EVEN)

    // a sum that would start right after one of these is part of a longer one
    private const val GLUED = "0123456789.)%"

    // this many zeros after the point, or more, and the result is written with its exponent
    private const val MAX_ZEROS = 6

    private const val NUMBER = "0123456789.,"

    private const val APART = '|'

    // whether the space at [i] stands between two numbers' characters, spaces aside
    private fun parts(text: CharSequence, i: Int, start: Int, end: Int): Boolean {
        var a = i - 1
        while (a >= start && text[a] == ' ') a--
        var b = i + 1
        while (b < end && text[b] == ' ') b++
        return a >= start && b < end && (normal(text[a]) ?: APART) in NUMBER && (normal(text[b]) ?: APART) in NUMBER
    }

    /** [c] as the parser reads it, or null where a sum cannot go on. */
    private fun normal(c: Char): Char? = when (c) {
        in '0'..'9', '+', '-', '*', '/', '^', '%', '(', ')', '.', ',', ' ' -> c
        in '０'..'９' -> '0' + (c - '０')
        '×', 'x', 'X', '＊', '·' -> '*'
        '÷', '／' -> '/'
        '＋' -> '+'
        '－', '−' -> '-'
        '（' -> '('
        '）' -> ')'
        '．' -> '.'
        '，' -> ','
        '％' -> '%'
        else -> null
    }

    private fun format(value: BigDecimal): String {
        // Android's stripTrailingZeros leaves a zero as it is, 0.0 for 0.5-0.5
        if (value.signum() == 0) return "0"
        val v = value.round(SHOWN).stripTrailingZeros()
        // 1E+3 reads as nothing to most: plain digits while they fit the significant ones, and while
        // the zeros after the point are few (10^-999 in full is a thousand characters)
        val long = v.precision() - v.scale() > SHOWN.precision || v.scale() - v.precision() >= MAX_ZEROS
        return if (long) v.toString() else v.toPlainString()
    }

    /**
     * sum := term (('+' | '-') term)*; term := unary (('*' | '/') unary)*; unary := '-'* power;
     * power := percent ('^' unary)?; percent := atom '%'*; atom := number | '(' sum ')'. So -2^2 is
     * -4 and 2^-1 is 0.5, as written on paper. Null for anything else, a sum with no operator
     * between two numbers included (`50%` alone is no sum). A sum that is one but cannot be worked
     * out (1/0, 2^0.5) parses, with [undefined] set.
     */
    private class Parser(private val text: String) {
        private var pos = 0
        private var operators = 0

        /** Whether some step had no result; the value is then meaningless. */
        var undefined = false
            private set

        fun value(): BigDecimal? = sum()?.takeIf { pos == text.length && operators > 0 }

        // a step's result, or a stand-in with [undefined] set: the parse goes on to tell a sum
        // that cannot be worked out from one that is none. ArithmeticException: a scale past an int's
        private inline fun defined(step: () -> BigDecimal?): BigDecimal {
            val v = try {
                step()
            } catch (_: ArithmeticException) {
                null
            }
            if (v == null) undefined = true
            return v ?: BigDecimal.ONE
        }

        private fun sum(): BigDecimal? {
            var v = term() ?: return null
            while (pos < text.length && (text[pos] == '+' || text[pos] == '-')) {
                val op = text[pos++]
                val r = term() ?: return null
                operators++
                v = defined { if (op == '+') v.add(r, CONTEXT) else v.subtract(r, CONTEXT) }
            }
            return v
        }

        private fun term(): BigDecimal? {
            var v = unary() ?: return null
            while (pos < text.length && (text[pos] == '*' || text[pos] == '/')) {
                val op = text[pos++]
                val r = unary() ?: return null
                operators++
                v = defined {
                    when {
                        op == '*' -> v.multiply(r, CONTEXT)
                        r.signum() == 0 -> null
                        else -> v.divide(r, CONTEXT)
                    }
                }
            }
            return v
        }

        private fun unary(): BigDecimal? {
            var negative = false
            while (pos < text.length && text[pos] == '-') {
                negative = !negative
                pos++
            }
            val v = power() ?: return null
            return if (negative) v.negate() else v
        }

        private fun power(): BigDecimal? {
            val v = percent() ?: return null
            if (pos >= text.length || text[pos] != '^') return v
            pos++
            val e = unary() ?: return null
            operators++
            return defined {
                val n = e.intValueExact()
                if (n !in -MAX_POWER..MAX_POWER || n < 0 && v.signum() == 0) return@defined null
                val p = if (n >= 0) v.pow(n, CONTEXT) else BigDecimal.ONE.divide(v.pow(-n, CONTEXT), CONTEXT)
                p.takeIf { abs(it.precision() - it.scale() - 1) <= 2 * MAX_POWER }
            }
        }

        private fun percent(): BigDecimal? {
            var v = atom() ?: return null
            while (pos < text.length && text[pos] == '%') {
                pos++
                v = defined { v.movePointLeft(2) }
            }
            return v
        }

        private fun atom(): BigDecimal? {
            if (pos >= text.length) return null
            if (text[pos] == '(') {
                pos++
                val v = sum() ?: return null
                if (pos >= text.length || text[pos] != ')') return null
                pos++
                return v
            }
            val start = pos
            while (pos < text.length && text[pos] in NUMBER) pos++
            val digits = text.substring(start, pos)
            if (!thousands(digits)) return null
            return digits.replace(",", "").toBigDecimalOrNull()
        }

        // `1,000` and `12,345.6` are numbers; `1,2` is two, not 12
        private fun thousands(n: String): Boolean {
            if (',' !in n) return true
            val whole = n.substringBefore('.')
            val groups = whole.split(',')
            return groups[0].length in 1..3 && groups.drop(1).all { it.length == 3 } && ',' !in n.substringAfter('.', "")
        }
    }
}
