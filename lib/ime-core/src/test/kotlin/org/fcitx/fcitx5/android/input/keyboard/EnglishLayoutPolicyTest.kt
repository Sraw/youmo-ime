/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.engine.host.Engines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnglishLayoutPolicyTest {

    @Test
    fun theLanguageKeyGoesBetweenNineKeyPinyinAndEnglishAlone() {
        val both = listOf(Engines.PINYIN, Engines.ENGLISH, Engines.T9)
        assertEquals(Engines.ENGLISH, EnglishLayoutPolicy.toggled(Engines.T9, both))
        assertEquals(Engines.T9, EnglishLayoutPolicy.toggled(Engines.ENGLISH, both))
        assertNull(EnglishLayoutPolicy.toggled(Engines.PINYIN, both))
        assertNull(EnglishLayoutPolicy.toggled(Engines.HANDWRITING, both))
        // English not enabled: nowhere, rather than a switch fcitx makes to none
        assertNull(EnglishLayoutPolicy.toggled(Engines.T9, listOf(Engines.PINYIN, Engines.T9)))
    }

    @Test
    fun englishIsOnTheNineKeysOnlyAsTheKeyBroughtIt() {
        assertTrue(EnglishLayoutPolicy.nineKeys(Engines.ENGLISH, onNineKeys = true))
        // come to another way: its own input method, the full keyboard
        assertFalse(EnglishLayoutPolicy.nineKeys(Engines.ENGLISH, onNineKeys = false))
        // anything else shown drops it
        assertFalse(EnglishLayoutPolicy.nineKeys(Engines.T9, onNineKeys = true))
        assertFalse(EnglishLayoutPolicy.nineKeys(Engines.PINYIN, onNineKeys = true))
    }

    @Test
    fun theRoundOfInputMethodsGoesOnFromNineKeyPinyinForItsEnglish() {
        // anything but English shown ends it, 九键拼音 too (the picker, a hardware key)
        assertTrue(EnglishLayoutPolicy.endsNineKeys(Engines.PINYIN, current = Engines.PINYIN))
        assertTrue(EnglishLayoutPolicy.endsNineKeys(Engines.T9, current = Engines.T9))
        assertFalse(EnglishLayoutPolicy.endsNineKeys(Engines.ENGLISH, current = Engines.ENGLISH))
        // 九键拼音's update come behind 中/英 pressed again, fcitx on English by now: not ended
        assertFalse(EnglishLayoutPolicy.endsNineKeys(Engines.T9, current = Engines.ENGLISH))
        assertEquals(Engines.PINYIN, EnglishLayoutPolicy.nextFromNineKeys(listOf(Engines.ENGLISH, Engines.T9, Engines.PINYIN)))
        // round to the first: English itself, the flag dropped all that changes (the keyboard told
        // to show it anew), the full keyboard
        assertEquals(Engines.ENGLISH, EnglishLayoutPolicy.nextFromNineKeys(listOf(Engines.ENGLISH, Engines.T9)))
        assertNull(EnglishLayoutPolicy.nextFromNineKeys(listOf(Engines.ENGLISH, Engines.PINYIN)))
    }

    @Test
    fun wordsAreSpelledFromTheKeysOnlyWhereEnglishSuggests() {
        assertTrue(EnglishLayoutPolicy.spellsWords(predictive = true, suggests = true, password = false, address = false, noSuggestions = false))
        assertFalse(EnglishLayoutPolicy.spellsWords(predictive = false, suggests = true, password = false, address = false, noSuggestions = false))
        assertFalse(EnglishLayoutPolicy.spellsWords(predictive = true, suggests = false, password = false, address = false, noSuggestions = false))
        assertFalse(EnglishLayoutPolicy.spellsWords(predictive = true, suggests = true, password = true, address = false, noSuggestions = false))
        assertFalse(EnglishLayoutPolicy.spellsWords(predictive = true, suggests = true, password = false, address = true, noSuggestions = false))
        assertFalse(EnglishLayoutPolicy.spellsWords(predictive = true, suggests = true, password = false, address = false, noSuggestions = true))
    }
}
