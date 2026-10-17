/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.phrase

import org.junit.Assert.assertEquals
import org.junit.Test

class ChaiziTest {

    @Test
    fun theCharactersReadAsWrittenTheCommonestFirst() {
        val text = Chaizi.format(mapOf("niuniuniu" to listOf("犇"), "koukou" to listOf("吕", "吅"), "none" to emptyList()), header = "a header")
        assertEquals("# a header\nkoukou\t吕 吅\nniuniuniu\t犇\n", text)
        val data = Chaizi.read(text)
        assertEquals(listOf("吕", "吅"), data.of("koukou"))
        assertEquals(listOf("犇"), data.of("niuniuniu"))
        assertEquals(emptyList<String>(), data.of("niuniu"))
        assertEquals(2, data.size)
    }

    @Test
    fun aLineThatIsNoSpellingIsSkippedAndASpellingKeepsAtMostMax() {
        val many = (1..Chaizi.MAX + 2).joinToString(" ") { "字$it" }
        val data = Chaizi.read("#koukou\t吅\nKouKou\t吅\nkou kou\t吅\n\nkou\t\nmumu\t$many\n")
        assertEquals(1, data.size)
        assertEquals(Chaizi.MAX, data.of("mumu").size)
        assertEquals(Chaizi.EMPTY.of("mumu"), emptyList<String>())
    }
}
