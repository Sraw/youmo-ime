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
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.fcitx.fcitx5.android.host.ChinesePunctuation
import org.fcitx.fcitx5.android.input.SceneShownKey
import org.fcitx.fcitx5.android.input.ShownKey
import org.fcitx.fcitx5.android.input.keys.ScrollListStore
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import org.fcitx.fcitx5.android.input.popup.PopupKeyboardUi
import org.fcitx.fcitx5.android.scene.keys.ScrollDefaults
import org.fcitx.fcitx5.android.scene.keys.ScrollItem
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
 * The column reads what it shows when one is picked: a syllable goes with its list's id, for the engine to drop if
 * a key since replaced that list; a click released on a replaced list is dropped. A digit sent makes 1 the separator
 * at once; 1's punctuation keeps cycling through the engine's reset, till what was typed goes or the cursor moves.
 */
// Robolectric: reading T9Keyboard's companion initialises the View class
@RunWith(RobolectricTestRunner::class)
class T9KeyboardTest {

    @Test
    fun whileTypingTheColumnOffersTheSyllablesOfItsList() {
        val state = T9State(composing = true, syllables = listOf("ni", "mi", "o"), id = 3)
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
        val four = KeyAction.HostKeyAction("4")
        assertEquals(four, T9Keyboard.resolve(four, keys, tap, 0))
        assertEquals(letter('h', again = true), T9Keyboard.resolve(four, keys, tap, 100))
        val back = KeyAction.SymAction(KeySym(0xff08))
        assertEquals(back, T9Keyboard.resolve(back, keys, tap, 150))
        assertEquals(four, T9Keyboard.resolve(four, keys, tap, 200))
        val off = MultiTap(0)
        assertEquals(four, T9Keyboard.resolve(four, keys, off, 0))
        assertEquals(four, T9Keyboard.resolve(four, keys, off, 1))
    }

    @Test
    @Config(application = FcitxApplication::class)
    fun aDigitPressedAgainGoesThroughTheLettersTheUserPutOnIt() {
        val tap = MultiTap(600)
        val keys = KeyActionListener.Source.Keyboard
        val letters = NineKeyLetters.of(listOf("abc", "def", "gi", "jkl", "mnoh"))
        val six = KeyAction.HostKeyAction("6")
        assertEquals(six, T9Keyboard.resolve(six, keys, tap, 0, letters = letters))
        assertEquals(letter('n', again = true), T9Keyboard.resolve(six, keys, tap, 100, letters = letters))
        assertEquals(letter('o', again = true), T9Keyboard.resolve(six, keys, tap, 200, letters = letters))
        assertEquals(letter('h', again = true), T9Keyboard.resolve(six, keys, tap, 300, letters = letters))
        val drawn = T9Keyboard(PlaceholderStrings(RuntimeEnvironment.getApplication()), TestThemes.PixelDark, letters, semicolon = false).laidOut()
        assertTrue(drawn.shownKeys().any { it.label == "MNOH" })
    }

    @Test
    fun theLongPressCommitsTheLetterOrDigitChosenAsItIs() {
        val tap = MultiTap(600)
        val popup = KeyActionListener.Source.Popup
        assertEquals(KeyAction.CommitAction("g"), T9Keyboard.resolve(KeyAction.HostKeyAction("g"), popup, tap, 0))
        assertEquals(KeyAction.CommitAction("G"), T9Keyboard.resolve(KeyAction.HostKeyAction("G"), popup, tap, 5, composing = true))
        // never the GHI key, nor a candidate picked
        assertEquals(KeyAction.CommitAction("4"), T9Keyboard.resolve(KeyAction.HostKeyAction("4"), popup, tap, 10))
        assertEquals(KeyAction.CommitAction("1"), T9Keyboard.resolve(KeyAction.HostKeyAction("1"), popup, tap, 15, composing = true))
        assertEquals(KeyAction.HostKeyAction("4"), T9Keyboard.resolve(KeyAction.HostKeyAction("4"), KeyActionListener.Source.Keyboard, tap, 20))
    }

