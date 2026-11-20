/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.ENGLISH
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.PINYIN
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.SHUANGPIN
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.WUBI
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard.CUSTOM
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard.FULL
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard.KEYS14
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard.KEYS18
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard.NINE_KEYS
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard.ZHUYIN_FULL
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard.ZHUYIN_NINE
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
        assertEquals(listOf(FULL, KEYS18, KEYS14, NINE_KEYS, ZHUYIN_NINE, ZHUYIN_FULL), ImKeyboards.choices(PINYIN, ziranma))
        assertEquals(listOf(FULL, KEYS18, KEYS14, NINE_KEYS), ImKeyboards.choices(SHUANGPIN, ziranma))
        assertEquals(listOf(FULL, NINE_KEYS), ImKeyboards.choices(ENGLISH, ziranma))
        assertEquals(emptyList<ImKeyboard>(), ImKeyboards.choices(WUBI, ziranma))
    }

    @Test
    fun schemesTypingTheSemicolonOfferNoFourteenKeys() {
        // 微软's ; is ing: no key of the 14 has it
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
        assertEquals(Layout.TEXT, ImKeyboards.layoutOf(WUBI, chosen, ziranma))
        val rows = listOf(
            Triple(PINYIN, FULL, Layout.TEXT),
            Triple(PINYIN, KEYS18, Layout.PINYIN18),
            Triple(PINYIN, KEYS14, Layout.PINYIN14),
            Triple(PINYIN, NINE_KEYS, Layout.PINYIN_NINE),
            Triple(PINYIN, ZHUYIN_NINE, Layout.ZHUYIN_NINE),
            Triple(PINYIN, ZHUYIN_FULL, Layout.ZHUYIN_FULL),
            Triple(SHUANGPIN, FULL, Layout.TEXT),
            Triple(SHUANGPIN, KEYS18, Layout.SHUANGPIN18),
            Triple(SHUANGPIN, KEYS14, Layout.SHUANGPIN14),
            Triple(SHUANGPIN, NINE_KEYS, Layout.SHUANGPIN_NINE),
        )
        // each keyboard of pinyin's and 双拼's choice is its own layout
        assertRows(rows, { "${it.first} ${it.second}" }) { (im, k, layout) ->
            assertEquals(layout, ImKeyboards.layoutOf(im, Chosen(pinyin = k, shuangpin = k), ziranma))
        }
        // zhuyin is pinyin's alone: 双拼 set so (no build writes it) shows the full keyboard
        assertEquals(Layout.TEXT, ImKeyboards.layoutOf(SHUANGPIN, Chosen(shuangpin = ZHUYIN_NINE), ziranma))
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

    // 清除's 恢复 types again on the same alone: the layout shown, and 双拼's scheme
    @Test
    fun whatIsTypedWithIsTheLayoutAndShuangpinsScheme() {
        val chosen = Chosen(pinyin = NINE_KEYS, shuangpin = KEYS14)
        val xiaohe = ShuangpinScheme.XIAOHE
        assertEquals(ImKeyboards.typedWith(PINYIN, chosen, xiaohe), ImKeyboards.typedWith(PINYIN, chosen, ShuangpinScheme.ZIRANMA))
        assertEquals(Layout.PINYIN_NINE, ImKeyboards.typedWith(PINYIN, chosen, xiaohe).layout)
        assertTrue(ImKeyboards.typedWith(PINYIN, chosen, xiaohe) != ImKeyboards.typedWith(PINYIN, Chosen(), xiaohe))
        assertTrue("another scheme", ImKeyboards.typedWith(SHUANGPIN, Chosen(), xiaohe) != ImKeyboards.typedWith(SHUANGPIN, Chosen(), ShuangpinScheme.ZIRANMA))
        // the 14 keys under 微软 are the 18: the layout shown, not the choice
        assertEquals(Layout.SHUANGPIN18, ImKeyboards.typedWith(SHUANGPIN, chosen, ShuangpinScheme.MICROSOFT).layout)
    }

    // a layout of the user's own: who may use it, what is shown, what stands in for it
    private val g18 = listOf("q", "we", "rt", "y", "u", "io", "p", "a", "sd", "fg", "h", "jk", "l;", "z", "xc", "v", "bn", "m")
    private val g14 = listOf("qw", "er", "ty", "ui", "op", "as", "df", "gh", "jk", "l", "zx", "cv", "bn", "m")
    private val letters = CustomChoice("my-qwerty", "我的", "letters", "qwerty", tableExtras = true)
    private val nine = CustomChoice("my-nine", "九", "nine", "nine")
    private val k18 = CustomChoice("my-k18", "18", "grouped", "k18", g18.reversed())
    private val k14 = CustomChoice("my-k14", "14", "grouped", "k14", g14)
    private val k17 = CustomChoice("k17", "17", "grouped", "k18", listOf("qw", "er", "t", "y", "u", "io", "p", "as", "df", "g", "h", "jk", "l", "zx", "cv", "bn", "m"))

    // two layouts of the 18 keys' groups in other orders type the same codes; two of their own groupings do not
    @Test
    fun theEighteenKeysRearrangedEitherWayAreTypedWithAlike() {
        val zr = ShuangpinScheme.ZIRANMA
        val asBuiltIn = CustomChoice("my-k18b", "18b", "grouped", "k18", g18)
        assertEquals(ImKeyboards.typedWith(PINYIN, chosen(PINYIN, k18), zr), ImKeyboards.typedWith(PINYIN, chosen(PINYIN, asBuiltIn), zr))
        assertEquals(null, ImKeyboards.typedWith(PINYIN, chosen(PINYIN, k18), zr).groups)
        val k17back = CustomChoice("k17b", "17b", "grouped", "k18", k17.groups.reversed())
        assertTrue(ImKeyboards.typedWith(PINYIN, chosen(PINYIN, k17), zr) != ImKeyboards.typedWith(PINYIN, chosen(PINYIN, k17back), zr))
    }

    private fun chosen(im: String, layout: CustomChoice, table: Boolean = false) = Chosen(
        pinyin = if (im == PINYIN) CUSTOM else FULL,
        shuangpin = if (im == SHUANGPIN) CUSTOM else FULL,
        english = if (im == ENGLISH) CUSTOM else FULL,
        table = if (table) CUSTOM else FULL,
        layouts = mapOf(ImKeyboards.slot(im) to layout.id),
        customs = mapOf(layout.id to layout),
    )

    @Test
    fun aLayoutOfOnesOwnIsUsedByTheInputMethodsItsFamilyTypes() {
        val all = listOf(letters, nine, k18, k14, k17)
        assertEquals(all, ImKeyboards.customs(PINYIN, ziranma, all))
        // 双拼: the 14 or 18 keys' groups in any order, the 14 not under 微软; no grouping of its own
        assertEquals(listOf(letters, nine, k18, k14), ImKeyboards.customs(SHUANGPIN, ziranma, all))
        assertEquals(listOf(letters, nine, k18), ImKeyboards.customs(SHUANGPIN, microsoft, all))
        assertEquals(listOf(letters, nine), ImKeyboards.customs(ENGLISH, ziranma, all))
        // a table: letters with the keys it types beside them
        assertEquals(listOf(letters), ImKeyboards.customs(WUBI, ziranma, all))
        assertEquals(emptyList<CustomChoice>(), ImKeyboards.customs(WUBI, ziranma, listOf(letters.copy(tableExtras = false))))
        assertTrue(ImKeyboards.offers(WUBI, ziranma, all))
        assertFalse(ImKeyboards.offers(WUBI, ziranma, listOf(nine)))
        // one not drawn: none
        assertFalse(ImKeyboards.usable(PINYIN, letters.copy(drawn = false), ziranma))
        assertEquals(KEYS18, ImKeyboards.groupedAs(k18))
        assertEquals(KEYS14, ImKeyboards.groupedAs(k14))
        assertNull(ImKeyboards.groupedAs(k17))
    }

    @Test
    @Uat("PY-154")
    fun aLayoutOfOnesOwnIsShownAsTheBuiltInOneItIsTypedAs() {
        assertEquals(ImKeyboards.Shown(Layout.TEXT, letters), ImKeyboards.shown(PINYIN, chosen(PINYIN, letters), ziranma))
        assertEquals(ImKeyboards.Shown(Layout.TEXT, letters), ImKeyboards.shown(WUBI, chosen(WUBI, letters, table = true), ziranma))
        assertEquals(ImKeyboards.Shown(Layout.SHUANGPIN_NINE, nine), ImKeyboards.shown(SHUANGPIN, chosen(SHUANGPIN, nine), ziranma))
        assertEquals(ImKeyboards.Shown(Layout.ENGLISH_NINE, nine), ImKeyboards.shown(ENGLISH, chosen(ENGLISH, nine), ziranma))
        assertEquals(ImKeyboards.Shown(Layout.PINYIN18, k18), ImKeyboards.shown(PINYIN, chosen(PINYIN, k18), ziranma))
        assertEquals(ImKeyboards.Shown(Layout.SHUANGPIN14, k14), ImKeyboards.shown(SHUANGPIN, chosen(SHUANGPIN, k14), ziranma))
        // pinyin's keyboard of its own groups: the 14 keys', which takes a layout's own
        assertEquals(ImKeyboards.Shown(Layout.PINYIN14, k17), ImKeyboards.shown(PINYIN, chosen(PINYIN, k17), ziranma))
        assertEquals(Layout.PINYIN14, ImKeyboards.layoutOf(PINYIN, chosen(PINYIN, k17), ziranma))
        // a password: letters stay; English's nine keys the full keyboard, the app's
        assertEquals(ImKeyboards.Shown(Layout.TEXT, letters), ImKeyboards.shown(ENGLISH, chosen(ENGLISH, letters), ziranma, password = true))
        assertEquals(ImKeyboards.Shown(Layout.TEXT), ImKeyboards.shown(ENGLISH, chosen(ENGLISH, nine), ziranma, password = true))
        // the grouping typed with: another grouping, other codes
        assertEquals(k17.groups, ImKeyboards.typedWith(PINYIN, chosen(PINYIN, k17), ziranma).groups)
        assertNull(ImKeyboards.typedWith(PINYIN, chosen(PINYIN, letters), ziranma).groups)
    }

    @Test
    fun aLayoutOfOnesOwnNotDrawnOrDeletedGivesWayToItsBase() {
        // not drawn (its file refused): the base, the choice kept
        val refused = k18.copy(drawn = false)
        assertEquals(KEYS18, ImKeyboards.effective(PINYIN, CUSTOM, ziranma, refused))
        assertEquals(ImKeyboards.Shown(Layout.PINYIN18), ImKeyboards.shown(PINYIN, chosen(PINYIN, refused), ziranma))
        assertNull(ImKeyboards.fallback(PINYIN, CUSTOM, ziranma, refused))
        // not read at all: no base known, the full keyboard
        assertEquals(FULL, ImKeyboards.effective(PINYIN, CUSTOM, ziranma, null))
        assertEquals(Layout.TEXT, ImKeyboards.layoutOf(PINYIN, Chosen(pinyin = CUSTOM, layouts = mapOf(PINYIN to "gone")), ziranma))
        assertNull(ImKeyboards.fallback(PINYIN, CUSTOM, ziranma, null))
        // one the scheme no longer has (the 14 keys' under 微软): stored as its base's nearest
        assertEquals(KEYS18, ImKeyboards.fallback(SHUANGPIN, CUSTOM, microsoft, k14))
        assertNull(ImKeyboards.fallback(SHUANGPIN, CUSTOM, ziranma, k14))
        // English has no 18 keys: the full keyboard
        assertEquals(FULL, ImKeyboards.effective(ENGLISH, CUSTOM, ziranma, k18))
        // deleted: each one that chose it its base where it has it, else the full keyboard
        val both = Chosen(pinyin = CUSTOM, english = CUSTOM, shuangpin = CUSTOM, layouts = mapOf(PINYIN to "my-k18", ENGLISH to "my-k18", SHUANGPIN to "other"))
        assertEquals(mapOf(PINYIN to KEYS18, ENGLISH to FULL), ImKeyboards.afterDelete(both, "my-k18", k18, ziranma))
        assertEquals(mapOf(PINYIN to FULL, ENGLISH to FULL), ImKeyboards.afterDelete(both, "my-k18", null, ziranma))
        val table = Chosen(table = CUSTOM, layouts = mapOf(ImKeyboards.TABLE to "my-qwerty"))
        assertEquals(mapOf(ImKeyboards.TABLE to FULL), ImKeyboards.afterDelete(table, "my-qwerty", letters, ziranma))
        // a layout chosen, but the choice another keyboard since: not its
        assertEquals(emptyMap<String, ImKeyboard>(), ImKeyboards.afterDelete(Chosen(layouts = mapOf(PINYIN to "my-k18")), "my-k18", k18, ziranma))
    }

    // 按键字符和快捷键's tabs of the user's layouts: each drawn once, in the slots' order
    @Test
    fun theLayoutsInUseAreThoseDrawn() {
        val customs = listOf(letters, nine, k14).associateBy { it.id }
        val chosen = Chosen(
            pinyin = CUSTOM, shuangpin = CUSTOM, english = CUSTOM, table = CUSTOM,
            layouts = mapOf(PINYIN to "my-nine", SHUANGPIN to "my-k14", ENGLISH to "my-qwerty", ImKeyboards.TABLE to "my-qwerty"),
            customs = customs,
        )
        assertEquals(listOf("my-nine", "my-k14", "my-qwerty"), ImKeyboards.inUse(chosen, ziranma).map { it.id })
        // 双拼 under 微软 types ; on the 14 keys' groups: its base drawn, not the layout
        assertEquals(listOf("my-nine", "my-qwerty"), ImKeyboards.inUse(chosen, microsoft).map { it.id })
        // chosen once, the choice another keyboard since; one that does not read: none
        assertEquals(emptyList<String>(), ImKeyboards.inUse(Chosen(layouts = mapOf(PINYIN to "my-nine"), customs = customs), ziranma).map { it.id })
        assertEquals(emptyList<String>(), ImKeyboards.inUse(Chosen(pinyin = CUSTOM, layouts = mapOf(PINYIN to "gone"), customs = customs), ziranma).map { it.id })
    }

    @Test
    fun aLayoutsCardSaysWhoUsesItAndWhoMay() {
        val both = Chosen(pinyin = CUSTOM, english = CUSTOM, table = CUSTOM, layouts = mapOf(PINYIN to "my-qwerty", ENGLISH to "my-qwerty", ImKeyboards.TABLE to "other"))
        assertEquals(listOf(PINYIN, ENGLISH), ImKeyboards.usedBy(both, "my-qwerty"))
        assertEquals(listOf(ImKeyboards.TABLE), ImKeyboards.usedBy(both, "other"))
        // who may: letters every one (a table where it has ; and *), the 14 keys' groups not 双拼 under 微软
        assertEquals(ImKeyboards.SLOTS, ImKeyboards.usableBy(letters, ziranma))
        assertEquals(listOf(PINYIN, SHUANGPIN, ENGLISH), ImKeyboards.usableBy(letters.copy(tableExtras = false), ziranma))
        assertEquals(listOf(PINYIN, SHUANGPIN), ImKeyboards.usableBy(k14, ziranma))
        assertEquals(listOf(PINYIN), ImKeyboards.usableBy(k14, microsoft))
        assertEquals(emptyList<String>(), ImKeyboards.usableBy(k18.copy(drawn = false), ziranma))
        // 用在… set: ticked it; unticked where it was, its base; unticked elsewhere, as it is
        val pinyin = Chosen(pinyin = CUSTOM, layouts = mapOf(PINYIN to "my-k18"))
        assertEquals(CUSTOM, ImKeyboards.ticked(pinyin, SHUANGPIN, k18, true, ziranma))
        assertEquals(KEYS18, ImKeyboards.ticked(pinyin, PINYIN, k18, false, ziranma))
        assertNull(ImKeyboards.ticked(pinyin, SHUANGPIN, k18, false, ziranma))
    }
}
