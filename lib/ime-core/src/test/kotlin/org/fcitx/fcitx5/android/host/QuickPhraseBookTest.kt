/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The user's quick phrases (design 051-quickphrase §3.1–3.2): files, order, matching keys. */
class QuickPhraseBookTest {

    private class Mem(private val files: Map<String, String>) : PkgDataDir {
        override fun read(path: String) = files[path]?.toByteArray()
        override fun list(dir: String) = files.keys.filter { it.startsWith("$dir/") }.map { it.removePrefix("$dir/") }
    }

    private val gh = QuickPhraseEntry("gh", "https://github.com/Sraw/youmo-ime")
    private val addr = QuickPhraseEntry("addr", "北京市海淀区\n3 号楼 502")
    private val hi = QuickPhraseEntry("Hi", "你好")

    private fun keys(s: String) = s.map { setOf(it.code) }

    private fun nine(s: String) = s.map { QuickPhraseKeys.of(Keyboard.pinyinNineKey(it), NineKeyLetters.STANDARD)!!.can }

    @Test
    fun mineFileRoundTripsQuotesBackslashesAndLines() {
        val odd = listOf(
            addr,
            QuickPhraseEntry("q", "say \"hi\" \\ back\tslash"),
            QuickPhraseEntry("sp", "  padded  "),
            QuickPhraseEntry("one", "word"),
        )
        val text = QuickPhraseFiles.formatMine(odd)
        assertEquals(odd, QuickPhraseFiles.parseMine(text.toByteArray()))
        // fcitx's own reader takes the file as it is: the export is what fcitx5 on a computer reads
        assertEquals(odd.map { it.key to it.content }, QuickPhraseTable.parse(text.toByteArray()))
        assertTrue(text.startsWith("addr \"北京市海淀区\\n3 号楼 502\"\n"))
    }

