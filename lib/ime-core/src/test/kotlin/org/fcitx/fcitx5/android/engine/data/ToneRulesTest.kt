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
        ("爱好" to listOf("ai", "hao")) to byteArrayOf(4, 4),
        ("电子" to listOf("dian", "zi")) to byteArrayOf(4, 3),
    )

    private fun bytes(s: String) = ByteArray(s.length) { (s[it] - '0').toByte() }

    private fun tones(word: String, reading: String, usual: String, own: String? = null): String {
        val syllables = reading.split(' ').map { Syllables.id(it) }
        return ToneRules.tones(word, word.codePoints().toArray(), syllables, own?.let(::bytes), bytes(usual)) { w, r ->
            listed[w to r.map(Syllables::spelling)]
        }.joinToString("")
    }

    @Test
    fun aWordTakesTheTonesOfTheLongestListedWordItEndsWith() {
        assertEquals("4244", tones("个人爱好", "ge ren ai hao", "4243"))
        // its own listing wins
        assertEquals("4243", tones("个人爱好", "ge ren ai hao", "4243", own = "4243"))
        assertEquals("143", tones("核电子", "he dian zi", "143"))
    }

    @Test
    fun weiAtTheEndIsWei2ButInYinwei() {
        assertEquals("22", tones("沦为", "lun wei", "24"))
        assertEquals("14", tones("因为", "yin wei", "14"))
        assertEquals("24", tones("沦为", "lun wei", "24", own = "24"))
    }

    @Test
    fun noRuleMakesAToneNeutral() {
        // the rules that did (们/么/子 endings, kinship said twice, directions, sides) are gone
        assertEquals("232", tones("朋友们", "peng you men", "232"))
        assertEquals("44", tones("舅舅", "jiu jiu", "44"))
        assertEquals("22", tones("回来", "hui lai", "22", own = "22"))
        assertEquals("44", tones("外面", "wai mian", "44"))
    }

    @Test
    fun aNeutralToneIsItsCharactersUsualOneUnlessThatIsNeutralToo() {
        // 东西 dōng xi as phrase-pinyin-data lists it: 西 is xī
        assertEquals("11", ToneRules.full(bytes("15"), bytes("11")).joinToString(""))
        // 我们: 们 has no tone but the neutral one
        assertEquals("35", ToneRules.full(bytes("35"), bytes("35")).joinToString(""))
        // a character with no usual tone known keeps what the source gave
        assertEquals("15", ToneRules.full(bytes("15"), bytes("10")).joinToString(""))
        // toned syllables stand, usual or not
        assertEquals("44", ToneRules.full(bytes("44"), bytes("43")).joinToString(""))
    }
}
