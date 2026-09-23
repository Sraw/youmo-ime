/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/**
 * [CodePoints] over random UTF-16 -- letters, surrogate pairs and lone surrogates in any mix --
 * against an oracle built on the JDK's own code point walk. Seeded, so a failure reproduces.
 */
class CodePointsPropertyTest {

    private val high = '\uD83D'
    private val low = '\uDC4B'

    private fun Random.utf16(maxLength: Int): String = buildString {
        repeat(nextInt(maxLength + 1)) {
            when (nextInt(6)) {
                0 -> append(high)
                1 -> append(low)
                2, 3 -> append(high).append(low)
                else -> append('a' + nextInt(26))
            }
        }
    }

    /** Walks [count] code points back from the end; null on meeting an unpaired surrogate. */
    private fun oracleLast(text: String, count: Int): Int? {
        var i = text.length
        repeat(count) {
            if (i == 0) return text.length
            val cp = Character.codePointBefore(text, i)
            if (Character.isSurrogate(cp.toChar()) && Character.charCount(cp) == 1) return null
            i -= Character.charCount(cp)
        }
        return text.length - i
    }

    private fun oracleFirst(text: String, count: Int): Int? {
        var i = 0
        repeat(count) {
            if (i == text.length) return i
            val cp = Character.codePointAt(text, i)
            if (Character.isSurrogate(cp.toChar()) && Character.charCount(cp) == 1) return null
            i += Character.charCount(cp)
        }
        return i
    }

    @Test
    fun lengthOfLastMatchesTheOracle() {
        val random = Random(20260923)
        repeat(20_000) {
            val text = random.utf16(12)
            val count = random.nextInt(15)
            assertEquals("'${text.escaped()}' last $count", oracleLast(text, count), CodePoints.lengthOfLast(text, count))
        }
    }

    @Test
    fun lengthOfFirstMatchesTheOracle() {
        val random = Random(20260924)
        repeat(20_000) {
            val text = random.utf16(12)
            val count = random.nextInt(15)
            assertEquals("'${text.escaped()}' first $count", oracleFirst(text, count), CodePoints.lengthOfFirst(text, count))
        }
    }

    /** On well-formed text the two directions agree with `codePointCount` for the whole string. */
    @Test
    fun wellFormedTextIsMeasuredWholeFromEitherEnd() {
        val random = Random(20260925)
        repeat(5_000) {
            val text = random.utf16(12).let { s -> if (s.any(Char::isSurrogate)) s.filterNot(Char::isSurrogate) else s }
                .let { s -> s + "👋".repeat(random.nextInt(3)) }
            val n = text.codePointCount(0, text.length)
            assertEquals(text.length, CodePoints.lengthOfLast(text, n))
            assertEquals(text.length, CodePoints.lengthOfFirst(text, n))
        }
    }

    private fun String.escaped() = map { if (it.isSurrogate()) "\\u%04X".format(it.code) else "$it" }.joinToString("")
}
