/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.app.Activity
import android.os.Looper
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
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
import org.junit.Assert.assertFalse
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
    fun theLongPressCommitsTheLetterOrDigitChosenAsItIs() {
        val tap = MultiTap(600)
        val popup = KeyActionListener.Source.Popup
        assertEquals(KeyAction.CommitAction("g"), T9Keyboard.resolve(KeyAction.FcitxKeyAction("g"), popup, tap, 0))
        assertEquals(KeyAction.CommitAction("G"), T9Keyboard.resolve(KeyAction.FcitxKeyAction("G"), popup, tap, 5, composing = true))
        // the digit too, while typing as well: never the GHI key, nor a candidate picked
        assertEquals(KeyAction.CommitAction("4"), T9Keyboard.resolve(KeyAction.FcitxKeyAction("4"), popup, tap, 10))
        assertEquals(KeyAction.CommitAction("1"), T9Keyboard.resolve(KeyAction.FcitxKeyAction("1"), popup, tap, 15, composing = true))
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
        // and a 2 pressed again soon after one is a 2 anew, not the next letter
        val two = KeyAction.FcitxKeyAction("2")
        val keys = KeyActionListener.Source.Keyboard
        assertEquals(two, T9Keyboard.resolve(two, keys, tap, 1000))
        T9Keyboard.resolve(PopupKeyboardUi.keyAction("!"), popup, tap, 1100, composing = true)
        assertEquals(two, T9Keyboard.resolve(two, keys, tap, 1200))
    }

    @Test
    fun eachKeysSymbolsAreSetApartAfterItsDigitAndLetters() {
        val symbols = NineKeySymbols.STANDARD.with('5', listOf("【", "】")).with('1', emptyList())
        val keys = T9Keyboard.layout(NineKeyLetters.STANDARD, symbols).flatten()
        fun popup(digit: String) = keys.single { (it.appearance as? KeyDef.Appearance.AltText)?.altText == digit }
            .popup?.filterIsInstance<KeyDef.Popup.Keyboard.Explicit>()?.singleOrNull()
        val two = popup("2")!!
        assertEquals(listOf("A", "B", "C", "2", "a", "b", "c", "@", "!", "\""), two.items.toList())
        assertEquals(7, two.symbolsFrom)
        assertEquals(3, two.inRow)
        assertEquals(false, two.asIs)
        val one = T9Keyboard.layout(NineKeyLetters.STANDARD).flatten()
            .single { (it.appearance as? KeyDef.Appearance.AltText)?.altText == "1" }.popup!!.single() as KeyDef.Popup.Keyboard.Explicit
        assertEquals(true, one.asIs)
        assertEquals(listOf("J", "K", "L", "5", "j", "k", "l", "【", "】"), popup("5")!!.items.toList())
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

    private fun nineKey(d: Char, upper: Boolean = false) =
        KeyAction.SymAction(KeySym(0x01000000 + Keyboard.nineKey(d, upper)), KeyStates.Virtual)

    @Test
    @Config(application = FcitxApplication::class)
    fun englishSpellsWithTheKeysTheLongPressPinsItsLetterInTheWord() {
        val tap = MultiTap(600)
        val keys = KeyActionListener.Source.Keyboard
        val popup = KeyActionListener.Source.Popup
        val four = KeyAction.FcitxKeyAction("4")
        fun spell(action: KeyAction, source: KeyActionListener.Source = keys, composing: Boolean = false, upper: Boolean = false) =
            T9Keyboard.resolveEnglish(action, source, tap, 0, composing, spells = true, upper = upper)
        assertEquals(nineKey('4'), spell(four))
        assertEquals(nineKey('4', upper = true), spell(four, upper = true))
        // pressed again soon, a key all the same
        assertEquals(nineKey('4'), spell(four))
        // a long press's letter pinned into the word as chosen, Shift or not; its digit committed
        fun pinned(c: Char) = KeyAction.SymAction(KeySym(0x01000000 + Keyboard.nineLetter(c)), KeyStates.Virtual)
        assertEquals(pinned('x'), spell(KeyAction.FcitxKeyAction("x"), popup, composing = true, upper = true))
        assertEquals(pinned('X'), spell(KeyAction.FcitxKeyAction("X"), popup))
        assertTrue(T9Keyboard.startsLetter(pinned('x')))
        assertEquals(KeyAction.CommitAction("4"), spell(four, popup, composing = true))
        // 连按 (no word spelled): the letter committed as it is
        assertEquals(KeyAction.CommitAction("x"), T9Keyboard.resolveEnglish(KeyAction.FcitxKeyAction("x"), popup, tap, 0, false, spells = false, upper = false))
        // 1 in a word an apostrophe (366 1 8, don't); 0 English's own key
        assertEquals(KeyAction.FcitxKeyAction("'"), spell(KeyAction.FcitxKeyAction("1"), composing = true))
        assertEquals(KeyAction.FcitxKeyAction("0"), spell(KeyAction.FcitxKeyAction("0"), composing = true))
        assertTrue(T9Keyboard.startsLetter(nineKey('4')))
        assertFalse(T9Keyboard.startsLetter(keypad(4)))
    }

    @Test
    @Config(application = FcitxApplication::class)
    fun englishOneWithNothingTypedIsItsMarksInTurn() {
        val tap = MultiTap(600)
        val one = KeyAction.FcitxKeyAction("1")
        fun press(time: Long) = T9Keyboard.resolveEnglish(one, KeyActionListener.Source.Keyboard, tap, time, composing = false, spells = true, upper = false)
        assertEquals(KeyAction.CommitAction("."), press(0))
        assertEquals(KeyAction.CommitAction(",", replacing = "."), press(100))
        assertEquals(KeyAction.CommitAction("?", replacing = ","), press(200))
        assertEquals(KeyAction.CommitAction("."), press(2000))
    }

    @Test
    @Config(application = FcitxApplication::class)
    fun englishALetterAtATimeTheCaseTheRunStartedIn() {
        val tap = MultiTap(600)
        val four = KeyAction.FcitxKeyAction("4")
        fun press(time: Long, upper: Boolean, runUpper: Boolean = upper) =
            T9Keyboard.resolveEnglish(four, KeyActionListener.Source.Keyboard, tap, time, composing = false, spells = false, upper = upper, runUpper = runUpper)
        assertEquals(KeyAction.CommitAction("G"), press(0, upper = true))
        // Shift used up by the G: the run keeps its case
        assertEquals(KeyAction.CommitAction("H", replacing = "G"), press(100, upper = false, runUpper = true))
        assertEquals(KeyAction.CommitAction("I", replacing = "H"), press(200, upper = false, runUpper = true))
        assertEquals(KeyAction.CommitAction("g"), press(2000, upper = false))
        assertTrue(T9Keyboard.startsLetter(KeyAction.CommitAction("g")))
        assertFalse(T9Keyboard.startsLetter(KeyAction.CommitAction("h", replacing = "g")))
    }

    @Test
    @Config(application = FcitxApplication::class)
    fun englishsKeysAreLowerCaseWithShiftForStartingOverAndItsOwnMarks() {
        val english = T9Keyboard.layout(NineKeyLetters.STANDARD, english = true)
        assertTrue(english[1].any { (it.appearance as? KeyDef.Appearance.AltText)?.displayText == "ghi" })
        assertTrue(english[1].any { it is CapsKey })
        assertFalse(english.flatten().any { (it.appearance as? KeyDef.Appearance.Text)?.displayText == "重输" })
        assertEquals(listOf(",", ".", "?", "!", "'"), T9Keyboard.labels(EngineBridge.T9State(composing = true), english = true).take(5))
        assertEquals(KeyAction.FcitxKeyAction(","), T9Keyboard.pick(EngineBridge.T9State(composing = true), 0, english = true))
    }

    @Test
    @Config(application = FcitxApplication::class)
    fun englishShiftCapitalisesTheNextKeyAndAPasswordTypesALetterAtATime() {
        val sent = ArrayList<KeyAction>()
        val keyboard = T9Keyboard(PlaceholderStrings(RuntimeEnvironment.getApplication()), ThemePreset.PixelDark, english = true).apply {
            setViewTreeLifecycleOwner(Owner())
            keyActionListener = KeyActionListener { action, _ -> sent += action }
        }
        keyboard.findViewById<View>(R.id.button_caps).performClick()
        keyboard.key('4').performClick()
        keyboard.key('4').performClick()
        assertEquals(listOf(nineKey('4', upper = true), nineKey('4')), sent.filterIsInstance<KeyAction.SymAction>())
        // never glides, whatever 滑行输入 says
        val glide = BaseKeyboard::class.java.getDeclaredMethod("getGlide").apply { isAccessible = true }
        assertEquals(null, glide.invoke(keyboard))
        sent.clear()
        keyboard.inPasswordField = true
        keyboard.key('4').performClick()
        assertEquals(listOf(KeyAction.CommitAction("g")), sent)
        // Shift ends the run: 4 again soon is a capital G of its own, not h in g's place
        sent.clear()
        keyboard.findViewById<View>(R.id.button_caps).performClick()
        keyboard.key('4').performClick()
        assertEquals(listOf(KeyAction.CommitAction("G")), sent.filterIsInstance<KeyAction.CommitAction>())
        // 1's long press: English's marks, none full width
        val one = T9Keyboard.layout(NineKeyLetters.STANDARD, english = true).flatten()
            .single { (it.appearance as? KeyDef.Appearance.AltText)?.altText == "1" }.popup!!.single() as KeyDef.Popup.Keyboard.Explicit
        assertTrue(one.items.all { s -> s.all { it.code < 0x80 } })
    }

    // as the full keyboard's: a URL's / or an email address's @ before the space, . and .com after it
    @Test
    @Config(application = FcitxApplication::class)
    fun anAddressFieldsKeysShowOnlyThere() {
        val keyboard = T9Keyboard(PlaceholderStrings(RuntimeEnvironment.getApplication()), ThemePreset.PixelDark)
        fun shown() = listOf(R.id.button_slash, R.id.button_at, R.id.button_dot, R.id.button_domain).map { keyboard.findViewById<View>(it).isVisible }
        assertEquals(listOf(false, false, false, false), shown())
        keyboard.address = AddressKeys.URL
        assertEquals(listOf(true, false, true, true), shown())
        keyboard.address = AddressKeys.EMAIL
        assertEquals(listOf(false, true, true, true), shown())
        keyboard.address = AddressKeys.NONE
        assertEquals(listOf(false, false, false, false), shown())
    }

    // 中/英 beside the space on both; the language key given up to an address's keys
    @Test
    @Config(application = FcitxApplication::class)
    fun bothHaveTheLanguageKeyAndAnAddressHidesTheGlobe() {
        val app = RuntimeEnvironment.getApplication()
        for (english in listOf(false, true)) {
            val keyboard = T9Keyboard(PlaceholderStrings(app), ThemePreset.PixelDark, english = english)
            val language = keyboard.findViewById<TextKeyView>(R.id.button_nine_language)
            // the language typed in full, the other with the slash smaller
            val label = language.mainText.text as Spanned
            val smaller = label.getSpans(0, label.length, RelativeSizeSpan::class.java).single()
            assertEquals(if (english) "中/" else "/英", label.substring(label.getSpanStart(smaller), label.getSpanEnd(smaller)))
            val sent = mutableListOf<KeyAction>()
            keyboard.keyActionListener = KeyActionListener { action, _ -> sent += action }
            language.performClick()
            assertEquals(listOf(KeyAction.NineKeyLanguageAction), sent)
            val globe = keyboard.findViewById<View>(R.id.button_lang)
            assertTrue(globe.isVisible)
            keyboard.address = AddressKeys.EMAIL
            assertFalse(globe.isVisible)
            assertTrue(keyboard.findViewById<View>(R.id.button_nine_language).isVisible)
            keyboard.address = AddressKeys.NONE
            assertTrue(globe.isVisible)
        }
    }
}
