/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.app.Activity
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.core.EngineBridge
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.engine.host.Keyboard
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import org.fcitx.fcitx5.android.input.popup.PopupKeyboardUi
import org.fcitx.fcitx5.android.ui.main.settings.PlaceholderStrings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * The nine keys' column keeps its items from one key to the next and reads what it shows when one
 * is picked: a syllable goes with its list's id, for the engine to drop if a key typed since
 * replaced that list; punctuation is committed as it is. A click released on a list the column has
 * replaced since is dropped, as removing the items dropped it.
 *
 * The keys as they are pressed, before the engine tells of what is typed: a digit sent makes 1 the
 * separator at once; 1's punctuation keeps cycling through the engine's own reset, and with 连按
 * off too, which is the letters'; it starts again once what was typed goes or the cursor moves. A
 * key's preview is its own, though another has the same letters; a key with none types no input.
 */
// Robolectric, as LayoutSwitchKeyTest: reading T9Keyboard's companion initialises the View class
@RunWith(RobolectricTestRunner::class)
class T9KeyboardTest {

    @Test
    fun whileTypingTheColumnOffersTheSyllablesOfItsList() {
        val state = EngineBridge.T9State(composing = true, syllables = listOf("ni", "mi", "o"), id = 3)
        assertEquals(listOf("ni", "mi", "o"), T9Keyboard.labels(state))
        // fcitx's key for a Unicode character: 0x01000000 plus its code point
        assertEquals(
            KeyAction.SymAction(KeySym(0x01000000 + Keyboard.syllableKey(1, 3).code), KeyStates.Virtual),
            T9Keyboard.pick(state, 1)
        )
    }

    @Test
    fun aDigitPressedAgainSoonIsItsNextLetterInPlaceOfTheLast() {
        val tap = MultiTap(600)
        val keys = KeyActionListener.Source.Keyboard
        val four = KeyAction.FcitxKeyAction("4")
        assertEquals(four, T9Keyboard.resolve(four, keys, tap, 0))
        assertEquals(letter('h', again = true), T9Keyboard.resolve(four, keys, tap, 100))
        // something else between: a key of its own again
        val back = KeyAction.SymAction(KeySym(0xff08))
        assertEquals(back, T9Keyboard.resolve(back, keys, tap, 150))
        assertEquals(four, T9Keyboard.resolve(four, keys, tap, 200))
        // off: every press a key
        val off = MultiTap(0)
        assertEquals(four, T9Keyboard.resolve(four, keys, off, 0))
        assertEquals(four, T9Keyboard.resolve(four, keys, off, 1))
    }

    @Test
    fun aDigitPressedAgainGoesThroughTheLettersTheUserPutOnIt() {
        val tap = MultiTap(600)
        val keys = KeyActionListener.Source.Keyboard
        val letters = NineKeyLetters.of(listOf("abc", "def", "gi", "jkl", "mnoh"))
        val six = KeyAction.FcitxKeyAction("6")
        assertEquals(six, T9Keyboard.resolve(six, keys, tap, 0, letters = letters))
        assertEquals(letter('n', again = true), T9Keyboard.resolve(six, keys, tap, 100, letters = letters))
        assertEquals(letter('o', again = true), T9Keyboard.resolve(six, keys, tap, 200, letters = letters))
        assertEquals(letter('h', again = true), T9Keyboard.resolve(six, keys, tap, 300, letters = letters))
        // drawn on the key too
        val row = T9Keyboard.layout(letters)[1]
        assertTrue(row.any { (it.appearance as? KeyDef.Appearance.AltText)?.displayText == "MNOH" })
    }

    @Test
    fun theLongPressTypesALetterAsItselfAfterTheLastOrCommitsTheDigit() {
        val tap = MultiTap(600)
        val popup = KeyActionListener.Source.Popup
        assertEquals(letter('g', again = false), T9Keyboard.resolve(KeyAction.FcitxKeyAction("g"), popup, tap, 0))
        // the digit a keypad's: typed as it is, or its candidate picked while typing, never the GHI key
        assertEquals(keypad(4), T9Keyboard.resolve(KeyAction.FcitxKeyAction("4"), popup, tap, 10))
        assertEquals(keypad(1), T9Keyboard.resolve(KeyAction.FcitxKeyAction("1"), popup, tap, 15, composing = true))
        // and the next press is a key of its own
        assertEquals(KeyAction.FcitxKeyAction("4"), T9Keyboard.resolve(KeyAction.FcitxKeyAction("4"), KeyActionListener.Source.Keyboard, tap, 20))
    }

