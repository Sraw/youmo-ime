/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.content.Context
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.ui.main.settings.PlaceholderStrings
import org.fcitx.fcitx5.android.ui.main.settings.placeholder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * What TalkBack hears as the keyboard window ([SpaceKeyInputMethod]) shows one layout after another.
 * Going back from 九键 to the 26 keys, these were told 九键 first, in the window, so that the change
 * from it would be announced on them too: the user heard the input method they were leaving, then
 * the one they went to, at every switch between the two.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class KeyboardWindowTest {

    /** The window's frame, keeping what its keyboards announce. */
    private class Frame(context: Context) : FrameLayout(context) {
        val announced = ArrayList<String>()

        override fun requestSendAccessibilityEvent(child: View, event: AccessibilityEvent): Boolean {
            if (event.eventType == AccessibilityEvent.TYPE_ANNOUNCEMENT) announced += event.text.joinToString()
            return true
        }
    }

    // under the app, whose preferences the keys read, with placeholder strings, as NumberKeyboardTest
    private val context = PlaceholderStrings(RuntimeEnvironment.getApplication())
    private val frame = Frame(context)
    private val spaceKeys = SpaceKeyInputMethod()

    @Before
    fun talkBackOn() {
        shadowOf(context.getSystemService(AccessibilityManager::class.java)).setEnabled(true)
    }

    // as KeyboardWindow's onImeUpdate and showLayout: the layout shown taken out, the next attached
    private fun show(keyboard: BaseKeyboard, inputMethod: String) {
        spaceKeys.current = InputMethodEntry(inputMethod, inputMethod, "", "", "", "zh", "", false)
        frame.removeAllViews()
        spaceKeys.attach(keyboard) { frame.addView(keyboard) }
    }

    @Test
    fun goingBackToALayoutAnnouncesOnlyTheInputMethodGoneTo() {
        val letters = TextKeyboard(context, ThemePreset.PixelDark)
        show(letters, InputMethodCatalog.PINYIN)
        assertEquals("the window appearing, which TalkBack says itself", emptyList<String>(), frame.announced)
        show(T9Keyboard(context, ThemePreset.PixelDark, shuangpin = true), InputMethodCatalog.SHUANGPIN)
        show(letters, InputMethodCatalog.PINYIN)
        assertEquals(listOf(placeholder(R.string.im_shuangpin), placeholder(R.string.im_pinyin)), frame.announced)
    }

    @Test
    fun aChangeUnderTheNumbersIsAnnouncedOnTheLetters() {
        val letters = TextKeyboard(context, ThemePreset.PixelDark)
        val numbers = NumberKeyboard(context, ThemePreset.PixelDark)
        show(letters, InputMethodCatalog.PINYIN)
        show(numbers, InputMethodCatalog.PINYIN)
        spaceKeys.current = InputMethodEntry(InputMethodCatalog.SHUANGPIN, InputMethodCatalog.SHUANGPIN, "", "", "", "zh", "", false)
        spaceKeys.show(numbers)
        show(letters, InputMethodCatalog.SHUANGPIN)
        assertEquals(listOf(placeholder(R.string.im_shuangpin)), frame.announced)
    }

    // each input method's keyboard its own choice (I1): pinyin's, 双拼's and English's apart
    @Test
    fun eachInputMethodShowsTheKeyboardChosenForIt() {
        val sp = ShuangpinScheme.ZIRANMA
        fun py(k: ImKeyboard) = lettersOf(InputMethodCatalog.PINYIN, ImKeyboards.Chosen(pinyin = k), sp)
        fun shp(k: ImKeyboard, scheme: ShuangpinScheme = sp) = lettersOf(InputMethodCatalog.SHUANGPIN, ImKeyboards.Chosen(shuangpin = k), scheme)
        fun en(k: ImKeyboard) = lettersOf(InputMethodCatalog.ENGLISH, ImKeyboards.Chosen(english = k), sp)
        assertEquals(TextKeyboard.Name, py(ImKeyboard.FULL))
        assertEquals(GroupedKeyboard.NamePinyin18, py(ImKeyboard.KEYS18))
        assertEquals(GroupedKeyboard.Name14, py(ImKeyboard.KEYS14))
        assertEquals(T9Keyboard.Name, py(ImKeyboard.NINE_KEYS))
        assertEquals(TextKeyboard.Name, shp(ImKeyboard.FULL))
        // 自然码 types no `;`: L alone; 微软's ing is L;
        assertEquals(GroupedKeyboard.Name18Plain, shp(ImKeyboard.KEYS18))
        assertEquals(GroupedKeyboard.Name18Plain, shp(ImKeyboard.KEYS18, ShuangpinScheme.XIAOHE))
        assertEquals(GroupedKeyboard.Name18, shp(ImKeyboard.KEYS18, ShuangpinScheme.MICROSOFT))
        assertEquals(GroupedKeyboard.NameShuangpin14, shp(ImKeyboard.KEYS14))
        assertEquals(T9Keyboard.ShuangpinPlainName, shp(ImKeyboard.NINE_KEYS))
        assertEquals(T9Keyboard.ShuangpinName, shp(ImKeyboard.NINE_KEYS, ShuangpinScheme.MICROSOFT))
        // 微软 has no 14 keys (`;` is ing): the 18 keys, the nearest
        assertEquals(GroupedKeyboard.Name18, shp(ImKeyboard.KEYS14, ShuangpinScheme.MICROSOFT))
        assertEquals(TextKeyboard.Name, en(ImKeyboard.FULL))
        assertEquals(T9Keyboard.EnglishName, en(ImKeyboard.NINE_KEYS))
        // one input method's choice moves no other's
        val all = ImKeyboards.Chosen(ImKeyboard.NINE_KEYS, ImKeyboard.KEYS18, ImKeyboard.NINE_KEYS)
        assertEquals(TextKeyboard.Name, lettersOf(InputMethodCatalog.WUBI, all, sp))
        assertEquals(HandwritingKeyboard.Name, lettersOf(InputMethodCatalog.HANDWRITING, all, sp))
        assertEquals(TextKeyboard.Name, lettersOf(InputMethodCatalog.ENGLISH, ImKeyboards.Chosen(pinyin = ImKeyboard.NINE_KEYS), sp))
    }

    @Test
    fun onlyTheNineKeysColumnsOwnTheSyllables() {
        assertEquals(true, T9Keyboard(context, ThemePreset.PixelDark, shuangpin = true).ownsSyllables)
        assertEquals(true, T9Keyboard(context, ThemePreset.PixelDark).ownsSyllables)
        assertEquals(false, T9Keyboard(context, ThemePreset.PixelDark, english = true).ownsSyllables)
        assertEquals(false, GroupedKeyboard(context, ThemePreset.PixelDark, NineKeyLetters.G18).ownsSyllables)
        assertEquals(false, TextKeyboard(context, ThemePreset.PixelDark).ownsSyllables)
    }

    @Test
    fun theCodeRowsSyllablesHideWhereTheKeyboardOffersThemOrItsLayoutShowsNone() {
        // the keyboard's own column (九键) owns them, whatever the layout
        assertTrue(syllablesOwned(true, CodeRowHeight.showsStrip(ImKeyboards.Layout.PINYIN_NINE)))
        // a layout without the strip (the full keys) hides them, floating or embedded alike
        assertTrue(syllablesOwned(false, CodeRowHeight.showsStrip(ImKeyboards.Layout.TEXT)))
        // the 14 and 18 keys, 双拼's nine keys (its 123 too): the strip shows
        for (layout in listOf(ImKeyboards.Layout.PINYIN14, ImKeyboards.Layout.PINYIN18, ImKeyboards.Layout.SHUANGPIN_NINE)) {
            assertFalse("$layout", syllablesOwned(false, CodeRowHeight.showsStrip(layout)))
        }
    }

    // the tall row goes with the letters that put syllables on it: kept in step with lettersOf
    @Test
    fun theTallCodeRowGoesWithTheGroupedLetters() {
        val grouped = setOf(GroupedKeyboard.Name14, GroupedKeyboard.NamePinyin18, GroupedKeyboard.Name18, GroupedKeyboard.Name18Plain, GroupedKeyboard.NameShuangpin14, T9Keyboard.ShuangpinName, T9Keyboard.ShuangpinPlainName, ZhuyinKeyboard.NameNine)
        for (layout in ImKeyboards.Layout.entries) {
            assertEquals("$layout", nameOf(layout) in grouped, CodeRowHeight.showsStrip(layout))
        }
    }

    // quick input is typed in letters: the pad and the zhuyin keyboards, which send none, give way to
    // the full keyboard while it lasts; the keyboards with letters of their own stay
    @Test
    fun quickInputShowsTheFullKeyboardInPlaceOfOnesWithoutLetters() {
        for (k in listOf(HandwritingKeyboard.Name, ZhuyinKeyboard.NameNine, ZhuyinKeyboard.NameFull)) {
            assertEquals(k, TextKeyboard.Name, KeyboardWindow.quickInputStandIn(k))
        }
        for (k in listOf(TextKeyboard.Name, T9Keyboard.Name, GroupedKeyboard.Name14, GroupedKeyboard.Name18, NumberKeyboard.Name)) {
            assertEquals(k, null, KeyboardWindow.quickInputStandIn(k))
        }
    }

    // quick input ended: the keyboard it stood in for, unless the letters changed meanwhile (onImeUpdate
    // shows another input method's at once; this is the backstop): then the new ones, not 大千 under the nine keys
    @Test
    fun quickInputEndedShowsTheKeyboardStoodInForOrTheNewLetters() {
        fun row(why: String, stoodInFor: String, lettersThen: String, letters: String, expected: String) =
            why to { assertEquals(expected, KeyboardWindow.afterQuickInput(stoodInFor, lettersThen, letters)) }
        assertRows(
            row("大千 stood in for, the letters the same: 大千", ZhuyinKeyboard.NameFull, ZhuyinKeyboard.NameFull, ZhuyinKeyboard.NameFull, ZhuyinKeyboard.NameFull),
            row("the pad shown over another input method's letters stays the pad", HandwritingKeyboard.Name, TextKeyboard.Name, TextKeyboard.Name, HandwritingKeyboard.Name),
            row("大千's letters gone to the nine keys meanwhile: the nine keys", ZhuyinKeyboard.NameFull, ZhuyinKeyboard.NameFull, T9Keyboard.Name, T9Keyboard.Name),
            row("注音九键's letters gone to 大千 meanwhile: 大千, not 大千 under the nine keys", ZhuyinKeyboard.NameNine, ZhuyinKeyboard.NameNine, ZhuyinKeyboard.NameFull, ZhuyinKeyboard.NameFull),
            row("the pad over the nine keys, the letters gone to the 26 meanwhile: the pad, as out of quick input", HandwritingKeyboard.Name, T9Keyboard.Name, TextKeyboard.Name, HandwritingKeyboard.Name),
        )
    }

    // another input method chosen during quick input: the keyboard stood in for gives way to its letters
    // as it would, shown (review Q1, the user's call 2026-11-02: the same as out of quick input)
    @Test
    fun theKeyboardStoodInForGivesWayAsItWouldShown() {
        fun outOfQuickInput(was: String, lettersThen: String, letters: String): Boolean {
            val l = LettersLayout(setOf(TextKeyboard.Name, T9Keyboard.Name, HandwritingKeyboard.Name, ZhuyinKeyboard.NameFull), TextKeyboard.Name, "symbol")
            l.inputMethodChanged(lettersThen, l.letters)
            return l.inputMethodChanged(letters, was) != null
        }
        fun row(why: String, was: String, then: String, now: String) =
            why to { assertEquals("$was $then $now", outOfQuickInput(was, then, now), KeyboardWindow.givesWay(was, then, now)) }
        assertRows(
            row("handwriting's pad, English chosen: English's letters", HandwritingKeyboard.Name, HandwritingKeyboard.Name, TextKeyboard.Name),
            row("大千, English chosen: English's letters", ZhuyinKeyboard.NameFull, ZhuyinKeyboard.NameFull, TextKeyboard.Name),
            row("the same input method again: nothing changes", ZhuyinKeyboard.NameFull, ZhuyinKeyboard.NameFull, ZhuyinKeyboard.NameFull),
            row("the pad over the nine keys, English chosen: the pad stays", HandwritingKeyboard.Name, T9Keyboard.Name, TextKeyboard.Name),
            row("the pad over English's letters, English again: the pad stays", HandwritingKeyboard.Name, TextKeyboard.Name, TextKeyboard.Name),
        )
    }
}
