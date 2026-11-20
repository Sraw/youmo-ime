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
 * The sum just written (`12*3.5=`) worked out, for the bar to offer after the `=`, as Gboard does; read off the text
 * before the cursor, never in a field it may not read. The longest run that is a sum (`价格12*3=` is `12*3`), with
 * an operator between two numbers. Decimal throughout (0.1+0.2 is 0.3), sixteen digits shown; division by zero, a
 * power past [MAX_POWER] or a sum glued to a number before it (`2(3+4)=`) offer nothing, no shorter sum standing in.
 */
object Calculation {

    /** [sum] as written, its `=` too: replaced by [result] where the user would have the sum replaced. */
    data class Worked(val result: String, val sum: String)

    /** [cut]: [before] is a window that may have cut the first number, so a sum at its very start offers nothing. */
    fun of(before: CharSequence, cut: Boolean = false): Worked? {
        val end = before.length - 1
        if (end < 1 || before[end] != '=' && before[end] != '＝') return null
        var start = end
        while (start > 0 && normal(before[start - 1]) != null) start--
        // a run from the window's start may begin mid-sum (`+23+1=`): any sum read from it could be a shorter one's
        if (cut && start == 0) return null
        // spaces left out, but one between two numbers parts them (`1 2+3` is no 12+3)
        val at = (start until end).filter { before[it] != ' ' || parts(before, it, start, end) }
        val chars = at.map { if (before[it] == ' ') APART else normal(before[it])!! }.joinToString("")
        // a word's letters (`x`) or a stray operator may lead it
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

    /** Also none for a result with more than twice as many digits either side of the point: (10^-999)^999. */
    const val MAX_POWER = 999

    // worked out to 34 digits, shown to 16: rounding each step to 16 makes 1/3*3 0.9999999999999999
    private val CONTEXT = MathContext.DECIMAL128

    private val SHOWN = MathContext(16, RoundingMode.HALF_EVEN)

    private const val GLUED = "0123456789.)%"

    private const val MAX_ZEROS = 6

    private const val NUMBER = "0123456789.,"

    private const val APART = '|'

    private fun parts(text: CharSequence, i: Int, start: Int, end: Int): Boolean {
        var a = i - 1
        while (a >= start && text[a] == ' ') a--
        var b = i + 1
        while (b < end && text[b] == ' ') b++
        return a >= start && b < end && (normal(text[a]) ?: APART) in NUMBER && (normal(text[b]) ?: APART) in NUMBER
    }

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
        // 1E+3 reads as nothing to most: plain digits while they fit, and while few zeros follow the point
        val long = v.precision() - v.scale() > SHOWN.precision || v.scale() - v.precision() >= MAX_ZEROS
        return if (long) v.toString() else v.toPlainString()
    }

    /**
     * sum := term (('+' | '-') term)*; term := unary (('*' | '/') unary)*; unary := '-'* power;
     * power := percent ('^' unary)?; percent := atom '%'*; atom := number | '(' sum ')'. So -2^2 is -4.
     * A sum that parses but cannot be worked out (1/0, 2^0.5) sets [undefined].
     */
    private class Parser(private val text: String) {
        private var pos = 0
        private var operators = 0

        /** The value is then meaningless. */
        var undefined = false
            private set

        fun value(): BigDecimal? = sum()?.takeIf { pos == text.length && operators > 0 }

        // a stand-in with [undefined] set, so the parse can still tell a sum that cannot be worked out from none
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
