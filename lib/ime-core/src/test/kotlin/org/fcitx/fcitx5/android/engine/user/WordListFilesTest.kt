/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.fcitx.fcitx5.android.engine.user.WordListFiles.EXPORT_MARK
import org.fcitx.fcitx5.android.engine.user.WordListFiles.baseLine
import org.fcitx.fcitx5.android.engine.user.WordListFiles.charset
import org.fcitx.fcitx5.android.engine.user.WordListFiles.exportHead
import org.fcitx.fcitx5.android.engine.user.WordListFiles.isUserExport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WordListFilesTest {

    private fun utf16le(text: String) = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)

    private fun utf16be(text: String) = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.toByteArray(Charsets.UTF_16BE)

    private fun charsetOf(bytes: ByteArray) = charset { bytes.inputStream() }

    @Test
    fun aListsCharsetIsToldAsDecodeTellsIt() {
        assertEquals(Charsets.UTF_16LE, charsetOf(utf16le("幽默 you'mo\n")))
        assertEquals(Charsets.UTF_16BE, charsetOf(utf16be("幽默 you'mo\n")))
        assertEquals(Charsets.UTF_8, charsetOf("幽默 you'mo\n".toByteArray()))
        // a UTF-8 byte order mark is UTF-8's: the line keeps it, baseLine drops it
        assertEquals(Charsets.UTF_8, charsetOf("\uFEFF幽默 you'mo\n".toByteArray()))
        assertEquals(charset("GB18030"), charsetOf("幽默 you'mo\n".toByteArray(charset("GB18030"))))
        assertEquals(Charsets.UTF_8, charsetOf(ByteArray(0)))
    }

    @Test
    fun aLineIsWrittenAsTheBaseReadsIt() {
        assertEquals("幽默 you'mo 0", baseLine("you mo 幽默 12"))
        assertEquals("幽默 you'mo 0", baseLine("'you'mo 幽默 1"))
        // kept with no cost: ImportedDictionaries.write gives it one (LibimeImport.dictionaryText)
        assertEquals("幽默 you'mo", baseLine("\uFEFF幽默 you'mo"))
        // one in libime's format already keeps its cost
        assertEquals("搭子 da'zi -5.5", baseLine("搭子 da'zi -5.5"))
        // the base can neither block nor guess a pinyin
        assertNull(baseLine("!尬 ga"))
        assertNull(baseLine("幽默"))
        assertNull(baseLine("# a comment"))
        assertNull(baseLine(""))
    }

    // into the base its blocked words would be dropped and the rest lose the user dictionary's place
    @Test
    fun theUserDictionarysExportIsTold() {
        // as the export writes it, in whichever language
        assertTrue(isUserExport((exportHead("# 用户词库") + "幽默 you mo\n").toByteArray()))
        assertTrue(isUserExport("$EXPORT_MARK\n# header\n幽默 you mo\n".toByteArray()))
        assertTrue(isUserExport("\uFEFF$EXPORT_MARK\r\n".toByteArray()))
        assertTrue(isUserExport(utf16le("$EXPORT_MARK\n")))
        assertTrue(isUserExport(utf16be("$EXPORT_MARK\n")))
        // a list with a blocked word, whatever its first line
        assertTrue(isUserExport("# other\n幽默 you mo\n!尬 ga\n".toByteArray()))
        assertFalse(isUserExport("幽默 you'mo 0\n$EXPORT_MARK\n".toByteArray()))
        assertFalse(isUserExport("# other\n搭子 da'zi 0\n".toByteArray()))
        // cut in a character's middle past the last whole line: still read as UTF-8
        assertTrue(isUserExport("$EXPORT_MARK\n幽默".toByteArray().let { it.copyOf(it.size - 1) }))
    }

    // made before the mark, under any language shipped: told by its header, with no blocked word to tell it
    @Test
    fun anOlderExportIsToldInAnyLanguage() {
        listOf(
            "# Youmo IME user dictionary: a word a line, its pinyin after it; a ! before a word blocks it",
            "# 幽默输入法用户词库：一行一个词，词后面是拼音；! 开头的是屏蔽的词",
            "# 幽默輸入法使用者詞庫：一行一個詞，詞後面是拼音；! 開頭的是封鎖的詞",
        ).forEach { assertTrue(it, isUserExport("$it\n幽默 you mo\n".toByteArray())) }
    }
}
