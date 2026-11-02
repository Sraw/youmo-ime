/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.reading

import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ZhuyinTest {

    private val pinyin = (0 until Syllables.count).filter { Syllables.spelling(it)[0].isLowerCase() }

    @Test
    fun everyPinyinSyllableHasZhuyin() {
        assertEquals(emptyList<String>(), pinyin.filter { Zhuyin.of(it) == null }.map(Syllables::spelling))
    }

    @Test
    fun onlyTheKnownPairsShareASpellingAndEachReadsBack() {
        val shared = pinyin.groupBy { Zhuyin.of(it)!! }.values.filter { it.size > 1 }
            .map { ids -> ids.map(Syllables::spelling).toSet() }.toSet()
        assertEquals(setOf(setOf("er", "r"), setOf("weng", "wong")), shared)
        for (id in pinyin) {
            val back = Zhuyin.syllable(Zhuyin.of(id)!!)
            assertEquals(Zhuyin.of(id), Zhuyin.of(back))
        }
        assertEquals(Syllables.id("er"), Zhuyin.syllable("ㄦ"))
        assertEquals(Syllables.id("weng"), Zhuyin.syllable("ㄨㄥ"))
        assertEquals(-1, Zhuyin.syllable("ㄅㄅ"))
    }

    @Test
    fun spellings() {
        val expected = mapOf(
            "zhi" to "ㄓ", "ci" to "ㄘ", "ri" to "ㄖ", "ji" to "ㄐㄧ", "yong" to "ㄩㄥ", "jiong" to "ㄐㄩㄥ",
            "lve" to "ㄌㄩㄝ", "lv" to "ㄌㄩ", "ju" to "ㄐㄩ", "quan" to "ㄑㄩㄢ", "xun" to "ㄒㄩㄣ",
            "lun" to "ㄌㄨㄣ", "gui" to "ㄍㄨㄟ", "liu" to "ㄌㄧㄡ", "zhuang" to "ㄓㄨㄤ", "er" to "ㄦ",
            "r" to "ㄦ", "ng" to "ㄫ", "m" to "ㄇ", "hao" to "ㄏㄠ", "ni" to "ㄋㄧ", "wo" to "ㄨㄛ",
            "yi" to "ㄧ", "a" to "ㄚ", "dong" to "ㄉㄨㄥ",
        )
        expected.forEach { (p, z) -> assertEquals(p, z, Zhuyin.of(p)) }
    }

    @Test
    fun latinLettersAndNonSyllablesHaveNone() {
        assertNull(Zhuyin.of("A"))
        assertNull(Zhuyin.of("zzz"))
        assertNull(Zhuyin.of(-1))
    }

    @Test
    fun symbolsCoverEverySpelling() {
        val used = pinyin.flatMap { Zhuyin.of(it)!!.toList() }.toSet() - 'ㄫ'
        assertEquals(used, Zhuyin.symbols.toSet())
    }
}