    @Test
    fun oneWithNothingTypedIsPunctuationTheNextInPlaceOfTheLastIfPressedAgainSoon() {
        val tap = MultiTap(600)
        val keys = KeyActionListener.Source.Keyboard
        val one = KeyAction.FcitxKeyAction("1")
        assertEquals(KeyAction.CommitAction("，"), T9Keyboard.resolve(one, keys, tap, 0, composing = false))
        assertEquals(KeyAction.CommitAction("。", replacing = "，"), T9Keyboard.resolve(one, keys, tap, 100, composing = false))
        assertEquals(KeyAction.CommitAction("？", replacing = "。"), T9Keyboard.resolve(one, keys, tap, 200, composing = false))
        assertEquals(KeyAction.CommitAction("！", replacing = "？"), T9Keyboard.resolve(one, keys, tap, 300, composing = false))
        assertEquals(KeyAction.CommitAction("，", replacing = "！"), T9Keyboard.resolve(one, keys, tap, 400, composing = false))
        // after a pause, a ， of its own
        assertEquals(KeyAction.CommitAction("，"), T9Keyboard.resolve(one, keys, tap, 2000, composing = false))
        // while typing, the separator the engine reads
        assertEquals(one, T9Keyboard.resolve(one, keys, tap, 2100, composing = true))
    }

    // 2 to 9's to the engine, as the full keyboard's (the candidate, then the mark); 1's committed
    // as they are already, not through the engine, where . would be 。
    @Test
    fun aLongPressedSymbolGoesOnAsThePopupGivesIt() {
        val tap = MultiTap(600)
        val popup = KeyActionListener.Source.Popup
        for (symbol in ('2'..'9').flatMap { NineKeySymbols.STANDARD.symbols(it) } + listOf("😀", "^_^")) {
            val picked = PopupKeyboardUi.keyAction(symbol)
            assertEquals(symbol, picked, T9Keyboard.resolve(picked, popup, tap, 0, composing = true))
        }
        for (symbol in NineKeySymbols.STANDARD.symbols('1')) {
            val picked = KeyAction.CommitAction(symbol)
            assertEquals(symbol, picked, T9Keyboard.resolve(picked, popup, tap, 0, composing = true))
        }
    }

    @Test
    fun eachKeysSymbolsAreSetApartAfterItsDigitAndLetters() {
        val symbols = NineKeySymbols.STANDARD.with('5', listOf("【", "】")).with('1', emptyList())
        val keys = T9Keyboard.layout(NineKeyLetters.STANDARD, symbols).flatten()
        fun popup(digit: String) = keys.single { (it.appearance as? KeyDef.Appearance.AltText)?.altText == digit }
            .popup?.filterIsInstance<KeyDef.Popup.Keyboard.Explicit>()?.singleOrNull()
        val two = popup("2")!!
        assertEquals(listOf("2", "a", "b", "c", "@", "!", "\""), two.items.toList())
        assertEquals(4, two.symbolsFrom)
        assertEquals(false, two.asTyped)
        val one = T9Keyboard.layout(NineKeyLetters.STANDARD).flatten()
            .single { (it.appearance as? KeyDef.Appearance.AltText)?.altText == "1" }.popup!!.single() as KeyDef.Popup.Keyboard.Explicit
        assertEquals(true, one.asTyped)
        assertEquals(listOf("5", "j", "k", "l", "【", "】"), popup("5")!!.items.toList())
        // 1 with none: no long press
        assertEquals(null, popup("1"))
    }

    @Test
    fun swipingADigitKeySendsItsKeypadDigit() {
        // a keypad's, as the number keyboard's: its candidate picked while typing, not committed after it
        fun swipe(row: Int, digit: String) = T9Keyboard.layout(NineKeyLetters.STANDARD)[row]
            .first { (it.appearance as? KeyDef.Appearance.AltText)?.altText == digit }
            .behaviors.filterIsInstance<KeyDef.Behavior.Swipe>().single().action
        assertEquals(keypad(1), swipe(0, "1"))
        assertEquals(keypad(2), swipe(0, "2"))
        assertEquals(keypad(9), swipe(2, "9"))
    }

