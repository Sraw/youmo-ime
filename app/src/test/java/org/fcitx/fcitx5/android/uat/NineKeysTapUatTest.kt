/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.text.InputType
import android.view.View
import android.widget.TextView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.UNICODE
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboards
import org.fcitx.fcitx5.android.input.keyboard.T9EnglishTyping
import org.fcitx.fcitx5.android.input.keyboard.T9MultiTap
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * uat/03's presses in time on the nine keys: 连按 (a key pressed again soon), 1's punctuation, and
 * English's letters typed one at a time, as the keyboard reads them on the clock and what it then
 * sends or commits itself. What the engine makes of the keys sent is NineKeysUatTest's (:lib:ime-eval).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class NineKeysTapUatTest {

    @get:Rule
    val prefs = PrefsRule()

    private var h: KeyboardHarness? = null

    // pinyin on the nine keys, English on them too (B0-九键英文)
    private fun harness(inputType: Int = InputType.TYPE_CLASS_TEXT) = KeyboardHarness(
        im = KeyboardHarness.PINYIN,
        keyboards = ImKeyboards.Chosen(pinyin = ImKeyboard.NINE_KEYS, english = ImKeyboard.NINE_KEYS),
    ).also {
        h = it
        it.api.availableIme = arrayOf(KeyboardHarness.PINYIN, KeyboardHarness.ENGLISH)
        it.api.enabledIme = it.api.availableIme
    }.start(inputType)

    @After
    fun close() {
        h?.close()
        EngineState.syllablesState.value = EngineState.SyllableState()
        EngineState.englishTypingState.value = false
    }

    // the globe to English, on its nine keys
    private fun KeyboardHarness.english() {
        emit(HostEvent.InputMethodChanged(KeyboardHarness.ENGLISH))
    }

    // [keys] (digits, or ⇧) tapped [gap] ms apart
    private fun KeyboardHarness.presses(keys: String, gap: Long) {
        keys.forEachIndexed { i, k ->
            if (i > 0) advance(gap)
            tap(if (k == '⇧') described("Shift") else nineKey(k.toString()))
        }
        advance(PAUSE)
    }

    // what was sent the engine: each key down, as fcitx's key names or a Unicode sym
    private fun KeyboardHarness.sent() = api.calls.filter { it.startsWith("sendKey") && "up=false" in it }
        .map { Regex("key=(.+?),").find(it)?.groupValues?.get(1) ?: Regex("sym=(\\d+)").find(it)!!.groupValues[1] }

    // pinyin's nine keys send a code of their own for each digit (Keyboard.pinyinNineKey)
    private fun nine(digits: String) = digits.map { (UNICODE + Keyboard.pinyinNineKey(it)).toString() }

    private fun letterAgain(c: Char) = (UNICODE + Keyboard.letterKey(c, again = true)).toString()

    private val KeyboardHarness.text get() = field.text.toString()

    private fun KeyboardHarness.clear() {
        field.setText("")
        idle()
        api.calls.clear()
    }

    @Test
    @Uat("T9-19")
    fun withMultiTapOnOnesPunctuationCyclesAndAQuickSecondDigitIsItsLetter() {
        prefs.set(AppPrefs.getInstance().keyboard.t9MultiTap, T9MultiTap.Medium)
        val h = harness()
        h.presses("11", QUICK)
        assertEquals("。", h.text)
        h.clear()
        h.presses("44", SLOW)
        assertEquals(nine("44"), h.sent())
        h.clear()
        // the engine's answer to the first 4 (composing): the second press is h, typed as itself
        h.presses("44", QUICK)
        assertEquals(nine("4") + letterAgain('h'), h.sent())
        h.clear()
        h.presses("7", 0)
        h.presses("44", QUICK)
        h.tap(h.inputView.findViewById<View>(R.id.button_backspace))
        // Backspace is no digit: sent, the engine takes the letter off whole (NineKeysUatTest)
        assertEquals(nine("74") + listOf(letterAgain('h'), FcitxKeyMapping.FcitxKey_BackSpace.toString()), h.sent())
    }

    @Test
    @Uat("T9-24")
    fun multiTapOffByDefaultOneIsACommaEachPress() {
        assertEquals(T9MultiTap.Off, AppPrefs.getInstance().keyboard.t9MultiTap.getValue())
        val h = harness()
        h.presses("11", FAST)
        assertEquals("，，", h.text)
        h.clear()
        h.presses("44", FAST)
        assertEquals(nine("44"), h.sent())
    }

    @Test
    @Uat("T9-32")
    fun englishsOneWithNothingTypedCyclesItsMarks() {
        val h = harness()
        h.english()
        h.presses("11", FAST)
        assertEquals(",", h.text)
        assertTrue(h.sent().toString(), h.sent().isEmpty())
    }

    @Test
    @Uat("T9-33")
    fun englishByMultiTapCommitsALetterAtATime() {
        prefs.set(AppPrefs.getInstance().keyboard.t9EnglishTyping, T9EnglishTyping.MultiTap)
        val h = harness()
        h.english()
        for (run in listOf("44", "33", "555", "555", "666")) h.presses(run, FASTER)
        assertEquals("hello", h.text)
        // nothing for the engine to read: no candidates (NineKeysUatTest)
        assertTrue(h.sent().toString(), h.sent().isEmpty())
    }

    @Test
    @Uat("T9-44")
    fun aFieldAskingNoSuggestionsTypesEnglishByMultiTap() {
        val h = harness(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        h.english()
        h.presses("44", FASTER)
        assertEquals("h", h.text)
        assertTrue(h.sent().toString(), h.sent().isEmpty())
    }

    @Test
    @Uat("T9-49")
    fun shiftEndsARunTheNextPressANewCapital() {
        val h = harness(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        h.english()
        h.presses("4⇧4", FAST)
        assertEquals("gG", h.text)
    }

    private fun KeyboardHarness.textView(text: String): TextView =
        KeyboardHarness.all(inputView).filterIsInstance<TextView>().last { shown(it) && it.text.toString() == text }

    // a long press on [key], the finger moved onto the popup's [label] and let go
    private fun KeyboardHarness.longPressTo(key: View, label: String) {
        val f = finger(key, at(key))
        advance(700)
        f.move(at(key))
        val cell = KeyboardHarness.all(inputView).filter { shown(it) && it is TextView && it.text.toString() == label }.last().parent as View
        fun centre(v: View) = IntArray(2).also(v::getLocationInWindow).let { it[0] + v.width / 2f to it[1] + v.height / 2f }
        val from = centre(KeyboardHarness.all(inputView).filter { shown(it) && it is TextView && it.text.toString() == "4" }.last().parent as View)
        val to = centre(cell)
        val step = org.fcitx.fcitx5.android.input.popup.PopupFocus.STEP / service.resources.displayMetrics.density
        val p = at(key, (to.first - from.first) * step, (to.second - from.second) * step)
        f.move(p)
        f.up(p)
    }

    // the word's end each way (the engine's commit of it: NineKeysUatTest)
    @Test
    @Uat("T9-36")
    fun anEnglishWordIsEndedByEnterTheColumnZeroOrAHeldDigit() {
        val h = harness()
        h.english()
        fun typing() {
            h.api.calls.clear()
            h.presses("4663", FAST)
            EngineState.englishTypingState.value = true
            h.idle()
        }
        typing()
        assertEquals(listOf("4663").flatMap { d -> d.map { (UNICODE + Keyboard.nineKey(it, upper = false)).toString() } }, h.sent())
        h.tap(h.inputView.findViewById<View>(R.id.button_return))
        assertEquals(FcitxKeyMapping.FcitxKey_Return.toString(), h.sent().last())
        typing()
        h.tap(h.textView(","))
        assertEquals(",", h.sent().last())
        typing()
        h.tap(h.key("0"))
        assertEquals("0", h.sent().last())
        typing()
        h.longPressTo(h.nineKey("4"), "4")
        // what is typed ended first (reset: the engine puts it in), then the digit as it is
        assertTrue(h.api.calls.toString(), "reset()" in h.api.calls)
        assertEquals("4", h.text)
    }

    private companion object {
        const val FASTER = 120L
        const val FAST = 150L
        const val QUICK = 300L
        const val SLOW = 1_200L
        const val PAUSE = 1_000L
    }
}
