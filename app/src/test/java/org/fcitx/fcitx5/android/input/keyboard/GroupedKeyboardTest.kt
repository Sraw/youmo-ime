/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.core.view.children
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.core.UNICODE
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.g14Code
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import splitties.views.existingOrNewId

/**
 * The 14 keys (拼音双键) as iFlytek lays them out: five, five, then the separator, four and
 * backspace; corners 1 to 0 and `~ ! @ ?`. A tap sends the group's code, never a letter (an ASCII
 * letter would go to full pinyin, the hardware keyboard's); a swipe its corner, a digit as a
 * keypad's; the long press a row of the group's letters around the corner, committed as they are.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class GroupedKeyboardTest {

    private val context = RuntimeEnvironment.getApplication()

    // what each letter's long press offers here: a symbol, a digit, a letter, and one corner
    private val layout = GroupedKeyboard.layout(NineKeyLetters.G14) { letter ->
        when (letter) {
            "q" -> listOf("1", "Q", "%")
            "w" -> listOf("2", "W", "%", "^")
            "b" -> listOf("@", "&")
            else -> emptyList()
        }
    }

    private fun key(face: String) = layout.flatten().single { (it.appearance as? KeyDef.Appearance.AltText)?.displayText == face }

    private fun keypad(d: Int) = KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_KP_0 + d), KeyStates.Virtual)

    @After
    fun prefsAtTheirDefaults() {
        AppPrefs.getInstance().keyboard.showLangSwitchKey.let { it.setValue(it.defaultValue) }
        AppPrefs.getInstance().internal.popupOverrides.let { it.setValue(it.defaultValue) }
    }

    @Test
    fun threeRowsOfGroupsFullWidthWithTheSeparatorAndBackspaceOnTheThird() {
        val faces = layout.take(3).map { row -> row.map { (it.appearance as? KeyDef.Appearance.Text)?.displayText } }
        assertEquals(listOf("QW", "ER", "TY", "UI", "OP"), faces[0])
        assertEquals(listOf("AS", "DF", "GH", "JK", "L"), faces[1])
        assertEquals(listOf("分词", "ZX", "CV", "BN", "M", null), faces[2])
        val widths = layout.take(3).map { row -> row.map { it.appearance.percentWidth } }
        assertEquals(List(5) { 0.2f }, widths[0])
        assertEquals(List(5) { 0.2f }, widths[1])
        assertEquals(listOf(0.15f, 0.175f, 0.175f, 0.175f, 0.175f, 0.15f), widths[2])
        widths.forEach { assertEquals(1f, it.sum(), 1e-4f) }
        assertTrue(layout[2].last() is BackspaceKey)
        assertEquals(KeyAction.FcitxKeyAction("'"), (layout[2][0].behaviors.single() as KeyDef.Behavior.Press).action)
    }

    @Test
    fun cornersAreIFlyteksDigitsOnTopAndMarksOnTheThirdRow() {
        val corners = layout.take(3).map { row -> row.mapNotNull { (it.appearance as? KeyDef.Appearance.AltText)?.altText } }
        assertEquals(listOf("1", "2", "3", "4", "5"), corners[0])
        assertEquals(listOf("6", "7", "8", "9", "0"), corners[1])
        assertEquals(listOf("~", "!", "@", "?"), corners[2])
    }

    @Test
    fun aTapSendsTheGroupsCodeAndASwipeItsCorner() {
        fun press(face: String) = key(face).behaviors.filterIsInstance<KeyDef.Behavior.Press>().single().action
        fun swipe(face: String) = key(face).behaviors.filterIsInstance<KeyDef.Behavior.Swipe>().single().action
        // fcitx's key for a Unicode character: UNICODE (0x01000000) plus its code point
        assertEquals(KeyAction.SymAction(KeySym(UNICODE + g14Code('b').code), KeyStates.Virtual), press("BN"))
        assertEquals(GroupedKeyboard.code(g14Code('q')), press("QW"))
        assertEquals(GroupedKeyboard.code(g14Code('m')), press("M"))
        NineKeyLetters.G14.codes.toList().zip(layout.take(3).flatten().filter { it.appearance is KeyDef.Appearance.AltText }).forEach { (code, def) ->
            assertEquals(GroupedKeyboard.code(code), def.behaviors.filterIsInstance<KeyDef.Behavior.Press>().single().action)
        }
        // a digit as a keypad's: typed, or picks that candidate while typing
        assertEquals(keypad(1), swipe("QW"))
        assertEquals(keypad(0), swipe("L"))
        assertEquals(KeyAction.FcitxKeyAction("@"), swipe("BN"))
        assertEquals(KeyAction.FcitxKeyAction("?"), swipe("M"))
    }

    @Test
    fun theLongPressIsARowOfTheLettersAroundTheCornerWithSymbolsAbove() {
        val qw = key("QW").popup!!.filterIsInstance<KeyDef.Popup.Keyboard.Explicit>().single()
        // digits, letters and duplicates left out of the symbols
        assertEquals(listOf("Q", "W", "1", "q", "w", "%", "^"), qw.items.toList())
        assertEquals(5, qw.symbolsFrom)
        assertEquals(2, qw.inRow)
        // the corner already in the row is not offered again
        val bn = key("BN").popup!!.filterIsInstance<KeyDef.Popup.Keyboard.Explicit>().single()
        assertEquals(listOf("B", "N", "@", "b", "n", "&"), bn.items.toList())
        val m = key("M").popup!!.filterIsInstance<KeyDef.Popup.Keyboard.Explicit>().single()
        assertEquals(listOf("M", "?", "m"), m.items.toList())
        assertEquals(1, m.inRow)
    }

    @Test
    fun aLetterOrDigitOfTheLongPressIsCommittedAsItIs() {
        val popup = KeyActionListener.Source.Popup
        assertEquals(KeyAction.CommitAction("q"), GroupedKeyboard.resolve(KeyAction.FcitxKeyAction("q"), popup))
        assertEquals(KeyAction.CommitAction("W"), GroupedKeyboard.resolve(KeyAction.FcitxKeyAction("W"), popup))
        assertEquals(KeyAction.CommitAction("1"), GroupedKeyboard.resolve(KeyAction.FcitxKeyAction("1"), popup))
        // a symbol goes through the engine, as the full keyboard's
        assertEquals(KeyAction.FcitxKeyAction("%"), GroupedKeyboard.resolve(KeyAction.FcitxKeyAction("%"), popup))
        // the keys' own, as they are
        val keys = KeyActionListener.Source.Keyboard
        assertEquals(KeyAction.FcitxKeyAction("'"), GroupedKeyboard.resolve(KeyAction.FcitxKeyAction("'"), keys))
        assertEquals(GroupedKeyboard.code(g14Code('b')), GroupedKeyboard.resolve(GroupedKeyboard.code(g14Code('b')), keys))
    }

    @Test
    @Uat("PY-91")
    fun theBottomRowHasNoChineseEnglishKeyAndTheLanguageSwitchFollowsItsSetting() {
        val keyboard = GroupedKeyboard(context, ThemePreset.PixelDark)
        // English is the language key's round, as on the full keyboard
        assertNull(keyboard.findViewById<View>(R.id.button_nine_language))
        assertEquals(View.VISIBLE, keyboard.findViewById<View>(R.id.button_lang).visibility)
        listOf(R.id.button_space, R.id.button_return, R.id.button_comma, R.id.button_period, R.id.button_backspace).forEach {
            assertEquals(View.VISIBLE, keyboard.findViewById<View>(it).visibility)
        }
        AppPrefs.getInstance().keyboard.showLangSwitchKey.setValue(false)
        assertEquals(View.GONE, keyboard.findViewById<View>(R.id.button_lang).visibility)
        assertEquals(View.GONE, GroupedKeyboard(context, ThemePreset.PixelDark).findViewById<View>(R.id.button_lang).visibility)
    }

    @Test
    @Uat("PY-93")
    fun theSpaceIsAtLeast15PercentOfTheRowInEveryField() {
        assertSpaceAtLeast15Percent(GroupedKeyboard(context, ThemePreset.PixelDark))
    }

    @Test
    fun anAddressFieldShowsWhatAddressesAreMadeOf() {
        val keyboard = GroupedKeyboard(context, ThemePreset.PixelDark)
        keyboard.address = AddressKeys.URL
        assertEquals(View.GONE, keyboard.findViewById<View>(R.id.button_comma).visibility)
        assertEquals(View.VISIBLE, keyboard.findViewById<View>(R.id.button_slash).visibility)
        assertEquals(View.VISIBLE, keyboard.findViewById<View>(R.id.button_domain).visibility)
    }

    @Test
    fun talkBackReadsAGroupLetterByLetterWithItsCorner() {
        val keyboard = GroupedKeyboard(context, ThemePreset.PixelDark)
        val views = keyboard.keyRows().flatMap { it.children.toList() }
        val described = views.filterIsInstance<AltTextKeyView>().associate { (it.def as KeyDef.Appearance.AltText).displayText to it.contentDescription.toString() }
        assertEquals(context.getString(R.string.a11y_key_grouped, "Q W", "1"), described["QW"])
        assertEquals(context.getString(R.string.a11y_key_grouped, "L", "0"), described["L"])
        assertEquals(context.getString(R.string.a11y_key_grouped, "B N", "@"), described["BN"])
        assertEquals(14, described.size)
        val separator = views.filterIsInstance<TextKeyView>().single { (it.def as? KeyDef.Appearance.Text)?.displayText == "分词" }
        assertEquals(context.getString(R.string.a11y_key_separator), separator.contentDescription)
    }

    @Test
    fun theCommaFullStopAndCornersShowWhatTheyType() {
        val keyboard = GroupedKeyboard(context, ThemePreset.PixelDark)
        keyboard.onPunctuationUpdate(mapOf("," to "，", "." to "。", "~" to "～"))
        assertEquals("，", keyboard.findViewById<TextKeyView>(R.id.button_comma).mainText.text.toString())
        assertEquals("。", keyboard.findViewById<TextKeyView>(R.id.button_period).mainText.text.toString())
        val zx = keyboard.keyRows().flatMap { it.children.toList() }.filterIsInstance<AltTextKeyView>()
            .single { (it.def as KeyDef.Appearance.AltText).displayText == "ZX" }
        assertEquals("～", zx.altText.text.toString())
        // and TalkBack reads the corner drawn, not the key's own ~
        assertEquals(context.getString(R.string.a11y_key_grouped, "Z X", "～"), zx.contentDescription)
        // an address's keys commit as they are, and say so
        keyboard.address = AddressKeys.URL
        assertEquals(".", keyboard.findViewById<TextKeyView>(R.id.button_dot).mainText.text.toString())
        // the preview too
        val previews = ArrayList<PopupAction>()
        keyboard.popupActionListener = PopupActionListener { previews += it }
        keyboard.onPopupAction(PopupAction.PreviewAction(R.id.button_comma, ",", Rect()))
        assertEquals("，", (previews.single() as PopupAction.PreviewAction).content)
    }

    @Test
    fun theLongPressIsReadAsItOpensFromTheUsersKeys() {
        val keyboard = GroupedKeyboard(context, ThemePreset.PixelDark)
        val shown = ArrayList<PopupAction>()
        keyboard.popupActionListener = PopupActionListener { shown += it }
        val qw = keyboard.keyRows().flatMap { it.children.toList() }.filterIsInstance<AltTextKeyView>()
            .single { (it.def as KeyDef.Appearance.AltText).displayText == "QW" }
        // changed after the keyboard was made
        AppPrefs.getInstance().internal.popupOverrides.setValue("q % ^\nw &")
        val built = key("QW").popup!!.filterIsInstance<KeyDef.Popup.Keyboard.Explicit>().single()
        keyboard.onPopupAction(PopupAction.ShowKeyboardAction(qw.existingOrNewId, built, Rect()))
        val opened = (shown.single() as PopupAction.ShowKeyboardAction).keyboard as KeyDef.Popup.Keyboard.Explicit
        assertEquals(listOf("Q", "W", "1", "q", "w", "%", "^", "&"), opened.items.toList())
    }

    // the rows of keys, as BaseKeyboard lays them out
    private fun GroupedKeyboard.keyRows() = (0 until childCount).map { getChildAt(it) }.filterIsInstance<ViewGroup>()
}

/**
 * The space takes what is left of the bottom row: at least 0.15 of it in every field (plain, an
 * address's, an e-mail's), the language key shown or not. Laid out 1080 px wide.
 */
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
                keyboard.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                )
                keyboard.layout(0, 0, width, 800)
                val space = keyboard.findViewById<View>(R.id.button_space).width
                if (space < 0.15 * width) narrow += "$address, language key $lang: space $space of $width"
            }
        }
    } finally {
        showLang.setValue(before)
    }
    assertTrue(narrow.joinToString("; "), narrow.isEmpty())
}