    // review L5, L11: Notepad's BOM is no part of a key; the same key in another case is the same entry
    @Test
    fun mineFileDropsABomTooLongContentAndCaseOnlyDuplicates() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val long = QuickPhraseEntry("long", "x".repeat(QuickPhraseBook.MAX_CONTENT + 1))
        val text = QuickPhraseFiles.formatMine(listOf(gh, hi, hi.copy(key = "HI"), long, addr)).toByteArray()
        assertEquals(listOf(gh, hi, addr), QuickPhraseFiles.parseMine(bom + text))
        assertEquals("gh", QuickPhraseFiles.parseLines(bom + text).first().key)
        assertEquals(5, QuickPhraseFiles.parseLines(text).size)
        assertTrue(hi.sameAs(hi.copy(key = "hI")))
        assertFalse(hi.sameAs(hi.copy(content = "您好")))
        // the book of such a file lists the first alone
        assertEquals(listOf(hi), QuickPhraseBook(QuickPhraseTable.EMPTY, listOf(hi, hi.copy(key = "HI"))).mine)
    }

    @Test
    fun usedFileKeepsOnlyEntriesStillThere() {
        val used = mapOf(gh to 5L, addr to 9L, QuickPhraseEntry("gone", "x\ty") to 7L)
        val text = QuickPhraseFiles.formatUsed(used, listOf(gh, addr))
        assertEquals(mapOf(gh to 5L, addr to 9L), QuickPhraseFiles.parseUsed(text))
        // a line that does not read is skipped
        assertEquals(mapOf(gh to 5L), QuickPhraseFiles.parseUsed("x\tgh\tz\n" + QuickPhraseFiles.formatUsed(mapOf(gh to 5L), listOf(gh)) + "bad\n"))
        val tabbed = QuickPhraseEntry("t", "a\tb")
        assertEquals(mapOf(tabbed to 1L), QuickPhraseFiles.parseUsed(QuickPhraseFiles.formatUsed(mapOf(tabbed to 1L), listOf(tabbed))))
    }

    @Test
    fun importSkipsDuplicatesTooLongAndBadKeys() {
        val long = QuickPhraseEntry("long", "x".repeat(QuickPhraseBook.MAX_CONTENT + 1))
        val longest = QuickPhraseEntry("longest", "x".repeat(QuickPhraseBook.MAX_CONTENT))
        val merged = QuickPhraseFiles.merge(
            listOf(gh),
            // GH: gh again, its key's case aside, as typing finds both
            listOf(gh, gh.copy(key = "GH"), addr, addr, long, longest, QuickPhraseEntry("x".repeat(31), "long"), QuickPhraseEntry("e", "")),
        )
        assertEquals(listOf(gh, addr, longest), merged.entries)
        assertEquals(3, merged.duplicates)
        assertEquals(1, merged.tooLong)
        // another content under the key is another entry
        assertEquals(2, QuickPhraseFiles.merge(listOf(gh), listOf(gh.copy(content = "x"))).entries.size)
        assertTrue(QuickPhraseFiles.validKey("x".repeat(30)))
        assertFalse(QuickPhraseFiles.validKey(""))
        assertFalse(QuickPhraseFiles.validKey("a b"))
    }

    @Test
    @Uat("QU-10")
    fun mineInOrderOfUseThenFileOrder() {
        val book = QuickPhraseBook(QuickPhraseTable.EMPTY, listOf(gh, addr, hi), mapOf(hi to 3L))
        assertEquals(listOf(hi, gh, addr), book.mine)
        book.use(addr, 10L)
        assertEquals(listOf(addr, hi, gh), book.mine)
        // one not in the book is left alone
        book.use(QuickPhraseEntry("no", "such"), 20L)
        assertEquals(listOf(addr, hi, gh), book.mine)
    }

    @Test
    fun loadReadsMineUsedAndDisablesOnlyFromTheUsersDirectory() {
        val builtIn = Mem(
            mapOf(
                "data/quickphrase.d/emoji.mb" to "happy :)\n",
                "data/quickphrase.d/latex.mb" to "\\alpha α\n",
            ),
        )
        val user = Mem(
            mapOf(
                "data/quickphrase.d/mine.mb" to QuickPhraseFiles.formatMine(listOf(gh, addr)),
                "data/quickphrase.d/mine.used" to QuickPhraseFiles.formatUsed(mapOf(addr to 1L), listOf(addr)),
                "data/quickphrase.d/latex.mb.disable" to "",
                // no release wrote one: not read, and it hides no built-in table
                "data/quickphrase.d/emoji.mb" to "sad :(\n",
            ),
        )
        val book = QuickPhraseBook.load(user, builtIn)
        assertEquals(listOf(addr, gh), book.mine)
        assertEquals(listOf("happy" to ":)"), book.builtIn.entries().toList())
        assertEquals(listOf("\\alpha" to "α", "happy" to ":)"), QuickPhraseBook.load(null, builtIn).builtIn.entries().toList())
    }

    @Test
    @Uat("QU-11")
    fun findMatchesKeysStartCaseAsideMineFirst() {
        val builtIn = QuickPhraseTable.load(listOf(Mem(mapOf("data/quickphrase.d/t.mb" to "ghost 👻\nHi hello\nhi lower\n"))))
        val book = QuickPhraseBook(builtIn, listOf(gh, addr, hi))
        assertEquals(listOf(gh, QuickPhraseEntry("ghost", "👻")), book.find(keys("gh")).map { it.entry })
        assertEquals(listOf(true, false), book.find(keys("gh")).map { it.mine })
        // mine case aside; the built-in ones' case as fcitx has it
        assertEquals(listOf(hi, QuickPhraseEntry("hi", "lower")), book.find(keys("hi")).map { it.entry })
        // the nine keys' 44: gh, hi, ig all read
        // (the built-in ones in code point order: capitals first)
        assertEquals(listOf(gh, hi, QuickPhraseEntry("Hi", "hello"), QuickPhraseEntry("ghost", "👻"), QuickPhraseEntry("hi", "lower")), book.find(nine("44")).map { it.entry })
        // 借手机: none of mine
        assertEquals(listOf(QuickPhraseEntry("ghost", "👻")), book.find(keys("gh"), withMine = false).map { it.entry })
        assertEquals(emptyList<QuickPhraseBook.Found>(), book.find(emptyList()))
        assertEquals(emptyList<QuickPhraseBook.Found>(), book.find(keys("ghx")).filter { it.mine })
    }

    @Test
    fun excerptIsTheFirstLineCutByWidth() {
        assertEquals("short", QuickPhraseExcerpt.of("short"))
        assertEquals("abcd…", QuickPhraseExcerpt.of("abcdefgh", width = 4))
        // a CJK character takes two columns
        assertEquals("北京…", QuickPhraseExcerpt.of("北京市海淀区", width = 5))
        assertEquals("北京市⏎", QuickPhraseExcerpt.of("北京市\n海淀区"))
        assertEquals("ab…⏎", QuickPhraseExcerpt.of("abc\nd", width = 2))
        // a trailing newline alone is no second line
        assertEquals("x", QuickPhraseExcerpt.of("x\n"))
        assertEquals("ｘ1…", QuickPhraseExcerpt.of("ｘ1ｙ", width = 3))
    }
}