    // the marks changed between two taps of a run (the engine tells the full-width map after the first ,): the run types
    // with its first tap's map and takes back what the last tap committed
    @Test
    fun oneAgainTypesWithTheRunsFirstMarksThoughTheyChanged() {
        val tap = MultiTap(600)
        val keys = KeyActionListener.Source.Keyboard
        val one = KeyAction.HostKeyAction("1")
        assertEquals(KeyAction.CommitAction(",", punctuation = true), T9Keyboard.resolve(one, keys, tap, 0, composing = false, marks = emptyMap()))
        assertEquals(KeyAction.CommitAction(".", replacing = ",", punctuation = true), T9Keyboard.resolve(one, keys, tap, 100, composing = false, marks = ZH))
        assertEquals(KeyAction.CommitAction("，", punctuation = true), T9Keyboard.resolve(one, keys, tap, 2000, composing = false, marks = ZH))
        T9Keyboard.resolve(KeyAction.HostKeyAction("4"), keys, tap, 2100)
        assertEquals(KeyAction.CommitAction("，", punctuation = true), T9Keyboard.resolve(one, keys, tap, 2200, composing = false, marks = ZH))
    }

    @Test
    fun oneWithNothingTypedIsPunctuationTheNextInPlaceOfTheLastIfPressedAgainSoon() {
        val tap = MultiTap(600)
        val keys = KeyActionListener.Source.Keyboard
        val one = KeyAction.HostKeyAction("1")
        assertEquals(KeyAction.CommitAction("，", punctuation = true), T9Keyboard.resolve(one, keys, tap, 0, composing = false, marks = ZH))
        assertEquals(KeyAction.CommitAction("。", replacing = "，", punctuation = true), T9Keyboard.resolve(one, keys, tap, 100, composing = false, marks = ZH))
        assertEquals(KeyAction.CommitAction("？", replacing = "。", punctuation = true), T9Keyboard.resolve(one, keys, tap, 200, composing = false, marks = ZH))
        assertEquals(KeyAction.CommitAction("！", replacing = "？", punctuation = true), T9Keyboard.resolve(one, keys, tap, 300, composing = false, marks = ZH))
        assertEquals(KeyAction.CommitAction("，", replacing = "！", punctuation = true), T9Keyboard.resolve(one, keys, tap, 400, composing = false, marks = ZH))
        assertEquals(KeyAction.CommitAction("，", punctuation = true), T9Keyboard.resolve(one, keys, tap, 2000, composing = false, marks = ZH))
        assertEquals(KeyAction.CommitAction(",", punctuation = true), T9Keyboard.resolve(one, keys, tap, 4000, composing = false))
        assertEquals(one, T9Keyboard.resolve(one, keys, tap, 2100, composing = true))
    }

    // 2 to 9's to the engine, as the full keyboard's; a user's literal item as it is; 1's defaults are source characters
    // typed through the punctuation, which the popup's own action carries
    @Test
    fun aLongPressedSymbolGoesOnAsThePopupGivesIt() {
        val tap = MultiTap(600)
        val popup = KeyActionListener.Source.Popup
        for (symbol in listOf("@", "!", "\"", "+", "#", "(", "\\", "*", "?", ":", "😀", "^_^")) {
            val picked = PopupKeyboardUi.keyAction(symbol)
            assertEquals(symbol, picked, T9Keyboard.resolve(picked, popup, tap, 0, composing = true))
        }
        for (symbol in listOf("…", "：", ".", "-", "@", "、")) {
            val picked = KeyAction.CommitAction(symbol)
            assertEquals(symbol, picked, T9Keyboard.resolve(picked, popup, tap, 0, composing = true))
        }
        val two = KeyAction.HostKeyAction("2")
        val keys = KeyActionListener.Source.Keyboard
        assertEquals(two, T9Keyboard.resolve(two, keys, tap, 1000))
        T9Keyboard.resolve(PopupKeyboardUi.keyAction("!"), popup, tap, 1100, composing = true)
        assertEquals(two, T9Keyboard.resolve(two, keys, tap, 1200))
    }

