/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class WordListsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun entry(text: String, vararg s: String) = UserModel.Entry(text, s.map { Syllables.id(it) }.toIntArray())

    @Test
    fun pinyinIsReadAsTypedApartOrRunTogether() {
        val nihao = entry("你好", "ni", "hao")
        assertEquals(nihao, WordLists.entry("你好", "ni hao"))
        assertEquals(nihao, WordLists.entry(" 你好 ", "Ni'Hao"))
        assertEquals(nihao, WordLists.entry("你好", "nihao"))
        assertEquals(entry("西安", "xi", "an"), WordLists.entry("西安", "xi'an"))
        assertEquals(entry("绿", "lv"), WordLists.entry("绿", "lü"))
        assertEquals(entry("略", "lve"), WordLists.entry("略", "lue"))
    }

    @Test
    fun whatDoesNotReadIsNull() {
        assertNull(WordLists.entry("你好", "ni"))
        assertNull(WordLists.entry("你好", "nizz"))
        assertNull(WordLists.entry("", "ni"))
        assertNull(WordLists.entry("你", ""))
        // fang'an or fan'gan, xi'an or xia'n: no telling which
        assertNull(WordLists.entry("方案", "fangan"))
        assertNull(WordLists.entry("西安", "xian"))
    }

    @Test
    fun theListsAreKeptInTheirFilesAndAppliedToAModel() {
        val dir = folder.newFolder()
        val lists = WordLists(dir)
        assertTrue(lists.add(entry("你好", "ni", "hao")))
        assertFalse(lists.add(entry("你好", "ni", "hao")))
        assertTrue(lists.block(entry("拟", "ni")))
        assertEquals("你好 ni'hao\n", File(dir, WordLists.ADDED).readText())
        val again = WordLists(dir)
        assertEquals(listOf(entry("你好", "ni", "hao")), again.addedWords)
        assertEquals(listOf(entry("拟", "ni")), again.blockedWords)
        assertTrue(again.unblock(entry("拟", "ni")))
        assertTrue(again.remove(entry("你好", "ni", "hao")))
        assertEquals("", File(dir, WordLists.BLOCKED).readText())
        assertEquals(emptyList<UserModel.Entry>(), WordLists(dir).addedWords)
    }

    @Test
    fun aListThatCannotBeWrittenIsKeptInMemory() {
        val errors = ArrayList<IOException>()
        // a file where the directory should be
        val lists = WordLists(folder.newFile(), errors::add)
        assertTrue(lists.add(entry("你好", "ni", "hao")))
        assertEquals(listOf(entry("你好", "ni", "hao")), lists.addedWords)
        assertEquals(1, errors.size)
    }

    @Test
    fun aLineToImportIsAWordAndItsPinyinEitherWayRound() {
        assertEquals(WordLists.Line("幽默", "you'mo", false), WordLists.line("幽默 you'mo"))
        assertEquals(WordLists.Line("幽默", "you mo", false), WordLists.line("you mo\t幽默\t3"))
        assertEquals(WordLists.Line("幽默", null, true), WordLists.line("!幽默"))
        assertEquals(WordLists.Line("幽默", "youmo", false), WordLists.line("\uFEFF幽默 youmo"))
        assertNull(WordLists.line("  "))
        assertNull(WordLists.line("# 幽默 you'mo"))
        assertEquals(WordLists.Line.UNREAD, WordLists.line("you mo"))
        assertEquals(WordLists.Line.UNREAD, WordLists.line("幽默 风趣"))
    }

    @Test
    fun aListIsReadInTheEncodingOtherInputMethodsWriteIn() {
        val text = "幽默 you'mo\n"
        assertEquals(text, WordLists.decode(text.toByteArray(Charsets.UTF_8)))
        assertEquals(text, WordLists.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)))
        assertEquals(text, WordLists.decode(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.toByteArray(Charsets.UTF_16BE)))
        assertEquals(text, WordLists.decode(text.toByteArray(charset("GB18030"))))
    }

    @Test
    fun aLetterIsASyllableAsSpelledAndReadsBackSo() {
        val stock = WordLists.entry("A股", "A'gu")!!
        assertEquals("A股 A'gu", "${stock.text} ${WordLists.code(stock.syllables)}")
        assertEquals(stock, WordLists.entry("A股", "A gu"))
        // typed in capitals, a word of pinyin still reads
        assertEquals(entry("你好", "ni", "hao"), WordLists.entry("你好", "Ni Hao"))
        // a capital where the word has no letter: the syllable
        assertEquals(entry("啊", "a"), WordLists.entry("啊", "A"))
    }
}
