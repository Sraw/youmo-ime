/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.graphics.Rect
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.g14Code
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The 14 keys (拼音双键) as iFlytek lays them out. A tap sends the group's code, never a letter (an ASCII letter
 * would go to full pinyin); a swipe its corner, a digit as a keypad's; the long press the group's letters.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class GroupedKeyboardTest {

    private val context = RuntimeEnvironment.getApplication()

    @After
    fun prefsAtTheirDefaults() {
        AppPrefs.getInstance().keyboard.showLangSwitchKey.let { it.setValue(it.defaultValue) }
    }

    @Test
    fun aLetterOrDigitOfTheLongPressIsCommittedAsItIs() {
        val popup = KeyActionListener.Source.Popup
        assertEquals(KeyAction.CommitAction("q"), GroupedKeyboard.resolve(KeyAction.HostKeyAction("q"), popup))
        assertEquals(KeyAction.CommitAction("W"), GroupedKeyboard.resolve(KeyAction.HostKeyAction("W"), popup))
        assertEquals(KeyAction.CommitAction("1"), GroupedKeyboard.resolve(KeyAction.HostKeyAction("1"), popup))
        assertEquals(KeyAction.HostKeyAction("%"), GroupedKeyboard.resolve(KeyAction.HostKeyAction("%"), popup))
        val keys = KeyActionListener.Source.Keyboard
        assertEquals(KeyAction.HostKeyAction("'"), GroupedKeyboard.resolve(KeyAction.HostKeyAction("'"), keys))
        assertEquals(GroupedKeyboard.code(g14Code('b')), GroupedKeyboard.resolve(GroupedKeyboard.code(g14Code('b')), keys))
    }

    @Test
    @Uat("PY-91", "SP-20")
    fun theBottomRowHasNoChineseEnglishKeyAndTheLanguageSwitchFollowsItsSetting() {
        assertRows(
            listOf("PY-91: fourteen keys" to NineKeyLetters.G14, "SP-20: eighteen keys" to NineKeyLetters.G18),
            { it.first },
        ) { (_, letters) ->
            val showLang = AppPrefs.getInstance().keyboard.showLangSwitchKey
            showLang.setValue(showLang.defaultValue)
            val keyboard = GroupedKeyboard(context, TestThemes.PixelDark, letters).laidOut()
            assertTrue(keyboard.shownKeyWithId(R.id.button_lang) != null)
            listOf(R.id.button_space, R.id.button_return, R.id.button_comma, R.id.button_period, R.id.button_backspace).forEach {
                assertTrue(keyboard.resources.getResourceEntryName(it), keyboard.shownKeyWithId(it) != null)
            }
            showLang.setValue(false)
            assertEquals(null, keyboard.shownKeyWithId(R.id.button_lang))
            assertEquals(null, GroupedKeyboard(context, TestThemes.PixelDark, letters).laidOut().shownKeyWithId(R.id.button_lang))
        }
    }

    @Test
    @Uat("PY-93")
    fun theSpaceIsAtLeast15PercentOfTheRowInEveryField() {
        assertSpaceAtLeast15Percent(GroupedKeyboard(context, TestThemes.PixelDark))
    }

    @Test
    fun anAddressFieldShowsWhatAddressesAreMadeOf() {
        val keyboard = GroupedKeyboard(context, TestThemes.PixelDark).laidOut()
        keyboard.address = AddressKeys.URL
        assertEquals(null, keyboard.shownKeyWithId(R.id.button_comma))
        assertTrue(keyboard.shownKeyWithId(R.id.button_slash) != null)
        assertTrue(keyboard.shownKeyWithId(R.id.button_domain) != null)
    }

    @Test
    @Uat("PY-105")
    fun talkBackReadsAGroupLetterByLetterWithItsCorner() {
        val keyboard = GroupedKeyboard(context, TestThemes.PixelDark).laidOut()
        val groups = keyboard.shownKeys().filter { it.id?.matches(PLACE) == true }
        val described = groups.associate { it.label to it.description }
        assertEquals(context.getString(R.string.a11y_key_grouped, "Q W", "1"), described["QW"])
        assertEquals(context.getString(R.string.a11y_key_grouped, "L", "0"), described["L"])
        assertEquals(context.getString(R.string.a11y_key_grouped, "B N", "@"), described["BN"])
        assertEquals(14, described.size)
        assertEquals(context.getString(R.string.a11y_key_separator), keyboard.shownKey("分词").description)
        // L shown without its `;` is still a group key: once it was matched against "L;" and left out
        val g18 = GroupedKeyboard(context, TestThemes.PixelDark, NineKeyLetters.G18, Keyboard::pinyin18Key, semicolon = false).laidOut()
        val keys18 = g18.shownKeys().filter { it.id?.matches(PLACE) == true }
        val l = keys18.single { it.label == "L" }
        assertEquals(context.getString(R.string.a11y_key_grouped, "L", l.alt), l.description)
        assertEquals(18, keys18.count { it.description != null })
    }

    @Test
    @Uat("KB-65")
    fun aSwipesShortcutShowsAsItIsNamedNotInTheLettersCase() {
        val keyboard = GroupedKeyboard(context, TestThemes.PixelDark).laidOut()
        val previews = ArrayList<PopupAction>()
        keyboard.popupActionListener = PopupActionListener { previews += it }
        val key = keyboard.shownKey("ZX").ref()
        // a shortcut's name and a user's text shortcut keep their case; a letter's bubble is in the faces' case
        keyboard.onPopupAction(PopupAction.PreviewUpdateAction(key, "Paste", words = true))
        keyboard.onPopupAction(PopupAction.PreviewUpdateAction(key, "ok", words = true))
        keyboard.onPopupAction(PopupAction.PreviewUpdateAction(key, "x", words = false))
        assertEquals("ZX", keyboard.shownKey("ZX").label)
        assertEquals(listOf("Paste", "ok", "X"), previews.map { (it as PopupAction.PreviewUpdateAction).content })
    }

    @Test
    fun theCommaFullStopAndCornersShowWhatTheyType() {
        val keyboard = GroupedKeyboard(context, TestThemes.PixelDark).laidOut()
        keyboard.onPunctuationUpdate(mapOf("," to "，", "." to "。", "~" to "～"))
        assertEquals("，", keyboard.shownKeyWithId(R.id.button_comma)!!.label)
        assertEquals("。", keyboard.shownKeyWithId(R.id.button_period)!!.label)
        val zx = keyboard.shownKey("ZX")
        assertEquals("～", zx.alt)
        // TalkBack reads the corner drawn, not the key's own ~
        assertEquals(context.getString(R.string.a11y_key_grouped, "Z X", "～"), zx.description)
        val previews = ArrayList<PopupAction>()
        keyboard.popupActionListener = PopupActionListener { previews += it }
        keyboard.onPopupAction(PopupAction.PreviewAction(keyboard.shownKeyWithId(R.id.button_comma)!!.ref(), ",", Rect()))
        assertEquals("，", (previews.single() as PopupAction.PreviewAction).content)
        keyboard.address = AddressKeys.URL
        assertEquals(".", keyboard.shownKeyWithId(R.id.button_dot)!!.label)
    }

    private companion object {
        val PLACE = Regex("r[0-9]c[0-9]")
    }
}

/** The space takes at least 0.15 of the bottom row in every field, the language key shown or not; laid out 1080 px wide. */
internal fun assertSpaceAtLeast15Percent(keyboard: GroupedKeyboard) {
    val width = 1080
    val showLang = AppPrefs.getInstance().keyboard.showLangSwitchKey
    val before = showLang.getValue()
    val narrow = ArrayList<String>()
    try {
        for (lang in listOf(true, false)) {
            showLang.setValue(lang)
            for (address in listOf(AddressKeys.NONE, AddressKeys.URL, AddressKeys.EMAIL)) {
                keyboard.address = address
                keyboard.laidOut(width, 800)
                val space = keyboard.shownKeyWithId(R.id.button_space)!!.width
                if (space < 0.15 * width) narrow += "$address, language key $lang: space $space of $width"
            }
        }
    } finally {
        showLang.setValue(before)
    }
    assertTrue(narrow.joinToString("; "), narrow.isEmpty())
}
