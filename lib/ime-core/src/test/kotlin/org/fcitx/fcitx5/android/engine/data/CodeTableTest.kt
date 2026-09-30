/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class CodeTableTest {

    private val table = CodeTable.Builder()
        .header("键码", "abcdefghijklmnopqrstuvwxy")
        .header("码长", "4")
        .rule("e2", "p11+p12+p21+p22")
        .entry("wq", "你")
        .entry("a", "工")
        .entry("aaaa", "工")
        .entry("wqiy", "你")
        .entry("a", "式") // same code: must stay after 工
        .entry("aa", "式")
        .entry("b", "了")
        .build()
        .toByteArray()
        .let { CodeTable.load(ByteBuffer.wrap(it)) }

    private fun texts(range: IntRange) = range.map { table.code(it) + ":" + table.text(it) }

    @Test
    fun headerAndRulesComeBackSeparately() {
        assertEquals(mapOf("键码" to "abcdefghijklmnopqrstuvwxy", "码长" to "4"), table.header)
        assertEquals(mapOf("e2" to "p11+p12+p21+p22"), table.rules)
        assertEquals(7, table.size)
    }

    @Test
    fun aPrefixFindsEveryLongerCodeInOrder() {
        assertEquals(listOf("a:工", "a:式", "aa:式", "aaaa:工"), texts(table.prefixRange("a")))
        assertEquals(listOf("aa:式", "aaaa:工"), texts(table.prefixRange("aa")))
        assertEquals(listOf("wq:你", "wqiy:你"), texts(table.prefixRange("w")))
        assertEquals(listOf("b:了"), texts(table.prefixRange("b")))
    }

    @Test
    fun anExactCodeKeepsTheSourceOrder() {
        assertEquals(listOf("a:工", "a:式"), texts(table.exactRange("a")))
        assertEquals(listOf("wqiy:你"), texts(table.exactRange("wqiy")))
        assertTrue(table.exactRange("aaa").isEmpty())
    }

    @Test
    fun unknownCodesGiveEmptyRanges() {
        assertTrue(table.prefixRange("c").isEmpty())
        assertTrue(table.prefixRange("zz").isEmpty())
        assertTrue(table.prefixRange("aab").isEmpty())
        assertTrue(table.exactRange("x").isEmpty())
    }

    @Test
    fun theEmptyPrefixCoversEverything() {
        assertEquals(0 until table.size, table.prefixRange(""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun anEmptyCodeIsRefused() {
        CodeTable.Builder().entry("", "工")
    }

    @Test(expected = DataFormatException::class)
    fun aPinyinFileIsNotACodeTable() {
        CodeTable.load(ByteBuffer.wrap(DataFile.Writer(DataFile.KIND_PINYIN, PinyinData.VERSION).toByteArray()))
    }
}
