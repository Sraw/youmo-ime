/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.dicttool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageCleanerTest {

    /** Ten sentences of 25 Han characters no other page has: 250, all different. */
    private fun article(page: Int) = (0 until 10).map { line ->
        (0 until 25).map { '一' + page * 400 + line * 30 + it }.joinToString("") + "。"
    }

    private val footer = "版权所有，转载请注明出处。"
    private val menu = "首页 新闻 关于我们"

    @Test
    fun aLineOnManyPagesAndAMenuAreTakenOut() {
        val cleaner = PageCleaner(sketchBits = 12, minRepeats = 3)
        val pages = (0 until 3).map { (listOf(menu) + article(it) + footer).joinToString("\n") }
        pages.forEach(cleaner::count)
        // the footer on all three pages; the menu, no sentence end and short, on any
        assertEquals(article(0).joinToString("\n"), cleaner.clean(pages[0]))
        // once more than the pages counted: still under three
        val fresh = PageCleaner(sketchBits = 12, minRepeats = 3)
        pages.take(2).forEach(fresh::count)
        assertEquals((article(0) + footer).joinToString("\n"), fresh.clean("  " + pages[0].replace(menu + "\n", "") + "\n\n"))
    }

    @Test
    fun aShortPageSpamAndTheSameCharactersOverAndOverAreLeftOut() {
        val cleaner = PageCleaner(sketchBits = 12)
        // 175 Han characters left: under 200
        assertNull(cleaner.clean(article(1).take(7).joinToString("\n")))
        // a spam word in each sentence: 10 in 254 Han characters, over 3 a thousand
        val spam = article(2).map { it.dropLast(3) + "在线观看。" }
        assertNull(cleaner.clean(spam.joinToString("\n")))
        // 3 in 1006 is not
        val some = (2..5).flatMap(::article).mapIndexed { i, line -> if (i < 3) line.dropLast(3) + "免费观看。" else line }
        assertEquals(some.joinToString("\n"), cleaner.clean(some.joinToString("\n")))
        // 300 Han characters of 2 different ones and a full stop
        assertNull(cleaner.clean("哈嘿".repeat(150) + "。"))
    }

    @Test
    fun theHashIsFnv1a() {
        // the published FNV-1a test vector for "a", 0xaf63dc4c8601ec8c, over chars rather than bytes: one and the same for ASCII
        assertEquals(-0x509c23b379fe1374L, PageCleaner.hash("a"))
    }
}