    private fun keypad(d: Int) = KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_KP_0 + d), KeyStates.Virtual)

    private fun letter(c: Char, again: Boolean) =
        KeyAction.SymAction(KeySym(0x01000000 + Keyboard.letterKey(c, again)), KeyStates.Virtual)

    @Test
    fun withNothingToOfferTheColumnCommitsPunctuation() {
        listOf(EngineBridge.T9State(), EngineBridge.T9State(composing = true)).forEach { state ->
            assertEquals("，", T9Keyboard.labels(state).first())
            assertEquals(KeyAction.CommitAction("，"), T9Keyboard.pick(state, 0))
            assertEquals(KeyAction.CommitAction("@"), T9Keyboard.pick(state, T9Keyboard.labels(state).lastIndex))
        }
    }

    /** Item [i] of the column, which is the ScrollView among the keyboard's children. */
    private fun T9Keyboard.columnItem(i: Int): View {
        val column = (0 until childCount).map { getChildAt(it) }.filterIsInstance<ScrollView>().single()
        return (column.getChildAt(0) as ViewGroup).getChildAt(i)
    }

    private fun tap(view: View) {
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            MotionEvent.obtain(0L, 0L, action, 1f, 1f, 0).also {
                view.dispatchTouchEvent(it)
                it.recycle()
            }
        }
    }

    // under the app, whose preferences the keys read, with placeholder strings, as NumberKeyboardTest
    @Test
    @Config(application = FcitxApplication::class)
    fun aClickReleasedOnAListSinceReplacedIsDropped() {
        val keyboard = T9Keyboard(PlaceholderStrings(RuntimeEnvironment.getApplication()), ThemePreset.PixelDark)
        val picked = ArrayList<KeyAction>()
        keyboard.keyActionListener = KeyActionListener { action, _ -> picked += action }
        // in a window, where View posts the click of a released item to the looper, as on a phone
        Robolectric.buildActivity(Activity::class.java).setup().get().setContentView(keyboard)
        val looper = shadowOf(Looper.getMainLooper())
        looper.idleFor(1, TimeUnit.SECONDS)
        assertTrue(keyboard.isAttachedToWindow)
        val twelve = EngineBridge.T9State(composing = true, syllables = List(12) { "ni" }, id = 1)
        keyboard.show(twelve)
        // punctuation has no item 10: picked from the list shown when it ran, the click threw
        tap(keyboard.columnItem(10))
        keyboard.show(EngineBridge.T9State())
        looper.idle()
        // nor did the user see the syllables of a list that came after their release
        tap(keyboard.columnItem(0))
        val next = twelve.copy(id = 2)
        keyboard.show(next)
        looper.idle()
        assertEquals(emptyList<KeyAction>(), picked)
        // a click on the list shown picks from it
        tap(keyboard.columnItem(0))
        looper.idle()
        assertEquals(listOf(T9Keyboard.pick(next, 0)), picked)
    }

    /** The input view's lifecycle, which the keys' long-press timers run in. */
    private class Owner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }

    // 连按 is off by default; [keyboard] sets it on for the tests of it
    @After
    fun multiTapAtItsDefault() {
        AppPrefs.getInstance().keyboard.t9MultiTap.let { it.setValue(it.defaultValue) }
    }

    /**
     * A keyboard of [letters] out of a window, 连按 at [multiTap], what it sends put in [sent] and what
     * it previews in [previews]. The clock stands still here: every press is within the multi-tap timeout of the last.
     */
    private fun keyboard(
        sent: MutableList<KeyAction>,
        previews: MutableList<PopupAction> = ArrayList(),
        letters: NineKeyLetters = NineKeyLetters.STANDARD,
        multiTap: T9MultiTap = T9MultiTap.Medium,
    ): T9Keyboard {
        AppPrefs.getInstance().keyboard.t9MultiTap.setValue(multiTap)
        return T9Keyboard(PlaceholderStrings(RuntimeEnvironment.getApplication()), ThemePreset.PixelDark, letters).apply {
            setViewTreeLifecycleOwner(Owner())
            keyActionListener = KeyActionListener { action, _ -> sent += action }
            popupActionListener = PopupActionListener { previews += it }
        }
    }

    /** The key of [digit], as drawn in its corner. */
    private fun T9Keyboard.key(digit: Char): AltTextKeyView =
        (0 until childCount).map { getChildAt(it) }.filterIsInstance<ViewGroup>()
            .flatMap { row -> (0 until row.childCount).map { row.getChildAt(it) } }
            .filterIsInstance<AltTextKeyView>()
            .single { (it.def as KeyDef.Appearance.AltText).altText == digit.toString() }

    @Test
    @Config(application = FcitxApplication::class)
    fun oneRightAfterADigitIsTheSeparatorBeforeTheEngineTells() {
        val sent = ArrayList<KeyAction>()
        val keyboard = keyboard(sent)
        keyboard.key('2').performClick()
        keyboard.key('1').performClick()
        assertEquals(listOf(KeyAction.FcitxKeyAction("2"), KeyAction.FcitxKeyAction("1")), sent)
    }

    @Test
    @Config(application = FcitxApplication::class)
    fun aDigitAfterWhatWasTypedWentIsAKeyOfItsOwn() {
        val sent = ArrayList<KeyAction>()
        val keyboard = keyboard(sent)
        keyboard.key('4').performClick()
        keyboard.show(EngineBridge.T9State(composing = true, syllables = listOf("g", "h", "i"), id = 1))
        // a candidate picked on the bar: nothing typed
        keyboard.show(EngineBridge.T9State())
        keyboard.key('4').performClick()
        assertEquals(listOf(KeyAction.FcitxKeyAction("4"), KeyAction.FcitxKeyAction("4")), sent)
    }

    @Test
    @Config(application = FcitxApplication::class)
    fun onesPunctuationCyclesThroughTheEnginesOwnReset() {
        val sent = ArrayList<KeyAction>()
        val keyboard = keyboard(sent)
        keyboard.key('1').performClick()
        // the punctuation resets the engine, which tells of nothing typed again
        keyboard.show(EngineBridge.T9State())
        keyboard.key('1').performClick()
        assertEquals(listOf(KeyAction.CommitAction("，"), KeyAction.CommitAction("。", replacing = "，")), sent)
    }

    // the ， now before the cursor may be the user's own
    @Test
    @Config(application = FcitxApplication::class)
    fun oneAfterTheCursorMovedIsASymbolOfItsOwn() {
        val sent = ArrayList<KeyAction>()
        val keyboard = keyboard(sent)
        keyboard.key('1').performClick()
        keyboard.cursorMoved()
        keyboard.key('1').performClick()
        assertEquals(listOf(KeyAction.CommitAction("，"), KeyAction.CommitAction("，")), sent)
    }

    // the app types it as its digit: nothing is typed in the engine, which tells nothing new
    @Test
    @Config(application = FcitxApplication::class)
    fun oneAfterAKeyWithNoLettersIsPunctuation() {
        val sent = ArrayList<KeyAction>()
        val keyboard = keyboard(sent, letters = NineKeyLetters.of(listOf("abc", "")))
        keyboard.key('3').performClick()
        keyboard.key('1').performClick()
        assertEquals(listOf(KeyAction.FcitxKeyAction("3"), KeyAction.CommitAction("，")), sent)
    }

    @Test
    @Config(application = FcitxApplication::class)
    fun aKeysPreviewIsItsOwnThoughAnotherHasTheSameLetters() {
        val sent = ArrayList<KeyAction>()
        val previews = ArrayList<PopupAction>()
        val keyboard = keyboard(sent, previews, NineKeyLetters.of(listOf("abc", "abc")))
        tap(keyboard.key('3'))
        tap(keyboard.key('3'))
        assertEquals(listOf("ABC", "B"), previews.filterIsInstance<PopupAction.PreviewAction>().map { it.content })
        assertEquals(listOf(KeyAction.FcitxKeyAction("3"), letter('b', again = true)), sent)
    }

    @Test
    @Config(application = FcitxApplication::class)
    fun withMultiTapOffTheDefaultADigitAndOneAreAKeyEachPress() {
        // off by default (2026-10-19): a quick 4 4 is two keys, gg/gh/hh… for the model to weigh
        assertEquals(T9MultiTap.Off, AppPrefs.getInstance().keyboard.t9MultiTap.defaultValue)
        val sent = ArrayList<KeyAction>()
        val previews = ArrayList<PopupAction>()
        val keyboard = keyboard(sent, previews, multiTap = T9MultiTap.Off)
        keyboard.key('1').performClick()
        keyboard.key('1').performClick()
        tap(keyboard.key('4'))
        tap(keyboard.key('4'))
        val four = KeyAction.FcitxKeyAction("4")
        // 1's punctuation too: two quick presses are two ，, the rest picked from the column
        assertEquals(listOf(KeyAction.CommitAction("，"), KeyAction.CommitAction("，"), four, four), sent)
        assertEquals(listOf("GHI", "GHI"), previews.filterIsInstance<PopupAction.PreviewAction>().map { it.content })
    }
}
