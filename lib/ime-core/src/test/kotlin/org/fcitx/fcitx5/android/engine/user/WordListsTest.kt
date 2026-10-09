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
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files

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
    fun aListThatDidNotReadIsNotWrittenOver() {
        val dir = folder.newFolder()
        val link = File(dir, WordLists.ADDED)
        // a list there that cannot be read as one, yet could be renamed over
        assumeTrue(runCatching { Files.createSymbolicLink(link.toPath(), folder.newFolder().toPath()) }.isSuccess)
        val errors = ArrayList<IOException>()
        val lists = WordLists(dir, errors::add)
        assertEquals(1, errors.size)
        assertTrue(lists.add(entry("你好", "ni", "hao")))
        assertEquals(listOf(entry("你好", "ni", "hao")), lists.addedWords)
        assertEquals(2, errors.size)
        assertTrue(Files.isSymbolicLink(link.toPath()))
        // the other list is written as ever
        assertTrue(lists.block(entry("拟", "ni")))
        assertEquals("拟 ni\n", File(dir, WordLists.BLOCKED).readText())
        assertEquals(2, errors.size)
    }

    @Test
    fun aListThatCannotBeRenamedInLeavesNoTempFile() {
        val dir = folder.newFolder()
        val errors = ArrayList<IOException>()
        val lists = WordLists(dir, errors::add)
        // where the list goes, a directory
        File(dir, WordLists.ADDED).mkdir()
        assertTrue(lists.add(entry("你好", "ni", "hao")))
        assertEquals(1, errors.size)
        assertFalse(File(dir, "${WordLists.ADDED}.new").exists())
    }

    @Test
    fun pinyinRunTogetherIsSplitForAWordNotASentence() {
        fun nis(chars: Int) = UserModel.Entry("你".repeat(chars), IntArray(chars) { Syllables.id("ni") })
        assertEquals(nis(WORD), WordLists.entry("你".repeat(WORD), "ni".repeat(WORD)))
        assertNull(WordLists.entry("你".repeat(WORD + 1), "ni".repeat(WORD + 1)))
        // apart, nothing to split: as long as it is
        assertEquals(nis(WORD + 1), WordLists.entry("你".repeat(WORD + 1), "ni ".repeat(WORD + 1)))
        assertNull(WordLists.entry("你好", "a".repeat(100_000)))
    }

    @Test
    fun aLineToImportIsAWordAndItsPinyinEitherWayRound() {
        assertEquals(WordLists.Line("幽默", "you'mo", false), WordLists.line("幽默 you'mo"))
        assertEquals(WordLists.Line("幽默", "you mo", false), WordLists.line("you mo\t幽默\t3"))
        // a word pack's line, its score after the pinyin: imported into the user dictionary as well
        assertEquals(WordLists.Line("搭子", "da'zi", false), WordLists.line("搭子\tda'zi\t-5.6"))
        assertEquals(WordLists.Line("3D", "san'di", false), WordLists.line("3D san'di"))
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

    private companion object {
        // the longest word whose pinyin, run together, is split
        const val WORD = 32
    }
}
