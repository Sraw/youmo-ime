/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.quickphrase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Whole-file parsing: [QuickPhraseData] is a list of entries with unparsable lines dropped. */
class QuickPhraseDataTest {

    @Test
    fun parsesEveryValidLine() {
        val data = QuickPhraseData.fromLines(listOf("a 1", "b 2", "c 3"))
        assertEquals(
            listOf(
                QuickPhraseEntry("a", "1"),
                QuickPhraseEntry("b", "2"),
                QuickPhraseEntry("c", "3"),
            ),
            data.toList(),
        )
    }

    @Test
    fun unparsableLinesAreDroppedWithoutFailingTheFile() {
        val data = QuickPhraseData.fromLines(
            listOf("a 1", "", "   ", "keywordonly", "b 2")
        )
        assertEquals(listOf(QuickPhraseEntry("a", "1"), QuickPhraseEntry("b", "2")), data.toList())
    }

    @Test
    fun anEmptyFileYieldsNoEntries() {
        assertTrue(QuickPhraseData.fromLines(emptyList()).isEmpty())
    }

    @Test
    fun aFileOfOnlyJunkYieldsNoEntries() {
        assertTrue(QuickPhraseData.fromLines(listOf("", "  ", "nope")).isEmpty())
    }

    @Test
    fun orderIsPreserved() {
        val lines = (1..10).map { "k$it v$it" }
        val data = QuickPhraseData.fromLines(lines)
        assertEquals((1..10).map { "k$it" }, data.map { it.keyword })
    }

    @Test
    fun duplicateKeywordsAreAllKept() {
        val data = QuickPhraseData.fromLines(listOf("k a", "k b"))
        assertEquals(2, data.size)
        assertEquals(listOf("a", "b"), data.map { it.phrase })
    }

    @Test
    fun serializeJoinsEntriesWithNewlines() {
        val data = QuickPhraseData.fromLines(listOf("a 1", "b 2"))
        assertEquals("a 1\nb 2", data.serialize())
    }

    @Test
    fun serializeOfAnEmptyFileIsEmpty() {
        assertEquals("", QuickPhraseData.fromLines(emptyList()).serialize())
    }

    @Test
    fun serializeThenReparseIsIdentity() {
        val original = QuickPhraseData.fromLines(
            listOf("hello world", """k "a b"""", "nihao 你好", """k "a\nb"""")
        )
        val reparsed = QuickPhraseData.fromLines(original.serialize().split("\n"))
        assertEquals(original.toList(), reparsed.toList())
    }

    /** It delegates `List`, so ordinary collection operations work on it directly. */
    @Test
    fun behavesAsAList() {
        val data = QuickPhraseData.fromLines(listOf("a 1", "b 2"))
        assertEquals(2, data.size)
        assertEquals(QuickPhraseEntry("a", "1"), data[0])
        assertTrue(data.any { it.keyword == "b" })
    }
}
