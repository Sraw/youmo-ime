/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyedRowsTest {

    @Test
    fun eachPickIsLearnedUnderItsKeysAloneTheWholeAfterTheWordBefore() {
        val rows = KeyedRows.of(
            Kinds.HANDWRITING,
            listOf(KeyedRows.Pick("人入八", "人", 'A', "人"), KeyedRows.Pick("天夫", "天", 'B', "夫")),
            listOf(Word("人", ""), Word("天", "")),
            "高",
        )
        assertEquals(3, rows.size)
        val (first, second, whole) = rows
        // no word before a pick (U-28, U-29), no reading (D28)
        assertEquals(Code(Kinds.HANDWRITING, "人入八"), first.code)
        assertEquals("", first.prev)
        assertEquals(Row.PICK, first.flags)
        assertEquals(Words.of("人"), first.words)
        // another than the first shown: corrected
        assertEquals(Row.PICK or Row.CORRECTED, second.flags)
        assertEquals('B', second.how)
        assertEquals("夫", second.shown)
        assertEquals(Code(Kinds.HANDWRITING, ""), whole.code)
        assertEquals(Row.WHOLE, whole.flags)
        assertEquals("高", whole.prev)
        assertEquals(listOf("人", "天"), whole.words.list.map { it.text })
        assertEquals('B', whole.how)
        // nothing put in, nothing learned
        assertEquals(emptyList<Row>(), KeyedRows.of(Kinds.table("engine-wubi"), emptyList(), emptyList(), ""))
    }

    // review C N4: a table no input method's (ime-eval's) has no kind to file it under
    @Test
    fun noKindNoRows() {
        assertEquals(emptyList<Row>(), KeyedRows.of("", listOf(KeyedRows.Pick("a", "工", 'A', "工")), listOf(Word("工", "")), ""))
    }

    @Test
    fun handwritingsKeysAreTheFirstThreeReadingsInAnyOrder() {
        assertEquals(KeyedRows.handwriting(listOf("天", "夫", "大", "犬")), KeyedRows.handwriting(listOf("夫", "大", "天")))
        assertEquals("大天夫", KeyedRows.handwriting(listOf("天", "夫", "大", "犬")))
        // by code point: one past the BMP after one in it, whatever its UTF-16 units say
        assertEquals("Ａ𠀀", KeyedRows.handwriting(listOf("𠀀", "Ａ")))
        assertEquals("人", KeyedRows.handwriting(listOf("人")))
    }
}
