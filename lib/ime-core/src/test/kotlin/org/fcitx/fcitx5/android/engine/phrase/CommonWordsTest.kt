/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.phrase

import org.fcitx.fcitx5.android.engine.phrase.CommonWords.Kind
import org.fcitx.fcitx5.android.engine.phrase.CommonWords.Problem
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [CommonWords]: the file read and written, the keys of a word, an import, the index's order. */
class CommonWordsTest {

    private val pinyin: (Int) -> String? = Syllables::spelling

    private fun read(text: String, guess: Map<String, String> = emptyMap()) = CommonWords.parse(text) { asked -> asked.associateWith { guess[it] } }

    private fun word(text: String, pinyin: String, vararg replacements: String) =
        CommonWords.Word(text, checkNotNull(CommonWords.syllables(text, pinyin)), replacements.toList())

    @Test
    fun writtenAsReadTabsApartWithTheHeader() {
        val words = read("米字旁[mi'zi'pang]\t米\t糸\n中华人民共和国[zhong'hua'ren'min'gong'he'guo]\t中国\n").words
        val text = words.format()
        assertEquals("# youmo-common-words 1\n米字旁[mi'zi'pang]\t米\t糸\n中华人民共和国[zhong'hua'ren'min'gong'he'guo]\t中国\n", text)
        assertEquals(words.words, read(text).words.words)
    }

    @Test
    fun spacesSeparateTooAndAReadingMayBeTypedAsAUserWould() {
        val r = read("# a comment\n\n  米字旁 [mi zi pang] 米   糸  \n重庆[chong qing] 渝\n绿色[lü'se] 绿\n")
        assertEquals(listOf("米字旁[mi'zi'pang] 米 糸", "重庆[chong'qing] 渝", "绿色[lv'se] 绿"), r.words.words.map { it.toString() })
        assertTrue(r.skipped.isEmpty())
    }

    @Test
    fun aWordWithoutItsReadingIsReadAsTheDictionaryReadsItAllAtOnce() {
        val asked = ArrayList<List<String>>()
        val r = CommonWords.parse("米字旁 米\n木字旁 木\n米字旁 糸\n") { texts -> asked += texts; mapOf("米字旁" to "mi zi pang", "木字旁" to null) }
        assertEquals(listOf(listOf("米字旁", "木字旁")), asked)
        // two lines of one word are one, its replacements in turn
        assertEquals(listOf("米字旁[mi'zi'pang] 米 糸"), r.words.words.map { it.toString() })
        assertEquals(listOf(2 to Problem.NO_PINYIN), r.skipped.map { it.line to it.problem })
    }

    @Test
    fun badLinesAreSkippedSayingWhy() {
        val r = read(
            "米字旁[mi'zi'pang]\n" +
                "米字旁[mi'zi'pang] 米字旁\n" +
                "米字旁[mi'zi] 米\n" +
                "米字旁[mi'zi'pxng] 米\n" +
                "一二三四五六七八九十一二三四五六七[yi'er'san'si'wu'liu'qi'ba'jiu'shi'yi'er'san'si'wu'liu'qi] 数\n" +
                "米字旁[mi'zi'pang] ${"米".repeat(33)}\n",
        )
        assertTrue(r.words.isEmpty)
        assertEquals(
            listOf(Problem.NO_REPLACEMENT, Problem.SAME_AS_WORD, Problem.BAD_PINYIN, Problem.BAD_PINYIN, Problem.TOO_LONG, Problem.TOO_LONG),
            r.skipped.map { it.problem },
        )
        assertEquals("米字旁[mi'zi] 米", r.skipped[2].text)
    }

    @Test
    fun replacementsAreKeptOnceNineAtMostNoneTheWordItself() {
        val r = read("米字旁[mi'zi'pang] 米 米字旁 糸 米 一 二 三 四 五 六 七 八 九\n")
        assertEquals(listOf("米", "糸", "一", "二", "三", "四", "五", "六", "七"), r.words.words.single().replacements)
    }

    @Test
    fun pastTheMostWordsTheRestAreSkipped() {
        val lines = (0 until CommonWords.MAX_WORDS + 2).joinToString("\n") { "你好[ni'hao] r$it" }
        // one word, its lines merged: no limit reached
        assertEquals(1, read(lines).words.words.size)
        val many = (0 until CommonWords.MAX_WORDS + 2).joinToString("\n") { "${'一' + it}[yi] r" }
        val r = read(many)
        assertEquals(CommonWords.MAX_WORDS, r.words.words.size)
        assertEquals(listOf(Problem.TOO_MANY, Problem.TOO_MANY), r.skipped.map { it.problem })
    }

    @Test
    fun theKeysOfAWordByItsLength() {
        fun keys(text: String, pinyin: String) = CommonWords.keys(word(text, pinyin, "x"), this.pinyin).map { "${it.first}:${it.second}" }
        // one character: its pinyin alone, as its initial would take every input it starts
        assertEquals(listOf("WHOLE:mi"), keys("米", "mi"))
        assertEquals(listOf("WHOLE:zhongguo", "INITIALS:zg"), keys("中国", "zhong'guo"))
        assertEquals(listOf("WHOLE:mizipang", "INITIALS:mzp"), keys("米字旁", "mi'zi'pang"))
        assertEquals(listOf("WHOLE:shichangbu", "INITIALS:scb"), keys("市场部", "shi'chang'bu"))
        assertEquals(
            listOf("WHOLE:zhonghuarenmingongheguo", "FIRST_THREE:zhonghuaren", "INITIALS:zhrmghg"),
            keys("中华人民共和国", "zhong'hua'ren'min'gong'he'guo"),
        )
    }

