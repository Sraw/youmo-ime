/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.text.InputType
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.View
import androidx.core.view.ViewCompat
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.Action
import org.fcitx.fcitx5.android.core.CapabilityFlag
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.core.RawConfig
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.punctuation.PunctuationManager
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.keyboard.AltTextKeyView
import org.fcitx.fcitx5.android.input.keyboard.SpaceLongPressBehavior
import org.fcitx.fcitx5.android.input.keyboard.TextKeyView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** UAT cases of the keyboard's keys, asserted on the whole keyboard (uat/02, 03, 04, 15). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class KeyboardUatTest {

    private var h: KeyboardHarness? = null

    private fun harness(im: org.fcitx.fcitx5.android.core.InputMethodEntry = KeyboardHarness.PINYIN) =
        KeyboardHarness(im = im).also { h = it }

    @After
    fun close() {
        h?.close()
    }

    // the share of 中/英 shown large: the other's span is made smaller (T9Keyboard.languageLabel)
    private fun large(key: TextKeyView): String {
        val text = key.mainText.text as Spanned
        val small = text.getSpans(0, text.length, RelativeSizeSpan::class.java).flatMap {
            (text.getSpanStart(it) until text.getSpanEnd(it)).toList()
        }.toSet()
        return text.indices.filter { it !in small }.joinToString("") { text[it].toString() }
    }

    @Test
    @Uat("T9-30")
    fun nineKeysLanguageKeySwitchesToNineKeysEnglishAndBack() {
        val h = harness(KeyboardHarness.T9).start()
        h.api.enabledIme = arrayOf(KeyboardHarness.PINYIN, KeyboardHarness.T9, KeyboardHarness.ENGLISH)
        h.api.availableIme = h.api.enabledIme
        assertEquals("中", large(h.key("中/英")))
        h.capture("KeyboardUatTest/t9_language_chinese")
        h.tap(h.key("中/英"))
        assertTrue(h.api.calls.toString(), "activateIme(${KeyboardHarness.ENGLISH.uniqueName})" in h.api.calls)
        // the engine says so, as fcitx does after activateIme
        h.emit(FcitxEvent.IMChangeEvent(KeyboardHarness.ENGLISH))
        val english = h.texts()
        assertEquals("英", large(h.key("中/英")))
        // still the nine keys, small letters, ⇧ where 重输 was, the space saying English
        assertTrue(english.toString(), "ghi" in english && "重输" !in english)
        h.described("Shift")
        assertTrue(english.toString(), "English" in english)
        h.capture("KeyboardUatTest/t9_language_english")
        h.api.calls.clear()
        h.tap(h.key("中/英"))
        assertTrue(h.api.calls.toString(), "activateIme(${KeyboardHarness.T9.uniqueName})" in h.api.calls)
        h.emit(FcitxEvent.IMChangeEvent(KeyboardHarness.T9))
        val back = h.texts()
        assertTrue(back.toString(), "重输" in back && "Pinyin (9 keys)" in back)
    }

    @Test
    @Uat("PAN-01")
    fun numberPadAndBack() {
        val h = harness().start()
        h.tap(h.key("123"))
        val rows = h.texts()
        assertEquals(listOf("+", "1", "2", "3", "/", "-", "4", "5", "6", "*", "7", "8", "9", "ABC", ",", "!?#", "0", "=", "."), rows)
        // space, ⌫ and ↵ are image keys
        space(h); h.described("Backspace")
        h.capture("KeyboardUatTest/number_pad")
        h.tap(h.key("4"))
        h.tap(h.key("2"))
        assertEquals(
            listOf(FcitxKeyMapping.FcitxKey_KP_4, FcitxKeyMapping.FcitxKey_KP_2),
            h.api.calls.filter { it.startsWith("sendKey") }.map { Regex("sym=(\\d+)").find(it)!!.groupValues[1].toInt() },
        )
        h.tap(h.key("ABC"))
        val letters = h.texts()
        assertTrue(letters.toString(), "q" in letters && "m" in letters && "123" in letters)
    }

    @Test
    @Uat("KB-22")
    fun addressFieldHasSlashDotAndDotCom() {
        val h = harness().start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val keys = h.texts()
        // the bottom row: 符 123 / space . .com
        assertEquals(listOf("符", "123", "/", "Pinyin", ".", ".com"), keys.takeLast(6))
        assertFalse(keys.toString(), "，" in keys || "。" in keys || "," in keys)
        h.tap(h.key(".com"))
        assertEquals(".com", h.field.text.toString())
    }

    @Test
    @Uat("KB-39")
    fun shiftChangesAllThreeRows() {
        val h = harness().start()
        val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        fun letters() = h.texts().filter { it.length == 1 && it[0].lowercaseChar() in 'a'..'z' }.joinToString("")
        assertEquals(rows.joinToString(""), letters())
        h.capture("KeyboardUatTest/shift_off")
        h.tap(h.described("Shift"))
        assertEquals(rows.joinToString("").uppercase(), letters())
        h.capture("KeyboardUatTest/shift_on")
        h.tap(h.described("Shift"))
        assertEquals(rows.joinToString(""), letters())
    }

    @Test
    @Uat("PR-03")
    fun passwordFieldTypesEnglishWithNumberRow() {
        val h = harness().start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        // the engine is told it is a password (CapabilityFlag.Password); it switches to English (androidengine)
        val flags = Regex("setCapFlags\\((\\d+)\\)").find(h.api.calls.joinToString())!!.groupValues[1].toULong()
        assertTrue("flags $flags", flags and CapabilityFlag.Password.flag != 0UL)
        h.emit(FcitxEvent.IMChangeEvent(KeyboardHarness.ENGLISH))
        val keys = h.texts()
        // the row above the keys is digits (PR-05), not candidates or the toolbar
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"), keys.take(10))
        assertTrue(keys.toString(), "English" in keys)
        h.capture("KeyboardUatTest/password")
        h.tap(h.key("n"))
        // the n itself reaches the engine, not just some key
        assertTrue(h.api.calls.toString(), sent().any { "char=n," in it || "sym=110," in it || "key=n," in it })
    }

    private fun sent() = h!!.api.calls.filter { it.startsWith("sendKey") && "up=false" in it }

    private fun lastSent() = sent().lastOrNull().orEmpty()

    private fun s(id: Int) = h!!.service.getString(id)

    // "Space, <the input method>"
    private fun space(h: KeyboardHarness) = KeyboardHarness.all(h.inputView)
        .first { h.shown(it) && it.contentDescription?.toString()?.let { d -> d == "Space" || d.startsWith("Space,") } == true }

    @Test
    @Uat("KB-03")
    fun swipingALetterDownTypesItsCorner() {
        val h = harness().start()
        h.swipe(h.key("w"), 0f, h.key("w").height * 1.3f)
        assertEquals(sent().toString(), 1, sent().size)
        assertTrue(lastSent(), "key=2," in lastSent())
    }

    @Test
    @Uat("KB-04")
    fun aLongPressLetGoTypesTheCorner() {
        val h = harness().start()
        h.longPress(h.key("e"))
        assertTrue(sent().toString(), sent().size == 1 && "key=3," in lastSent())
    }

    @Test
    @Uat("KB-05")
    fun aLongPressesChoicesTypeWhereTheFingerIs() {
        val h = harness().start()
        val shown = h.longPress(h.key("q"), "Q")
        assertTrue(shown.toString(), "1" in shown && "Q" in shown)
        assertTrue(sent().toString(), sent().size == 1 && "key=Q," in lastSent())
    }

    private fun corner(h: KeyboardHarness, letter: String) = (h.key(letter) as AltTextKeyView).altText.text.toString()

    @Test
    @Uat("KB-06")
    fun cornersShowChinesePunctuationWhileChinese() {
        val h = harness().start()
        // fcitx's punctuation table, as its punctuation addon has it on
        val entries = listOf("\\" to "、", "?" to "？").mapIndexed { i, (k, v) ->
            org.fcitx.fcitx5.android.data.punctuation.PunctuationMapEntry(k, v, "").toRawConfig(i)
        }
        kotlinx.coroutines.runBlocking {
            h.api.setAddonSubConfig(
                "punctuation", "punctuationmap/zh_CN",
                RawConfig(arrayOf(RawConfig("cfg", arrayOf(RawConfig(PunctuationManager.ENTRIES, entries.toTypedArray()))))),
            )
        }
        PunctuationManager.forget()
        val active = arrayOf(Action(1, false, false, false, "punctuation", "fcitx-punc-active", "", "", null))
        h.api.statusAreaActionsCached = active
        h.emit(FcitxEvent.StatusAreaEvent(FcitxEvent.StatusAreaEvent.Data(active, KeyboardHarness.PINYIN)))
        assertEquals("、", corner(h, "m"))
        assertEquals("？", corner(h, "v"))
        // English: the addon off for it
        val inactive = arrayOf(Action(1, false, false, false, "punctuation", "fcitx-punc-inactive", "", "", null))
        h.emit(FcitxEvent.IMChangeEvent(KeyboardHarness.ENGLISH))
        h.emit(FcitxEvent.StatusAreaEvent(FcitxEvent.StatusAreaEvent.Data(inactive, KeyboardHarness.ENGLISH)))
        assertEquals("\\", corner(h, "m"))
        assertEquals("?", corner(h, "v"))
        PunctuationManager.forget()
    }

    @Test
    @Uat("KB-08")
    fun shiftTwiceLocksCaps() {
        val h = harness(KeyboardHarness.ENGLISH).start()
        // a double tap, its two touches at one time
        repeat(2) {
            h.press(h.described("Shift"))
            h.release()
        }
        assertEquals(s(R.string.a11y_state_caps_lock), ViewCompat.getStateDescription(h.described("Shift")))
        h.tap(h.key("O"))
        h.tap(h.key("K"))
        assertTrue(sent().toString(), "key=O," in sent()[0] && "key=K," in sent()[1])
        h.tap(h.described("Shift"))
        h.tap(h.key("a"))
        assertTrue(lastSent(), "key=a," in lastSent())
    }

    private fun backspaces() = sent().count { "sym=${FcitxKeyMapping.FcitxKey_BackSpace}," in it }

    @Test
    @Uat("KB-09")
    fun backspaceOnce() {
        val h = harness().start()
        h.tap(h.described("Backspace"))
        assertEquals(1, backspaces())
    }

    @Test
    @Uat("KB-11")
    fun holdingBackspaceRepeatsFasterTillLetGo() {
        val h = harness().start()
        val key = h.described("Backspace")
        h.press(key)
        h.after(1000)
        val first = backspaces()
        h.after(1000)
        val second = backspaces() - first
        h.release()
        val all = backspaces()
        h.after(500)
        assertEquals("none after letting go", all, backspaces())
        assertTrue("first second $first, then $second", first >= 2 && second > first)
        // a finger moved a little (under half a key) repeats all the same
        h.api.calls.clear()
        val b = h.box(key)
        h.drag(key, b.exactCenterX() + b.width() / 4f, b.exactCenterY(), holdMs = 1500, steps = 2, lift = true)
        assertTrue(sent().toString(), backspaces() >= 3)
    }

    @Test
    @Uat("KB-12")
    fun swipingBackspaceLeftSelectsAndDeletes() {
        val h = harness().start()
        h.field.setText("今天天气很好")
        h.field.setSelection(6)
        h.idle()
        val key = h.described("Backspace")
        val b = h.box(key)
        h.drag(key, b.exactCenterX() - h.service.resources.displayMetrics.widthPixels * 0.4f, b.exactCenterY(), steps = 16, lift = false)
        assertTrue("selected ${h.field.selectionStart}..${h.field.selectionEnd}", h.field.selectionEnd - h.field.selectionStart >= 1)
        h.release()
        assertTrue(h.field.text.toString(), h.field.text.length < 6 && "今天天气很好".startsWith(h.field.text.toString()))
    }

    @Test
    @Uat("KB-13")
    fun swipingBackspaceWhileTypingClearsThePinyin() {
        val h = harness().start()
        h.api.inputPanelCached = FcitxEvent.InputPanelEvent.Data(FormattedText(arrayOf("zhong guo"), intArrayOf(0), 9), FormattedText.Empty, FormattedText.Empty, emptyArray())
        h.emit(FcitxEvent.InputPanelEvent(h.api.inputPanelCached))
        val key = h.described("Backspace")
        val b = h.box(key)
        h.drag(key, b.exactCenterX() - h.service.resources.displayMetrics.widthPixels * 0.4f, b.exactCenterY(), steps = 16)
        assertTrue(h.api.calls.toString(), "reset()" in h.api.calls)
    }

    @Test
    @Uat("KB-16")
    fun holdingTheCommaOffersTwoRows() {
        val h = harness().start()
        val comma = h.key(",")
        val shown = h.longPress(comma)
        assertTrue(shown.toString(), listOf(".", "?", "!", ":", ";").all { it in shown })
        // what one lift typed: a key sent to the engine, or text put in the field
        fun typed() = h.field.text.toString().ifEmpty {
            sent().lastOrNull()?.let { Regex("(?:char|key)=(.+?), states").find(it)?.groupValues?.get(1) }.orEmpty()
        }
        // let go where it opened: the first, typed
        assertEquals(h.api.calls.toString() + h.field.text, ".", typed())
        val step = h.service.resources.displayMetrics.density * 25
        val b = h.box(comma)
        val got = listOf(step to 0f, 0f to -step, 0f to -b.height() * 6f).map { (dx, dy) ->
            h.field.setText("")
            h.api.calls.clear()
            h.drag(comma, b.exactCenterX() + dx, b.exactCenterY() + dy, holdMs = 700, steps = 4)
            typed().also { assertTrue("moved $dx $dy: ${h.api.calls} ${h.field.text}", it in shown) }
        }
        // a little right is the one beside it, a little up the row above: two rows, each its own key
        assertEquals(got.toString(), 3, (listOf(".") + got.take(2)).distinct().size)
    }

    @Test
    @Uat("KB-17")
    fun spaceSlidesMoveTheCursor() {
        val h = harness().start()
        val space = space(h)
        h.swipe(space, -space.width * 0.6f, 0f)
        val left = sent().count { "sym=${FcitxKeyMapping.FcitxKey_Left}," in it }
        assertTrue(sent().toString(), left >= 2 && sent().all { "sym=${FcitxKeyMapping.FcitxKey_Left}," in it })
        h.api.calls.clear()
        h.swipe(space, space.width * 0.2f, 0f)
        assertTrue(sent().toString(), sent().isNotEmpty() && sent().all { "sym=${FcitxKeyMapping.FcitxKey_Right}," in it })
        assertTrue(sent().size < left)
    }

    private fun actions(h: KeyboardHarness): MutableList<Int> {
        val done = mutableListOf<Int>()
        h.field.setOnEditorActionListener { _, action, _ -> done += action; true }
        return done
    }

    /**
     * The ⏎ sent to the engine, then given back as the host gives back a virtual key no input
     * method took (the fake simulates no input): the service's own handling of it is what is checked.
     */
    private fun returnLetThrough(h: KeyboardHarness) {
        assertTrue(h.api.calls.toString(), sent().any { "sym=${FcitxKeyMapping.FcitxKey_Return}," in it })
        h.emit(FcitxEvent.KeyEvent(FcitxEvent.KeyEvent.Data(KeySym(FcitxKeyMapping.FcitxKey_Return), KeyStates.Virtual, 0, false, 0)))
    }

    @Test
    @Uat("KB-20")
    fun searchFieldsReturnSearches() {
        val h = harness().start(android.text.InputType.TYPE_CLASS_TEXT, android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH)
        val ret = h.described(s(R.string.a11y_key_action_search))
        val done = actions(h)
        h.tap(ret)
        // nothing typed, the engine lets the key through: the app's own action
        returnLetThrough(h)
        assertEquals(listOf(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH), done)
        assertFalse(h.field.text.contains("\n"))
    }

    @Test
    @Uat("KB-21")
    fun nextFieldsReturnGoesOn() {
        val h = harness().start(android.text.InputType.TYPE_CLASS_TEXT, android.view.inputmethod.EditorInfo.IME_ACTION_NEXT)
        val ret = h.described(s(R.string.a11y_key_action_next))
        val done = actions(h)
        h.tap(ret)
        returnLetThrough(h)
        assertEquals(listOf(android.view.inputmethod.EditorInfo.IME_ACTION_NEXT), done)
    }

    @Test
    @Uat("KB-23")
    fun holdingDotComOffersMore() {
        val h = harness().start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val shown = h.longPress(h.key(".com"), ".cn")
        assertTrue(shown.toString(), listOf(".cn", ".net", ".org").all { it in shown })
        assertEquals(".cn", h.field.text.toString())
    }

    @Test
    @Uat("KB-30")
    fun aKeyHeldShowsItLarger() {
        val h = harness().start()
        h.press(h.key("a"))
        assertTrue(h.popupKeys().map { it.text.toString() }.toString(), h.popupKeys().any { it.text.toString() == "a" })
        h.release()
        h.after(300)
        assertTrue(h.popupKeys().map { it.text.toString() }.toString(), h.popupKeys().none { it.text.toString() == "a" })
    }

    @Test
    @Uat("KB-32")
    fun glideIsOffByDefault() {
        assertFalse(AppPrefs.getInstance().keyboard.glideTyping.getValue())
        val h = harness(KeyboardHarness.ENGLISH).start()
        // h across to o: a finger's path over e and l
        val o = h.box(h.key("o"))
        h.drag(h.key("h"), o.exactCenterX(), o.exactCenterY(), steps = 12)
        assertFalse(h.api.calls.toString(), "offerGlide" in h.api.calls)
    }

    @Test
    @Uat("KB-35")
    fun tapsShortSwipesAndHoldsAreNoGlide() {
        val glides = AppPrefs.getInstance().keyboard.glideTyping
        glides.setValue(true)
        try {
            val h = harness(KeyboardHarness.ENGLISH).start()
            h.tap(h.key("o"))
            h.tap(h.key("k"))
            assertTrue(sent().toString(), "key=o," in sent()[0] && "key=k," in sent()[1])
            h.swipe(h.key("q"), 0f, h.key("q").height * 1.3f)
            assertTrue(lastSent(), "key=1," in lastSent())
            val shown = h.longPress(h.key("e"))
            assertTrue(shown.toString(), shown.size > 1)
            assertFalse(h.api.calls.toString(), "offerGlide" in h.api.calls)
        } finally {
            glides.setValue(false)
        }
    }

    @Test
    @Uat("KB-38")
    fun holdingTheSpaceSetToNothingIsOneSpace() {
        // set, not left at the default: the voice build's default is voice input
        val behavior = AppPrefs.getInstance().keyboard.spaceKeyLongPressBehavior
        behavior.setValue(SpaceLongPressBehavior.None)
        try {
            val h = harness(KeyboardHarness.ENGLISH).start()
            h.press(space(h))
            h.after(1000)
            assertTrue(h.popupKeys().map { it.text }.toString(), h.popupKeys().isEmpty())
            h.release()
            assertEquals(sent().toString(), 1, sent().count { "sym=${FcitxKeyMapping.FcitxKey_space}," in it })
        } finally {
            behavior.setValue(behavior.defaultValue)
        }
    }

    @Test
    @Uat("KB-41")
    fun aFirstMoveSidewaysAfterSlippingOffKeepsThePopup() {
        val h = harness().start()
        val q = h.key("q")
        val b = h.box(q)
        val dp = h.service.resources.displayMetrics.density
        h.press(q)
        h.drag(q, b.exactCenterX(), b.bottom + 3 * dp, steps = 0, lift = false)
        h.after(AppPrefs.getInstance().keyboard.longPressDelay.getValue() + 100L)
        h.move(b.exactCenterX() + 25 * dp, b.bottom + 3 * dp, steps = 1)
        val keys = h.popupKeys()
        val shown = keys.map { it.text.toString() }
        assertTrue("the popup is still up", shown.isNotEmpty())
        // the key the finger is on, as the popup shows it (its focus is the only key with a background):
        // any popup key would also pass for a neighbour or a plain q listed in the popup (F4293); the
        // boxes cannot say it, Robolectric leaves the popup's keys unplaced. The background is
        // PopupKeyboardUi.markFocus's focusBackground on the key's root (markInactive sets it null)
        val x = b.exactCenterX() + 25 * dp
        val under = keys.filter { (it.parent as View).background != null }.map { it.text.toString() }
        assertEquals("one key in focus: $shown", 1, under.size)
        h.move(x, b.bottom + 3 * dp, steps = 0, lift = true)
        // the lift types the popup's key under the finger, not a plain q nor a neighbour (F4211), read as KB-16 does
        val typed = h.field.text.toString().ifEmpty {
            sent().lastOrNull()?.let { Regex("(?:char|key)=(.+?), states").find(it)?.groupValues?.get(1) }.orEmpty()
        }
        assertEquals("$typed of $shown: ${h.api.calls}", under.single(), typed)
    }
}
