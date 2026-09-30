/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TableConfTest {

    @Test
    fun wubisConfReadsAsTheBuiltInWubi() {
        // fcitx's wbx.conf as installed
        val conf = TableConf.parse(
            """
            [InputMethod]
            Name=Wubi
            Icon=fcitx-wubi
            Label=五
            LangCode=zh_CN
            Addon=table
            Configurable=True

            [Table]
            File=table/wbx.main.dict
            AutoSelect=True
            AutoSelectLength=-1
            NoMatchAutoSelectLength=-1
            NoSortInputLength=2
            Hint=True
            MatchingKey=z
            PinyinKey=z
            OrderPolicy=Freq
            AutoPhraseLength=4
            SaveAutoPhraseAfter=3

            [Table/EndKey]
            0=grave
            """.trimIndent(),
        )
        assertEquals("table/wbx.main.dict", conf.file)
        assertEquals(TableOptions.WUBI, conf.options)
        assertTrue(conf.learning)
    }

    @Test
    fun whatItDoesNotSayIsLibimesDefault() {
        val conf = TableConf.parse("[Table]\nFile=table/my.main.dict\n")
        assertEquals(TableConf.DEFAULTS, conf.options)
        assertFalse(conf.options.autoSelect)
        assertEquals(-1, conf.options.autoPhraseLength)
    }

    @Test
    fun valuesAsFcitxWritesThem() {
        val conf = TableConf.parse(
            """
            # a comment
            [Table]
            File="table/my table.main.dict"
            AutoSelect=true
            OrderPolicy=Fast
            MatchingKey=asterisk
            PinyinKey=bracketleft
            Hint=maybe
            NoSortInputLength=x
            Learning=False
            [Other]
            AutoSelect=False
            """.trimIndent(),
        )
        assertEquals("table/my table.main.dict", conf.file)
        assertTrue(conf.options.autoSelect)
        assertTrue(conf.options.orderByUse)
        assertEquals('*', conf.options.matchingKey)
        assertEquals('[', conf.options.pinyinKey)
        // what does not read is left as libime has it
        assertFalse(conf.options.hint)
        assertEquals(0, conf.options.noSortInputLength)
        assertFalse(conf.learning)
        assertFalse(TableConf.parse("[Table]\nFile=a\nOrderPolicy=No\n").options.orderByUse)
    }

    @Test
    fun keysAreCharactersOrKeysymNames() {
        assertEquals('z', TableConf.keyChar("z"))
        assertEquals('`', TableConf.keyChar("grave"))
        assertEquals(';', TableConf.keyChar("semicolon"))
        assertNull(TableConf.keyChar("Control+z"))
        assertNull(TableConf.keyChar(""))
    }

    @Test
    fun quotedValuesUnescape() {
        assertEquals(mapOf("A" to "a \"b\"\nc", "B" to "\""), TableConf.section("[S]\nA=\"a \\\"b\\\"\\nc\"\nB=\"\n", "S"))
    }

    @Test
    fun noTableFileIsNoTable() {
        assertThrows(IllegalArgumentException::class.java) { TableConf.parse("[InputMethod]\nName=x\n") }
        assertThrows(IllegalArgumentException::class.java) { TableConf.parse("[Table]\nFile=\n") }
    }

    @Test
    fun whatTheUserSetIsOverTheConf() {
        val conf = TableConf.parse("[Table]\nFile=a\nAutoSelect=True\nHint=True\n", "[Table]\nAutoSelect=False\n")
        assertEquals("a", conf.file)
        assertFalse(conf.options.autoSelect)
        assertTrue(conf.options.hint)
    }
}