    @Test
    fun shuangpinsKeysAreItsSchemesTheInitialsTheirFirstKeys() {
        val zrm = ShuangpinScheme.ZIRANMA
        val keys = CommonWords.keys(word("中华人民共和国", "zhong'hua'ren'min'gong'he'guo", "中国")) { zrm.encode(it) }
        // 自然码: zhong vs, hua hw, ren rf, min mn, gong gs, he he, guo go
        assertEquals(listOf(Kind.WHOLE to "vshwrfmngshego", Kind.FIRST_THREE to "vshwrf", Kind.INITIALS to "vhrmghg"), keys)
        // a syllable the scheme cannot type (a Latin letter): no key of it
        assertEquals(emptyList<Pair<Kind, String>>(), CommonWords.keys(word("A股", "A'gu", "股票")) { zrm.encode(it) })
    }

    @Test
    fun anImportLaysItsWordsOverThePhones() {
        val phone = CommonWords.of(listOf(word("米字旁", "mi'zi'pang", "米"), word("木字旁", "mu'zi'pang", "木")))
        val file = CommonWords.of(listOf(word("木字旁", "mu'zi'pang", "木"), word("米字旁", "mi'zi'pang", "糸"), word("中国", "zhong'guo", "华")))
        val i = phone.importing(file)
        assertEquals(listOf("米字旁[mi'zi'pang] 糸", "木字旁[mu'zi'pang] 木", "中国[zhong'guo] 华"), i.words.words.map { it.toString() })
        // the same word as it was is no update
        assertEquals(1 to 1, i.added to i.updated)
        assertTrue(i.full.isEmpty())
    }

    @Test
    fun anImportPastTheMostWordsLeavesTheRestOut() {
        val phone = CommonWords.of((0 until CommonWords.MAX_WORDS).map { word("${'一' + it}", "yi", "r") })
        val i = phone.importing(CommonWords.of(listOf(word("中国", "zhong'guo", "华"), word("一", "yi", "壹"))))
        assertEquals(0 to 1, i.added to i.updated)
        assertEquals(listOf("中国"), i.full.map { it.text })
    }

    @Test
    fun foundByKindThenAsListedEachTextOnce() {
        val words = CommonWords.of(
            listOf(
                word("米字旁边", "mi'zi'pang'bian", "边"),
                word("木字旁", "mu'zi'pang", "木", "米"),
                word("米字旁", "mi'zi'pang", "米", "糸"),
            ),
        )
        val index = words.index(pinyin)
        // 米字旁边's first three is 米字旁's whole: the whole first
        assertEquals(listOf("米", "糸", "边"), index.found(listOf("mizipang")).map { it.text })
        // two words' initials: as listed, 米 once, as 木字旁's
        assertEquals(listOf("木" to "木字旁", "米" to "木字旁", "糸" to "米字旁"), index.found(listOf("mzp")).map { it.text to it.word })
        assertEquals(emptyList<String>(), index.found(listOf("mizipan")).map { it.text })
    }

    @Test
    fun aReplacementTakenOffItsWordAWordLeftWithNoneGoes() {
        val words = CommonWords.of(listOf(word("米字旁", "mi'zi'pang", "米", "糸"), word("中国", "zhong'guo", "华")))
        assertEquals(listOf("米字旁[mi'zi'pang] 糸", "中国[zhong'guo] 华"), words.without("米字旁", "米").words.map { it.toString() })
        assertEquals(listOf("米字旁[mi'zi'pang] 米 糸"), words.without("中国", "华").words.map { it.toString() })
    }

    @Test
    fun aWritersChangesLaidOverTheFileAsItIsNow() {
        val mi = word("米字旁", "mi'zi'pang", "米", "糸")
        val mu = word("木字旁", "mu'zi'pang", "木")
        val zg = word("中国", "zhong'guo", "华")
        val base = CommonWords.of(listOf(mi, mu))
        // the file unchanged since: mine as it is
        val mine = CommonWords.of(listOf(mu, zg))
        assertEquals(mine.words, CommonWords.merged(base, mine, base).words)
        // the keyboard took 米 off 米字旁 meanwhile, and 木字旁 out; the page added 中国 and changed nothing else
        val theirs = CommonWords.of(listOf(mi.let { CommonWords.Word(it.text, it.syllables, listOf("糸")) }))
        val page = CommonWords.of(listOf(mi, mu, zg))
        assertEquals(listOf("米字旁[mi'zi'pang] 糸", "中国[zhong'guo] 华"), CommonWords.merged(base, page, theirs).words.map { it.toString() })
        // the page changed 木字旁 and took 米字旁 out: as it has them, the file's new word kept
        val edited = CommonWords.of(listOf(CommonWords.Word(mu.text, mu.syllables, listOf("沐"))))
        val added = CommonWords.of(listOf(mi, mu, zg))
        assertEquals(listOf("木字旁[mu'zi'pang] 沐", "中国[zhong'guo] 华"), CommonWords.merged(base, edited, added).words.map { it.toString() })
    }

    @Test
    fun whatTheSettingsCheckBeforeSaving() {
        val s = CommonWords.syllables("米字旁", "mizipang")
        assertNull(CommonWords.problem("米字旁", s, listOf("米", " ")))
        assertEquals(Problem.NO_REPLACEMENT, CommonWords.problem("米字旁", s, listOf(" ")))
        assertEquals(Problem.SAME_AS_WORD, CommonWords.problem("米字旁", s, listOf("米字旁")))
        assertEquals(Problem.NO_PINYIN, CommonWords.problem("米字旁", null, listOf("米")))
        assertNull(CommonWords.syllables("米字旁", "mi zi"))
        // a blank inside a replacement is taken out: a word, not a sentence
        assertEquals(listOf("米糸"), CommonWords.clean("米字旁", listOf("米 糸")))
    }
}
