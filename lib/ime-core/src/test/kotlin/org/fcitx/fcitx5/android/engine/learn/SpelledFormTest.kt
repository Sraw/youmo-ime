/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn

import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.learn.SpelledForm.FULL
import org.fcitx.fcitx5.android.engine.learn.SpelledForm.INITIALS
import org.fcitx.fcitx5.android.engine.learn.SpelledForm.PART
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.junit.Assert.assertEquals
import org.junit.Test

/** [SpelledForm]: how a code spells a word of its keyboard, by its keys' count (U-20, D32 B). */
class SpelledFormTest {
    private class Row(val why: String, val kind: String, val keys: String, val reading: String, val form: SpelledForm?)

    private val t9 = Kinds.nineKeys(NineKeyLetters.STANDARD.tag())
    private val sp = Kinds.shuangpin("Ziranma")

    @Test
    fun aCodeSpellsAWordInFullByInitialsInPartOrNot() {
        val rows = listOf(
            Row("every letter", Kinds.FULL, "fenghua", "feng hua", FULL),
            Row("a separator is no key", Kinds.FULL, "xi'an", "xi an", FULL),
            Row("a key a syllable", Kinds.FULL, "fh", "feng hua", INITIALS),
            Row("a part: dian and l", Kinds.FULL, "dianl", "dian le", PART),
            Row("a part: feng and h", Kinds.FULL, "fengh", "feng hua", PART),
            Row("a part, the separator typed", Kinds.FULL, "feng'h", "feng hua", PART),
            Row("zh typed whole is a part, not the 简拼 of a key a syllable", Kinds.FULL, "zhd", "zhi dao", PART),
            Row("the nine keys: a digit a letter", t9, "3364482", "feng hua", FULL),
            Row("the nine keys: a digit a syllable", t9, "34", "feng hua", INITIALS),
            Row("the nine keys: zhi dao by its letters", t9, "9443", "zhi dao", PART),
            Row("the nine keys: a part", t9, "33644", "feng hua", PART),
            Row("双拼: two keys a syllable", sp, "fghw", "feng hua", FULL),
            Row("双拼: a key a syllable", sp, "fh", "feng hua", INITIALS),
            Row("双拼: three keys of two syllables", sp, "fgh", "feng hua", PART),
            Row("fewer keys than syllables spell nothing (F3741)", Kinds.FULL, "f", "feng hua", null),
            Row("a syllable alone", Kinds.FULL, "f", "feng", null),
            Row("no reading", Kinds.FULL, "fenghua", "", null),
            Row("a table: no readings", Kinds.table("wubi"), "fh", "feng hua", null),
            Row("handwriting", Kinds.HANDWRITING, "x", "feng hua", null),
            Row("a 联想", Kinds.PREDICTION, "fh", "feng hua", null),
        )
        assertRows(rows, { it.why }) { assertEquals(it.why, it.form, SpelledForm.of(it.kind, it.keys, it.reading)) }
    }

    // the look before a word is walked in: each syllable's start in turn
    @Test
    fun spellsTellsTheStartsOfTheSyllablesInTurn() {
        class Look(val why: String, val kind: String, val keys: String, val reading: String, val spells: Boolean)
        // the user's own letters: f on 2 (F3768)
        val custom = Kinds.nineKeys(NineKeyLetters.parse("abcf,de,ghi,jkl,mno,pqrs,tuv,wxyz").tag())
        val rows = listOf(
            Look("fengh", Kinds.FULL, "fengh", "feng hua", true),
            Look("feng'h", Kinds.FULL, "feng'h", "feng hua", true),
            Look("fh", Kinds.FULL, "fh", "feng hua", true),
            Look("dl for dian le", Kinds.FULL, "dl", "dian le", true),
            Look("fanh is no start of feng hua", Kinds.FULL, "fanh", "feng hua", false),
            Look("keys left over", Kinds.FULL, "fenghuan", "feng hua", false),
            Look("33644 on the nine keys", t9, "33644", "feng hua", true),
            Look("连按's letters on the nine keys", t9, "fengh", "feng hua", true),
            Look("34 for zhi dao is not", t9, "34", "zhi dao", false),
            Look("a layout of the user's: f on 2", custom, "23644", "feng hua", true),
            Look("a layout of the user's: 3 no longer types f", custom, "33644", "feng hua", false),
            Look("双拼 not looked at", sp, "xx", "feng hua", true),
        )
        assertRows(rows, { it.why }) { assertEquals(it.why, it.spells, SpelledForm.spells(it.kind, it.keys, it.reading)) }
    }
}
