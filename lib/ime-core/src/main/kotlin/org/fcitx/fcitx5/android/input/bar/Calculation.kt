/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

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
 * (0.1+0.2 is 0.3, not 0.30000000000000004), sixteen significant digits, so 1/3 is
 * 0.3333333333333333; division by zero and a power past [MAX_POWER] offer nothing.
 */
object Calculation {

    /**
     * The sum as written, its `=` too, at the end of the text before the cursor ([sum]), and its
     * [result] as the bar offers it: put after the `=`, or in place of [sum] where the user
     * would have the sum replaced.
     */
    data class Worked(val result: String, val sum: String)

    /** The sum [before] ends with (its `=` last), worked out; null if none. */
    fun of(before: CharSequence): Worked? {
        val end = before.length - 1
        if (end < 1 || before[end] != '=' && before[end] != '＝') return null
        var start = end
        while (start > 0 && normal(before[start - 1]) != null) start--
        // where each of the run's characters is in [before]: the spaces are left out, but for one
        // between two numbers, which parts them (`1 2+3` is no 12+3) and stays as a mark no sum has
        val at = (start until end).filter { before[it] != ' ' || parts(before, it, start, end) }
        val run = at.map { if (before[it] == ' ') APART else normal(before[it])!! }.joinToString("")
        // the longest run that is a sum: a word's letters (`x`) or a stray operator may lead it
        return run.indices.firstNotNullOfOrNull { from ->
            run.takeIf { it[from] !in ")*/^%,." }?.let { Parser(it.substring(from)).run() }?.let { from to it }
        }?.let { (from, value) -> Worked(format(value), before.substring(at[from])) }
    }

    /** A power above this offers nothing: 2^100000 would take long and fill the bar. */
    const val MAX_POWER = 999

    private val CONTEXT = MathContext(16, RoundingMode.HALF_EVEN)

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
        val v = value.round(CONTEXT).stripTrailingZeros()
        // 1E+3 reads as nothing to most: plain digits while they fit the significant ones
        return if (v.precision() - v.scale() > CONTEXT.precision) v.toString() else v.toPlainString()
    }

    /**
     * sum := term (('+' | '-') term)*; term := unary (('*' | '/') unary)*; unary := '-'* power;
     * power := percent ('^' unary)?; percent := atom '%'*; atom := number | '(' sum ')'. So -2^2 is
     * -4 and 2^-1 is 0.5, as written on paper. Null for anything else, a sum with no operator
     * between two numbers included (`50%` alone is no sum).
     */
    private class Parser(private val s: String) {
        private var i = 0
        private var operators = 0

        fun run(): BigDecimal? = try {
            sum()?.takeIf { i == s.length && operators > 0 }
        } catch (_: ArithmeticException) {
            // a scale past an int's: (10^999)^999 and the like
            null
        }

        private fun sum(): BigDecimal? {
            var v = term() ?: return null
            while (i < s.length && (s[i] == '+' || s[i] == '-')) {
                val op = s[i++]
                val r = term() ?: return null
                operators++
                v = if (op == '+') v.add(r, CONTEXT) else v.subtract(r, CONTEXT)
            }
            return v
        }

        private fun term(): BigDecimal? {
            var v = unary() ?: return null
            while (i < s.length && (s[i] == '*' || s[i] == '/')) {
                val op = s[i++]
                val r = unary() ?: return null
                operators++
                v = if (op == '*') v.multiply(r, CONTEXT) else {
                    if (r.signum() == 0) return null
                    v.divide(r, CONTEXT)
                }
            }
            return v
        }

        private fun unary(): BigDecimal? {
            var negative = false
            while (i < s.length && s[i] == '-') {
                negative = !negative
                i++
            }
            val v = power() ?: return null
            return if (negative) v.negate() else v
        }

        private fun power(): BigDecimal? {
            val v = percent() ?: return null
            if (i >= s.length || s[i] != '^') return v
            i++
            val e = unary() ?: return null
            operators++
            val n = try {
                e.intValueExact()
            } catch (_: ArithmeticException) {
                return null
            }
            if (n !in -MAX_POWER..MAX_POWER || n < 0 && v.signum() == 0) return null
            return if (n >= 0) v.pow(n, CONTEXT) else BigDecimal.ONE.divide(v.pow(-n, CONTEXT), CONTEXT)
        }

        private fun percent(): BigDecimal? {
            var v = atom() ?: return null
            while (i < s.length && s[i] == '%') {
                i++
                v = v.movePointLeft(2)
            }
            return v
        }

        private fun atom(): BigDecimal? {
            if (i >= s.length) return null
            if (s[i] == '(') {
                i++
                val v = sum() ?: return null
                if (i >= s.length || s[i] != ')') return null
                i++
                return v
            }
            val start = i
            while (i < s.length && s[i] in NUMBER) i++
            val digits = s.substring(start, i)
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
