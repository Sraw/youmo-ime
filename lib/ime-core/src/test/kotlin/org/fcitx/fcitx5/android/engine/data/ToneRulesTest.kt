/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.data

import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.junit.Assert.assertEquals
import org.junit.Test

class ToneRulesTest {

    private val listed = mapOf(
        ("时候" to listOf("shi", "hou")) to byteArrayOf(2, 5),
        ("电子" to listOf("dian", "zi")) to byteArrayOf(4, 3),
    )

    private fun tones(word: String, reading: String, usual: String, own: String? = null, neutral: Boolean = true): String {
        val syllables = reading.split(' ').map { Syllables.id(it) }
        fun bytes(s: String) = ByteArray(s.length) { (s[it] - '0').toByte() }
        return ToneRules.tones(word, word.codePoints().toArray(), syllables, own?.let(::bytes), bytes(usual), { _, _ -> neutral }) { w, r ->
            listed[w to r.map(Syllables::spelling)]
        }.joinToString("")
    }

    @Test
    fun aWordTakesTheTonesOfTheLongestListedWordItEndsWith() {
        assertEquals("325", tones("有时候", "you shi hou", "324"))
        // its own listing wins
        assertEquals("324", tones("有时候", "you shi hou", "324", own = "324"))
        // a listed 子 zǐ is no neutral ending
        assertEquals("143", tones("核电子", "he dian zi", "143"))
    }

    @Test
    fun men_me_ziAtTheEndAreNeutralButAPersonsOrASeeds() {
        assertEquals("235", tones("朋友们", "peng you men", "232"))
        assertEquals("35", tones("怎么", "zen me", "35"))
        assertEquals("335", tones("小本子", "xiao ben zi", "333"))
        assertEquals("23", tones("男子", "nan zi", "23"))
        // no neutral tone the character has: none set
        assertEquals("333", tones("小本子", "xiao ben zi", "333", neutral = false))
        // a source's listing stands
        assertEquals("13", tones("妻子", "qi zi", "13", own = "13"))
    }

    @Test
    fun kinshipSaidTwiceHasTheSecondNeutral() {
        assertEquals("25", tones("爷爷", "ye ye", "22", own = "22"))
        assertEquals("45", tones("舅舅", "jiu jiu", "44"))
        assertEquals("22", tones("常常", "chang chang", "22", own = "22"))
    }

    @Test
    fun aDirectionAfterAVerbAndASideAfterAPlaceAreNeutral() {
        assertEquals("25", tones("回来", "hui lai", "22", own = "22"))
        assertEquals("245", tones("停下来", "ting xia lai", "242"))
        // the past
        assertEquals("44", tones("过去", "guo qu", "44", own = "44"))
        assertEquals("35", tones("里边", "li bian", "31", own = "31"))
        assertEquals("45", tones("外面", "wai mian", "44"))
        // 一起 then 来: no direction
        assertEquals("134", tones("一起来", "yi qi lai", "134"))
        assertEquals("435", tones("看起来", "kan qi lai", "434"))
        // only a word of the two
        assertEquals("244", tones("从下面", "cong xia mian", "244"))
    }

    @Test
    fun weiAtTheEndIsWei2ButInYinwei() {
        assertEquals("22", tones("沦为", "lun wei", "24"))
        assertEquals("14", tones("因为", "yin wei", "14"))
        assertEquals("24", tones("沦为", "lun wei", "24", own = "24"))
    }
}