    private fun keypad(d: Int) = KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_KP_0 + d), KeyStates.Virtual)

    private fun letter(c: Char, again: Boolean) =
        KeyAction.SymAction(KeySym(0x01000000 + Keyboard.letterKey(c, again)), KeyStates.Virtual)

    @Test
    fun withNothingToOfferTheColumnCommitsPunctuation() {
        val zh = ChinesePunctuation.map
        listOf(T9State(), T9State(composing = true)).forEach { state ->
            // as the punctuation maps it, shown as typed
            assertEquals(listOf("，", "。", "？", "！", "…"), T9Keyboard.labels(state, marks = zh).take(5))
            assertEquals(listOf(",", ".", "?", "!", "…"), T9Keyboard.labels(state).take(5))
            assertEquals(KeyAction.CommitAction("，", punctuation = true), T9Keyboard.pick(state, 0, marks = zh))
            assertEquals(KeyAction.CommitAction(",", punctuation = true), T9Keyboard.pick(state, 0))
            // … and 、 no key's mark: as they are, not held back
            assertEquals(KeyAction.CommitAction("…"), T9Keyboard.pick(state, 4, marks = zh))
            assertEquals(KeyAction.CommitAction("@", punctuation = true), T9Keyboard.pick(state, T9Keyboard.labels(state).lastIndex))
        }
    }

    @Test
    @Config(application = FcitxApplication::class)
    fun theKeyboardsColumnTypesTheUsersListAsTheInputMethodMarksIt() {
        val pref = AppPrefs.getInstance().internal.scrollLists
        try {
            val keyboard = T9Keyboard(PlaceholderStrings(RuntimeEnvironment.getApplication()), TestThemes.PixelDark, semicolon = false)
            val sent = mutableListOf<KeyAction>()
            keyboard.keyActionListener = KeyActionListener { action, _ -> sent += action.placeless() }
            val items = (0 until keyboard.childCount).map { keyboard.getChildAt(it) }.filterIsInstance<ScrollView>().single().getChildAt(0) as ViewGroup
            fun column() = (0 until items.childCount).map { items.getChildAt(it) as TextView }.filter { it.isVisible }.map { it.text.toString() }
            keyboard.onPunctuationUpdate(ChinesePunctuation.map)
            assertEquals(T9Keyboard.labels(T9State(), marks = ChinesePunctuation.map), column())
            items.getChildAt(0).performClick()
            assertEquals(T9Keyboard.pick(T9State(), 0, marks = ChinesePunctuation.map), sent.last())
            // the user's list, which the defaults' labels and pick never read
            pref.setValue(ScrollListStore.Empty.with(ScrollDefaults.NINE, listOf(ScrollItem.Text("!"), ScrollItem.Text("x"))).serialize())
            assertEquals(listOf("！", "x"), column())
            items.getChildAt(1).performClick()
            assertEquals(KeyAction.CommitAction("x"), sent.last())
        } finally {
            pref.setValue(ScrollListStore.HEADER)
        }
    }

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

    private fun tap(key: ShownKey) {
        key as SceneShownKey
        val keyboard = key.canvas.parent as View
        val x = key.canvas.left + key.bounds.exactCenterX()
        val y = key.canvas.top + key.bounds.exactCenterY()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            MotionEvent.obtain(0L, 0L, action, x, y, 0).also {
                keyboard.dispatchTouchEvent(it)
                it.recycle()
            }
        }
    }

    @Test
    @Config(application = FcitxApplication::class)
    fun aClickReleasedOnAListSinceReplacedIsDropped() {
        val keyboard = T9Keyboard(PlaceholderStrings(RuntimeEnvironment.getApplication()), TestThemes.PixelDark, semicolon = false)
        val picked = ArrayList<KeyAction>()
        keyboard.keyActionListener = KeyActionListener { action, _ -> picked += action.placeless() }
        // in a window, where View posts the click of a released item to the looper, as on a phone
        Robolectric.buildActivity(Activity::class.java).setup().get().setContentView(keyboard)
        val looper = shadowOf(Looper.getMainLooper())
        looper.idleFor(1, TimeUnit.SECONDS)
        assertTrue(keyboard.isAttachedToWindow)
        val twelve = T9State(composing = true, syllables = List(12) { "ni" }, id = 1)
        keyboard.show(twelve)
        // punctuation has no item 10: picked from the list shown when it ran, the click threw
        tap(keyboard.columnItem(10))
        keyboard.show(T9State())
        looper.idle()
        tap(keyboard.columnItem(0))
        val next = twelve.copy(id = 2)
        keyboard.show(next)
        looper.idle()
        assertEquals(emptyList<KeyAction>(), picked)
        tap(keyboard.columnItem(0))
        looper.idle()
        assertEquals(listOf(T9Keyboard.pick(next, 0)), picked)
    }

    private class Owner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }

    @After
    fun multiTapAtItsDefault() {
        AppPrefs.getInstance().keyboard.t9MultiTap.let { it.setValue(it.defaultValue) }
    }

    /** The clock stands still here: every press is within the multi-tap timeout of the last. */
    private fun keyboard(
        sent: MutableList<KeyAction>,
        previews: MutableList<PopupAction> = ArrayList(),
        letters: NineKeyLetters = NineKeyLetters.STANDARD,
        multiTap: T9MultiTap = T9MultiTap.Medium,
    ): T9Keyboard {
        AppPrefs.getInstance().keyboard.t9MultiTap.setValue(multiTap)
        return T9Keyboard(PlaceholderStrings(RuntimeEnvironment.getApplication()), TestThemes.PixelDark, letters, semicolon = false).apply {
            setViewTreeLifecycleOwner(Owner())
            keyActionListener = KeyActionListener { action, _ -> sent += action.placeless() }
            popupActionListener = PopupActionListener { previews += it }
            onPunctuationUpdate(ZH)
        }
    }

    private fun T9Keyboard.key(digit: Char): ShownKey =
        apply { if (width == 0) laidOut() }.shownKeys().single { it.id == digit.toString() }

    @Test
    @Config(application = FcitxApplication::class)
    fun oneRightAfterADigitIsTheSeparatorBeforeTheEngineTells() {
        val sent = ArrayList<KeyAction>()
        val keyboard = keyboard(sent)
        keyboard.key('2').click()
        keyboard.key('1').click()
        assertEquals(listOf(coded('2'), coded('1')), sent)
    }

    // told between a digit sent and the engine's answer: 1 still the separator, the labels redrawn only
    @Test
    @Config(application = FcitxApplication::class)
    fun aPunctuationUpdateBeforeTheEngineTellsKeepsOneTheSeparator() {
        val sent = ArrayList<KeyAction>()
        val keyboard = keyboard(sent)
        keyboard.key('2').click()
        keyboard.onPunctuationUpdate(emptyMap())
        keyboard.key('1').click()
        assertEquals(listOf(coded('2'), coded('1')), sent)
    }

    @Test
    @Config(application = FcitxApplication::class)
    fun aDigitAfterWhatWasTypedWentIsAKeyOfItsOwn() {
        val sent = ArrayList<KeyAction>()
        val keyboard = keyboard(sent)
        keyboard.key('4').click()
        keyboard.show(T9State(composing = true, syllables = listOf("g", "h", "i"), id = 1))
        keyboard.show(T9State())
        keyboard.key('4').click()
        assertEquals(listOf(coded('4'), coded('4')), sent)
    }

    @Test
    @Config(application = FcitxApplication::class)
    fun onesPunctuationCyclesThroughTheEnginesOwnReset() {
        val sent = ArrayList<KeyAction>()
        val keyboard = keyboard(sent)
        keyboard.key('1').click()
        // the punctuation resets the engine, which tells of nothing typed again
        keyboard.show(T9State())
        keyboard.key('1').click()
        assertEquals(listOf(KeyAction.CommitAction("，", punctuation = true), KeyAction.CommitAction("。", replacing = "，", punctuation = true)), sent)
    }

    // the ， now before the cursor may be the user's own
    @Test
    @Config(application = FcitxApplication::class)
    fun oneAfterTheCursorMovedIsASymbolOfItsOwn() {
        val sent = ArrayList<KeyAction>()
        val keyboard = keyboard(sent)
        keyboard.key('1').click()
        keyboard.cursorMoved()
        keyboard.key('1').click()
        assertEquals(listOf(KeyAction.CommitAction("，", punctuation = true), KeyAction.CommitAction("，", punctuation = true)), sent)
    }

    // the app types it as its digit: nothing is typed in the engine, which tells nothing new
    @Test
    @Config(application = FcitxApplication::class)
    fun oneAfterAKeyWithNoLettersIsPunctuation() {
        val sent = ArrayList<KeyAction>()
        val keyboard = keyboard(sent, letters = NineKeyLetters.of(listOf("abc", "")))
        keyboard.key('3').click()
        keyboard.key('1').click()
        assertEquals(listOf(KeyAction.HostKeyAction("3"), KeyAction.CommitAction("，", punctuation = true)), sent)
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
        assertEquals(listOf(coded('3'), letter('b', again = true)), sent)
    }

    @Test
    @Config(application = FcitxApplication::class)
    fun withMultiTapOffTheDefaultADigitAndOneAreAKeyEachPress() {
        // a quick 4 4 is two keys, gg/gh/hh… for the model to weigh
        assertEquals(T9MultiTap.Off, AppPrefs.getInstance().keyboard.t9MultiTap.defaultValue)
        val sent = ArrayList<KeyAction>()
        val previews = ArrayList<PopupAction>()
        val keyboard = keyboard(sent, previews, multiTap = T9MultiTap.Off)
        keyboard.key('1').click()
        keyboard.key('1').click()
        tap(keyboard.key('4'))
        tap(keyboard.key('4'))
        val four = coded('4')
        assertEquals(listOf(KeyAction.CommitAction("，", punctuation = true), KeyAction.CommitAction("，", punctuation = true), four, four), sent)
        assertEquals(listOf("GHI", "GHI"), previews.filterIsInstance<PopupAction.PreviewAction>().map { it.content })
    }

    // 0 and a key emptied of letters end the input while typing, never pick the candidate they number
    @Test
    fun whileTypingEveryDigitIsItsCode() {
        val emptied = NineKeyLetters.of(listOf("abc", "def", "ghi", "jkl", "", "pqrs", "tuv", "wxyz"))
        fun of(d: Char, typing: Boolean, letters: NineKeyLetters = NineKeyLetters.STANDARD) =
            T9Keyboard.coded(KeyAction.HostKeyAction(d.toString()), typing, letters)
        assertEquals(coded('0'), of('0', typing = true))
        assertEquals(KeyAction.HostKeyAction("0"), of('0', typing = false))
        assertEquals(coded('1'), of('1', typing = true))
        assertEquals(KeyAction.HostKeyAction("1"), of('1', typing = false))
        assertEquals(coded('6'), of('6', typing = false))
        assertEquals(coded('6'), of('6', typing = true, emptied))
        assertEquals(KeyAction.HostKeyAction("6"), of('6', typing = false, emptied))
    }

    private fun coded(d: Char) = KeyAction.SymAction(KeySym(0x01000000 + Keyboard.pinyinNineKey(d)), KeyStates.Virtual)

    private fun nineKey(d: Char, upper: Boolean = false) =
        KeyAction.SymAction(KeySym(0x01000000 + Keyboard.nineKey(d, upper)), KeyStates.Virtual)

    @Test
    @Config(application = FcitxApplication::class)
    fun englishSpellsWithTheKeysTheLongPressPinsItsLetterInTheWord() {
        val tap = MultiTap(600)
        val keys = KeyActionListener.Source.Keyboard
        val popup = KeyActionListener.Source.Popup
        val four = KeyAction.HostKeyAction("4")
        fun spell(action: KeyAction, source: KeyActionListener.Source = keys, composing: Boolean = false, upper: Boolean = false) =
            T9Keyboard.resolveEnglish(action, source, tap, 0, composing, spells = true, upper = upper)
        assertEquals(nineKey('4'), spell(four))
        assertEquals(nineKey('4', upper = true), spell(four, upper = true))
        assertEquals(nineKey('4'), spell(four))
        fun pinned(c: Char) = KeyAction.SymAction(KeySym(0x01000000 + Keyboard.nineLetter(c)), KeyStates.Virtual)
        assertEquals(pinned('x'), spell(KeyAction.HostKeyAction("x"), popup, composing = true, upper = true))
        assertEquals(pinned('X'), spell(KeyAction.HostKeyAction("X"), popup))
        assertTrue(T9Keyboard.startsLetter(pinned('x')))
        assertEquals(KeyAction.CommitAction("4"), spell(four, popup, composing = true))
        assertEquals(KeyAction.CommitAction("x"), T9Keyboard.resolveEnglish(KeyAction.HostKeyAction("x"), popup, tap, 0, false, spells = false, upper = false))
        // 1 in a word an apostrophe (366 1 8, don't); 0 English's own key
        assertEquals(KeyAction.HostKeyAction("'"), spell(KeyAction.HostKeyAction("1"), composing = true))
        assertEquals(KeyAction.HostKeyAction("0"), spell(KeyAction.HostKeyAction("0"), composing = true))
        assertTrue(T9Keyboard.startsLetter(nineKey('4')))
        assertFalse(T9Keyboard.startsLetter(keypad(4)))
    }

    @Test
    @Config(application = FcitxApplication::class)
    fun englishOneWithNothingTypedIsPinyinsMarksInTurn() {
        val tap = MultiTap(600)
        val one = KeyAction.HostKeyAction("1")
        fun press(time: Long, marks: Map<String, String> = emptyMap()) =
            T9Keyboard.resolveEnglish(one, KeyActionListener.Source.Keyboard, tap, time, composing = false, spells = true, upper = false, marks = marks)
        // , . ? ! as pinyin's, no ' or - (the long presses have them)
        assertEquals(KeyAction.CommitAction(",", punctuation = true), press(0))
        assertEquals(KeyAction.CommitAction(".", replacing = ",", punctuation = true), press(100))
        assertEquals(KeyAction.CommitAction("?", replacing = ".", punctuation = true), press(200))
        assertEquals(KeyAction.CommitAction("!", replacing = "?", punctuation = true), press(300))
        assertEquals(KeyAction.CommitAction(",", replacing = "!", punctuation = true), press(400))
        assertEquals(KeyAction.CommitAction("，", punctuation = true), press(2000, ZH))
    }

    @Test
    @Config(application = FcitxApplication::class)
    fun englishALetterAtATimeTheCaseTheRunStartedIn() {
        val tap = MultiTap(600)
        val four = KeyAction.HostKeyAction("4")
        fun press(time: Long, upper: Boolean, runUpper: Boolean = upper) =
            T9Keyboard.resolveEnglish(four, KeyActionListener.Source.Keyboard, tap, time, composing = false, spells = false, upper = upper, runUpper = runUpper)
        assertEquals(KeyAction.CommitAction("G"), press(0, upper = true))
        assertEquals(KeyAction.CommitAction("H", replacing = "G"), press(100, upper = false, runUpper = true))
        assertEquals(KeyAction.CommitAction("I", replacing = "H"), press(200, upper = false, runUpper = true))
        assertEquals(KeyAction.CommitAction("g"), press(2000, upper = false))
        assertTrue(T9Keyboard.startsLetter(KeyAction.CommitAction("g")))
        assertFalse(T9Keyboard.startsLetter(KeyAction.CommitAction("h", replacing = "g")))
    }

    @Test
    @Config(application = FcitxApplication::class)
    fun englishsKeysAreLowerCaseWithShiftForStartingOverAndItsOwnMarks() {
        val english = T9Keyboard(PlaceholderStrings(RuntimeEnvironment.getApplication()), TestThemes.PixelDark, english = true, semicolon = false).laidOut()
        val labels = english.shownKeys().map { it.label }
        assertTrue(labels.toString(), "ghi" in labels && "重输" !in labels)
        assertTrue(english.shownKeyWithId(R.id.button_caps) != null)
        assertEquals(listOf(",", ".", "?", "!", "'"), T9Keyboard.labels(T9State(composing = true), english = true).take(5))
        assertEquals(KeyAction.HostKeyAction(","), T9Keyboard.pick(T9State(composing = true), 0, english = true))
    }

    @Test
    @Config(application = FcitxApplication::class)
    fun englishShiftCapitalisesTheNextKeyAndAPasswordTypesALetterAtATime() {
        val sent = ArrayList<KeyAction>()
        val keyboard = T9Keyboard(PlaceholderStrings(RuntimeEnvironment.getApplication()), TestThemes.PixelDark, english = true, semicolon = false).apply {
            setViewTreeLifecycleOwner(Owner())
            keyActionListener = KeyActionListener { action, _ -> sent += action.placeless() }
        }
        keyboard.laidOut().shownKeyWithId(R.id.button_caps)!!.click()
        keyboard.key('4').click()
        keyboard.key('4').click()
        assertEquals(listOf(nineKey('4', upper = true), nineKey('4')), sent.filterIsInstance<KeyAction.SymAction>())
        val glide = BaseKeyboard::class.java.getDeclaredMethod("getGlide").apply { isAccessible = true }
        assertEquals(null, glide.invoke(keyboard))
        sent.clear()
        keyboard.inPasswordField = true
        keyboard.key('4').click()
        assertEquals(listOf(KeyAction.CommitAction("g")), sent)
        // Shift ends the run: 4 again soon is a capital G of its own
        sent.clear()
        keyboard.laidOut().shownKeyWithId(R.id.button_caps)!!.click()
        keyboard.key('4').click()
        assertEquals(listOf(KeyAction.CommitAction("G")), sent.filterIsInstance<KeyAction.CommitAction>())
    }

    @Test
    @Config(application = FcitxApplication::class)
    fun anAddressFieldsMarksAreInTheColumn() {
        for (english in listOf(false, true)) {
            val keyboard = T9Keyboard(PlaceholderStrings(RuntimeEnvironment.getApplication()), TestThemes.PixelDark, english = english, semicolon = false)
            val sent = mutableListOf<KeyAction>()
            keyboard.keyActionListener = KeyActionListener { action, _ -> sent += action.placeless() }
            val items = (0 until keyboard.childCount).map { keyboard.getChildAt(it) }.filterIsInstance<ScrollView>().single().getChildAt(0) as ViewGroup
            fun column() = (0 until items.childCount).map { items.getChildAt(it) as TextView }.filter { it.isVisible }.map { it.text.toString() }
            fun pickFirst() = items.getChildAt(0).performClick()
            assertTrue(listOf(R.id.button_slash, R.id.button_at, R.id.button_dot, R.id.button_domain).all { keyboard.findViewById<View>(it) == null })
            keyboard.address = AddressKeys.URL
            assertEquals(listOf("/", ".", ".com", ":", "-", "_", "?", "=", "&", "#", ".cn", ".net", ".org"), column())
            pickFirst()
            assertEquals(KeyAction.CommitAction("/"), sent.last())
            keyboard.address = AddressKeys.EMAIL
            assertEquals(listOf("@", ".", ".com", "-", "_", "+", ".cn", ".net", ".org"), column())
            pickFirst()
            assertEquals(KeyAction.CommitAction("@"), sent.last())
            keyboard.address = AddressKeys.NONE
            assertEquals(T9Keyboard.labels(T9State(), english), column())
        }
        val typing = T9State(composing = true, syllables = listOf("ni", "mi"))
        assertEquals(listOf("ni", "mi"), T9Keyboard.labels(typing, address = AddressKeys.URL))
        assertEquals(KeyAction.CommitAction(".com"), T9Keyboard.pick(T9State(), 2, address = AddressKeys.URL))
        assertEquals(KeyAction.CommitAction(".com"), T9Keyboard.pick(T9State(composing = true), 2, english = true, address = AddressKeys.URL))
    }

    @Test
    @Config(application = FcitxApplication::class)
    fun neitherHasALanguageKeyAndBothTheGlobeEvenInAnAddressField() {
        val app = RuntimeEnvironment.getApplication()
        for (english in listOf(false, true)) {
            val keyboard = T9Keyboard(PlaceholderStrings(app), TestThemes.PixelDark, english = english, semicolon = false).laidOut()
            val labels = keyboard.shownKeys().map { it.label }
            assertFalse(labels.toString(), labels.any { "英" in it || "中" in it })
            fun globe() = keyboard.laidOut().shownKeyWithId(R.id.button_lang) != null
            assertTrue(globe())
            keyboard.address = AddressKeys.EMAIL
            assertTrue(globe())
            keyboard.address = AddressKeys.NONE
            assertTrue(globe())
        }
    }

    @Test
    @Config(application = FcitxApplication::class)
    fun theColumnShowsTheNineKeysSyllablesAloneNotAnotherInputMethods() {
        val keyboard = T9Keyboard(PlaceholderStrings(RuntimeEnvironment.getApplication()), TestThemes.PixelDark, semicolon = false)
        val items = (0 until keyboard.childCount).map { keyboard.getChildAt(it) }.filterIsInstance<ScrollView>().single().getChildAt(0) as ViewGroup
        fun column() = (0 until items.childCount).map { items.getChildAt(it) as TextView }.filter { it.isVisible }.map { it.text.toString() }
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setContentView(keyboard)
        val looper = shadowOf(Looper.getMainLooper())
        try {
            EngineState.syllablesState.value = EngineState.SyllableState(InputMethodCatalog.SHUANGPIN, composing = true, syllables = listOf("bu", "nu"), id = 1)
            looper.idle()
            assertEquals(T9Keyboard.labels(T9State()), column())
            EngineState.syllablesState.value = EngineState.SyllableState(InputMethodCatalog.PINYIN, composing = true, syllables = listOf("ni", "mi"), id = 2)
            looper.idle()
            assertEquals(listOf("ni", "mi"), column())
            // a switch away: both answers at once, the last alone seen (a filter on the input method once kept stale syllables)
            (keyboard.parent as ViewGroup).removeView(keyboard)
            EngineState.syllablesState.value = EngineState.SyllableState(InputMethodCatalog.SHUANGPIN, composing = true, syllables = listOf("bu"), id = 3)
            activity.setContentView(keyboard)
            looper.idle()
            assertEquals(T9Keyboard.labels(T9State()), column())
        } finally {
            EngineState.syllablesState.value = EngineState.SyllableState()
        }
    }

    private companion object {
        val ZH = mapOf("," to "，", "." to "。", "?" to "？", "!" to "！")
    }
}

// where a key fell is NeighbourTouchTest's
private fun KeyAction.placeless(): KeyAction = when (this) {
    is KeyAction.HostKeyAction -> copy(touch = null)
    is KeyAction.SymAction -> copy(touch = null)
    else -> this
}
