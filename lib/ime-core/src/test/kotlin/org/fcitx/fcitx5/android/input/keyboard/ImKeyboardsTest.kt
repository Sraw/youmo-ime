/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.engine.InputMethodCatalog.ENGLISH
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.HANDWRITING
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.PINYIN
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.SHUANGPIN
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.WUBI
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard.FULL
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard.KEYS14
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard.KEYS18
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard.NINE_KEYS
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboards.Chosen
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboards.Layout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImKeyboardsTest {

    private val ziranma = ShuangpinScheme.ZIRANMA
    private val microsoft = ShuangpinScheme.MICROSOFT

    @Test
    fun eachInputMethodOffersItsKeyboardsInOrder() {
        assertEquals(listOf(FULL, KEYS18, KEYS14, NINE_KEYS), ImKeyboards.choices(PINYIN, ziranma))
        assertEquals(listOf(FULL, KEYS18, KEYS14, NINE_KEYS), ImKeyboards.choices(SHUANGPIN, ziranma))
        assertEquals(listOf(FULL, NINE_KEYS), ImKeyboards.choices(ENGLISH, ziranma))
        assertEquals(emptyList<ImKeyboard>(), ImKeyboards.choices(WUBI, ziranma))
        assertEquals(emptyList<ImKeyboard>(), ImKeyboards.choices(HANDWRITING, ziranma))
    }

    @Test
    fun schemesTypingTheSemicolonOfferNoFourteenKeys() {
        // 微软's ; is ing: no key of the 14 has it (I1 Q5)
        assertEquals(listOf(FULL, KEYS18, NINE_KEYS), ImKeyboards.choices(SHUANGPIN, microsoft))
        assertFalse(ImKeyboards.has14(microsoft))
        assertTrue(ImKeyboards.has14(ShuangpinScheme.XIAOHE))
        // the nearest it offers, the 18 keys, while the scheme has none of the 14; stored so (fallback)
        assertEquals(KEYS18, ImKeyboards.effective(SHUANGPIN, KEYS14, microsoft))
        assertEquals(KEYS14, ImKeyboards.effective(SHUANGPIN, KEYS14, ziranma))
        assertEquals(Layout.SHUANGPIN18, ImKeyboards.layoutOf(SHUANGPIN, Chosen(shuangpin = KEYS14), microsoft))
        assertEquals(KEYS18, ImKeyboards.fallback(SHUANGPIN, KEYS14, microsoft))
        assertNull(ImKeyboards.fallback(SHUANGPIN, KEYS14, ziranma))
        for (k in listOf(FULL, KEYS18, NINE_KEYS)) assertNull(ImKeyboards.fallback(SHUANGPIN, k, microsoft))
        // an input method with no choice has nothing to store; English's nine keys are its own
        assertNull(ImKeyboards.fallback(WUBI, KEYS14, microsoft))
        assertEquals(FULL, ImKeyboards.fallback(ENGLISH, KEYS14, ziranma))
        assertEquals(Layout.SHUANGPIN14, ImKeyboards.layoutOf(SHUANGPIN, Chosen(shuangpin = KEYS14), ziranma))
    }

    @Test
    fun eachInputMethodShowsItsOwnChoice() {
        val chosen = Chosen(pinyin = NINE_KEYS, shuangpin = KEYS18, english = NINE_KEYS)
        assertEquals(Layout.PINYIN_NINE, ImKeyboards.layoutOf(PINYIN, chosen, ziranma))
        assertEquals(Layout.SHUANGPIN18, ImKeyboards.layoutOf(SHUANGPIN, chosen, ziranma))
        assertEquals(Layout.ENGLISH_NINE, ImKeyboards.layoutOf(ENGLISH, chosen, ziranma))
        assertEquals(Layout.HANDWRITING, ImKeyboards.layoutOf(HANDWRITING, chosen, ziranma))
        assertEquals(Layout.TEXT, ImKeyboards.layoutOf(WUBI, chosen, ziranma))
        val rows = listOf(
            Triple(PINYIN, FULL, Layout.TEXT),
            Triple(PINYIN, KEYS18, Layout.PINYIN18),
            Triple(PINYIN, KEYS14, Layout.PINYIN14),
            Triple(PINYIN, NINE_KEYS, Layout.PINYIN_NINE),
            Triple(SHUANGPIN, FULL, Layout.TEXT),
            Triple(SHUANGPIN, KEYS18, Layout.SHUANGPIN18),
            Triple(SHUANGPIN, KEYS14, Layout.SHUANGPIN14),
            Triple(SHUANGPIN, NINE_KEYS, Layout.SHUANGPIN_NINE),
        )
        // each keyboard of pinyin's and 双拼's choice is its own layout
        assertRows(rows, { "${it.first} ${it.second}" }) { (im, k, layout) ->
            assertEquals(layout, ImKeyboards.layoutOf(im, Chosen(pinyin = k, shuangpin = k), ziranma))
        }
        // English has no 18 or 14 keys: one set so (no build writes it) shows the full keyboard
        assertEquals(Layout.TEXT, ImKeyboards.layoutOf(ENGLISH, Chosen(english = KEYS14), ziranma))
        // a table has no choice: whatever pinyin's is
        assertEquals(FULL, Chosen(pinyin = NINE_KEYS).of(WUBI))
    }

    @Test
    fun englishInAPasswordFieldIsTheFullKeyboardWhateverItChose() {
        // the password typed as on a computer; pinyin's nine keys stay, it commits letters there anyway
        assertEquals(Layout.TEXT, ImKeyboards.layoutOf(ENGLISH, Chosen(english = NINE_KEYS), ziranma, password = true))
        assertEquals(Layout.PINYIN_NINE, ImKeyboards.layoutOf(PINYIN, Chosen(pinyin = NINE_KEYS), ziranma, password = true))
    }

    @Test
    fun wordsAreSpelledFromTheKeysOnlyWhereEnglishSuggests() {
        assertTrue(ImKeyboards.spellsWords(predictive = true, suggests = true, password = false, address = false, noSuggestions = false))
        assertFalse(ImKeyboards.spellsWords(predictive = false, suggests = true, password = false, address = false, noSuggestions = false))
        assertFalse(ImKeyboards.spellsWords(predictive = true, suggests = false, password = false, address = false, noSuggestions = false))
        assertFalse(ImKeyboards.spellsWords(predictive = true, suggests = true, password = true, address = false, noSuggestions = false))
        assertFalse(ImKeyboards.spellsWords(predictive = true, suggests = true, password = false, address = true, noSuggestions = false))
        assertFalse(ImKeyboards.spellsWords(predictive = true, suggests = true, password = false, address = false, noSuggestions = true))
    }
}
