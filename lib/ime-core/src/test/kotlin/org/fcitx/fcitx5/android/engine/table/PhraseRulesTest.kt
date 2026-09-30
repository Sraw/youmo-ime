/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PhraseRulesTest {

    // 五笔 86 full codes
    private val codes = mapOf("你" to "wqiy", "们" to "wun", "我" to "trnt", "的" to "rqyy", "工" to "a", "人" to "w", "民" to "nav", "共" to "aw", "和" to "tkg", "国" to "lgyi")

    private val wubi = PhraseRules(linkedMapOf("e2" to "p11+p12+p21+p22", "e3" to "p11+p21+p31+p32", "a4" to "p11+p21+p31+n11"))

    private fun encode(rules: PhraseRules, text: String) = rules.encode(text, codes::get)

    @Test
    fun wubiPhrasesTakeTheirKeysFromEachCharacterByLength() {
        assertEquals("wqwu", encode(wubi, "你们"))
        assertEquals("wtrq", encode(wubi, "你我的"))
        // four or more: the first three characters and the last
        assertEquals("wnat", encode(wubi, "人民共和"))
        assertEquals("wnal", encode(wubi, "人民共和国"))
    }

    @Test
    fun aCodeShorterThanTheRuleReachesGivesWhatItHas() {
        // 工 is just a, 人 just w: p12 and p22 find nothing and are skipped
        assertEquals("aw", encode(wubi, "工人"))
    }

    @Test
    fun noRuleOrNoCodeGivesNothing() {
        assertNull(encode(wubi, "你"))
        assertNull(encode(wubi, "你他"))
        assertNull(encode(PhraseRules(emptyMap()), "你们"))
    }

    @Test
    fun keysMayCountFromTheCodesEndAndATakenKeyIsNotTakenTwice() {
        // z is the last key of a code, y the one before
        val rules = PhraseRules(mapOf("e2" to "p1z+p1y+p2z+p00"))
        assertEquals("yin", encode(rules, "你们"))
        // 人 is one key: p11 and p1z are the same key
        assertEquals("w", PhraseRules(mapOf("e1" to "p11+p1z")).encode("人", codes::get))
    }

    @Test
    fun theFirstRuleThatAppliesWins() {
        val rules = PhraseRules(linkedMapOf("a2" to "p11+n11", "e2" to "p11+p12+p21+p22"))
        assertEquals("ww", encode(rules, "你们"))
    }

    @Test
    fun malformedRulesAreRefused() {
        for ((name, value) in listOf("x2" to "p11", "e" to "p11", "e2" to "q11", "e2" to "p1", "e2" to "p10", "e2" to "p1!")) {
            assertThrows("$name=$value", IllegalArgumentException::class.java) { PhraseRules(mapOf(name to value)) }
        }
    }
}
